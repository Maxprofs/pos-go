/*
 * Copyright 2014 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.shopify.posgo;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.support.annotation.NonNull;
import android.util.Log;
import android.view.MotionEvent;

import com.google.atap.tangoservice.TangoCameraIntrinsics;
import com.google.atap.tangoservice.TangoPoseData;
import com.kanawish.raja.raja.ScenePoseCalculator;

import org.rajawali3d.Object3D;
import org.rajawali3d.lights.DirectionalLight;
import org.rajawali3d.materials.Material;
import org.rajawali3d.materials.methods.DiffuseMethod;
import org.rajawali3d.materials.methods.SpecularMethod;
import org.rajawali3d.materials.textures.ATexture;
import org.rajawali3d.materials.textures.StreamingTexture;
import org.rajawali3d.materials.textures.Texture;
import org.rajawali3d.math.Matrix4;
import org.rajawali3d.math.Quaternion;
import org.rajawali3d.math.vector.Vector3;
import org.rajawali3d.primitives.Line3D;
import org.rajawali3d.primitives.Plane;
import org.rajawali3d.primitives.ScreenQuad;
import org.rajawali3d.renderer.Renderer;
import org.rajawali3d.util.Intersector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Stack;

import javax.microedition.khronos.opengles.GL10;

import rx.functions.Action1;
import rx.functions.Action3;

/**
 * In floorplan edit mode, we are creating a list of planes in clockwise order.
 * <p>
 * FIXME WIP just getting started
 */
public class FloorPlanEditRenderer extends Renderer {

    private static final String TAG = FloorPlanEditRenderer.class.getSimpleName();

    private static final float CUBE_SIDE_LENGTH = 0.5f;

    // Augmented Reality related fields
    private ATexture tangoCameraTexture;
    private boolean sceneCameraConfigured;

    // TODO: Floor, Viewer's camera, 2-3 Models

    Map<float[], Plane> planeMap = new HashMap<>();
    List<Object3D> lines = new ArrayList<>();

    private Material wallMaterial;
    private Material selectedWallMaterial;

    private boolean modelUpdated = false;
    private ArrayList<float[]> updatedList;
    private Plane selectedPlane = null;
    private Material linesMaterial;
    private Material intersectMaterial;

    public FloorPlanEditRenderer(Context context) {
        super(context);
    }

    @Override
    protected void initScene() {
        // Create a quad covering the whole background and assign a texture to it where the
        // Tango color camera contents will be rendered.
        ScreenQuad backgroundQuad = new ScreenQuad();
        backgroundQuad.setDoubleSided(true);
        Material tangoCameraMaterial = new Material();
        tangoCameraMaterial.setColorInfluence(0);

        // We need to use Rajawali's {@code StreamingTexture} since it sets up the texture
        // for GL_TEXTURE_EXTERNAL_OES rendering
        tangoCameraTexture =
                new StreamingTexture("camera", (StreamingTexture.ISurfaceListener) null);
        try {
            tangoCameraMaterial.addTexture(tangoCameraTexture);
            backgroundQuad.setMaterial(tangoCameraMaterial);
        } catch (ATexture.TextureException e) {
            Log.e(TAG, "Exception creating texture for RGB camera contents", e);
        }
        getCurrentScene().addChildAt(backgroundQuad, 0);
        backgroundQuad.rotate(Vector3.Axis.X, 180);

        // Add a directional light in an arbitrary direction.
        DirectionalLight light = new DirectionalLight(1, -0.5, -1);
        light.setColor(1, 1, 1);
        light.setPower(1.2f);
        light.setPosition(0, 10, 0);
        getCurrentScene().addLight(light);

        Bitmap wallBitmap = buildWallBitmap("Wall");

        // Set-up materials
        wallMaterial = new Material();
        Texture wallTexture = new Texture("wallTexture", wallBitmap);
        try {
            wallMaterial.addTexture(wallTexture);
            wallMaterial.setColorInfluence(0);
        } catch (ATexture.TextureException e) {
            e.printStackTrace();
        }

        selectedWallMaterial = new Material();
        try {
            selectedWallMaterial.addTexture(new Texture("checkerboard", R.drawable.checkerboard));
            selectedWallMaterial.setColorInfluence(0);
        } catch (ATexture.TextureException e) {
            e.printStackTrace();
        }

        linesMaterial = new Material();

        intersectMaterial = new Material();
        intersectMaterial.useVertexColors(true);
    }

