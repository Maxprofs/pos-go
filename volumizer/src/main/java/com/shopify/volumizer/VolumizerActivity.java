package com.shopify.volumizer;

import android.os.Bundle;
import android.support.annotation.MainThread;
import android.support.v7.app.AppCompatActivity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.atap.tangoservice.Tango;
import com.google.atap.tangoservice.TangoCameraIntrinsics;
import com.google.atap.tangoservice.TangoException;
import com.projecttango.tangosupport.TangoPointCloudManager;
import com.shopify.volumizer.manager.TangoManager;
import com.shopify.volumizer.render.VolumizerUiFrameCallback;
import com.shopify.volumizer.render.VolumizerUiRenderer;

import org.rajawali3d.math.Matrix4;
import org.rajawali3d.math.vector.Vector3;
import org.rajawali3d.scene.ASceneFrameCallback;
import org.rajawali3d.view.SurfaceView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.OnClick;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;
import io.reactivex.functions.Action;
import io.reactivex.functions.Consumer;
import io.reactivex.subjects.PublishSubject;
import timber.log.Timber;
import toothpick.Scope;
import toothpick.Toothpick;

public class VolumizerActivity extends AppCompatActivity implements View.OnTouchListener {

    // New Volumizer modes
    // NOTE: Think width/height/depth are relative to plane.
    enum VolumizerMode {
        ADDING_PLANE, // No selections, next plane fit = new plane added.
        EDITING_PLANE // Plane currently selected, editing width / height / depth.
    }

    // MockProduct class as a placeholder for real products.
    private static class MockProduct {

        String name;
    }

    // *** UI Views and Widgets ***
    @BindView(R.id.log_text)
    protected TextView logTextView;
    @BindView(R.id.parent)
    protected LinearLayout parentLayout;
    @BindView(R.id.actionButton)
    protected Button actionButton;

    // *** GL View Components ***
    private SurfaceView surfaceView;
    private VolumizerUiRenderer renderer; // NOTE: Scene graph is not thread safe.

    // ** View States **
    private VolumizerMode mode = VolumizerMode.ADDING_PLANE;

    // *** 'Model' State Stores and Emitters ***
    private CompositeDisposable mainThreadDisposables;

    private List<float[]> planes = new ArrayList<>();
    private float[] selectedPlane;

    //    private boolean isAreaLearningMode;
    private boolean isLoadAdfMode;

    // *** Tango Service State ***
    @Inject TangoManager tangoManager;
    @Inject TangoPointCloudManager tangoPointCloudManager;


    // *** GL Rendering Related ***
//    // NOTE: Naming indicates which thread is in charge of updating this variable
//    private AtomicBoolean isFrameAvailableTangoThread = new AtomicBoolean(false);
//    private double rgbTimestampGlThread;

    // TODO: This makes no sense in activity. Should probably move to renderer, expose 'threadsafe' methods/functions.
    // NOTE: Will be faking it here, since we can only really access glThread via surfaceView's queueEvent(Runnable) method.
    private PublishSubject<Runnable> glThreadActionQueue = PublishSubject.create();
    private Disposable glThreadActionDisposable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Scope scope = Toothpick.openScopes(getApplication(), this);
        Toothpick.inject(this, scope);

        setContentView(R.layout.activity_main);
        ButterKnife.bind(this);

        // Setup our GL rendering surfaces.
        surfaceView = new SurfaceView(this);
        surfaceView.setOnTouchListener(this);
        renderer = new VolumizerUiRenderer(this);
        surfaceView.setSurfaceRenderer(renderer);

        parentLayout.addView(surfaceView);

        glThreadActionDisposable = glThreadActionQueue.subscribe(
                r -> surfaceView.queueEvent(r),
                Timber::e,
                () -> Timber.i("glThreadActionQueue onComplete()")
        );

