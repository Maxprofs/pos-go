package com.shopify.volumizer.render;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;

import java.io.IOException;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 */
public class CustomRenderer implements GLSurfaceView.Renderer {

    private final OpenGlCameraPreview glCameraPreview;
    private final OpenGlRectangle glPlane;

    float[][] planeTransforms = new float[][]{};
    private double rgbTimestamp;

    public CustomRenderer(Context context) throws IOException {
        glCameraPreview = new OpenGlCameraPreview(context);
        glPlane = new OpenGlRectangle(context);
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {

        // Enable depth test to discard fragments that are behind of another fragment.
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        // Enable face culling to discard back facing triangles.
        GLES20.glEnable(GLES20.GL_CULL_FACE);
        GLES20.glCullFace(GLES20.GL_BACK);
        // Enable transparency
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);

        GLES20.glClearColor(1.0f, 1.0f, 0.0f, 1.0f);

        glCameraPreview.setUpProgramAndBuffers();
        glPlane.setUpProgramAndBuffers();

//        final BitmapFactory.Options options = new BitmapFactory.Options();
//        options.inScaled = false;
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        GLES20.glViewport(0, 0, width, height);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

        // Don't write depth buffer because we want to draw the camera as background.
        GLES20.glDepthMask(false);

        glCameraPreview.drawAsBackground();

        // Enable depth buffer again for AR.
        GLES20.glDepthMask(true);
        GLES20.glCullFace(GLES20.GL_BACK);

        for (float[] planeTransform : planeTransforms) {
            glPlane.setModelMatrix(planeTransform);
            glPlane.draw();
        }
    }

    public int getCameraTextureId() {
        return glCameraPreview.getTextureId();
    }

    /**
     * Set the Projection matrix matching the Tango RGB camera in order to be able to do
     * Augmented Reality.
     */
    public void setProjectionMatrix(float[] projectionMatrix) {
        // setProjectionMatrix(projectionMatrix) on scene objects.
        glPlane.setProjectionMatrix(projectionMatrix);
    }

    /**
     * Update the View matrix matching the pose of the Tango RGB camera.
     *
     * @param ssTcamera The transform from RGB camera to Start of Service.
     */
    public void updateViewMatrix(float[] ssTcamera) {
        float[] viewMatrix = new float[16];
        Matrix.invertM(viewMatrix, 0, ssTcamera, 0);

        // setViewMatrix(viewMatrix) on scene objects.
        glPlane.setViewMatrix(viewMatrix);
    }

    public void updateScene(double rgbT) {
        rgbTimestamp = rgbT;
        // Update scene object model transforms in relation to time if appropriate...
    }

    public void updatePlanes(float[][] planes) {
        planeTransforms = planes;
    }

    public void setPlaneTransform(float[] transform) {
        glPlane.setModelMatrix(transform);
    }

    public double getRgbTimestamp() {
        return rgbTimestamp;
    }
}
