/**
 * Copyright (C) 2025 The Android Open Source Project
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
package android.ext.services.smsretriever;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Trace;
import android.util.Log;

import androidx.annotation.RequiresApi;

import com.android.textclassifier.TextClassifierSmsRetrieverHandler;

@RequiresApi(Build.VERSION_CODES.BAKLAVA)
public class PackageChangeReceiver extends BroadcastReceiver {
    private static final String TAG = PackageChangeReceiver.class.getSimpleName();
    private static final boolean DEBUG = false;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getData() == null) {
            return;
        }

        String action = intent.getAction();
        if (action == null) {
            return;
        }

        if (action.equals(Intent.ACTION_PACKAGE_ADDED)) {
            try {
                Trace.beginSection("addAppHashOnPackageAdd");
                addAppHash(context, intent);
            } finally {
                Trace.endSection();
            }
        }
    }

    private void addAppHash(Context context, Intent intent) {
        PackageManager packageManager = context.getPackageManager();
        String packageName = intent.getData().getSchemeSpecificPart();
        PackageInfo packageInfo;
        try {
            packageInfo =
                    packageManager.getPackageInfo(packageName,
                            PackageManager.GET_SIGNING_CERTIFICATES);
        } catch (PackageManager.NameNotFoundException ignored) {
            return;
        }
        // Skip packages that don't contain any executable code.
        if (packageInfo.applicationInfo != null && (packageInfo.applicationInfo.flags
                & android.content.pm.ApplicationInfo.FLAG_HAS_CODE) == 0) {
            return;
        }
        String appHash = AppHashHelper.getAppHash(packageInfo.signingInfo, packageName);
        if (appHash == null) {
            Log.w(TAG, "App hash is null for packageName=" + packageName);
            return;
        }
        TextClassifierSmsRetrieverHandler.addHash(appHash);
        if (DEBUG) {
            Log.d(TAG, String.format(
                    "Added app hash for package %s, total hashes: %d",
                    packageName,
                    TextClassifierSmsRetrieverHandler.getAppHashCount()));
        }
    }

}
