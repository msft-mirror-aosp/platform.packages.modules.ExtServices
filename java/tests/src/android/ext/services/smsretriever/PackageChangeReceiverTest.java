/*
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
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
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
import android.net.Uri;
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
public class PackageChangeReceiverTest {
    private static final String TEST_PACKAGE_NAME = "com.example.app";
    private static final String SIGNATURE_STRING = "mysignature";
    private static final String EXPECTED_HASH = "TH60ej87DlP";

    @Mock
    private Context mMockContext;
    @Mock
    private PackageManager mMockPackageManager;
    @Mock
    private Intent mMockIntent;
    @Mock
    private Uri mMockUri;
    @Mock
    private Signature mMockSignature;

    private PackageChangeReceiver mReceiver;
    private File mTempFile;

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @Before
    public void setUp() throws IOException {
        mReceiver = new PackageChangeReceiver();
        mTempFile = File.createTempFile("apphashes", ".txt");

        when(mMockContext.getPackageManager()).thenReturn(mMockPackageManager);
        when(mMockIntent.getData()).thenReturn(mMockUri);
        when(mMockUri.getSchemeSpecificPart()).thenReturn(TEST_PACKAGE_NAME);
        when(mMockSignature.toCharsString()).thenReturn(SIGNATURE_STRING);
        AppHashHelper.clearAllHashes();
    }

    @After
    public void tearDown() {
        if (mTempFile != null) {
            mTempFile.delete();
        }
    }

    @Test
    public void onReceive_packageAdded_addsHash() throws Exception {
        when(mMockIntent.getAction()).thenReturn(Intent.ACTION_PACKAGE_ADDED);
        when(mMockContext.openFileOutput(eq(AppHashHelper.APP_HASHES_FILENAME), anyInt()))
                .thenReturn(new FileOutputStream(mTempFile));
        when(mMockContext.openFileInput(eq(AppHashHelper.APP_HASHES_FILENAME)))
                .thenReturn(new FileInputStream(mTempFile));

        SigningInfo signingInfo = new SigningInfo(SigningInfo.VERSION_JAR,
                Collections.singletonList(mMockSignature), null, null);

        PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = TEST_PACKAGE_NAME;
        packageInfo.signingInfo = signingInfo;
        packageInfo.applicationInfo = new ApplicationInfo();
        packageInfo.applicationInfo.flags = ApplicationInfo.FLAG_HAS_CODE;

        when(mMockPackageManager.getPackageInfo(
                TEST_PACKAGE_NAME, PackageManager.GET_SIGNING_CERTIFICATES))
                .thenReturn(packageInfo);

        mReceiver.onReceive(mMockContext, mMockIntent);

        assertEquals(1, AppHashHelper.getAppHashes().size());
        assertTrue(AppHashHelper.hasHash(EXPECTED_HASH));
        SystemUtil.eventually(() -> {
            List<String> lines = Files.readAllLines(mTempFile.toPath());
            assertEquals(1, lines.size());
            assertEquals(EXPECTED_HASH, lines.get(0));
        });
    }

    @Test
    public void onReceive_nullIntent_doesNothing() {
        mReceiver.onReceive(mMockContext, null);

        assertEquals(0, AppHashHelper.getAppHashes().size());
    }

    @Test
    public void onReceive_nullAction_doesNothing() throws PackageManager.NameNotFoundException {
        when(mMockIntent.getAction()).thenReturn(null);

        mReceiver.onReceive(mMockContext, mMockIntent);

        assertEquals(0, AppHashHelper.getAppHashes().size());
        verify(mMockPackageManager, never()).getPackageInfo(anyString(), anyInt());
    }

    @Test
    public void onReceive_wrongAction_doesNothing() throws PackageManager.NameNotFoundException {
        when(mMockIntent.getAction()).thenReturn(Intent.ACTION_PACKAGE_REMOVED);

        mReceiver.onReceive(mMockContext, mMockIntent);

        assertEquals(0, AppHashHelper.getAppHashes().size());
        verify(mMockPackageManager, never()).getPackageInfo(anyString(), anyInt());
    }

    @Test
    public void onReceive_nullData_doesNothing() {
        when(mMockIntent.getData()).thenReturn(null);

        mReceiver.onReceive(mMockContext, mMockIntent);

        assertEquals(0, AppHashHelper.getAppHashes().size());
    }

    @Test
    public void onReceive_packageNotFound_doesNothing() throws Exception {
        when(mMockIntent.getAction()).thenReturn(Intent.ACTION_PACKAGE_ADDED);
        when(mMockPackageManager.getPackageInfo(
                TEST_PACKAGE_NAME, PackageManager.GET_SIGNING_CERTIFICATES))
                .thenThrow(new PackageManager.NameNotFoundException());

        mReceiver.onReceive(mMockContext, mMockIntent);

        assertEquals(0, AppHashHelper.getAppHashes().size());
    }

    @Test
    public void onReceive_packageHasNoCode_doesNothing() throws Exception {
        when(mMockIntent.getAction()).thenReturn(Intent.ACTION_PACKAGE_ADDED);

        SigningInfo signingInfo = new SigningInfo(SigningInfo.VERSION_JAR,
                Collections.singletonList(mMockSignature), null, null);

        PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = TEST_PACKAGE_NAME;
        packageInfo.signingInfo = signingInfo;
        packageInfo.applicationInfo = new ApplicationInfo();
        packageInfo.applicationInfo.flags = 0; // No FLAG_HAS_CODE

        when(mMockPackageManager.getPackageInfo(
                TEST_PACKAGE_NAME, PackageManager.GET_SIGNING_CERTIFICATES))
                .thenReturn(packageInfo);

        mReceiver.onReceive(mMockContext, mMockIntent);

        assertEquals(0, AppHashHelper.getAppHashes().size());
    }

    @Test
    public void onReceive_nullAppHash_doesNothing() throws Exception {
        when(mMockIntent.getAction()).thenReturn(Intent.ACTION_PACKAGE_ADDED);

        SigningInfo signingInfo = new SigningInfo(SigningInfo.VERSION_JAR,
                Collections.emptyList(), null, null);

        PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = TEST_PACKAGE_NAME;
        packageInfo.signingInfo = signingInfo;
        packageInfo.applicationInfo = new ApplicationInfo();
        packageInfo.applicationInfo.flags = ApplicationInfo.FLAG_HAS_CODE;

        when(mMockPackageManager.getPackageInfo(
                TEST_PACKAGE_NAME, PackageManager.GET_SIGNING_CERTIFICATES))
                .thenReturn(packageInfo);

        mReceiver.onReceive(mMockContext, mMockIntent);

        assertEquals(0, AppHashHelper.getAppHashes().size());
        assertTrue(Files.readAllLines(mTempFile.toPath()).isEmpty());
    }
}
