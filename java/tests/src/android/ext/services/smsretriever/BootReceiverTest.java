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

import static android.os.Build.VERSION_CODES.BAKLAVA;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;

import androidx.test.filters.SdkSuppress;

import com.android.compatibility.common.util.SystemUtil;
import com.android.textclassifier.utils.AppHashHelper;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;

@RunWith(MockitoJUnitRunner.class)
@SdkSuppress(minSdkVersion = BAKLAVA)
@RequiresFlagsEnabled(com.android.internal.telephony.flags.Flags.FLAG_REDACT_OTP_SMS_API)
public class BootReceiverTest {
    private static final String TEST_PACKAGE_NAME = "com.example.app";
    private static final String SIGNATURE_STRING = "mysignature";
    private static final String EXPECTED_HASH = "TH60ej87DlP";

    @Mock
    private Context mMockContext;
    @Mock
    private PackageManager mMockPackageManager;
    @Mock
    private Signature mMockSignature;

    private BootReceiver mBootReceiver;
    private File mTempFile;

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @Before
    public void setUp() throws IOException {
        AppHashHelper.resetLoadFromPackageManager();
        AppHashHelper.sAppHashCache.clear();

        when(mMockContext.getPackageManager()).thenReturn(mMockPackageManager);
        when(mMockSignature.toCharsString()).thenReturn(SIGNATURE_STRING);

        mBootReceiver = new BootReceiver();
        mTempFile = File.createTempFile("apphashes", ".txt");
    }

    @After
    public void tearDown() {
        if (mTempFile != null) {
            mTempFile.delete();
        }
    }

    @Test
    public void onReceive_withBootCompletedAction_populatesAppHash() throws IOException {
        Intent bootIntent = new Intent(Intent.ACTION_BOOT_COMPLETED);

        SigningInfo signingInfo = new SigningInfo(SigningInfo.VERSION_JAR,
                Collections.singletonList(mMockSignature), null, null);
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = TEST_PACKAGE_NAME;
        packageInfo.signingInfo = signingInfo;
        packageInfo.applicationInfo = new ApplicationInfo();
        packageInfo.applicationInfo.flags = ApplicationInfo.FLAG_HAS_CODE;

        when(mMockPackageManager.getInstalledPackages(PackageManager.GET_SIGNING_CERTIFICATES))
                .thenReturn(Collections.singletonList(packageInfo));
        ApplicationInfo appInfo = new ApplicationInfo();
        appInfo.packageName = "test.package";
        when(mMockContext.getApplicationInfo()).thenReturn(appInfo);
        BootReceiver.processName = "test.package";

        when(mMockContext.openFileOutput(eq(AppHashHelper.APP_HASHES_FILENAME), anyInt()))
                .thenReturn(new FileOutputStream(mTempFile));
        when(mMockContext.openFileInput(eq(AppHashHelper.APP_HASHES_FILENAME)))
                .thenReturn(new FileInputStream(mTempFile));

        mBootReceiver.onReceive(mMockContext, bootIntent);

        SystemUtil.eventually(() ->
                assertTrue("LoadedFromPackageManager should be true after ensureLoaded is called",
                        AppHashHelper.isLoadedFromPackageManager())
        );
        assertTrue(AppHashHelper.sAppHashCache.containsKey(EXPECTED_HASH));
        verify(mMockPackageManager).getInstalledPackages(PackageManager.GET_SIGNING_CERTIFICATES);

        List<String> lines = Files.readAllLines(mTempFile.toPath());
        assertEquals(1, lines.size());
        String expectedLine = EXPECTED_HASH + AppHashHelper.HASH_DELIMITER + TEST_PACKAGE_NAME;
        assertEquals(expectedLine, lines.get(0));
    }

    @Test
    public void onReceive_withBootCompletedAction_notInMainProcess_doesNotTriggerAppHashLoading() {
        Intent bootIntent = new Intent(Intent.ACTION_BOOT_COMPLETED);
        ApplicationInfo appInfo = new ApplicationInfo();
        appInfo.packageName = "test.package";
        when(mMockContext.getApplicationInfo()).thenReturn(appInfo);
        BootReceiver.processName = "different.process";

        mBootReceiver.onReceive(mMockContext, bootIntent);

        assertFalse("LoadedFromPackageManager should be false",
                AppHashHelper.isLoadedFromPackageManager());
        verify(mMockPackageManager, never()).getInstalledPackages(anyInt());
    }

    @Test
    public void onReceive_withDifferentAction_doesNotTriggerAppHashLoading() {
        Intent otherIntent = new Intent("com.example.some.OTHER_ACTION");

        mBootReceiver.onReceive(mMockContext, otherIntent);

        assertFalse("LoadedFromPackageManager should be false",
                AppHashHelper.isLoadedFromPackageManager());
        verify(mMockPackageManager, never()).getInstalledPackages(anyInt());
    }

    @Test
    public void onReceive_withNullAction_doesNotTriggerAppHashLoading() {
        Intent nullActionIntent = new Intent();

        mBootReceiver.onReceive(mMockContext, nullActionIntent);

        assertFalse("LoadedFromPackageManager should be false",
                AppHashHelper.isLoadedFromPackageManager());
        verify(mMockPackageManager, never()).getInstalledPackages(anyInt());
    }
}
