package com.shopify.volumizer;

import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Bundle;
import android.support.annotation.MainThread;
import android.support.v7.app.AppCompatActivity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Plane;
import com.badlogic.gdx.math.Vector3;
import com.google.atap.tangoservice.Tango;
import com.google.atap.tangoservice.TangoCameraIntrinsics;
import com.google.atap.tangoservice.TangoPoseData;
import com.projecttango.tangosupport.TangoPointCloudManager;
import com.projecttango.tangosupport.TangoSupport;
import com.shopify.volumizer.manager.TangoManager;
import com.shopify.volumizer.render.CustomRenderer;
import com.shopify.volumizer.utils.MatrixUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import butterknife.BindView;
import butterknife.ButterKnife;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;
import io.reactivex.subjects.PublishSubject;
import timber.log.Timber;
import toothpick.Scope;
import toothpick.Toothpick;

public class VolumizerActivity extends AppCompatActivity implements View.OnTouchListener {

    // Used to cast from List<float[]> to float[][].
    private final static float[][] PLANES_TO_ARRAY_TYPE = {};


    // *** UI Views and Widgets ***
    @BindView(R.id.log_text)
    protected TextView logTextView;
    @BindView(R.id.parent)
    protected LinearLayout parentLayout;
    @BindView(R.id.glSurfaceView)
    protected GLSurfaceView glSurfaceView;

    // *** Tango Service State ***
    @Inject TangoManager tangoManager;
    @Inject TangoPointCloudManager tangoPointCloudManager;

    // *** Lifecycle ***
    private CompositeDisposable mainDisposables;
    private Disposable glDisposable;

    //    private boolean isAreaLearningMode;
    //    private boolean isLoadAdfMode;

    // ** OpenGL **
    private PublishSubject<Runnable> glThreadActionQueue = PublishSubject.create();
    private CustomRenderer renderer; // NOTE: Scene graph is not thread safe.


    // *** State ***
    private List<float[]> planes = new ArrayList<>();


    // TODO: This makes no sense in activity. Should probably move to renderer, expose 'threadsafe' methods/functions.
    // NOTE: Will be faking it here, since we can only really access glThread via glSurfaceView's queueEvent(Runnable) method.
    private int displayRotation;

    /**
     * Function to calculate projection matrix from displayRotation.
     */
    private static float[] calculateProjectionMatrix(int displayRotation) {
        return projectionMatrixFromCameraIntrinsics(
                TangoSupport.getCameraIntrinsicsBasedOnDisplayRotation(
                        TangoCameraIntrinsics.TANGO_CAMERA_COLOR, displayRotation));
    }

    /**
     * Use Tango camera intrinsics to calculate the projection Matrix for the OpenGL scene.
     *
     * @param intrinsics camera instrinsics for computing the project matrix.
     */
    private static float[] projectionMatrixFromCameraIntrinsics(TangoCameraIntrinsics intrinsics) {
        float cx = (float) intrinsics.cx;
        float cy = (float) intrinsics.cy;
        float width = (float) intrinsics.width;
        float height = (float) intrinsics.height;
        float fx = (float) intrinsics.fx;
        float fy = (float) intrinsics.fy;

        // Uses frustumM to create a projection matrix taking into account calibrated camera
        // intrinsic parameter.
        // Reference: http://ksimek.github.io/2013/06/03/calibrated_cameras_in_opengl/
        float near = 0.1f;
        float far = 100;

        float xScale = near / fx;
        float yScale = near / fy;
        float xOffset = (cx - (width / 2.0f)) * xScale;
        // Color camera's coordinates has y pointing downwards so we negate this term.
        float yOffset = -(cy - (height / 2.0f)) * yScale;

        float m[] = new float[16];
        Matrix.frustumM(m, 0,
                xScale * (float) -width / 2.0f - xOffset,
                xScale * (float) width / 2.0f - xOffset,
                yScale * (float) -height / 2.0f - yOffset,
                yScale * (float) height / 2.0f - yOffset,
                near, far);
        return m;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Scope scope = Toothpick.openScopes(getApplication(), this);
        Toothpick.inject(this, scope);

        setContentView(R.layout.activity_main);
        ButterKnife.bind(this);

        // Setup our GL rendering surfaces.
        glSurfaceView.setEGLContextClientVersion(2);
        try {
            renderer = new CustomRenderer(getApplicationContext());
        } catch (IOException e) {
            new RuntimeException("Oh crap.");
        }
        glSurfaceView.setRenderer(renderer);
        glSurfaceView.setOnTouchListener(this);

        // Subscribe to the glThreadActionQueue, the Tango initialization 'follow up' code queues runnables on this.
        glDisposable = glThreadActionQueue.subscribe(
                r -> glSurfaceView.queueEvent(r),
                Timber::e,
                () -> Timber.i("glThreadActionQueue onComplete()")
        );

        displayRotation = getWindowManager().getDefaultDisplay().getRotation();

        // Jump in "add" mode if empty.
//        changeMode(planes.isEmpty() ? VolumizerMode.ADDING_PLANE : VolumizerMode.EDITING_PLANE);

//        isAreaLearningMode = getIntent().getBooleanExtra(StartActivity.USE_AREA_LEARNING, false);
//        isLoadAdfMode = getIntent().getBooleanExtra(StartActivity.LOAD_ADF, false);

    }

