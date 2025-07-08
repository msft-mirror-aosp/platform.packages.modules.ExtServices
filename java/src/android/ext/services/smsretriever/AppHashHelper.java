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

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.RequiresApi;
import androidx.annotation.VisibleForTesting;

import com.android.textclassifier.TextClassifierSmsRetrieverHandler;

import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@RequiresApi(Build.VERSION_CODES.BAKLAVA)
public class AppHashHelper {
    private static final Charset CHARSET_UTF_8 = Charset.forName("UTF-8");
    private static final String HASH_TYPE = "SHA-256";
    private static final int NUM_HASHED_BYTES = 9; // 9 bytes = 72 bits = 12 Base64s

    private static final String TAG = AppHashHelper.class.getSimpleName();
    private static final int NUM_BASE64_CHARS = 11; // truncate 12 into 11 Base64 chars

    @VisibleForTesting
    public static final AtomicBoolean sIsLoadComplete = new AtomicBoolean(false);

    /**
     * Scans all installed packages on the device to generate and cache their SMS Retriever app
     * hash codes.
     * <p>
     * This method iterates through every package installed on the system, generates the unique
     * 11-character hash code for each one, and stores it in a shared set for fast lookups.
     *
     * @param context The application context, used to access the {@link PackageManager}.
     */
    public static void load(Context context) {
        // Atomically check and set the flag. If it's already true, or if another thread
        // sets it to true first, this call will return, preventing duplicate work.
        if (!sIsLoadComplete.compareAndSet(false, true)) {
            return;
        }
        PackageManager packageManager = context.getPackageManager();
        List<PackageInfo> packages = packageManager.getInstalledPackages(
                PackageManager.GET_SIGNING_CERTIFICATES);
        for (PackageInfo packageInfo : packages) {
            // Skip packages that don't contain any executable code.
            if (packageInfo.applicationInfo != null && (packageInfo.applicationInfo.flags
                    & android.content.pm.ApplicationInfo.FLAG_HAS_CODE) == 0) {
                continue;
            }
            String appHash = AppHashHelper.getAppHash(packageInfo.signingInfo,
                    packageInfo.packageName);
            if (appHash != null) {
                TextClassifierSmsRetrieverHandler.addHash(appHash);
            }
        }
        Log.i(TAG, "App hash codes loaded successfully, total found="
                + TextClassifierSmsRetrieverHandler.getAppHashCount());
    }

    /**
     * Generates the app-specific hash code used by the SMS Retriever API.
     * <p>
     * This is created by hashing the combination of the app's package name and its
     * first signing certificate using SHA-256. The resulting hash is then Base64-encoded
     * and truncated to an 11-character string.
     *
     * @param signingInfo    The {@link SigningInfo} for the package, containing its signing
     *                       certificates.
     * @param packageName    The package name of the app for which to generate the hash.
     * @return The 11-character app-specific hash string, or {@code null} if the package
     *         could not be found or has no signing information.
     */
    public static String getAppHash(SigningInfo signingInfo, String packageName) {
        if (signingInfo == null) {
            return null;
        }

        Signature[] signatures;
        if (signingInfo.hasMultipleSigners()) {
            signatures = signingInfo.getApkContentsSigners();
        } else {
            signatures = signingInfo.getSigningCertificateHistory();
        }

        if (signatures == null || signatures.length == 0) {
            Log.e(TAG, "Signatures is null or empty for packageName=" + packageName);
            return null;
        }

        String appInfo = packageName + " " + signatures[0].toCharsString();
        return hash(appInfo);
    }

    private static String hash(String appInfo) {
        MessageDigest messageDigest;
        try {
            messageDigest = MessageDigest.getInstance(HASH_TYPE);
        } catch (NoSuchAlgorithmException e) {
            Log.e(TAG, "Could not find " + HASH_TYPE
                    + " algorithm, which is required for generating app hash.", e);
            throw new RuntimeException(e);
        }

        messageDigest.update(appInfo.getBytes(CHARSET_UTF_8));
        byte[] hashSignature = messageDigest.digest();

        // truncated into NUM_HASHED_BYTES
        hashSignature = Arrays.copyOf(hashSignature, NUM_HASHED_BYTES);
        // encode into Base64
        String base64Hash = Base64.encodeToString(hashSignature,
                Base64.NO_PADDING | Base64.NO_WRAP);
        base64Hash = base64Hash.substring(0, NUM_BASE64_CHARS);

        return base64Hash;
    }
}
