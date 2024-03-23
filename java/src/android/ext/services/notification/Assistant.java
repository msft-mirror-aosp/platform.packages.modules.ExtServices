/**
 * Copyright (C) 2017 The Android Open Source Project
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

package android.ext.services.notification;

import static android.content.pm.PackageManager.FEATURE_WATCH;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.UserHandle;
import android.service.notification.Adjustment;
import android.service.notification.NotificationAssistantService;
import android.service.notification.NotificationStats;
import android.service.notification.StatusBarNotification;
import android.util.ArrayMap;
import android.util.Log;
import android.view.textclassifier.TextClassificationManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.textclassifier.notification.SmartSuggestions;
import com.android.textclassifier.notification.SmartSuggestionsHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Notification assistant that provides guidance on notification channel blocking
 */
@SuppressLint("OverrideAbstract")
public class Assistant extends NotificationAssistantService {
    private static final String TAG = "ExtAssistant";
    private static final float TC_THRESHOLD = 0.6f;
    private static final ArrayList<String> OTP_SUPPORTED_LANGS = new ArrayList<>(List.of("en"));
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    // SBN key : entry
    protected ArrayMap<String, NotificationEntry> mLiveNotifications = new ArrayMap<>();

    @VisibleForTesting
    protected boolean mUseTextClassifier = true;

    @VisibleForTesting
    protected PackageManager mPm;

    @VisibleForTesting
    protected ActivityManager mAm;

    protected final ExecutorService mSingleThreadExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService mClassificationExecutor = Executors.newSingleThreadExecutor();
    @VisibleForTesting
    protected AssistantSettings.Factory mSettingsFactory = AssistantSettings.FACTORY;
    @VisibleForTesting
    protected AssistantSettings mSettings;
    private SmsHelper mSmsHelper;
    @VisibleForTesting
    protected SmartSuggestionsHelper mSmartSuggestionsHelper;

    @VisibleForTesting
    protected TextClassificationManager mTcm;

    public Assistant() {
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // Contexts are correctly hooked up by the creation step, which is required for the observer
        // to be hooked up/initialized.
        mPm = getPackageManager();
        mAm = getSystemService(ActivityManager.class);
        mTcm = getSystemService(TextClassificationManager.class);
        mSettings = mSettingsFactory.createAndRegister();
        mSmartSuggestionsHelper = new SmartSuggestionsHelper(this, mSettings);
        mSmsHelper = new SmsHelper(this);
        mSmsHelper.initialize();
        setUseTextClassifier();
    }

    @VisibleForTesting
    protected void setUseTextClassifier() {
        mUseTextClassifier = !(mAm.isLowRamDevice() || mPm.hasSystemFeature(FEATURE_WATCH));
    }

    @Override
    public void onDestroy() {
        // This null check is only for the unit tests as ServiceTestCase.tearDown calls onDestroy
        // without having first called onCreate.
        if (mSmsHelper != null) {
            mSmsHelper.destroy();
        }
        super.onDestroy();
    }

    @Override
    public Adjustment onNotificationEnqueued(@NonNull StatusBarNotification sbn) {
        // we use the version with channel, so this is never called.
        return null;
    }

    @Override
    public Adjustment onNotificationEnqueued(@NonNull StatusBarNotification sbn,
            @NonNull NotificationChannel channel) {
        if (DEBUG) Log.i(TAG, "ENQUEUED " + sbn.getKey() + " on " + channel.getId());
        if (!isForCurrentUser(sbn)) {
            return null;
        }

        final boolean shouldCheckForOtp =
                NotificationOtpDetectionHelper.shouldCheckForOtp(sbn.getNotification());
        boolean foundOtpWithRegex = shouldCheckForOtp
                && NotificationOtpDetectionHelper
                .containsOtp(sbn.getNotification(), true, null);
        Adjustment earlyOtpReturn = null;
        if (foundOtpWithRegex) {
            earlyOtpReturn = createNotificationAdjustment(sbn, null, null, true);
        }

        // Ignoring the result of the future
        Future<?> ignored = mClassificationExecutor.submit(() -> {
            Boolean containsOtp = null;
            if (shouldCheckForOtp && mUseTextClassifier) {
                // If we can use the text classifier, do a second pass, using the TC to detect
                // languages, and potentially using the TC to remove false positives
                containsOtp = NotificationOtpDetectionHelper.containsOtp(
                        sbn.getNotification(), true, mTcm.getTextClassifier());
            }

            // If we found an otp (and didn't already send an adjustment), send an adjustment early
            if (Boolean.TRUE.equals(containsOtp) && !foundOtpWithRegex) {
                adjustNotificationIfNotNull(createNotificationAdjustment(sbn, null, null, true));
            }

            SmartSuggestions suggestions = mSmartSuggestionsHelper.onNotificationEnqueued(sbn);
            if (DEBUG) {
                Log.d(TAG, String.format(
                        "Creating Adjustment for %s, with %d actions, and %d replies.",
                        sbn.getKey(),
                        suggestions.getActions().size(),
                        suggestions.getReplies().size()));
            }

            adjustNotificationIfNotNull(createNotificationAdjustment(
                    sbn,
                    new ArrayList<>(suggestions.getActions()),
                    new ArrayList<>(suggestions.getReplies()),
                    containsOtp));
        });

        return earlyOtpReturn;
    }

