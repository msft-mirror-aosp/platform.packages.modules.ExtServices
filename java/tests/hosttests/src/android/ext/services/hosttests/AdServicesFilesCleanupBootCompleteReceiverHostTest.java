/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.ext.services.hosttests;

import static com.google.common.truth.Truth.assertWithMessage;

import android.ext.services.hosttests.utils.ExtServicesLogcatReceiver;

import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.PackageInfo;
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;
import com.android.tradefed.testtype.IDeviceTest;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Pattern;

// TODO(b/297207132) - extend AdServicesHostSideTestCase instead.
@RunWith(DeviceJUnit4ClassRunner.class)
public class AdServicesFilesCleanupBootCompleteReceiverHostTest implements IDeviceTest {
    private static final String EXTSERVICES_PACKAGE_SUFFIX = "android.ext.services";
    private static final String CLEANUP_RECEIVER_CLASS_NAME =
            "android.ext.services.common.AdServicesFilesCleanupBootCompleteReceiver";
    private static final String LOGCAT_COMMAND = "logcat -s extservices";
    private static final String RECEIVER_DISABLED_LOG_TEXT =
            "Disabled AdServices files cleanup receiver";
    private static final String RECEIVER_EXECUTED_LOG_TEXT = "AdServices files cleanup receiver";

    private ITestDevice mDevice;
    private String mAdServicesFilePath;
    private String mExtServicesPackageName;

    @Override
    public void setDevice(ITestDevice device) {
        mDevice = device;
    }

    @Override
    public ITestDevice getDevice() {
        return mDevice;
    }

    @Before
    public void setUp() throws Exception {
        overridePhSync();

        ITestDevice device = getDevice();

        // Find the extservices package
        PackageInfo extServicesPackage =
                device.getAppPackageInfos().stream()
                        .filter(s -> s.getPackageName().endsWith(EXTSERVICES_PACKAGE_SUFFIX))
                        .findFirst()
                        .orElse(null);
        assertWithMessage("ExtServices package").that(extServicesPackage).isNotNull();
        mExtServicesPackageName = extServicesPackage.getPackageName();

        // Put some data in the ExtServices apk
        mAdServicesFilePath =
                String.format(
                        "/data/user/%d/%s/adservices_data.txt",
                        device.getCurrentUser(), extServicesPackage.getPackageName());
        String dataPutCommand = String.format("echo \"Hello\" > %s", mAdServicesFilePath);
        device.executeShellCommand(dataPutCommand);
        assertWithMessage("%s exists", mAdServicesFilePath)
                .that(device.doesFileExist(mAdServicesFilePath))
                .isTrue();
    }

    @After
    public void tearDown() throws Exception {
        resetPhSync();

        if (mDevice != null && mAdServicesFilePath != null
                && mDevice.doesFileExist(mAdServicesFilePath)) {
            mDevice.deleteFile(mAdServicesFilePath);
        }
    }

    @Test
    public void testReceiver_doesNotExecuteOnSMinus() throws Exception {
        // TODO(b/297207132) - use SdkLevelSupportRule instead of this manual check
        Assume.assumeTrue(getDevice().getApiLevel() < 33); // Run only on Android S-

        ITestDevice device = getDevice();

        // TODO(b/297207132) - use a rule instead of this shell command
        // Enable the flag that the receiver checks. By default, the flag is enabled in the binary,
        // so it's enough to just delete the flag override, if any.
        device.executeShellCommand(
                "device_config delete adservices extservices_adservices_data_cleanup_enabled");

        // Reboot, wait, and verify logs.
        verifyReceiverDidNotExecute(device);

        // Verify that adservices files are still present.
        assertWithMessage("%s exists", mAdServicesFilePath)
                .that(device.doesFileExist(mAdServicesFilePath))
                .isTrue();
    }

    @Test
    public void testReceiver_deletesFiles() throws Exception {
        // TODO(b/297207132) - use SdkLevelSupportRule instead of this manual check
        Assume.assumeTrue(getDevice().getApiLevel() >= 33); // Run only on Android T+

        ITestDevice device = getDevice();

        // Re-enable the cleanup receiver in case it's been disabled due to a prior run
        enableReceiver(device);

        // Enable the flag that the receiver checks. By default, the flag is enabled in the binary,
        // so it's enough to just delete the flag override, if any.
        device.executeShellCommand(
                "device_config delete adservices extservices_adservices_data_cleanup_enabled");

        // Reboot, wait, and verify logs.
        verifyReceiverExecuted(device);

        // Verify that all adservices files were deleted.
        assertWithMessage("%s exists", mAdServicesFilePath)
                .that(device.doesFileExist(mAdServicesFilePath))
                .isFalse();

        String lsCommand =
                String.format(
                        "ls /data/user/%d/%s -R", device.getCurrentUser(), mExtServicesPackageName);
        String lsOutput = device.executeShellCommand(lsCommand).toLowerCase(Locale.ROOT);
        assertWithMessage("Output of %s", lsCommand).that(lsOutput).doesNotContain("adservices");

        // Verify that after a reboot the receiver does not execute
        verifyReceiverDidNotExecute(device);
    }

