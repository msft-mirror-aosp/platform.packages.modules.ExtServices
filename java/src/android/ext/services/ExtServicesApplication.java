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

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Application;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.ext.services.smsretriever.PackageChangeReceiver;
import android.os.Build;

import androidx.annotation.RequiresApi;
import androidx.work.Configuration;

/**
 * Application class to provide default configurations for the initialization of other modules.
 */
public final class ExtServicesApplication extends Application implements Configuration.Provider {

    private PackageChangeReceiver mPackageChangeReceiver;

    public ExtServicesApplication() {
        super();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // The receiver is registered only when the corresponding feature flag is enabled and the
        // current process is the main application process. This prevents the receiver from
        // being registered in other processes where it is not needed.
        if (isAtLeast25Q4() && com.android.internal.telephony.flags.Flags.redactOtpSmsApi()
                && getApplicationInfo().packageName.equals(Application.getProcessName())) {
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

    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private void registerPackageChangeReceiver() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        intentFilter.addDataScheme("package");
        mPackageChangeReceiver = new PackageChangeReceiver();
        if (checkSelfPermission(Manifest.permission.INTERACT_ACROSS_USERS_FULL)
                == PackageManager.PERMISSION_GRANTED) {
            this.registerReceiverForAllUsers(mPackageChangeReceiver, intentFilter, null, null);
        } else {
            this.registerReceiver(mPackageChangeReceiver, intentFilter);
        }
    }

    private boolean isAtLeast25Q4() {
        // Since SDK_INT_FULL doesn't exist until 36, we are ensuring v36 first
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA
                && Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1;
    }
}
