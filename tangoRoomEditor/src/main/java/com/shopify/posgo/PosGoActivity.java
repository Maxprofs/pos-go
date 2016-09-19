package com.shopify.posgo;

import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.atap.tangoservice.Tango;
import com.google.atap.tangoservice.TangoCameraIntrinsics;
import com.google.atap.tangoservice.TangoConfig;
import com.google.atap.tangoservice.TangoCoordinateFramePair;
import com.google.atap.tangoservice.TangoEvent;
import com.google.atap.tangoservice.TangoException;
import com.google.atap.tangoservice.TangoOutOfDateException;
import com.google.atap.tangoservice.TangoPoseData;
import com.google.atap.tangoservice.TangoXyzIjData;
import com.jakewharton.rxrelay.PublishRelay;
import com.projecttango.tangosupport.TangoPointCloudManager;
import com.projecttango.tangosupport.TangoSupport;
import com.shopify.posgo.utils.TangoMath;

import org.rajawali3d.math.Matrix4;
import org.rajawali3d.math.vector.Vector3;
import org.rajawali3d.scene.ASceneFrameCallback;
import org.rajawali3d.view.SurfaceView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.OnClick;
import rx.Subscription;
import rx.android.schedulers.AndroidSchedulers;

import static com.google.atap.tangoservice.Tango.OnTangoUpdateListener;
import static com.projecttango.tangosupport.TangoSupport.IntersectionPointPlaneModelPair;
import static com.projecttango.tangosupport.TangoSupport.TANGO_SUPPORT_ENGINE_OPENGL;
import static com.projecttango.tangosupport.TangoSupport.TANGO_SUPPORT_ENGINE_TANGO;
import static com.projecttango.tangosupport.TangoSupport.TangoMatrixTransformData;
import static com.projecttango.tangosupport.TangoSupport.getPoseAtTime;

public class PosGoActivity extends AppCompatActivity implements View.OnTouchListener {

    /**
     * Think of these as states in the UI state machine.
     *
     * Mode ->
     *      -> FloorplanModes
     *      -> ProductModes
     */
    enum Mode {
        FLOORPLAN,
        PRODUCT
    }

    // MockProduct modes, Identical to FloorplanMode for now, but that could change.
    enum ProductMode {
        // VIEW -> [SELECTED, ADD]
        VIEW,
        // SELECTED -> [DELETE,ADD]
        SELECTED,
        // ADD -> [VIEW, SELECTED]
        ADD
    }

    // Floorplan modes
    enum FloorplanMode {
        // VIEW -> [SELECTED, ADD]
        // From view, select a plane, or add to last in list.
        // NOTE: Add to last should auto-select last plane in list.
        VIEW,
        // SELECTED -> [DELETE,ADD]
        // From here, you could delete the selected, or "add clockwise" to it.
        SELECTED,
        // ADD -> [VIEW, SELECTED]
        // From here, you can go back to VIEW via 'Done' button.
        // I think it would be nice to be able to jump back to 'selected' by tapping an existing plane, save a step.
        ADD
    }

    interface PlaneTranformProcessor {

        void processPlaneFitTransform(float[] planeFitTransform);
    }

    // MockProduct class as a placeholder for real products.
    private static class MockProduct {
        String name ;
    }

    private static final String TAG = PosGoActivity.class.getSimpleName();

    private static final int INVALID_TEXTURE_ID = 0;

    // *** Tango Service State ***
    private Tango tango;
    private TangoCameraIntrinsics tangoCameraIntrinsics;
    private TangoPointCloudManager tangoPointCloudManager;
    private boolean isConnected = false;
    private double cameraPoseTimestamp = 0;

    // *** GL Rendering Related ***
    // NOTE: Naming indicates which thread is in charge of updating this variable
    private int connectedTextureIdGlThread = INVALID_TEXTURE_ID;
    private AtomicBoolean isFrameAvailableTangoThread = new AtomicBoolean(false);
    private double rgbTimestampGlThread;

