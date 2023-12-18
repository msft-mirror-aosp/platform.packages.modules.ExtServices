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
import static android.app.Notification.EXTRA_MESSAGES;
import static android.app.Notification.EXTRA_SUB_TEXT;
import static android.app.Notification.EXTRA_TEXT;
import static android.app.Notification.EXTRA_TEXT_LINES;
import static android.app.Notification.EXTRA_TITLE;

import android.app.Notification;
import android.app.Notification.MessagingStyle;
import android.app.Notification.MessagingStyle.Message;
import android.os.Bundle;
import android.os.Parcelable;

import com.android.modules.utils.build.SdkLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Class with helper methods related to detecting OTP codes in notifications
 */
public class NotificationOtpDetectionHelper {

    // Use an ArrayList because a List.of list will throw NPE when calling "contains(null)"
    private static final List<String> SENSITIVE_NOTIFICATION_CATEGORIES = new ArrayList<>(
            List.of(CATEGORY_MESSAGE, CATEGORY_EMAIL, CATEGORY_SOCIAL));

    private static final int MAX_SENSITIVE_TEXT_LEN = 600;

    /**
     * Gets the sections of text in a notification that should be checked for sensitive content.
     * This includes the text, title, subtext, messages, and extra text lines.
     * @param notification The notification whose content should be filtered
     * @return The extracted text fields
     */
    public static String getTextForDetection(Notification notification) {
        if (notification.extras == null || !SdkLevel.isAtLeastV()) {
            return "";
        }
        Bundle extras = notification.extras;
        CharSequence title = notification.extras.getCharSequence(EXTRA_TITLE);
        CharSequence text = notification.extras.getCharSequence(EXTRA_TEXT);
        CharSequence subText = notification.extras.getCharSequence(EXTRA_SUB_TEXT);
        // TODO b/317408921: Validate that the ML model still works with this
        StringBuilder builder = new StringBuilder()
                .append(title != null ? title : "").append(" ")
                .append(text != null ? text : "").append(" ")
                .append(subText != null ? subText : "").append(" ");
        CharSequence[] textLines = extras.getCharSequenceArray(EXTRA_TEXT_LINES);
        if (textLines != null) {
            for (CharSequence line : textLines) {
                builder.append(line).append(" ");
            }
        }
        List<Message> messages = Message.getMessagesFromBundleArray(
                extras.getParcelableArray(EXTRA_MESSAGES, Parcelable.class));
        // Sort the newest messages (largest timestamp) first
        messages.sort((MessagingStyle.Message lhs, MessagingStyle.Message rhs) ->
                Math.toIntExact(rhs.getTimestamp() - lhs.getTimestamp()));
        for (MessagingStyle.Message message : messages) {
            builder.append(message.getText()).append(" ");
        }
        return builder.length() <= MAX_SENSITIVE_TEXT_LEN ? builder.toString()
                : builder.substring(0, MAX_SENSITIVE_TEXT_LEN);
    }

    /**
     * Determines if a notification should be checked for an OTP, based on category, style, and
     * possible otp content (as determined by a regular expression).
     * @param notification The notification whose content should be checked
     * @return true, if further checks for OTP codes should be performed, false otherwise
     */
    public static boolean shouldCheckForOtp(Notification notification) {
        if (notification == null || !SdkLevel.isAtLeastV()) {
            return false;
        }
        return SENSITIVE_NOTIFICATION_CATEGORIES.contains(notification.category)
                || isStyle(notification, Notification.MessagingStyle.class)
                || isStyle(notification, Notification.InboxStyle.class)
                || shouldCheckForOtp(notification.publicVersion);
    }

    private static boolean isStyle(Notification notification,
            Class<? extends Notification.Style> styleClass) {
        if (notification.extras == null) {
            return false;
        }
        String templateClass = notification.extras.getString(Notification.EXTRA_TEMPLATE);
        return Objects.equals(templateClass, styleClass.getName());
    }

    private NotificationOtpDetectionHelper() { }
}
