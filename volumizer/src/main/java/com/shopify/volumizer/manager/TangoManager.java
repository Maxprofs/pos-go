package com.shopify.volumizer.manager;

import android.app.Application;
import android.os.HandlerThread;
import android.os.Looper;
import android.support.annotation.MainThread;
import android.support.annotation.NonNull;

import com.google.atap.tangoservice.Tango;
import com.google.atap.tangoservice.TangoAreaDescriptionMetaData;
import com.google.atap.tangoservice.TangoCameraIntrinsics;
import com.google.atap.tangoservice.TangoConfig;
import com.google.atap.tangoservice.TangoCoordinateFramePair;
import com.google.atap.tangoservice.TangoEvent;
import com.google.atap.tangoservice.TangoOutOfDateException;
import com.google.atap.tangoservice.TangoPointCloudData;
import com.google.atap.tangoservice.TangoPoseData;
import com.google.atap.tangoservice.TangoXyzIjData;
import com.projecttango.tangosupport.TangoPointCloudManager;
import com.projecttango.tangosupport.TangoSupport;
import com.shopify.volumizer.R;
import com.shopify.volumizer.utils.TangoMath;

import java.util.ArrayList;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.functions.Action;
import io.reactivex.functions.Consumer;
import io.reactivex.subjects.PublishSubject;
import timber.log.Timber;

import static com.projecttango.tangosupport.TangoSupport.TANGO_SUPPORT_ENGINE_OPENGL;
import static com.projecttango.tangosupport.TangoSupport.TANGO_SUPPORT_ENGINE_TANGO;

/**
 * Ideally just abstracts the setup and teardown of Tango services.
 */
@Singleton
public class TangoManager {

    @Inject Application appContext ;

    @Inject TangoPointCloudManager tangoPointCloudManager;

    // TODO: This might be a clever thing, or a very bad idea. Ask someone to review.
    // The idea is to use this to execute jobs on the main thread.
    private PublishSubject<Action> mainThreadActionQueue = PublishSubject.create();
    // Tango `connect()` can't run off of main thread,
    private PublishSubject<Action> internalActionQueue = PublishSubject.create();

    // *** Tango Service State ***
    private Tango tango;

    private Observable<Object> sharedObservable;
    private Disposable disposableMain;
    private Disposable disposableInternal;

    public TangoManager() {
    }

    /**
     * NOTES - Threading considerations
     *
     * ... WiP, basically trying to use message queues vs the synchronize blocks in Tango code samples.
     *
     * @param tangoReadyHandler
     */
    @MainThread
    public void startTango(Consumer<Tango> tangoReadyHandler) {

        // Creates a main-thread job queue.
        disposableMain = mainThreadActionQueue
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        Action::run,
                        Timber::e,
                        () -> Timber.i("mainThreadActionQueue onComplete()"));

        // Create a job queue internal to tango manager.
        HandlerThread handlerThread = new HandlerThread("CustomTangoManager");
        handlerThread.start();
        disposableInternal = internalActionQueue
                .observeOn(AndroidSchedulers.from(handlerThread.getLooper()))
                .subscribe(
                        Action::run,
                        Timber::e,
                        () -> Timber.i("internalActionQueue onComplete()"));

