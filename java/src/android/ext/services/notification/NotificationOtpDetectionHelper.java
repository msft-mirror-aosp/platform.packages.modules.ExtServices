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

package android.ext.services.notification;

import static android.app.Notification.CATEGORY_EMAIL;
import static android.app.Notification.CATEGORY_MESSAGE;
import static android.app.Notification.CATEGORY_SOCIAL;
import static android.app.Notification.EXTRA_BIG_TEXT;
import static android.app.Notification.EXTRA_MESSAGES;
import static android.app.Notification.EXTRA_SUB_TEXT;
import static android.app.Notification.EXTRA_SUMMARY_TEXT;
import static android.app.Notification.EXTRA_TEXT;
import static android.app.Notification.EXTRA_TEXT_LINES;
import static android.app.Notification.EXTRA_TITLE_BIG;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.Notification.MessagingStyle;
import android.app.Notification.MessagingStyle.Message;
import android.ext.services.ExtServicesStatsLog;
import android.icu.util.ULocale;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.permission.flags.Flags;
import android.view.textclassifier.TextClassifier;
import android.view.textclassifier.TextLinks;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.VisibleForTesting;

import com.android.modules.utils.build.SdkLevel;

import com.google.common.collect.ImmutableList;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Class with helper methods related to detecting OTP codes in notifications.
 * This file needs to only use public android API methods, see b/361149088
 */
@SuppressLint("ObsoleteSdkInt")
@RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
public class NotificationOtpDetectionHelper {

    // Use an ArrayList because a List.of list will throw NPE when calling "contains(null)"
    private static final List<String> SENSITIVE_NOTIFICATION_CATEGORIES =
            Arrays.asList(CATEGORY_MESSAGE, CATEGORY_EMAIL, CATEGORY_SOCIAL);

    private static final List<String> SENSITIVE_STYLES =
            Arrays.asList(
                    Notification.MessagingStyle.class.getName(),
                    Notification.InboxStyle.class.getName(),
                    Notification.BigTextStyle.class.getName()
            );

    private static final List<String> EXCLUDED_STYLES =
            Arrays.asList(
                    Notification.MediaStyle.class.getName(),
                    Notification.BigPictureStyle.class.getName(),
                    Notification.CallStyle.class.getName()
            );

    private static final int MAX_SENSITIVE_TEXT_LEN = 600;

    private static final String TYPE_OTP =
            SdkLevel.isAtLeastB() && Flags.textClassifierChoiceApiEnabled()
                    ? TextClassifier.TYPE_OTP : "otp";

    private static final TextClassifier.EntityConfig TC_REQUEST_CONFIG =
            new TextClassifier.EntityConfig.Builder()
                    .setIncludedTypes(ImmutableList.of(TYPE_OTP))
                    .includeTypesFromTextClassifier(false)
                    .build();

    /**
     * Checks if any text fields in a notification might contain an OTP, based on several
     * regular expressions, and potentially using a textClassifier to eliminate false positives.
     * Each text field will be examined individually.
     *
     * @param notification The notification whose content should be checked
     * @param checkForFalsePositives If true, will ensure the content does not match the date regex.
     *                               If a TextClassifier is provided, it will then try to find a
     *                               language specific regex. If it is successful, it will use that
     *                               regex to check for false positives. If it is not, it will use
     *                               the TextClassifier (if provided), plus the year and three
     *                               lowercase regexes to remove possible false positives.
     * @param tc If use of TC for otp detection is enabled then the TC instance will be handling
     *           OTP detection. If not and non null, the provided TextClassifier will be used to
     *           find the language of the text, and look for a language-specific regex for it. If
     *           checkForFalsePositives is true will also use the classifier to find flight codes
     *           and addresses.
     * @return True if we believe an OTP is in the message, false otherwise.
     */
    public static boolean containsOtp(Notification notification,
            boolean checkForFalsePositives, @Nullable TextClassifier tc) {
        if (notification == null || notification.extras == null || !SdkLevel.isAtLeastV()) {
            return false;
        }

        // Get all the individual fields
        Set<String> fields = getNotificationTextFields(notification);

        if (tc != null && Assistant.sUseTcForOtpDetection) {
            for (String field : fields) {
                if (containsOtpByTextClassifier(field, tc)) {
                    return true;
                }
            }
        } else {
            // Get the language of the text once
            ULocale textLocale = LegacyOtpDetector.getLanguageWithRegex(
                    getTextForDetection(notification), tc);
            for (String field : fields) {
                // Makes use of legacy local logic for OTP detection in V.
                if (LegacyOtpDetector.containsOtp(field.toString(), checkForFalsePositives,
                        tc, textLocale)) {
                    return true;
                }
            }
        }
        return false;
    }

