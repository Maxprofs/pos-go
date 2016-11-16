package com.shopify.volumizer;

import android.app.Application;

import com.projecttango.tangosupport.TangoPointCloudManager;
import com.shopify.volumizer.manager.TangoManager;

import timber.log.Timber;
import toothpick.Scope;
import toothpick.Toothpick;
import toothpick.config.Module;
import toothpick.smoothie.module.SmoothieApplicationModule;

/**
 * Created by kanawish on 2016-07-17.
 */

public class VolumizerApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();

        Scope appScope = Toothpick.openScope(this);
        appScope.installModules(
                new SmoothieApplicationModule(this),
                new TestModule());

        if (BuildConfig.DEBUG) {
            Timber.plant(new Timber.DebugTree());
        } // NOTE: No logging in release mode.
    }
}