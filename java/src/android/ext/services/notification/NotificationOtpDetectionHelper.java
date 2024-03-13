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
import static android.view.textclassifier.TextClassifier.TYPE_ADDRESS;
import static android.view.textclassifier.TextClassifier.TYPE_FLIGHT_NUMBER;
import static android.view.textclassifier.TextClassifier.TYPE_PHONE;

import static java.lang.String.format;

import android.app.Notification;
import android.app.Notification.MessagingStyle;
import android.app.Notification.MessagingStyle.Message;
import android.icu.util.ULocale;
import android.os.Bundle;
import android.os.Parcelable;
import android.service.notification.Flags;
import android.util.ArrayMap;
import android.view.textclassifier.TextClassifier;
import android.view.textclassifier.TextLanguage;
import android.view.textclassifier.TextLinks;

import com.android.modules.utils.build.SdkLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Class with helper methods related to detecting OTP codes in notifications
 */
public class NotificationOtpDetectionHelper {

    // Use an ArrayList because a List.of list will throw NPE when calling "contains(null)"
    private static final List<String> SENSITIVE_NOTIFICATION_CATEGORIES = new ArrayList<>(
            List.of(CATEGORY_MESSAGE, CATEGORY_EMAIL, CATEGORY_SOCIAL));

    private static final float TC_THRESHOLD = 0.6f;

    private static final ArrayMap<String, Matcher> EXTRA_LANG_OTP_REGEX = new ArrayMap<>();

    private static final int MAX_SENSITIVE_TEXT_LEN = 600;

    // A regex matching a line start, space, open paren, arrow, colon (not proceeded by a digit),
    // open square bracket, equals sign, double or single quote, or ideographic char. It will
    // not consume the start char (meaning START won't be included in the matched string)
    private static final String START = "(^|(?<=[>\\s(\"'=\\[\\p{IsIdeographic}]|[^0-9]:))";


    // One single OTP char. A number or alphabetical char (that isn't also ideographic), followed by
    // an optional dash
    private static final String OTP_CHAR = "([0-9\\p{IsAlphabetic}&&[^\\p{IsIdeographic}]]-?)";

    // Performs a lookahead to find a digit after 0 to 7 OTP_CHARs. This ensures that our potential
    // OTP code contains at least one number
    private static final String FIND_DIGIT = format("(?=%s{0,7}\\d)", OTP_CHAR);

    // Matches between 5 and 8 OTP_CHARs. Here, we are assuming an OTP code is 5-8 characters long
    private static final String OTP_CHARS = format("(%s{5,8})", OTP_CHAR);

    // A regex matching a line end, non-word char (except dash or underscore), or ideographic char.
    // It will not consume the end char
    private static final String END = "(?=\\W|$|\\p{IsIdeographic})";

    // A regex matching four digit numerical codes
    private static final String FOUR_DIGIT = "(\\d{4})";

    private static final String ALPHANUM_OTP = format("(%s%s)", FIND_DIGIT, OTP_CHARS);

    // A regex matching two pairs of 3 digits (ex "123 456")
    private static final String SIX_DIGIT_WITH_SPACE = "(\\d{3}\\s\\d{3})";

    /**
     * Combining the regular expressions above, we get an OTP regex:
     * 1. start with START, THEN
     * 2. lookahead to find a digit mixed in with OTP_CHARs, then find 4-8 OTP_CHARs, OR
     * 3. find 4 digits, OR
     * 4. find 2 sets of 3 digits, separated by a space, THEN
     * 5. finish with END
     */
    private static final String ALL_OTP =
            format("%s(%s|%s|%s)%s",
                    START, ALPHANUM_OTP, FOUR_DIGIT, SIX_DIGIT_WITH_SPACE, END);



