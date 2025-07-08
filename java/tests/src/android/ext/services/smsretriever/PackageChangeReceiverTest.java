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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
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

import com.android.modules.utils.build.SdkLevel;
import com.android.textclassifier.TextClassifierSmsRetrieverHandler;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.Collections;

@RunWith(MockitoJUnitRunner.class)
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

    @Before
    public void setUp() {
        Assume.assumeTrue(SdkLevel.isAtLeastB());

        mReceiver = new PackageChangeReceiver();

        when(mMockContext.getPackageManager()).thenReturn(mMockPackageManager);
        when(mMockIntent.getData()).thenReturn(mMockUri);
        when(mMockUri.getSchemeSpecificPart()).thenReturn(TEST_PACKAGE_NAME);
        when(mMockSignature.toCharsString()).thenReturn(SIGNATURE_STRING);

        TextClassifierSmsRetrieverHandler.clearAllHashes();
    }

    @Test
    public void onReceive_packageAdded_addsHash() throws Exception {
        when(mMockIntent.getAction()).thenReturn(Intent.ACTION_PACKAGE_ADDED);

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

        assertEquals(1, TextClassifierSmsRetrieverHandler.getAppHashCount());
        assertTrue(
                TextClassifierSmsRetrieverHandler.hasHash(EXPECTED_HASH));
    }

    @Test
    public void onReceive_nullIntent_doesNothing() {
        mReceiver.onReceive(mMockContext, null);

        assertEquals(0, TextClassifierSmsRetrieverHandler.getAppHashCount());
    }

    @Test
    public void onReceive_nullAction_doesNothing() throws PackageManager.NameNotFoundException {
        when(mMockIntent.getAction()).thenReturn(null);

        mReceiver.onReceive(mMockContext, mMockIntent);

        assertEquals(0, TextClassifierSmsRetrieverHandler.getAppHashCount());
        verify(mMockPackageManager, never()).getPackageInfo(anyString(), anyInt());
    }

    @Test
    public void onReceive_wrongAction_doesNothing() throws PackageManager.NameNotFoundException {
        when(mMockIntent.getAction()).thenReturn(Intent.ACTION_PACKAGE_REMOVED);

        mReceiver.onReceive(mMockContext, mMockIntent);

        assertEquals(0, TextClassifierSmsRetrieverHandler.getAppHashCount());
        verify(mMockPackageManager, never()).getPackageInfo(anyString(), anyInt());
    }

    @Test
    public void onReceive_nullData_doesNothing() {
        when(mMockIntent.getData()).thenReturn(null);

        mReceiver.onReceive(mMockContext, mMockIntent);

        assertEquals(0, TextClassifierSmsRetrieverHandler.getAppHashCount());
    }

    @Test
    public void onReceive_packageNotFound_doesNothing() throws Exception {
        when(mMockIntent.getAction()).thenReturn(Intent.ACTION_PACKAGE_ADDED);
        when(mMockPackageManager.getPackageInfo(
                TEST_PACKAGE_NAME, PackageManager.GET_SIGNING_CERTIFICATES))
                .thenThrow(new PackageManager.NameNotFoundException());

        mReceiver.onReceive(mMockContext, mMockIntent);

        assertEquals(0, TextClassifierSmsRetrieverHandler.getAppHashCount());
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

        assertEquals(0, TextClassifierSmsRetrieverHandler.getAppHashCount());
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

        assertEquals(0, TextClassifierSmsRetrieverHandler.getAppHashCount());
    }
}
