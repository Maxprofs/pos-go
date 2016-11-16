package com.shopify.volumizer;

import com.projecttango.tangosupport.TangoPointCloudManager;
import com.shopify.volumizer.manager.TangoManager;

import toothpick.config.Module;

/**
 * Created by kanawish on 2016-11-05.
 */

public class TestModule extends Module {

    public TestModule() {
        bind(TangoPointCloudManager.class).toInstance(new TangoPointCloudManager());
        bind(TangoManager.class).to(TangoManager.class);
    }
}