        // Jump in "add" mode if empty.
        changeMode(planes.isEmpty() ? VolumizerMode.ADDING_PLANE : VolumizerMode.EDITING_PLANE);

//        isAreaLearningMode = getIntent().getBooleanExtra(StartActivity.USE_AREA_LEARNING, false);
        isLoadAdfMode = getIntent().getBooleanExtra(StartActivity.LOAD_ADF, false);
    }

    @Override
    protected void onResume() {
        super.onResume();

        mainThreadDisposables = new CompositeDisposable();
        tangoManager.startTango(this::onTangoConnected);
    }

    @MainThread
    private void onTangoConnected(Tango tango) {
        // Poses are reported to screen
        tangoManager.getPoseObservable()
                .throttleLast(100, TimeUnit.MILLISECONDS)
                .map(TangoManager::buildPoseLogMessage)
//                .doOnNext(Timber::i) // Uncomment for logging.
                .observeOn(AndroidSchedulers.mainThread()) // NOTE: Needed since throttleLast() goes to computation.
                .subscribe(
                        log -> logTextView.setText(log),
                        throwable -> Timber.e(throwable, "pose stream onError()"),
                        () -> Timber.i("pose stream onComplete()"),
                        mainThreadDisposables::add
                );

        tangoManager.getFrameObservable()
                // Check if the frame available is for the camera we want and update its frame on the view.
                .filter(cameraId -> cameraId == TangoCameraIntrinsics.TANGO_CAMERA_COLOR)
                .doOnSubscribe(mainThreadDisposables::add)
                // Mark a camera frame is available for rendering in the OpenGL thread
                .subscribe(cameraId -> glThreadActionQueue.onNext( () -> renderer.setFrameAvailable(true) ));

        // Save the cloud and point data for later use.
        tangoManager.getPointCloudDataObservable()
                .doOnSubscribe(mainThreadDisposables::add)
                .subscribe(tangoPointCloudManager::updatePointCloud);

        // Renderer needs tango before starting up.
        connectRenderer(tango);
    }

    @Override
    protected void onPause() {
        super.onPause();

        // Ensures no tango event observers will be executed going forward.
        mainThreadDisposables.dispose();
        // Shut down gl rendering.
        glThreadActionQueue.onNext(()->{
            glThreadActionDisposable.dispose();

            renderer.getCurrentScene().clearFrameCallbacks();
            // We can stop tango now, and renderer won't try to access it.
            tangoManager.stopTango();
        });
    }

    /**
     * Connects the view and renderer to the color camera and callbacks.
     */
    private void connectRenderer(Tango tango) {
        glThreadActionQueue.onNext(()->{
            ASceneFrameCallback sceneFrameCallback = new VolumizerUiFrameCallback(tango, renderer);
            renderer.getCurrentScene().registerFrameCallback(sceneFrameCallback);
        });
    }

    @Override
    public boolean onTouch(View view, MotionEvent motionEvent) {
        switch (mode) {
            case ADDING_PLANE:
                break;
            case EDITING_PLANE:
                break;
        }
        return true;
    }

    // Plane selector
    private void handleViewModeTouch(View view, MotionEvent motionEvent) {
        findPlane(0.5f,0.5f, planeFitTransform -> {
            // TODO: We need to change this to a ray collision hit detection.
            // TODO: Add code to detect proximity of touch vs existing ones.
            Matrix4 objectTransform = new Matrix4(planeFitTransform);
            Vector3 planeLocation = objectTransform.getTranslation();
            for (float[] currentPlane : planes) {
                if (planeLocation.distanceTo(new Matrix4(currentPlane).getTranslation()) < .5) {
                    renderer.updateSelectedTransform(currentPlane);
                    selectedPlane = currentPlane;
                }
            }
//            Quaternion planeOrientation = new Quaternion().fromMatrix(objectTransform).conjugate();

        });
    }

    // TODO: Think of moving this to renderer, and refactor that one to better split model / business logic from rendering...
    // The 'domain seam' here might be something like
    private void findPlane(float u, float v, Consumer<float[]> planeProcessor) {
        glThreadActionQueue.onNext( () -> {
            try {
                // Fit a plane on the clicked point.
                float[] planeFitTransform = tangoManager.doFitPlane(u, v, renderer.getRgbTimestampGlThread());

                if (planeFitTransform != null) {
                    planeProcessor.accept(planeFitTransform);
                }
            } catch (TangoException e) {
                Toast.makeText(getApplicationContext(), R.string.failed_measurement, Toast.LENGTH_SHORT).show();
                Timber.e(getString(R.string.failed_measurement));
            } catch (SecurityException e) {
                Toast.makeText(getApplicationContext(), R.string.failed_permissions, Toast.LENGTH_SHORT).show();
                Timber.e(e, getString(R.string.failed_permissions));
            } catch (Exception e) {
                Timber.e(e,"Unknown issue");
            }
        });
    }

    @OnClick(R.id.actionButton)
    void onAddClick() {
        switch (mode) {
            case ADDING_PLANE:
                findPlane(0.5f, 0.5f, planeFitTransform -> {
                    planes.add(planeFitTransform);
                    renderer.updateWallPlanes(planes);
                });
                break;
            case EDITING_PLANE:
                break;
        }
    }

    void changeMode(VolumizerMode mode) {
        this.mode = mode;
        switch (mode) {
            case ADDING_PLANE:
                actionButton.setText("ADD");
                break;
            case EDITING_PLANE:
                actionButton.setText("EDIT");
                break;
        }
    }
}