    @Test
    public void testReceiver_doesNotExecuteIfFlagDisabled() throws Exception {
        // TODO(b/297207132) - use SdkLevelSupportRule instead of this manual check
        Assume.assumeTrue(getDevice().getApiLevel() >= 33); // Run only on Android T+

        ITestDevice device = getDevice();

        // Re-enable the cleanup receiver in case it's been disabled due to a prior run
        enableReceiver(device);

        // Disable the flag that the receiver checks
        device.executeShellCommand(
                "device_config put adservices extservices_adservices_data_cleanup_enabled false");

        // Verify that after a reboot the receiver executes but doesn't disable itself
        ExtServicesLogcatReceiver logcatReceiver =
                rebootDeviceAndCollectLogs(device, RECEIVER_DISABLED_LOG_TEXT);
        Pattern errorPattern = Pattern.compile(makePattern(RECEIVER_DISABLED_LOG_TEXT));
        assertWithMessage("Presence of log indicating receiver disabled itself")
                .that(logcatReceiver.patternMatches(errorPattern))
                .isFalse();

        // Verify that the file is still there and that the receiver didn't delete it.
        assertWithMessage("%s exists", mAdServicesFilePath)
                .that(device.doesFileExist(mAdServicesFilePath))
                .isTrue();
    }

    private void verifyReceiverExecuted(ITestDevice device)
            throws DeviceNotAvailableException, InterruptedException {
        ExtServicesLogcatReceiver logcatReceiver =
                rebootDeviceAndCollectLogs(device, RECEIVER_DISABLED_LOG_TEXT);
        Pattern errorPattern = Pattern.compile(makePattern(RECEIVER_DISABLED_LOG_TEXT));
        assertWithMessage("Presence of log indicating receiver disabled itself")
                .that(logcatReceiver.patternMatches(errorPattern))
                .isTrue();
    }

    private void verifyReceiverDidNotExecute(ITestDevice device)
            throws DeviceNotAvailableException, InterruptedException {
        ExtServicesLogcatReceiver logcatReceiver =
                rebootDeviceAndCollectLogs(device, RECEIVER_EXECUTED_LOG_TEXT);

        Pattern errorPattern = Pattern.compile(makePattern(RECEIVER_EXECUTED_LOG_TEXT));
        assertWithMessage("Presence of log indicating receiver was invoked")
                .that(logcatReceiver.patternMatches(errorPattern))
                .isFalse();
    }

    private Predicate<String[]> stopIfTextOccurs(String toMatch) {
        return (s) -> Arrays.stream(s).anyMatch(t -> t.contains(toMatch));
    }

    private ExtServicesLogcatReceiver rebootDeviceAndCollectLogs(ITestDevice device, String text)
            throws DeviceNotAvailableException, InterruptedException {
        // reboot the device
        device.reboot();
        device.waitForDeviceAvailable();

        // Enable verbose logs
        // TODO(b/297207132) - add to the rule instead of this shell command
        device.executeShellCommand("setprop log.tag.extservices VERBOSE");

        // Start log collection
        ExtServicesLogcatReceiver logcatReceiver =
                new ExtServicesLogcatReceiver.Builder()
                        .setDevice(device)
                        .setLogCatCommand(LOGCAT_COMMAND)
                        .setEarlyStopCondition(stopIfTextOccurs(text))
                        .build();
        logcatReceiver.collectLogs(/* timeoutMilliseconds= */ 5 * 60 * 1000); // Wait up to 5 mins
        return logcatReceiver;
    }

    private String makePattern(String text) {
        return ".*" + text + ".*";
    }

    private void overridePhSync() throws DeviceNotAvailableException {
        getDevice()
                .executeShellCommand(
                        "device_config put adservices set_sync_disabled_for_tests persistent");
    }

    private void resetPhSync() throws DeviceNotAvailableException {
        getDevice()
                .executeShellCommand(
                        "device_config put adservices set_sync_disabled_for_tests none");
    }

    private void enableReceiver(ITestDevice device) throws DeviceNotAvailableException {
        String enableCommand =
                String.format(
                        "pm enable %s/%s", mExtServicesPackageName, CLEANUP_RECEIVER_CLASS_NAME);
        device.executeShellCommand(enableCommand);
    }
}
