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
import static android.app.Notification.EXTRA_TITLE;
import static android.app.Notification.EXTRA_TITLE_BIG;
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

    private static final ArrayMap<String, ThreadLocal<Matcher>> EXTRA_LANG_OTP_REGEX =
            new ArrayMap<>();

    private static final int MAX_SENSITIVE_TEXT_LEN = 600;

    /**
     * A regex matching a line start, space, open paren, arrow, colon (not proceeded by a digit),
     * open square bracket, equals sign, double or single quote, or ideographic char. It will
     * not consume the start char (meaning START won't be included in the matched string)
     */
    private static final String START = "(^|(?<=[>\\s(\"'=\\[\\p{IsIdeographic}]|[^0-9]:))";


    /**
     * One single OTP char. A number or alphabetical char (that isn't also ideographic)
     */
    private static final String OTP_CHAR = "([0-9\\p{IsAlphabetic}&&[^\\p{IsIdeographic}]])";

    /**
     * One OTP char, followed by an optional dash
     */
    private static final String OTP_CHAR_WITH_DASH = format("(%s-?)", OTP_CHAR);

    /**
     * Performs a lookahead to find a digit after 0 to 7 OTP_CHARs. This ensures that our potential
     * OTP code contains at least one number
     */
    private static final String FIND_DIGIT = format("(?=%s{0,7}\\d)", OTP_CHAR_WITH_DASH);

    /**
     * Matches between 5 and 8 otp chars, with dashes in between. Here, we are assuming an OTP code
     * is 5-8 characters long. The last char must not be followed by a dash
     */
    private static final String OTP_CHARS = format("(%s{4,7}%s)", OTP_CHAR_WITH_DASH, OTP_CHAR);

    /**
     * A regex matching a line end, non-alphanumeric char (except dash or underscore), or an
     * ideographic char. It will not consume the end char
     */
    private static final String END = "(?=[^-_\\w]|$|\\p{IsIdeographic})";

    /**
     * A regex matching four digit numerical codes
     */
    private static final String FOUR_DIGITS = "(\\d{4})";

    private static final String FIVE_TO_EIGHT_ALPHANUM_AT_LEAST_ONE_NUM =
            format("(%s%s)", FIND_DIGIT, OTP_CHARS);

    /**
     * A regex matching two pairs of 3 digits (ex "123 456")
     */
    private static final String SIX_DIGITS_WITH_SPACE = "(\\d{3}\\s\\d{3})";

    /**
     * Combining the regular expressions above, we get an OTP regex:
     * 1. search for START, THEN
     * 2. match ONE of
     *   a. alphanumeric sequence, at least one number, length 5-8, with optional dashes
     *   b. 4 numbers in a row
     *   c. pair of 3 digit codes separated by a space
     * THEN
     * 3. search for END Ex:
     * "6454", " 345 678.", "[YDT-456]"
     */
    private static final String ALL_OTP =
            format("%s(%s|%s|%s)%s",
                    START, FIVE_TO_EIGHT_ALPHANUM_AT_LEAST_ONE_NUM, FOUR_DIGITS,
                    SIX_DIGITS_WITH_SPACE, END);



    private static final ThreadLocal<Matcher> OTP_REGEX = ThreadLocal.withInitial(() ->
            Pattern.compile(ALL_OTP).matcher(""));
    /**
     * A Date regular expression. Looks for dates with the month, day, and year separated by dashes.
     * Handles one and two digit months and days, and four or two-digit years. It makes the
     * following assumptions:
     * Dates and months will never be higher than 39
     * If a four digit year is used, the leading digit will be 1 or 2
     */
    private static final String DATE_WITH_DASHES = "([0-3]?\\d-[0-3]?\\d-([12]\\d)?\\d\\d)";

    /**
     * matches a ten digit phone number, when the area code is separated by a space or dash.
     * Supports optional parentheses around the area code, and an optional dash or space in between
     * the rest of the numbers.
     * This format registers as an otp match due to the space between the area code and the rest,
     * but shouldn't.
     */
    private static final String PHONE_WITH_SPACE = "(\\(?\\d{3}\\)?(-|\\s)?\\d{3}(-|\\s)?\\d{4})";

    /**
     * A combination of common false positives. These matches are expected to be longer than (or
     * equal in length to) otp matches, and are always run, even if we have a language specific
     * regex
     */
    private static final ThreadLocal<Matcher> FALSE_POSITIVE_LONGER_REGEX =
            ThreadLocal.withInitial(() -> Pattern.compile(
                    format("%s(%s|%s)%s", START, DATE_WITH_DASHES, PHONE_WITH_SPACE, END))
                    .matcher(""));

    /**
     * A regex matching the common years of 19xx and 20xx. Used for false positive reduction
     */
    private static final String COMMON_YEARS = "((19|20)\\d\\d)";

    /**
     * A regex matching three lower case letters. Used for false positive reduction, as no known
     *  OTPs have 3 lowercase letters in sequence.
     */
    private static final String THREE_LOWERCASE = "(\\p{Ll}{3})";

    /**
     * A combination of common false positives. Run in cases where we don't have a language specific
     * regular expression. These matches are expect to be shorter than (or equal in length to) otp
     * matches
     */
    private static final ThreadLocal<Matcher> FALSE_POSITIVE_SHORTER_REGEX =
            ThreadLocal.withInitial(() -> Pattern.compile(
                    format("%s|%s", COMMON_YEARS, THREE_LOWERCASE)).matcher(""));

    /**
     * A list of regular expressions representing words found in an OTP context (non case sensitive)
     * Note: TAN is short for Transaction Authentication Number
     */
    private static final String[] ENGLISH_CONTEXT_WORDS = new String[] {
            "pin", "pass[-\\s]?(code|word)", "TAN", "otp", "2fa", "(two|2)[-\\s]?factor",
            "log[-\\s]?in", "auth(enticat(e|ion))?", "code", "secret", "verif(y|ication)",
            "confirm(ation)?", "one(\\s|-)?time"
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
        EXTRA_LANG_OTP_REGEX.put(ULocale.ENGLISH.toLanguageTag(), ThreadLocal.withInitial(() ->
                createDictionaryRegex(ENGLISH_CONTEXT_WORDS)));
    }

    /**
     * Checks if the sensitive parts of a notification might contain an OTP, based on several
     * regular expressions, and potentially using a textClassifier to eliminate false positives
     *
     * @param notification The notification whose content should be checked
     * @param checkForFalsePositives If true, will ensure the content does not match the date regex.
     *                               If a TextClassifier is provided, it will then try to find a
     *                               language specific regex. If it is successful, it will use that
     *                               regex to check for false positives. If it is not, it will use
     *                               the TextClassifier (if provided), plus the year and three
     *                               lowercase regexes to remove possible false positives.
     * @param tc If non null, the provided TextClassifier will be used to find the language of the
     *           text, and look for a language-specific regex for it. If checkForFalsePositives is
     *           true will also use the classifier to find flight codes and addresses.
     * @return True if the regex matches and ensureNotDate is false, or the date regex failed to
     *     match, false otherwise.
     */
    public static boolean containsOtp(Notification notification,
            boolean checkForFalsePositives, TextClassifier tc) {
        if (notification == null || !SdkLevel.isAtLeastV()) {
            return false;
        }

        String sensitiveText = getTextForDetection(notification);
        Matcher otpMatcher = OTP_REGEX.get();
        otpMatcher.reset(sensitiveText);
        boolean otpMatch = otpMatcher.find();
        if (!checkForFalsePositives || !otpMatch) {
            return otpMatch;
        }

        if (allOtpMatchesAreFalsePositives(
                sensitiveText, FALSE_POSITIVE_LONGER_REGEX.get(), true)) {
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

        return !allOtpMatchesAreFalsePositives(sensitiveText, FALSE_POSITIVE_SHORTER_REGEX.get(),
                false);
    }

    /**
     * Checks that a given text has at least one match for one regex, that doesn't match another
     * @param text The full text to check
     * @param falsePositiveRegex A regex that should not match the OTP regex (for at least one match
     *                           found by the OTP regex). The false positive regex matches may be
     *                           longer or shorter than the OTP matches.
     * @param fpMatchesAreLongerThanOtp Whether the false positives are longer than the otp matches.
     *                                  If true, this method will search the whole text for false
     *                                  positives, and verify at least one OTP match is not
     *                                  contained by any of the false positives. If false, then this
     *                                  method will search individual OTP matches for false
     *                                  positives, and will verify at least one OTP match doesn't
     *                                  contain a false positive.
     * @return true, if all matches found by OTP_REGEX are contained in, or themselves contain a
     *         match to falsePositiveRegex, or there are no OTP matches, false otherwise.
     */
    private static boolean allOtpMatchesAreFalsePositives(String text, Matcher falsePositiveRegex,
            boolean fpMatchesAreLongerThanOtp) {
        List<String> falsePositives = new ArrayList<>();
        if (fpMatchesAreLongerThanOtp) {
            // if the false positives are longer than the otp, search for them in the whole text
            falsePositives = getAllMatches(text, falsePositiveRegex);
        }
        List<String> otpMatches = getAllMatches(text, OTP_REGEX.get());
        for (String otpMatch: otpMatches) {
            boolean otpMatchContainsNoFp = true, noFpContainsOtpMatch = true;
            if (!fpMatchesAreLongerThanOtp) {
                // if the false positives are shorter than the otp, search for them in the otp match
                falsePositives = getAllMatches(otpMatch, falsePositiveRegex);
            }
            for (String falsePositive : falsePositives) {
                otpMatchContainsNoFp = fpMatchesAreLongerThanOtp
                        || (otpMatchContainsNoFp && !otpMatch.contains(falsePositive));
                noFpContainsOtpMatch = !fpMatchesAreLongerThanOtp
                        || (noFpContainsOtpMatch && !falsePositive.contains(otpMatch));
            }
            if (otpMatchContainsNoFp && noFpContainsOtpMatch) {
                return false;
            }
        }
        return true;
    }

    private static List<String> getAllMatches(String text, Matcher regex) {
        ArrayList<String> matches = new ArrayList<>();
        regex.reset(text);
        while (regex.find()) {
            matches.add(regex.group());
        }
        return matches;
    }

    private static Matcher getLanguageSpecificRegex(String text, TextClassifier tc) {
        TextLanguage.Request langRequest = new TextLanguage.Request.Builder(text).build();
        TextLanguage lang = tc.detectLanguage(langRequest);
        for (int i = 0; i < lang.getLocaleHypothesisCount(); i++) {
            ULocale locale = lang.getLocale(i);
            if (lang.getConfidenceScore(locale) >= TC_THRESHOLD
                    && EXTRA_LANG_OTP_REGEX.containsKey(locale.toLanguageTag())) {
                return EXTRA_LANG_OTP_REGEX.get(locale.toLanguageTag()).get();
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
        CharSequence title = extras.getCharSequence(EXTRA_TITLE);
        CharSequence text = extras.getCharSequence(EXTRA_TEXT);
        CharSequence subText = extras.getCharSequence(EXTRA_SUB_TEXT);
        StringBuilder builder = new StringBuilder()
                .append(title != null ? title : "").append(" ")
                .append(text != null ? text : "").append(" ")
                .append(subText != null ? subText : "").append(" ");
        if (Flags.redactSensitiveNotificationsBigTextStyle()) {
            CharSequence bigText = extras.getCharSequence(EXTRA_BIG_TEXT);
            CharSequence bigTitleText = extras.getCharSequence(EXTRA_TITLE_BIG);
            CharSequence summaryText = extras.getCharSequence(EXTRA_SUMMARY_TEXT);
            builder.append(bigText != null ? bigText : "").append(" ")
                    .append(bigTitleText != null ? bigTitleText : "").append(" ")
                    .append(summaryText != null ? summaryText : "").append(" ");
        }
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