    @NonNull
    private Bitmap buildWallBitmap(String text) {
        Bitmap wallBitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
//            BitmapFactory.decodeResource(getContext().getResources(),R.drawable.instructions);
        // Init Canvas and Paint
        Canvas textCanvas = new Canvas(wallBitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.WHITE);
        paint.setTextSize(36);

        // Clear and draw text in canvas.
        textCanvas.drawColor(0, PorterDuff.Mode.CLEAR);

        Rect cr = new Rect(5, 5, textCanvas.getWidth() - 5, textCanvas.getHeight() - 5);
        float cX = cr.exactCenterX();
        float cY = cr.exactCenterY();

        textCanvas.rotate(180, cX, cY);
        paint.setColor(Color.YELLOW);
        paint.setAlpha(128);
        textCanvas.drawRect(cr, paint);
        paint.setColor(Color.RED);
        paint.setAlpha(255);
        textCanvas.drawCircle(cr.right, cr.top, 5, paint);
        paint.setColor(Color.MAGENTA);
        textCanvas.drawCircle(cX, cY, 5, paint);
        paint.setColor(Color.BLUE);
        textCanvas.drawCircle(cr.left, cr.bottom, 5, paint);

        paint.setColor(Color.GREEN);
        paint.setTextAlign(Paint.Align.CENTER);
        textCanvas.drawText(text, cX, cY, paint);
        return wallBitmap;
    }

    @NonNull
    private Material buildMaterial(int color) {
        Material material = new Material();
        material.setColor(color);
        material.enableLighting(true);
        material.setDiffuseMethod(new DiffuseMethod.Lambert());
        material.setSpecularMethod(new SpecularMethod.Phong());
        return material;
    }

    @Override
    protected void onRender(long elapsedRealTime, double deltaTime) {
        // Update the AR object if necessary
        // Synchronize against concurrent access with the setter below.
        synchronized (this) {
            if (modelUpdated) {
                // Anything missing is considered dead
                for (float[] currentPlane : new HashSet<>(planeMap.keySet())) {
                    if (!updatedList.contains(currentPlane)) {
                        getCurrentScene().removeChild(planeMap.get(currentPlane));
                        planeMap.remove(currentPlane);
                    }
                }

                // Anything new is an add-on.
                for (float[] currentPlane : updatedList) {
                    if (!planeMap.containsKey(currentPlane)) {
                        Plane plane = new Plane();
                        plane.setMaterial(wallMaterial);
                        plane.setTransparent(true);

                        Matrix4 objectTransform = new Matrix4(currentPlane);
                        plane.setPosition(objectTransform.getTranslation());
                        plane.setOrientation(new Quaternion().fromMatrix(objectTransform).conjugate());
                        plane.setVisible(true);
                        getCurrentScene().addChild(plane);

                        planeMap.put(currentPlane, plane);
                    } else {
                        // Possibly Reset the previous selected state
                        planeMap.get(currentPlane).setMaterial(wallMaterial);
                    }
                }

                if (selectedPlane != null) selectedPlane.setMaterial(selectedWallMaterial);

                // Clear out old lines.
                if (!lines.isEmpty()) {
                    for (Object3D line : lines) {
                        getCurrentScene().removeChild(line);
                    }
                }
                lines.clear();

                Plane previous = null;
                Action3<Action1<Object3D>, Object3D, Integer> addLine = buildAddLineAction();

                for (float[] planeKey : updatedList) {
                    Plane plane = planeMap.get(planeKey);
                    addLine.call(o3d -> o3d.moveRight(0.5), plane, 0xffff0000);
                    addLine.call(o3d -> o3d.moveForward(0.5), plane, 0xff00ff00);
                    addLine.call(o3d -> o3d.moveUp(0.5), plane, 0xff0000ff);

                    if (previous != null) {
                        // Finds intersection of previous to new, or just gives a partial line segment.
                        lines.add(buildPlaneIntersectLine(previous, plane));
                    }

                    previous = plane;
                }
                getCurrentScene().addChildren(lines);

                // TODO: Let's process a list of transforms here.

                // TODO: Add a way to orient things placed on the floor.

                modelUpdated = false;
            }
        }

        super.onRender(elapsedRealTime, deltaTime);
    }

    @NonNull
    private Action3<Action1<Object3D>, Object3D, Integer> buildAddLineAction() {
        Stack<Vector3> linePoints = new Stack<>();
        Vector3 start = new Vector3();
        Object3D tmpEnd = new Object3D();

        // Movement and color changes.
        return (endPointAction, plane, color) -> {
            start.setAll(plane.getPosition());
            tmpEnd.setPosition(start);
            tmpEnd.setOrientation(plane.getOrientation());
            endPointAction.call(tmpEnd);
            linePoints.clear();
            linePoints.add(start);
            linePoints.add(tmpEnd.getPosition());
            Line3D line = new Line3D(linePoints, 10, color);
            line.setMaterial(linesMaterial);
            lines.add(line);
        };
    }