    // *** UI Views and Widgets ***
    @BindView(R.id.log_text)
    protected TextView logTextView;
    @BindView(R.id.parent)
    protected LinearLayout parentLayout;
    @BindView(R.id.addButton)
    protected Button addButton;
    @BindView(R.id.deleteButton)
    protected Button deleteButton;
    @BindView(R.id.doneButton)
    protected Button doneButton;
    @BindView(R.id.clearAllButton)
    protected Button clearAllButton;

    // ** View States **
    private FloorplanMode currentFloorplanMode = FloorplanMode.VIEW;

    // *** GL View Components ***
    private SurfaceView surfaceView;
    private FloorPlanEditRenderer renderer;

    // *** 'Model' State Stores and Emitters ***
    private PublishRelay<String> log = PublishRelay.create();
    private Subscription logSubscription;
    private List<float[]> wallPlanes = new ArrayList<>();
    private Map<float[],MockProduct> productMap = new HashMap<>();
    private float[] selectedPlane;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);
        ButterKnife.bind(this);

        surfaceView = new SurfaceView(this);
        surfaceView.setOnTouchListener(this);
        renderer = new FloorPlanEditRenderer(this);
        surfaceView.setSurfaceRenderer(renderer);

        parentLayout.addView(surfaceView);

        tangoPointCloudManager = new TangoPointCloudManager();

        // Jump in "add" mode if sempty.
        changeMode(wallPlanes.isEmpty()?FloorplanMode.ADD:FloorplanMode.VIEW);
    }

    @Override
    protected void onResume() {
        super.onResume();

        logSubscription = log
                .throttleFirst(100, TimeUnit.MILLISECONDS)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        log -> logTextView.setText(log),
                        throwable -> Log.e(TAG, "log error", throwable));

        if (!isConnected) {
            // runOnTangoReady is run on a new Thread().
            tango = new Tango(PosGoActivity.this, () -> {
                try {
                    TangoSupport.initialize();
                    connectTango();
                    connectRenderer();
                    isConnected = true;
                } catch (TangoOutOfDateException e) {
                    Log.e(TAG, getString(R.string.exception_out_of_date), e);
                }
            });
        }
    }

    @Override
    protected void onPause() {
        super.onPause();

        logSubscription.unsubscribe();

        // mainThread
        synchronized (this) {
            if (isConnected) {
                renderer.getCurrentScene().clearFrameCallbacks();
                tango.disconnectCamera(TangoCameraIntrinsics.TANGO_CAMERA_COLOR);
                // We need to invalidate the connected texture ID so that we cause a re-connection
                // in the OpenGL thread after resume
                connectedTextureIdGlThread = INVALID_TEXTURE_ID;
                tango.disconnect();
                isConnected = false;
            }
        }
    }

    /**
     * Configures the Tango service and connect it to callbacks.
     */
    private void connectTango() {

        TangoConfig config = buildTangoConfig();
        tango.connect(config);

        // Defining the coordinate frame pairs we are interested in.
        ArrayList<TangoCoordinateFramePair> framePairs = new ArrayList<>();
        framePairs.add(new TangoCoordinateFramePair(
                TangoPoseData.COORDINATE_FRAME_START_OF_SERVICE,
                TangoPoseData.COORDINATE_FRAME_DEVICE));
        tango.connectListener(framePairs, buildTangoUpdateListener());

        tangoCameraIntrinsics = tango.getCameraIntrinsics(TangoCameraIntrinsics.TANGO_CAMERA_COLOR);
    }

    @NonNull
    private TangoConfig buildTangoConfig() {
        // Use default configuration for Tango Service, plus low latency IMU integration.
        TangoConfig config = tango.getConfig(TangoConfig.CONFIG_TYPE_DEFAULT);
        config.putBoolean(TangoConfig.KEY_BOOLEAN_DEPTH, true);
        config.putBoolean(TangoConfig.KEY_BOOLEAN_COLORCAMERA, true);

        // NOTE: Low latency integration is necessary to achieve a precise alignment of
        // NOTE: virtual objects with the RBG image and produce a good AR effect.
        config.putBoolean(TangoConfig.KEY_BOOLEAN_LOWLATENCYIMUINTEGRATION, true);

        // NOTE: These are extra motion tracking flags.
        config.putBoolean(TangoConfig.KEY_BOOLEAN_MOTIONTRACKING, true);
        config.putBoolean(TangoConfig.KEY_BOOLEAN_AUTORECOVERY, true);
        return config;
    }

    @NonNull
    private OnTangoUpdateListener buildTangoUpdateListener() {
        return new OnTangoUpdateListener() {
            @Override
            public void onPoseAvailable(TangoPoseData pose) {
                // We could process pose data here, but we are not
                // directly using onPoseAvailable() for this app.
                logPose(pose);
            }

            @Override
            public void onFrameAvailable(int cameraId) {
                // Check if the frame available is for the camera we want and update its frame on the view.
                if (cameraId == TangoCameraIntrinsics.TANGO_CAMERA_COLOR) {
                    // Mark a camera frame is available for rendering in the OpenGL thread
                    isFrameAvailableTangoThread.set(true);
                    surfaceView.requestRender();
                }
            }

            @Override
            public void onXyzIjAvailable(TangoXyzIjData xyzIj) {
                // Save the cloud and point data for later use.
                tangoPointCloudManager.updateXyzIj(xyzIj);
            }

            @Override
            public void onTangoEvent(TangoEvent event) {
                // Information about events that occur in the Tango system.
                // Allows you to monitor the health of services at runtime.
            }
        };
    }

    /**
     * Connects the view and renderer to the color camara and callbacks.
     */
    private void connectRenderer() {
        // Register a Rajawali Scene Frame Callback to update the scene camera pose whenever a new RGB frame is rendered.
        // (@see https://github.com/Rajawali/Rajawali/wiki/Scene-Frame-Callbacks)
        renderer.getCurrentScene().registerFrameCallback(new ASceneFrameCallback() {
            @Override
            public void onPreFrame(long sceneTime, double deltaTime) {
                // NOTE: This is called from the OpenGL render thread, after all the renderer
                // onRender callbacks had a chance to run and before scene objects are rendered
                // into the scene.

                // TODO: Check, but very likely Tango thread.
                synchronized (PosGoActivity.this) {
                    // Don't execute any tango API actions if we're not connected to the service
                    if (!isConnected) {
                        return;
                    }

                    // Set-up scene camera projection to match RGB camera intrinsics
                    if (!renderer.isSceneCameraConfigured()) {
                        renderer.setProjectionMatrix(tangoCameraIntrinsics);
                    }

                    // Connect the camera texture to the OpenGL Texture if necessary
                    // NOTE: When the OpenGL context is recycled, Rajawali may re-generate the
                    // texture with a different ID.
                    if (connectedTextureIdGlThread != renderer.getTextureId()) {
                        tango.connectTextureId(TangoCameraIntrinsics.TANGO_CAMERA_COLOR, renderer.getTextureId());
                        connectedTextureIdGlThread = renderer.getTextureId();
                        Log.d(TAG, "connected to texture id: " + renderer.getTextureId());
                    }

                    // If there is a new RGB camera frame available, update the texture with it
                    if (isFrameAvailableTangoThread.compareAndSet(true, false)) {
                        rgbTimestampGlThread =
                                tango.updateTexture(TangoCameraIntrinsics.TANGO_CAMERA_COLOR);
                    }

                    if (rgbTimestampGlThread > cameraPoseTimestamp) {
                        // Calculate the camera color pose at the camera frame update time in
                        // OpenGL engine.
                        TangoPoseData lastFramePose = getPoseAtTime(
                                rgbTimestampGlThread,
                                TangoPoseData.COORDINATE_FRAME_START_OF_SERVICE,
                                TangoPoseData.COORDINATE_FRAME_CAMERA_COLOR,
                                TANGO_SUPPORT_ENGINE_OPENGL, 0);
                        if (lastFramePose.statusCode == TangoPoseData.POSE_VALID) {
                            // Update the camera pose from the renderer
                            renderer.updateRenderCameraPose(lastFramePose);
                            cameraPoseTimestamp = lastFramePose.timestamp;
                        } else {
                            Log.w(TAG, "Can't get device pose at time: " +
                                    rgbTimestampGlThread);
                        }
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
        });
    }

    @Override
    public boolean onTouch(View view, MotionEvent motionEvent) {
        switch (currentFloorplanMode) {
            case VIEW:
            case SELECTED:
                // TODO: Select a surface
                // TODO: Select a different surface, or potentially de-select.
                handleViewModeTouch(view, motionEvent);
                break;
            case ADD:
                handleAddModeTouch(view, motionEvent);
                break;
        }

        return true;
    }

    private void handleViewModeTouch(View view, MotionEvent motionEvent) {
        findPlane(view, motionEvent, planeFitTransform -> {
            // TODO: We need to change this to a ray collision hit detection.
            // TODO: Add code to detect proximity of touch vs existing ones.
            Matrix4 objectTransform = new Matrix4(planeFitTransform);
            Vector3 planeLocation = objectTransform.getTranslation();
            for (float[] currentPlane : wallPlanes) {
                if (planeLocation.distanceTo(new Matrix4(currentPlane).getTranslation()) < .5) {
                    renderer.updateSelectedTransform(currentPlane);
                    selectedPlane = currentPlane ;
                    changeMode(FloorplanMode.SELECTED);
                }
            }
//            Quaternion planeOrientation = new Quaternion().fromMatrix(objectTransform).conjugate();

        });
    }

    private void handleAddModeTouch(View view, MotionEvent motionEvent) {
        findPlane(view, motionEvent, planeFitTransform -> {
            wallPlanes.add(planeFitTransform);
            renderer.updateWallPlanes(wallPlanes);
        });
    }

    private void findPlane(View view, MotionEvent motionEvent, PlaneTranformProcessor planeTranformProcessor) {
        if (motionEvent.getAction() == MotionEvent.ACTION_UP) {
            // Calculate click location in u,v (0;1) coordinates.
            float u = motionEvent.getX() / view.getWidth();
            float v = motionEvent.getY() / view.getHeight();

            try {
                // Fit a plane on the clicked point.
                float[] planeFitTransform;

                // mainThread
                synchronized (this) {
                    planeFitTransform = doFitPlane(u, v, rgbTimestampGlThread);
                }

                if (planeFitTransform != null) {
                    planeTranformProcessor.processPlaneFitTransform(planeFitTransform);
                }
            } catch (TangoException t) {
                Toast.makeText(getApplicationContext(), R.string.failed_measurement, Toast.LENGTH_SHORT).show();
                Log.e(TAG, getString(R.string.failed_measurement), t);
            } catch (SecurityException t) {
                Toast.makeText(getApplicationContext(), R.string.failed_permissions, Toast.LENGTH_SHORT).show();
                Log.e(TAG, getString(R.string.failed_permissions), t);
            }
        }
    }

    /**
     * Use the TangoSupport library with point cloud data to calculate the plane
     * of the world feature pointed at the location the camera is looking.
     * It returns the transform of the fitted plane in a double array.
     */
    private float[] doFitPlane(float u, float v, double rgbTimestamp) {
        TangoXyzIjData xyzIj = tangoPointCloudManager.getLatestXyzIj();

        if (xyzIj == null) {
            return null;
        }

        // We need to calculate the transform between the color camera at the
        // time the user clicked, and the depth camera at the time the depth
        // cloud was acquired.
        TangoPoseData colorTdepthPose =
                TangoSupport.calculateRelativePose(
                        rgbTimestamp, TangoPoseData.COORDINATE_FRAME_CAMERA_COLOR,
                        xyzIj.timestamp, TangoPoseData.COORDINATE_FRAME_CAMERA_DEPTH);

        // Perform plane fitting with the latest available point cloud data.
        IntersectionPointPlaneModelPair intersectionPointPlaneModelPair =
                TangoSupport.fitPlaneModelNearClick(xyzIj, tangoCameraIntrinsics, colorTdepthPose, u, v);

        // Get the transform from depth camera to OpenGL world at
        // the timestamp of the cloud.
        TangoMatrixTransformData transform =
                TangoSupport.getMatrixTransformAtTime(
                        xyzIj.timestamp,
                        TangoPoseData.COORDINATE_FRAME_START_OF_SERVICE,
                        TangoPoseData.COORDINATE_FRAME_CAMERA_DEPTH,
                        TANGO_SUPPORT_ENGINE_OPENGL,
                        TANGO_SUPPORT_ENGINE_TANGO);

        if (transform.statusCode == TangoPoseData.POSE_VALID) {
            float[] openGlTPlane = TangoMath.calculatePlaneTransform(
                    intersectionPointPlaneModelPair.intersectionPoint,
                    intersectionPointPlaneModelPair.planeModel, transform.matrix);

            return openGlTPlane;
        } else {
            Log.w(TAG, "Can't get depth camera transform at time " + xyzIj.timestamp);
            return null;
        }
    }

    /**
     * Log the Position and Orientation of the given pose in the Logcat as information.
     *
     * @param pose the pose to log.
     */
    private void logPose(TangoPoseData pose) {
        StringBuilder stringBuilder = new StringBuilder();
        float translation[] = pose.getTranslationAsFloats();
        float orientation[] = pose.getRotationAsFloats();

        stringBuilder.append(String.format("[%+3.3f,%+3.3f,%+3.3f]\n", translation[0], translation[1], translation[2]));
        stringBuilder.append(String.format("(%+3.3f,%+3.3f,%+3.3f,%+3.3f)", orientation[0], orientation[1], orientation[2], orientation[3]));

        log.call(stringBuilder.toString());
    }

    @OnClick(R.id.addButton)
    void onAddClick() {
        changeMode(FloorplanMode.ADD);
    }

    @OnClick(R.id.deleteButton)
    void onDeleteClick() {
        if( selectedPlane!=null ) {
            wallPlanes.remove(selectedPlane);
            selectedPlane = null;
            renderer.updateWallPlanes(wallPlanes);
        }
        changeMode(FloorplanMode.VIEW);
    }

    @OnClick(R.id.clearAllButton)
    void onClearAllClick() {
        wallPlanes.clear();
        if (selectedPlane != null) {
            selectedPlane = null;
            changeMode(FloorplanMode.VIEW);
        }
        renderer.updateWallPlanes(wallPlanes);
    }

    @OnClick(R.id.doneButton)
    void onDoneClick() {
        changeMode(FloorplanMode.VIEW);
    }

    void changeMode(FloorplanMode floorplanMode) {
        currentFloorplanMode = floorplanMode;
        switch (floorplanMode) {
            case VIEW:
                addButton.setEnabled(true);
                deleteButton.setEnabled(false);
                clearAllButton.setEnabled(true);
                doneButton.setEnabled(false);
                break;
            case SELECTED:
                addButton.setEnabled(true);
                deleteButton.setEnabled(true);
                clearAllButton.setEnabled(false);
                doneButton.setEnabled(true);
                break;
            case ADD:
                addButton.setEnabled(false);
                deleteButton.setEnabled(false);
                clearAllButton.setEnabled(true);
                doneButton.setEnabled(true);
                break;
        }
    }
}