    @Override
    protected void onResume() {
        super.onResume();
        glSurfaceView.onResume();

        mainDisposables = new CompositeDisposable();
        tangoManager.startTango(this::onTangoConnected);
    }

    @MainThread
    private void onTangoConnected(Tango tango) {

        // Poses are reported to screen
        tangoManager.getPoseObservable()
                .throttleLast(100, TimeUnit.MILLISECONDS)
                .map(TangoManager::buildPoseLogMessage)
                .observeOn(AndroidSchedulers.mainThread()) // NOTE: Needed since throttleLast() goes to computation.
                .subscribe(
                        log -> logTextView.setText(log),
                        throwable -> Timber.e(throwable, "pose stream onError()"),
                        () -> Timber.i("pose stream onComplete()"),
                        mainDisposables::add
                );

        // Save the cloud and point data for later use.
        tangoManager.getPointCloudDataObservable()
                .doOnSubscribe(mainDisposables::add)
                .subscribe(tangoPointCloudManager::updatePointCloud);

        // INITIALIZE -> GLSurfaceView.CustomRenderer
        glThreadActionQueue.onNext(() -> initCustomRenderer(tango));

        // CAMERA FRAME FEED -> GLSurfaceView.CustomRenderer
        tangoManager.getFrameObservable()
                // Check if the frame available is for the camera we want and update its frame on the view.
                .filter(cameraId -> cameraId == TangoCameraIntrinsics.TANGO_CAMERA_COLOR)
                .map(cameraId -> (Runnable) () -> processCameraFrame(tango))
                .doOnSubscribe(mainDisposables::add)
                // Queue the process call on the GlThread.
                .subscribe(glThreadActionQueue::onNext);
    }

    private void initCustomRenderer(Tango tango) {
        renderer.setProjectionMatrix(calculateProjectionMatrix(displayRotation));

        // "Empty state" when we start.
        float[] triangleTransform = new float[16];
        Matrix.setIdentityM(triangleTransform, 0);
        Matrix.translateM(triangleTransform, 0, 0, 0, -2.5f);
        renderer.updatePlanes(new float[][]{triangleTransform});

        // We only connect to renderer once tango is connected, so,
        // the OpenGL camera texture should always be ready at this point.
        Timber.d("tango.connectTextureId( %d )", renderer.getCameraTextureId());
        tango.connectTextureId(
                TangoCameraIntrinsics.TANGO_CAMERA_COLOR,
                renderer.getCameraTextureId());

        // TODO: Add more of the cross-boundary 'gl init' code here, if needed.
    }

    private void processCameraFrame(Tango tango) {
        final double rgbTimestamp = tango.updateTexture(TangoCameraIntrinsics.TANGO_CAMERA_COLOR);

        TangoSupport.TangoMatrixTransformData transform =
                TangoSupport.getMatrixTransformAtTime(
                        rgbTimestamp,
                        TangoPoseData.COORDINATE_FRAME_AREA_DESCRIPTION,
                        TangoPoseData.COORDINATE_FRAME_CAMERA_COLOR,
                        TangoSupport.TANGO_SUPPORT_ENGINE_OPENGL,
                        TangoSupport.TANGO_SUPPORT_ENGINE_OPENGL,
                        displayRotation);

        if (transform.statusCode == TangoPoseData.POSE_VALID) {
            renderer.updateViewMatrix(transform.matrix);
            renderer.updateScene(rgbTimestamp);
        } else {
            // NOTE: Shaking the device hard seems to flip the status code to 'initializing' and it never comes back?

            // When the pose status is not valid, it indicates tracking
            // has been lost. In this case, we simply stop rendering.

            // This is also the place to display UI to suggest the user
            // walk to recover tracking.
            Timber.w("transform { statusCode: %d, timestamp: %f }, rgbTimestamp %f ",
                    transform.statusCode, transform.timestamp, rgbTimestamp);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        glSurfaceView.onPause();

        // Ensures no tango event observers will be executed going forward.
        mainDisposables.dispose();
        // Shut down gl rendering.
        glThreadActionQueue.onNext(() -> {
            glDisposable.dispose();

            // We can stop tango now, and renderer won't try to access it.
            tangoManager.stopTango();
        });
    }

    @Override
    public boolean onTouch(View view, MotionEvent motionEvent) {

        if (motionEvent.getAction() == MotionEvent.ACTION_UP) {
            // Calculate click location in u,v (0;1) coordinates.
            float u = motionEvent.getX() / view.getWidth();
            float v = motionEvent.getY() / view.getHeight();

            float[] planeFitTransform = tangoManager.doFitPlane(u, v, renderer.getRgbTimestamp(), displayRotation);
            if (planeFitTransform != null) {
                planes.add(planeFitTransform);
                renderer.updatePlanes(planes.toArray(PLANES_TO_ARRAY_TYPE));
            }
        }

        return true;
    }

    private void consumeTransform(float[] transform) {
        Timber.i(MatrixUtils.matrixToString(transform));
//        Plane
        final Matrix4 mat = new Matrix4(transform);
        final Vector3 translation = mat.getTranslation(new Vector3());

        Vector3
    }

}