    private static final Matcher OTP_REGEX =
            Pattern.compile(ALL_OTP).matcher("");
    /**
     * A Date regular expression. Looks for dates with the month, day, and year separated by dashes.
     * Handles one and two digit months and days, and four or two-digit years. It makes the
     * following assumptions:
     * Dates and months will never be higher than 39
     * If a four digit year is used, the leading digit will be 1 or 2
     * This regex is used to eliminate the most common false positive of the OTP regex, and is run
     * on all messages, even before looking at language-specific regexs.
     */
    private static final Matcher DATE_WITH_DASHES_REGEX =
            Pattern.compile(format("%s([0-3]?\\d-[0-3]?\\d-([12]\\d)?\\d\\d)%s", START, END))
                    .matcher("");

    // A regex matching the common years of 19xx and 20xx. Used for false positive reduction
    private static final String COMMON_YEARS = format("%s((19|20)\\d\\d)%s", START, END);

    // A regex matching three lower case letters. Used for false positive reduction, as no known
    // OTPs have 3 lowercase letters in sequence.
    private static final String THREE_LOWERCASE = "(\\p{Ll}{3})";

    // A combination of common false positives. Run in cases where we don't have a language specific
    // regular expression.
    private static final Matcher FALSE_POSITIVE_REGEX =
            Pattern.compile(format("%s|%s", COMMON_YEARS, THREE_LOWERCASE)).matcher("");

    /**
     * A list of regular expressions representing words found in an OTP context (non case sensitive)
     * Note: TAN is short for Transaction Authentication Number
     */
    private static final String[] ENGLISH_CONTEXT_WORDS = new String[] {
            "pin", "pass[-\\s]?(code|word)", "TAN", "otp", "2fa", "(two|2)[-\\s]?factor",
            "log[-\\s]?in", "auth(enticat(e|ion))?", "code", "secret", "verif(y|ication)",
            "confirm(ation)?"
    };

    /**
     * Creates a regular expression to match any of a series of individual words, case insensitive.
     */
    private static Matcher createDictionaryRegex(String[] words) {
        StringBuilder regex = new StringBuilder("(?i)\\b(");
        for (int i = 0; i < words.length; i++) {
            regex.append(words[i]);
            if (i != words.length - 1) {
                regex.append("|");
            }
        }
        regex.append(")\\b");
        return Pattern.compile(regex.toString()).matcher("");
    }

    static {
        EXTRA_LANG_OTP_REGEX.put(ULocale.ENGLISH.toLanguageTag(),
                createDictionaryRegex(ENGLISH_CONTEXT_WORDS));
    }

    /**
     * Checks if the sensitive parts of a notification might contain an OTP, based on several
     * regular expressions, and potentially using a textClassifier to eliminate false positives
     * @param notification The notification whose content should be checked
     * @param checkForFalsePositives If true, will ensure the content does not match the date regex.
     *                               It will then try to find a language specific regex. If it is
     *                               successful, it will use that regex to check for false
     *                               positives. If it is not, it will use the TextClassifier
     *                               (if provided), plus the year and three lowercase regexes to
     *                               remove possible false positives
     * @param tc If non null, the provided TextClassifier will be used to find the language of the
     *           text, and look for a language-specific regex for it. If checkForFalsePositives is
     *           true will also use the classifier to find flight codes and addresses
     * @return True if the regex matches and ensureNotDate is false, or the date regex failed to
     * match, false otherwise.
     */
    public static boolean containsOtp(Notification notification,
            boolean checkForFalsePositives, TextClassifier tc) {
        if (notification == null || !SdkLevel.isAtLeastV()) {
            return false;
        }

        String sensitiveText = getTextForDetection(notification);
        OTP_REGEX.reset(sensitiveText);
        boolean otpMatch = OTP_REGEX.find();
        if (!checkForFalsePositives || !otpMatch) {
            return otpMatch;
        }

        if (allOtpMatchesAreFalsePositives(sensitiveText, DATE_WITH_DASHES_REGEX)) {
            return false;
        }

        if (tc != null) {
            Matcher languageSpecificMatcher = getLanguageSpecificRegex(sensitiveText, tc);
            if (languageSpecificMatcher != null) {
                languageSpecificMatcher.reset(sensitiveText);
                // Only use the language-specific regex for false positives
                return languageSpecificMatcher.find();
            }
            // Else, use TC to check for false positives
            if (hasFalsePositivesTcCheck(sensitiveText, tc)) {
                return false;
            }
        }

        return !allOtpMatchesAreFalsePositives(sensitiveText, FALSE_POSITIVE_REGEX);
    }