    @SuppressLint("WrongConstant")
    private static boolean containsOtpByTextClassifier(@NonNull String text,
            @NonNull TextClassifier tc) {
        TextLinks.Request request =
                new TextLinks.Request.Builder(text).setEntityConfig(TC_REQUEST_CONFIG).build();

        long startTime = System.currentTimeMillis();
        TextLinks links = tc.generateLinks(request);
        reportOtpDetectionDurationMs(System.currentTimeMillis() - startTime);

        for (TextLinks.TextLink link : links.getLinks()) {
            for (int i = 0; i < link.getEntityCount(); i++) {
                if (link.getEntity(i).equals(TYPE_OTP)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void reportOtpDetectionDurationMs(long duration) {
        ExtServicesStatsLog.write(ExtServicesStatsLog.NOTIFICATION_ASSISTANT_DURATION_STATS,
                duration);
    }

    /**
     * Gets the sections of text in a notification that should be checked for sensitive content.
     * This includes the text, title, subtext, messages, and extra text lines.
     * @param notification The notification whose content should be filtered
     * @return The extracted text fields
     */
    @VisibleForTesting
    protected static String getTextForDetection(Notification notification) {
        if (notification == null || notification.extras == null || !SdkLevel.isAtLeastV()) {
            return "";
        }
        String joinedString = String.join(" ", getNotificationTextFields(notification));
        return joinedString.length() <= MAX_SENSITIVE_TEXT_LEN
                ? joinedString
                : joinedString.substring(0, MAX_SENSITIVE_TEXT_LEN);
    }

    protected static Set<String> getNotificationTextFields(Notification notification) {
        if (notification == null || notification.extras == null || !SdkLevel.isAtLeastV()) {
            return new HashSet<>() {
            };
        }
        ArrayList<CharSequence> fields = new ArrayList<>();
        Bundle extras = notification.extras;
        fields.add(extras.getCharSequence(EXTRA_TEXT));
        fields.add(extras.getCharSequence(EXTRA_SUB_TEXT));
        fields.add(extras.getCharSequence(EXTRA_BIG_TEXT));
        fields.add(extras.getCharSequence(EXTRA_TITLE_BIG));
        fields.add(extras.getCharSequence(EXTRA_SUMMARY_TEXT));
        CharSequence[] textLines = extras.getCharSequenceArray(EXTRA_TEXT_LINES);
        if (textLines != null) {
            fields.addAll(Arrays.asList(textLines));
        }
        List<Message> messages = Message.getMessagesFromBundleArray(
                extras.getParcelableArray(EXTRA_MESSAGES, Parcelable.class));
        for (MessagingStyle.Message message : messages) {
            fields.add(message.getText());
        }
        Set<String> uniqueFields = new HashSet<>();
        for (CharSequence field : fields) {
            if (field != null && !field.isEmpty()) {
                uniqueFields.add((field.toString()));
            }
        }
        return uniqueFields;
    }

    /**
     * Determines if a notification should be checked for an OTP, based on category, style, and
     * possible otp content (as determined by a regular expression).
     * @param notification The notification whose content should be checked
     * @return true, if further checks for OTP codes should be performed, false otherwise
     */
    public static boolean shouldCheckForOtp(Notification notification) {
        if (notification == null || !SdkLevel.isAtLeastV()
                || EXCLUDED_STYLES.stream().anyMatch(s -> isStyle(notification, s))) {
            return false;
        }
        // We do not pre-check while using TC for otp detection
        if (Assistant.sUseTcForOtpDetection) {
            return SENSITIVE_NOTIFICATION_CATEGORIES.contains(notification.category)
                    || SENSITIVE_STYLES.stream().anyMatch(s -> isStyle(notification, s))
                    || shouldCheckForOtp(notification.publicVersion);
        } else {
            return SENSITIVE_NOTIFICATION_CATEGORIES.contains(notification.category)
                    || SENSITIVE_STYLES.stream().anyMatch(s -> isStyle(notification, s))
                    || containsOtp(notification, false, null)
                    || shouldCheckForOtp(notification.publicVersion);
        }
    }

    private static boolean isStyle(Notification notification, String styleClassName) {
        if (notification.extras == null) {
            return false;
        }
        String templateClass = notification.extras.getString(Notification.EXTRA_TEMPLATE);
        return Objects.equals(templateClass, styleClassName);
    }

    private NotificationOtpDetectionHelper() { }
}