    // Due to Mockito setup, some methods marked @NonNull can sometimes be called with a
    // null parameter. This method accounts for that.
    private void adjustNotificationIfNotNull(@Nullable Adjustment adjustment) {
        if (adjustment != null) {
            adjustNotification(adjustment);
        }
    }

    /** A convenience helper for creating an adjustment for an SBN. */
    @VisibleForTesting
    protected Adjustment createNotificationAdjustment(
            StatusBarNotification sbn,
            ArrayList<Notification.Action> smartActions,
            ArrayList<CharSequence> smartReplies,
            Boolean hasSensitiveContent) {
        if (sbn == null) {
            // Only happens during mocking tests, when setting up `verify` calls
            return null;
        }

        Bundle signals = new Bundle();

        if (smartActions != null && !smartActions.isEmpty()) {
            signals.putParcelableArrayList(Adjustment.KEY_CONTEXTUAL_ACTIONS, smartActions);
        }
        if (smartReplies != null && !smartReplies.isEmpty()) {
            signals.putCharSequenceArrayList(Adjustment.KEY_TEXT_REPLIES, smartReplies);
        }

        if (hasSensitiveContent != null) {
            signals.putBoolean(Adjustment.KEY_SENSITIVE_CONTENT, hasSensitiveContent);
        }

        return new Adjustment(sbn.getPackageName(), sbn.getKey(), signals, "",
                sbn.getUser().getIdentifier());
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn, RankingMap rankingMap) {
        if (DEBUG) Log.i(TAG, "POSTED " + sbn.getKey());
        try {
            if (!isForCurrentUser(sbn)) {
                return;
            }
            Ranking ranking = new Ranking();
            boolean found = rankingMap.getRanking(sbn.getKey(), ranking);
            if (found && ranking.getChannel() != null) {
                NotificationEntry entry = new NotificationEntry(this, mPm,
                        sbn, ranking.getChannel(), mSmsHelper);
                mLiveNotifications.put(sbn.getKey(), entry);
            }
        } catch (Throwable e) {
            Log.e(TAG, "Error occurred processing post", e);
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn, RankingMap rankingMap,
            NotificationStats stats, int reason) {
        try {
            if (!isForCurrentUser(sbn)) {
                return;
            }

            mLiveNotifications.remove(sbn.getKey());

        } catch (Throwable e) {
            Log.e(TAG, "Error occurred processing removal of " + sbn.getKey(), e);
        }
    }

    @Override
    public void onNotificationSnoozedUntilContext(@NonNull StatusBarNotification sbn,
            @NonNull String snoozeCriterionId) {
    }

    @Override
    public void onNotificationsSeen(@NonNull List<String> keys) {
    }

    @Override
    public void onNotificationExpansionChanged(@NonNull String key, boolean isUserAction,
            boolean isExpanded) {
        if (DEBUG) {
            Log.d(TAG, "onNotificationExpansionChanged() called with: key = [" + key
                    + "], isUserAction = [" + isUserAction + "], isExpanded = [" + isExpanded
                    + "]");
        }
        NotificationEntry entry = mLiveNotifications.get(key);

        if (entry != null) {
            mSingleThreadExecutor.submit(
                    () -> mSmartSuggestionsHelper.onNotificationExpansionChanged(
                            entry.getSbn(), isExpanded));
        }
    }

    @Override
    public void onNotificationDirectReplied(@NonNull String key) {
        if (DEBUG) Log.i(TAG, "onNotificationDirectReplied " + key);
        mSingleThreadExecutor.submit(
                () -> mSmartSuggestionsHelper.onNotificationDirectReplied(key));
    }

    @Override
    public void onSuggestedReplySent(@NonNull String key, @NonNull CharSequence reply,
            int source) {
        if (DEBUG) {
            Log.d(TAG, "onSuggestedReplySent() called with: key = [" + key + "], reply = [" + reply
                    + "], source = [" + source + "]");
        }
        mSingleThreadExecutor.submit(
                () -> mSmartSuggestionsHelper.onSuggestedReplySent(key, reply, source));
    }

    @Override
    public void onActionInvoked(@NonNull String key, @NonNull Notification.Action action,
            int source) {
        if (DEBUG) {
            Log.d(TAG,
                    "onActionInvoked() called with: key = [" + key + "], action = [" + action.title
                            + "], source = [" + source + "]");
        }
        mSingleThreadExecutor.submit(
                () -> mSmartSuggestionsHelper.onActionClicked(key, action, source));
    }

    @Override
    public void onListenerConnected() {
        if (DEBUG) Log.i(TAG, "Connected");
    }

    @Override
    public void onListenerDisconnected() {
    }

    private boolean isForCurrentUser(StatusBarNotification sbn) {
        return sbn != null && sbn.getUserId() == UserHandle.myUserId();
    }
}
