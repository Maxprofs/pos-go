package com.shopify.volumizer.render;

import com.google.atap.tangoservice.Tango;
import com.google.atap.tangoservice.TangoCameraIntrinsics;
import com.google.atap.tangoservice.TangoPoseData;
import com.projecttango.tangosupport.TangoSupport;

import org.rajawali3d.scene.ASceneFrameCallback;

import timber.log.Timber;

import static com.projecttango.tangosupport.TangoSupport.TANGO_SUPPORT_ENGINE_OPENGL;

/**
 */
public class VolumizerUiFrameCallback extends ASceneFrameCallback {

    private final Tango tango;
    private final TangoCameraIntrinsics cameraIntrinsics;

    private VolumizerUiRenderer renderer;

    private int[] connectedTextureIdGlThread = {0}; // No texture yet.
    private double cameraPoseTimestamp = 0;

    public VolumizerUiFrameCallback(Tango tango, VolumizerUiRenderer renderer) {
        this.tango = tango;
        // Register a Rajawali Scene Frame Callback to update the scene camera pose whenever a new RGB frame is rendered.
        // (@see https://github.com/Rajawali/Rajawali/wiki/Scene-Frame-Callbacks)
        this.cameraIntrinsics = tango.getCameraIntrinsics(TangoCameraIntrinsics.TANGO_CAMERA_COLOR);
        this.renderer = renderer;
    }

    @Override
    public void onPreFrame(long sceneTime, double deltaTime) {
        // NOTE: This is called from the OpenGL render thread,
        // after all the renderer onRender callbacks executed,
        // and before scene objects are rendered into the scene.

        // Set-up scene camera projection to match RGB camera intrinsics
        if (!renderer.isSceneCameraConfigured()) {
            renderer.setProjectionMatrix(cameraIntrinsics);
        }

        // Connect the camera texture to the OpenGL Texture if necessary
        // NOTE: When the OpenGL context is recycled, Rajawali may re-generate the texture with a different ID.
        if (connectedTextureIdGlThread[0] != renderer.getTextureId()) {
            tango.connectTextureId(TangoCameraIntrinsics.TANGO_CAMERA_COLOR, renderer.getTextureId());
            connectedTextureIdGlThread[0] = renderer.getTextureId();
            Timber.d("connected to texture id: %d", renderer.getTextureId());
        }

        // If there is a new RGB camera frame available, update the texture with it
        if (renderer.isFrameAvailable()) {
            renderer.setFrameAvailable(false);
            // NOTE: updateTexture() must always be called from GLThread.
            renderer.setRgbTimestampGlThread(tango.updateTexture(TangoCameraIntrinsics.TANGO_CAMERA_COLOR));
        }

        if (renderer.getRgbTimestampGlThread() > cameraPoseTimestamp) {
            // Calculate the camera color pose at the camera frame update time in OpenGL engine.
            TangoPoseData lastFramePose = TangoSupport.getPoseAtTime(
                    renderer.getRgbTimestampGlThread(),
                    TangoPoseData.COORDINATE_FRAME_START_OF_SERVICE,
                    TangoPoseData.COORDINATE_FRAME_CAMERA_COLOR,
                    TANGO_SUPPORT_ENGINE_OPENGL, 0);
            if (lastFramePose.statusCode == TangoPoseData.POSE_VALID) {
                // Update the camera pose for the renderer
                renderer.updateRenderCameraPose(lastFramePose);
                cameraPoseTimestamp = lastFramePose.timestamp;
            } else {
                Timber.w("Can't get device pose at time: %.3f", renderer.getRgbTimestampGlThread());
            }
        }
    }

    @Override
    public void onPreDraw(long sceneTime, double deltaTime) {

    }

    @Override
    public void onPostFrame(long sceneTime, double deltaTime) {

    }

    @Override
    public boolean callPreFrame() {
        return true;
    }
}
