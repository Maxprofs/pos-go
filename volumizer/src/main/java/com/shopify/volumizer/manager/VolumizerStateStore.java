package com.shopify.volumizer.manager;

import android.app.Application;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 */
@Singleton
public class VolumizerStateStore {
    @Inject Application appContext ;

    public VolumizerStateStore() {

    }
}