    /**
     * Checks that a given text has at least one match for one regex, that doesn't match another
     * @param text The full text to check
     * @param falsePositiveRegex A regex that should not match the OTP regex (for at least one match
     *                           found by the OTP regex
     * @return true, if all matches found by OTP_REGEX are also found by "shouldNotMatch"
     */
    private static boolean allOtpMatchesAreFalsePositives(String text,
            Matcher falsePositiveRegex) {
        falsePositiveRegex = falsePositiveRegex.reset(text);
        if (!falsePositiveRegex.find()) {
            return false;
        }
        OTP_REGEX.reset(text);
        while (OTP_REGEX.find()) {
            falsePositiveRegex.reset(OTP_REGEX.group());
            if (!falsePositiveRegex.find()) {
                // A possible otp was not matched by the false positive regex
                return false;
            }
        }
        // All otp matches were matched by the false positive regex
        return true;
    }

    private static Matcher getLanguageSpecificRegex(String text, TextClassifier tc) {
        TextLanguage.Request langRequest = new TextLanguage.Request.Builder(text).build();
        TextLanguage lang = tc.detectLanguage(langRequest);
        for (int i = 0; i < lang.getLocaleHypothesisCount(); i++) {
            ULocale locale = lang.getLocale(i);
            if (lang.getConfidenceScore(locale) >= TC_THRESHOLD
                    && EXTRA_LANG_OTP_REGEX.containsKey(locale.toLanguageTag())) {
                return EXTRA_LANG_OTP_REGEX.get(locale.toLanguageTag());
            }
        }
        return null;
    }

    private static boolean hasFalsePositivesTcCheck(String text, TextClassifier tc) {
        // Use TC to eliminate false positives from a regex match, namely: flight codes, and
        // addresses
        List<String> included = new ArrayList<>(List.of(TYPE_FLIGHT_NUMBER, TYPE_ADDRESS));
        List<String> excluded = new ArrayList<>(List.of(TYPE_PHONE));
        TextClassifier.EntityConfig config =
                new TextClassifier.EntityConfig.Builder().setIncludedTypes(
                        included).setExcludedTypes(excluded).build();
        TextLinks.Request request =
                new TextLinks.Request.Builder(text).setEntityConfig(config).build();
        TextLinks links = tc.generateLinks(request);
        for (TextLinks.TextLink link : links.getLinks()) {
            if (link.getConfidenceScore(TYPE_FLIGHT_NUMBER) > TC_THRESHOLD
                    || link.getConfidenceScore(TYPE_ADDRESS) > TC_THRESHOLD) {
                return true;
            }
        }
        return false;
    }

    /**
     * Gets the sections of text in a notification that should be checked for sensitive content.
     * This includes the text, title, subtext, messages, and extra text lines.
     * @param notification The notification whose content should be filtered
     * @return The extracted text fields
     */
    public static String getTextForDetection(Notification notification) {
        if (notification.extras == null || !SdkLevel.isAtLeastV()
                || !Flags.redactSensitiveNotificationsFromUntrustedListeners()) {
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
        if (notification == null || !SdkLevel.isAtLeastV()
                || !Flags.redactSensitiveNotificationsFromUntrustedListeners()) {
            return false;
        }
        return SENSITIVE_NOTIFICATION_CATEGORIES.contains(notification.category)
                || isStyle(notification, Notification.MessagingStyle.class)
                || isStyle(notification, Notification.InboxStyle.class)
                || containsOtp(notification, false, null)
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
