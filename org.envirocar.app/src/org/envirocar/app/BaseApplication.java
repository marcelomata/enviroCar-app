/**
 * Copyright (C) 2013 - 2015 the enviroCar community
 *
 * This file is part of the enviroCar app.
 *
 * The enviroCar app is free software: you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * The enviroCar app is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with the enviroCar app. If not, see http://www.gnu.org/licenses/.
 */
package org.envirocar.app;

import android.annotation.TargetApi;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.preference.PreferenceManager;

import com.google.common.base.Preconditions;

import org.acra.ACRA;
import org.acra.annotation.ReportsCrashes;
import org.envirocar.app.handler.PreferenceConstants;
import org.envirocar.core.injection.InjectionModuleProvider;
import org.envirocar.core.injection.Injector;
import org.envirocar.core.logging.ACRACustomSender;
import org.envirocar.core.logging.Logger;
import org.envirocar.core.util.Util;

import java.util.Arrays;
import java.util.List;

import dagger.ObjectGraph;

/**
 * @author dewall
 */
@ReportsCrashes
public class BaseApplication extends Application implements Injector, InjectionModuleProvider {
    private static final String TAG = BaseApplication.class.getSimpleName();
    private static final Logger LOGGER = Logger.getLogger(BaseApplication.class);

    protected ObjectGraph mObjectGraph;
    protected BroadcastReceiver mScreenReceiver;
    protected BroadcastReceiver mGPSReceiver;

    private SharedPreferences.OnSharedPreferenceChangeListener preferenceListener
            = (sharedPreferences, key) -> {
        if (PreferenceConstants.ENABLE_DEBUG_LOGGING.equals(key)) {
            Logger.initialize(Util.getVersionString(BaseApplication.this),
                    sharedPreferences.getBoolean(PreferenceConstants.ENABLE_DEBUG_LOGGING, false));
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();

        // create initial ObjectGraph
        mObjectGraph = ObjectGraph.create(getInjectionModules().toArray());
        mObjectGraph.validate();

        // Inject the LazyLoadingStrategy into track. Its the only static injection
        // TODO: Remove the static injection.
        mObjectGraph.injectStatics();

        SharedPreferences preferences = PreferenceManager
                .getDefaultSharedPreferences(getApplicationContext());
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener);

        // Initialize ACRA
        ACRA.init(this);
        ACRACustomSender yourSender = new ACRACustomSender();
        ACRA.getErrorReporter().setReportSender(yourSender);

//        // check if the background remoteService is already running.
//        if (!ServiceUtils.isServiceRunning(this, SystemStartupService.class)) {
//            // Start a new remoteService
//            Intent startIntent = new Intent(this, SystemStartupService.class);
//            startService(startIntent);
//        }

        mScreenReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent.getAction().equals(Intent.ACTION_SCREEN_OFF)) {
                    // do whatever you need to do here
                    LOGGER.info("SCREEN IS OFF");
                } else if (intent.getAction().equals(Intent.ACTION_SCREEN_ON)) {
                    // and do whatever you need to do here
                    LOGGER.info("SCREEN IS ON");
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(mScreenReceiver, filter);


        mGPSReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent.getAction().matches("android.location.PROVIDERS_CHANGED")) {
                    LOGGER.info("GPS PROVIDER CHANGED");
                }
            }
        };

        IntentFilter filter2 = new IntentFilter();
        filter.addAction("android.location.PROVIDERS_CHANGED");
        registerReceiver(mGPSReceiver, filter2);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean obfus = prefs.getBoolean(PreferenceConstants.OBFUSCATE_POSITION, false);

        LOGGER.info("Obfuscation enabled? "+ obfus);

        Logger.initialize(Util.getVersionString(this),
                prefs.getBoolean(PreferenceConstants.ENABLE_DEBUG_LOGGING, false));

//        OBDServiceHandler.setRecordingState(OBDServiceState.UNCONNECTED);
    }

    @Override
    public void onTerminate() {
        super.onTerminate();
        if (mScreenReceiver != null)
            unregisterReceiver(mScreenReceiver);
        if (mGPSReceiver != null)
            unregisterReceiver(mGPSReceiver);
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        LOGGER.info("onLowMemory called");
    }

    @TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        LOGGER.info("onTrimMemory called");
        LOGGER.info("maxMemory: " + Runtime.getRuntime().maxMemory());
        LOGGER.info("totalMemory: " + Runtime.getRuntime().totalMemory());
        LOGGER.info("freeMemory: " + Runtime.getRuntime().freeMemory());
    }


    @Override
    public ObjectGraph getObjectGraph() {
        return mObjectGraph;
    }

    @Override
    public List<Object> getInjectionModules() {
        return Arrays.<Object>asList(
                new BaseApplicationModule(this));
    }

    @Override
    public void injectObjects(Object instance) {
        Preconditions.checkNotNull(instance, "Cannot inject into Null objects.");
        Preconditions.checkNotNull(mObjectGraph, "The ObjectGraph must be initialized before use.");
        mObjectGraph.inject(instance);
    }

}
