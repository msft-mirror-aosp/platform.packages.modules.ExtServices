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

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.VisibleForTesting;

import com.android.textclassifier.utils.AppHashHelper;

public class BootReceiver extends BroadcastReceiver {

    @VisibleForTesting
    public static String processName = Application.getProcessName();

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) && isAtLeast25Q4()
                && com.android.internal.telephony.flags.Flags.redactOtpSmsApi()
                && context.getApplicationInfo().packageName.equals(processName)) {
            AppHashHelper.ensureLoaded(context);
        }
    }

    private boolean isAtLeast25Q4() {
        // Since SDK_INT_FULL doesn't exist until 36, we are ensuring v36 first
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA
                && Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1;
    }
}