    private Line3D buildPlaneIntersectLine(Plane leftPlane, Plane rightPlane) {
        Vector3 start = new Vector3();
        Vector3 hitPoint = new Vector3();
        Object3D tmp = new Object3D();
        tmp.setPosition(leftPlane.getPosition());
        tmp.setOrientation(leftPlane.getOrientation());
        tmp.moveRight(-0.5);

        org.rajawali3d.math.Plane rightMathPlane = convertPlane(rightPlane);

        start.setAll(leftPlane.getPosition());
        boolean intersects = Intersector.intersectRayPlane(start, tmp.getPosition(), rightMathPlane, hitPoint);

        Stack<Vector3> linePoints = new Stack<>();

        Line3D newLine;
        if (intersects) {
            // Easier to follow if the angle indicator doesn't move up or down.
            Vector3 endPos = new Vector3(rightPlane.getPosition());
            endPos.y = hitPoint.y = start.y = 0;
            linePoints.add(start);
            linePoints.add(hitPoint);
            linePoints.add(hitPoint);
            linePoints.add(endPos);
            newLine = new Line3D(linePoints, 40, new int[]{0xffffff00, 0xffffff00, 0xffff00ff, 0xffff00ff});
        } else {
            linePoints.add(start);
            linePoints.add(tmp.getPosition());
            newLine = new Line3D(linePoints, 40, 0xffff0000);
        }

        newLine.setMaterial(intersectMaterial);
        return newLine;
    }

    @NonNull
    private org.rajawali3d.math.Plane convertPlane(Plane primitivePlane) {
        // TODO: There has to be a cleaner / more efficient way. Optimize.
        Object3D tmp = new Object3D();
        Vector3 a = new Vector3(), b = new Vector3(), c = new Vector3();
        a.setAll(primitivePlane.getPosition());
        tmp.setPosition(primitivePlane.getPosition());
        tmp.setOrientation(primitivePlane.getOrientation());
        tmp.moveRight(1);
        b.setAll(tmp.getPosition());
        tmp.setPosition(primitivePlane.getPosition());
        tmp.setOrientation(primitivePlane.getOrientation());
        tmp.moveUp(1);
        c.setAll(tmp.getPosition());
        return new org.rajawali3d.math.Plane(a, b, c);
    }

    /**
     * Save the updated plane fit pose to update the AR object on the next render pass.
     * This is synchronized against concurrent access in the render loop above.
     * public synchronized void updateObjectPose(float[] planeFitTransform) {
     * // TODO: Let's process a list of transforms here.
     * objectTransform = new Matrix4(planeFitTransform);
     * modelUpdated = true;
     * }
     */

    public synchronized void updateWallPlanes(List<float[]> planeFitTransform) {
        updatedList = new ArrayList<>(planeFitTransform);
        modelUpdated = true;
    }

    // TODO: This synchronized setup sucks a bit, fix it one day.
    public synchronized void updateSelectedTransform(float[] selectedFitTransform) {
        if (planeMap.containsKey(selectedFitTransform)) {
            selectedPlane = planeMap.get(selectedFitTransform);
            // NOTE: Should not run into contention, since the render block is synchronized.
            modelUpdated = true;
        }
    }

    /**
     * Update the scene camera based on the provided pose in Tango start of service frame.
     * The camera pose should match the pose of the camera color at the time the last rendered RGB
     * frame, which can be retrieved with this.getTimestamp();
     * <p/>
     * NOTE: This must be called from the OpenGL render thread - it is not thread safe.
     */
    public void updateRenderCameraPose(TangoPoseData cameraPose) {
        float[] translation = cameraPose.getTranslationAsFloats();
        float[] rotation = cameraPose.getRotationAsFloats();

        getCurrentCamera().setPosition(translation[0], translation[1], translation[2]);
        Quaternion quaternion = new Quaternion(rotation[3], rotation[0], rotation[1], rotation[2]);
//        getCurrentCamera().setRotation(quaternion.conjugate());
        getCurrentCamera().setRotation(quaternion.conjugate());
    }

    /**
     * It returns the ID currently assigned to the texture where the Tango color camera contents
     * should be rendered.
     * NOTE: This must be called from the OpenGL render thread - it is not thread safe.
     */
    public int getTextureId() {
        return tangoCameraTexture == null ? -1 : tangoCameraTexture.getTextureId();
    }

    /**
     * We need to override this method to mark the camera for re-configuration (set proper
     * projection matrix) since it will be reset by Rajawali on surface changes.
     */
    @Override
    public void onRenderSurfaceSizeChanged(GL10 gl, int width, int height) {
        super.onRenderSurfaceSizeChanged(gl, width, height);
        sceneCameraConfigured = false;
    }

    public boolean isSceneCameraConfigured() {
        return sceneCameraConfigured;
    }

    /**
     * Sets the projection matrix for the scene camera to match the parameters of the color camera,
     * provided by the {@code TangoCameraIntrinsics}.
     */
    public void setProjectionMatrix(TangoCameraIntrinsics intrinsics) {
        Matrix4 projectionMatrix = ScenePoseCalculator.calculateProjectionMatrix(
                intrinsics.width, intrinsics.height,
                intrinsics.fx, intrinsics.fy, intrinsics.cx, intrinsics.cy);
        getCurrentCamera().setProjectionMatrix(projectionMatrix);
    }

    @Override
    public void onOffsetsChanged(float xOffset, float yOffset,
                                 float xOffsetStep, float yOffsetStep,
                                 int xPixelOffset, int yPixelOffset) {
    }

    @Override
    public void onTouchEvent(MotionEvent event) {

    }
}
