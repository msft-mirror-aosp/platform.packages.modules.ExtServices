/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.ext.services;

import android.app.Application;
import android.content.Intent;
import android.content.IntentFilter;
import android.ext.services.smsretriever.AppHashHelper;
import android.ext.services.smsretriever.PackageChangeReceiver;
import android.os.Build;
import android.os.Trace;

import androidx.annotation.RequiresApi;
import androidx.work.Configuration;

import com.android.modules.utils.build.SdkLevel;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Application class to provide default configurations for the initialization of other modules.
 */
public final class ExtServicesApplication extends Application implements Configuration.Provider {

    private PackageChangeReceiver mPackageChangeReceiver;
    private final Executor mBackgroundExecutor = Executors.newSingleThreadExecutor();

    public ExtServicesApplication() {
        super();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (SdkLevel.isAtLeastB() && com.android.internal.telephony.flags.Flags.redactOtpSmsApi()) {
            mBackgroundExecutor.execute(() -> {
                try {
                    Trace.beginSection("loadAllAppHashes");
                    AppHashHelper.load(this);
                } finally {
                    Trace.endSection();
                }
            });
            registerPackageChangeReceiver();
        }
    }

    /**
     * Provides initialization configuration for WorkManager, used by TextClassifierService.
     */
    @Override
    public Configuration getWorkManagerConfiguration() {
        return new Configuration.Builder().build();
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private void registerPackageChangeReceiver() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        intentFilter.addDataScheme("package");
        mPackageChangeReceiver = new PackageChangeReceiver();
        this.registerReceiver(mPackageChangeReceiver, intentFilter);
    }
}
