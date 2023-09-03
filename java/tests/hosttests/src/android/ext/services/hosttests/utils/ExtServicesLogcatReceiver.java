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

package android.ext.services.hosttests.utils;

import com.android.ddmlib.MultiLineReceiver;
import com.android.tradefed.device.BackgroundDeviceAction;
import com.android.tradefed.device.ITestDevice;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Enables capturing device logs and exposing them to the host test.
 */
// TODO(b/288892905) consolidate with existing logcat receiver
public final class ExtServicesLogcatReceiver extends MultiLineReceiver {
    private volatile boolean mCancelled;
    private final List<String> mLines = new ArrayList<>();
    private final CountDownLatch mCountDownLatch = new CountDownLatch(1);
    private final String mName;
    private final String mLogcatCmd;
    private final ITestDevice mTestDevice;
    private final Predicate<String[]> mEarlyStopCondition;
    private BackgroundDeviceAction mBackgroundDeviceAction;

    private ExtServicesLogcatReceiver(String name, String logcatCmd, ITestDevice device,
            Predicate<String[]> earlyStopCondition) {
        mName = name;
        mLogcatCmd = logcatCmd;
        mEarlyStopCondition = earlyStopCondition;
        mTestDevice = device;
    }

    @Override
    public void processNewLines(String[] lines) {
        if (lines.length == 0) {
            return;
        }

        Arrays.stream(lines).filter(s -> !s.trim().isEmpty()).forEach(mLines::add);

        if (mEarlyStopCondition != null && mEarlyStopCondition.test(lines)) {
            mCountDownLatch.countDown();
        }
    }

    @Override
    public boolean isCancelled() {
        return mCancelled;
    }

    /**
     * Begins log collection. This method needs to be only used once per instance.
     *
     * @param timeoutMilliseconds the maximum time after which log collection should stop, if the
     *                            early stop condition was not encountered previously.
     * @return true if log collection stopped because the early stop condition was encountered,
     * false if log collection stopped due to timeout
     * @throws InterruptedException if the current thread is interrupted
     */
    public boolean collectLogs(long timeoutMilliseconds) throws InterruptedException {
        if (mBackgroundDeviceAction != null) {
            throw new IllegalStateException("This method should only be called once per instance");
        }

        mBackgroundDeviceAction = new BackgroundDeviceAction(mLogcatCmd, mName, mTestDevice, this,
                0);
        mBackgroundDeviceAction.start();

        boolean earlyStop = mCountDownLatch.await(timeoutMilliseconds, TimeUnit.MILLISECONDS);
        stop();

        return earlyStop;
    }

    private void stop() {
        if (mBackgroundDeviceAction != null) mBackgroundDeviceAction.cancel();
        if (isCancelled()) return;
        mCancelled = true;
    }

    public boolean patternMatches(Pattern pattern) {
        String joined = String.join("\n", mLines);
        return joined.length() > 0 && pattern.matcher(joined).find();
    }

    public List<String> getCollectedLogs() {
        return mLines;
    }

    public static final class Builder {
        private ITestDevice mDevice;
        private String mLogCatCommand;
        private Predicate<String[]> mEarlyStopCondition;

        public Builder setDevice(ITestDevice device) {
            Objects.requireNonNull(device);
            mDevice = device;
            return this;
        }

        public Builder setLogCatCommand(String command) {
            Objects.requireNonNull(command);
            mLogCatCommand = command;
            return this;
        }

        public Builder setEarlyStopCondition(Predicate<String[]> earlyStopCondition) {
            mEarlyStopCondition = earlyStopCondition;
            return this;
        }

        public ExtServicesLogcatReceiver build() {
            Objects.requireNonNull(mDevice);
            Objects.requireNonNull(mLogCatCommand);

            return new ExtServicesLogcatReceiver("extservices-logcat-receiver", mLogCatCommand,
                    mDevice, mEarlyStopCondition);
        }
    }
}
