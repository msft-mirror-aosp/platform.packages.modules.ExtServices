/**
 * Copyright (C) 2018 The Android Open Source Project
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

import android.content.ContentResolver;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.VisibleForTesting;

/**
 * Observes the settings for {@link Assistant}.
 */
final class AssistantSettings extends ContentObserver {
    private static final String LOG_TAG = "AssistantSettings";
    public static Factory FACTORY = AssistantSettings::createAndRegister;
    private static final boolean DEFAULT_GENERATE_REPLIES = true;
    private static final boolean DEFAULT_GENERATE_ACTIONS = true;
    private static final int DEFAULT_MAX_MESSAGES_TO_EXTRACT = 5;
    @VisibleForTesting
    static final int DEFAULT_MAX_SUGGESTIONS = 3;


    // Copied from SystemUiDeviceConfigFlags.java
    /**
     * Whether the Notification Assistant should generate replies for notifications.
     */
    static final String NAS_GENERATE_REPLIES = "nas_generate_replies";

    /**
     * Whether the Notification Assistant should generate contextual actions for notifications.
     */
    static final String NAS_GENERATE_ACTIONS = "nas_generate_actions";

    /**
     * The maximum number of messages the Notification Assistant should extract from a
     * conversation when constructing responses for that conversation.
     */
    static final String NAS_MAX_MESSAGES_TO_EXTRACT = "nas_max_messages_to_extract";

    /**
     * The maximum number of suggestions the Notification Assistant should provide for a
     * messaging conversation.
     */
    static final String NAS_MAX_SUGGESTIONS = "nas_max_suggestions";
    // Copied from Settings.Global
    /**
     * Settings key for the ratio of notification dismissals to notification views - one of the
     * criteria for showing the notification blocking helper.
     *
     * <p>The value is a float ranging from 0.0 to 1.0 (the closer to 0.0, the more intrusive
     * the blocking helper will be).
     *
     * @hide
     */
    static final String BLOCKING_HELPER_DISMISS_TO_VIEW_RATIO_LIMIT =
            "blocking_helper_dismiss_to_view_ratio";

    /**
     * Settings key for the longest streak of dismissals  - one of the criteria for showing the
     * notification blocking helper.
     *
     * <p>The value is an integer greater than 0.
     */
    static final String BLOCKING_HELPER_STREAK_LIMIT = "blocking_helper_streak_limit";

    private static final Uri STREAK_LIMIT_URI =
            Settings.Global.getUriFor(BLOCKING_HELPER_STREAK_LIMIT);
    private static final Uri DISMISS_TO_VIEW_RATIO_LIMIT_URI =
            Settings.Global.getUriFor(BLOCKING_HELPER_DISMISS_TO_VIEW_RATIO_LIMIT);

    private final ContentResolver mResolver;

    private final Handler mHandler;

    @VisibleForTesting
    protected final Runnable mOnUpdateRunnable;

    // Actual configuration settings.
    float mDismissToViewRatioLimit;
    int mStreakLimit;
    boolean mGenerateReplies = DEFAULT_GENERATE_REPLIES;
    boolean mGenerateActions = DEFAULT_GENERATE_ACTIONS;
    int mMaxMessagesToExtract = DEFAULT_MAX_MESSAGES_TO_EXTRACT;
    int mMaxSuggestions = DEFAULT_MAX_SUGGESTIONS;

    private AssistantSettings(Handler handler, ContentResolver resolver,
            Runnable onUpdateRunnable) {
        super(handler);
        mHandler = handler;
        mResolver = resolver;
        mOnUpdateRunnable = onUpdateRunnable;
    }

    private static AssistantSettings createAndRegister(
            Handler handler, ContentResolver resolver, Runnable onUpdateRunnable) {
        AssistantSettings assistantSettings =
                new AssistantSettings(handler, resolver, onUpdateRunnable);
        assistantSettings.register();
        assistantSettings.registerDeviceConfigs();
        return assistantSettings;
    }

    /**
     * Creates an instance but doesn't register it as an observer.
     */
    @VisibleForTesting
    protected static AssistantSettings createForTesting(
            Handler handler, ContentResolver resolver, Runnable onUpdateRunnable) {
        return new AssistantSettings(handler, resolver, onUpdateRunnable);
    }

    private void register() {
        mResolver.registerContentObserver(DISMISS_TO_VIEW_RATIO_LIMIT_URI, false, this);
        mResolver.registerContentObserver(STREAK_LIMIT_URI, false, this);

        // Update all uris on creation.
        update(null);
    }

    private void registerDeviceConfigs() {
        DeviceConfig.addOnPropertiesChangedListener(
                DeviceConfig.NAMESPACE_SYSTEMUI,
                this::postToHandler,
                (properties) -> onDeviceConfigPropertiesChanged(properties.getNamespace()));

        // Update the fields in this class from the current state of the device config.
        updateFromDeviceConfigFlags();
    }

    private void postToHandler(Runnable r) {
        this.mHandler.post(r);
    }

    @VisibleForTesting
    void onDeviceConfigPropertiesChanged(String namespace) {
        if (!DeviceConfig.NAMESPACE_SYSTEMUI.equals(namespace)) {
            Log.e(LOG_TAG, "Received update from DeviceConfig for unrelated namespace: "
                    + namespace);
            return;
        }

        updateFromDeviceConfigFlags();
    }

    private void updateFromDeviceConfigFlags() {
        mGenerateReplies = DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_SYSTEMUI,
                NAS_GENERATE_REPLIES, DEFAULT_GENERATE_REPLIES);

        mGenerateActions = DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_SYSTEMUI,
                NAS_GENERATE_ACTIONS, DEFAULT_GENERATE_ACTIONS);

        mMaxMessagesToExtract = DeviceConfig.getInt(DeviceConfig.NAMESPACE_SYSTEMUI,
                NAS_MAX_MESSAGES_TO_EXTRACT,
                DEFAULT_MAX_MESSAGES_TO_EXTRACT);

        mMaxSuggestions = DeviceConfig.getInt(DeviceConfig.NAMESPACE_SYSTEMUI,
                NAS_MAX_SUGGESTIONS, DEFAULT_MAX_SUGGESTIONS);

        mOnUpdateRunnable.run();
    }

    @Override
    public void onChange(boolean selfChange, Uri uri) {
        update(uri);
    }

    private void update(Uri uri) {
        if (uri == null || DISMISS_TO_VIEW_RATIO_LIMIT_URI.equals(uri)) {
            mDismissToViewRatioLimit = Settings.Global.getFloat(
                    mResolver, BLOCKING_HELPER_DISMISS_TO_VIEW_RATIO_LIMIT,
                    ChannelImpressions.DEFAULT_DISMISS_TO_VIEW_RATIO_LIMIT);
        }
        if (uri == null || STREAK_LIMIT_URI.equals(uri)) {
            mStreakLimit = Settings.Global.getInt(
                    mResolver, BLOCKING_HELPER_STREAK_LIMIT,
                    ChannelImpressions.DEFAULT_STREAK_LIMIT);
        }

        mOnUpdateRunnable.run();
    }

    public interface Factory {
        AssistantSettings createAndRegister(Handler handler, ContentResolver resolver,
                Runnable onUpdateRunnable);
    }
}