        tango = new Tango(appContext, () -> {
            internalActionQueue.onNext(() -> {
                try {
                    TangoSupport.initialize();
                    tango.connect(buildTangoConfig());

                    // Defining the coordinate frame pairs we are interested in.
                    ArrayList<TangoCoordinateFramePair> framePairs = new ArrayList<>();
                    framePairs.add(new TangoCoordinateFramePair(
                            TangoPoseData.COORDINATE_FRAME_START_OF_SERVICE,
                            TangoPoseData.COORDINATE_FRAME_DEVICE));
                    // NOTE: sharedObservable emits "on" the android main thread
                    sharedObservable = buildSourceSharedObservable(tango, framePairs);

                } catch (TangoOutOfDateException e) {
                    Timber.e(e, appContext.getString(R.string.exception_out_of_date));
                }

                // NOTE: Instead of using a message queue, maybe I'll turn this into an event...
                mainThreadActionQueue.onNext(() -> {
                    tangoReadyHandler.accept(tango);
                });

            });
        });
    }

    @NonNull
    private TangoConfig buildTangoConfig() {
        // Use default configuration for Tango Service, plus low latency IMU integration.
        TangoConfig config = tango.getConfig(TangoConfig.CONFIG_TYPE_DEFAULT);

        // NOTE: Low latency integration is necessary to achieve a precise alignment of
        // NOTE: virtual objects with the RBG image and produce a good AR effect.
        config.putBoolean(TangoConfig.KEY_BOOLEAN_LOWLATENCYIMUINTEGRATION, true);
        config.putBoolean(TangoConfig.KEY_BOOLEAN_COLORCAMERA, true);

        config.putBoolean(TangoConfig.KEY_BOOLEAN_DEPTH, true);
        config.putInt(TangoConfig.KEY_INT_DEPTH_MODE, TangoConfig.TANGO_DEPTH_MODE_POINT_CLOUD);

        // NOTE: These are extra motion tracking flags.
        config.putBoolean(TangoConfig.KEY_BOOLEAN_MOTIONTRACKING, true);
        config.putBoolean(TangoConfig.KEY_BOOLEAN_AUTORECOVERY, true);

        // NOTE: Area learning is necessary to achieve better precision is pose estimation
        config.putBoolean(TangoConfig.KEY_BOOLEAN_LEARNINGMODE, true);

        // ADF configuration
/*
        if (isAreaLearningMode) {
            config.putBoolean(TangoConfig.KEY_BOOLEAN_LEARNINGMODE, true);
        }
        if (isLoadAdfMode) {
            // List of all available ADF
            ArrayList<String> fullUuidList = tango.listAreaDescriptions();
            if (!fullUuidList.isEmpty()) {
                // Get latest, for now.
                String latestUuid = fullUuidList.get(fullUuidList.size() - 1);
                config.putString(TangoConfig.KEY_STRING_AREADESCRIPTION, latestUuid);
                loadAdfName(latestUuid);
            }
        }
*/
        return config;
    }

    private static Observable<Object> buildSourceSharedObservable(Tango tango, ArrayList<TangoCoordinateFramePair> framePairs) {
        return Observable
                .create(e -> {
                    Tango.OnTangoUpdateListener updateListener = new Tango.OnTangoUpdateListener() {
                        @Override
                        public void onPoseAvailable(TangoPoseData tangoPoseData) {
                            e.onNext(tangoPoseData);
                        }

                        @Override
                        public void onXyzIjAvailable(TangoXyzIjData tangoXyzIjData) {
                            e.onNext(tangoXyzIjData);
                        }

                        @Override
                        public void onFrameAvailable(int i) {
                            e.onNext(i);
                        }

                        @Override
                        public void onTangoEvent(TangoEvent tangoEvent) {
                            e.onNext(tangoEvent);
                        }

                        @Override
                        public void onPointCloudAvailable(TangoPointCloudData tangoPointCloudData) {
                            e.onNext(tangoPointCloudData);
                        }
                    };

                    tango.connectListener(framePairs, updateListener);

                    e.setCancellable(() -> tango.connectListener(null, null));
                })
                .share()
                .observeOn(AndroidSchedulers.mainThread());
    }

    // Thread safe
    public void stopTango() {
        Timber.d("tangoManager.stopTango()");

        mainThreadActionQueue.onNext(() -> {
            Timber.d("tangoManager.stopTango()->mainThreadActionQueue.onNext()");

            // Insures no `tangoReadyHandler` execute going forward.
            disposableMain.dispose();

            internalActionQueue.onNext(() -> {
                Timber.d("tangoManager.stopTango()->internalActionQueue.onNext()");

                // Insures no `runOnTangoReady` execute going forward.
                disposableInternal.dispose();
                tango.disconnectCamera(TangoCameraIntrinsics.TANGO_CAMERA_COLOR);
                tango.disconnect();

                Looper looper = Looper.myLooper();
                if (looper != null) looper.quit();
            });
        });
    }

    public Observable<TangoPoseData> getPoseObservable() {
        return sharedObservable.ofType(TangoPoseData.class);
    }

    public Observable<TangoXyzIjData> getXyzIjObservable() {
        return sharedObservable.ofType(TangoXyzIjData.class);
    }

    public Observable<Integer> getFrameObservable() {
        return sharedObservable.ofType(Integer.class);
    }

    public Observable<TangoEvent> getTangoEventObservable() {
        return sharedObservable.ofType(TangoEvent.class);
    }

    public Observable<TangoPointCloudData> getPointCloudDataObservable() {
        return sharedObservable.ofType(TangoPointCloudData.class);
    }

    private String loadAdfName(String latestUuid) {
        TangoAreaDescriptionMetaData metaData = tango.loadAreaDescriptionMetaData(latestUuid);
        byte[] nameBytes = metaData.get(TangoAreaDescriptionMetaData.KEY_NAME);
        if (nameBytes != null) {
            String result = new String(nameBytes);
            Timber.i("ADF name: %s", result);
            return result;
        } else {
            return null;
        }
    }

    public void saveAdfName(String name, String uuid) {
        TangoAreaDescriptionMetaData metadata = new TangoAreaDescriptionMetaData();
        metadata = tango.loadAreaDescriptionMetaData(uuid);
        metadata.set(TangoAreaDescriptionMetaData.KEY_NAME, name.getBytes());
        tango.saveAreaDescriptionMetadata(uuid, metadata);
    }

    /**
     * Use the TangoSupport library with point cloud data to calculate the plane
     * of the world feature pointed at the location the camera is looking.
     * It returns the transform of the fitted plane in a double array.
     */
    public float[] doFitPlane(float u, float v, double rgbTimestamp) {
        TangoPointCloudData pointCloud = tangoPointCloudManager.getLatestPointCloud();

        if (pointCloud == null) {
            return null;
        }

        // We need to calculate the transform between the color camera at the
        // time the user clicked, and the depth camera at the time the depth
        // cloud was acquired.
        TangoPoseData colorTdepthPose =
                TangoSupport.calculateRelativePose(
                        rgbTimestamp, TangoPoseData.COORDINATE_FRAME_CAMERA_COLOR,
                        pointCloud.timestamp, TangoPoseData.COORDINATE_FRAME_CAMERA_DEPTH);

        // Perform plane fitting with the latest available point cloud data.
        TangoSupport.IntersectionPointPlaneModelPair intersectionPointPlaneModelPair =
                TangoSupport.fitPlaneModelNearPoint(pointCloud, colorTdepthPose, u, v);

        // Get the transform from depth camera to OpenGL world at
        // the timestamp of the cloud.
        TangoSupport.TangoMatrixTransformData transform =
                TangoSupport.getMatrixTransformAtTime(
                        pointCloud.timestamp,
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
            Timber.w("Can't get depth camera transform at time %.3f", pointCloud.timestamp);
            return null;
        }
    }


    @NonNull
    public static String buildPoseLogMessage(TangoPoseData pose) {
        StringBuilder stringBuilder = new StringBuilder();
        float translation[] = pose.getTranslationAsFloats();
        float orientation[] = pose.getRotationAsFloats();

        stringBuilder.append(String.format("[%+3.3f,%+3.3f,%+3.3f]\n", translation[0], translation[1], translation[2]));
        stringBuilder.append(String.format("(%+3.3f,%+3.3f,%+3.3f,%+3.3f)", orientation[0], orientation[1], orientation[2], orientation[3]));

        return stringBuilder.toString();
    }


}
