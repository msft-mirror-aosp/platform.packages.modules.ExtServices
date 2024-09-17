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
import static android.os.Build.VERSION.SDK_INT;
import static android.view.textclassifier.TextClassifier.TYPE_ADDRESS;
import static android.view.textclassifier.TextClassifier.TYPE_FLIGHT_NUMBER;
import static android.view.textclassifier.TextClassifier.TYPE_PHONE;

import static java.lang.String.format;

import android.app.Notification;
import android.app.Notification.MessagingStyle;
import android.app.Notification.MessagingStyle.Message;
import android.icu.util.ULocale;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.util.ArrayMap;
import android.view.textclassifier.TextClassifier;
import android.view.textclassifier.TextLanguage;
import android.view.textclassifier.TextLinks;

import androidx.annotation.VisibleForTesting;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Class with helper methods related to detecting OTP codes in notifications.
 * This file needs to only use public android API methods, see b/361149088
 */
public class NotificationOtpDetectionHelper {

    // Use an ArrayList because a List.of list will throw NPE when calling "contains(null)"
    private static final List<String> SENSITIVE_NOTIFICATION_CATEGORIES = new ArrayList<>(
            Arrays.asList(CATEGORY_MESSAGE, CATEGORY_EMAIL, CATEGORY_SOCIAL));

    private static final List<Class<? extends Notification.Style>> SENSITIVE_STYLES =
            new ArrayList<>(Arrays.asList(Notification.MessagingStyle.class,
                    Notification.InboxStyle.class, Notification.BigTextStyle.class));

    private static final List<Class<? extends Notification.Style>> EXCLUDED_STYLES =
            new ArrayList<>(Arrays.asList(Notification.MediaStyle.class,
                    Notification.BigPictureStyle.class));
    static {
        if (SDK_INT >= Build.VERSION_CODES.S) {
            EXCLUDED_STYLES.add(Notification.CallStyle.class);
        }
    }

    private static final float TC_THRESHOLD = 0.6f;

    private static final ArrayMap<String, ThreadLocal<Matcher>> EXTRA_LANG_OTP_REGEX =
            new ArrayMap<>();

    private static final int MAX_SENSITIVE_TEXT_LEN = 600;

    /**
     * A regex matching a line start, open paren, arrow, colon (not proceeded by a digit),
     * open square bracket, equals sign, double or single quote, ideographic char, or a space that
     * is not preceded by a number. It will not consume the start char (meaning START won't be
     * included in the matched string)
     */
    private static final String START =
            "(^|(?<=((^|[^0-9])\\s)|[>(\"'=\\[\\p{IsIdeographic}]|[^0-9]:))";


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
     * A regex matching a line end, a space that is not followed by a number, an ideographic char,
     * or a period, close paren, close square bracket, single or double quote, exclamation point,
     * question mark, or comma. It will not consume the end char
     */
    private static final String END = "(?=\\s[^0-9]|$|\\p{IsIdeographic}|[.?!,)'\\]\"])";

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
    private static final String COMMON_YEARS = format("%s((19|20)\\d\\d)%s", START, END);

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
            "one(\\s|-)?time", "access", "validat(e|ion)"
    };

    /**
     * Creates a regular expression to match any of a series of individual words, case insensitive.
     * It also verifies the position of the word, relative to the OTP match
     */
    private static Matcher createDictionaryRegex(String[] words) {
        StringBuilder regex = new StringBuilder("(?i)(");
        for (int i = 0; i < words.length; i++) {
            regex.append(findContextWordWithCode(words[i]));
            if (i != words.length - 1) {
                regex.append("|");
            }
        }
        regex.append(")");
        return Pattern.compile(regex.toString()).matcher("");
    }

    /**
     * Creates a regular expression that will find a context word, if that word occurs in the
     * sentence preceding an OTP, or in the same sentence as an OTP (before or after). In both
     * cases, the context word must occur within 50 characters of the suspected OTP
     * @param contextWord The context word we expect to find around the OTP match
     * @return A string representing a regular expression that will determine if we found a context
     * word occurring before an otp match, or after it, but in the same sentence.
     */
    private static String findContextWordWithCode(String contextWord) {
        String boundedContext = "\\b" + contextWord + "\\b";
        // Asserts that we find the OTP code within 50 characters after the context word, with at
        // most one sentence punctuation between the OTP code and the context word (i.e. they are
        // in the same sentence, or the context word is in the previous sentence)
        String contextWordBeforeOtpInSameOrPreviousSentence =
                String.format("(%s(?=.{1,50}%s)[^.?!]*[.?!]?[^.?!]*%s)",
                        boundedContext, ALL_OTP, ALL_OTP);
        // Asserts that we find the context word within 50 characters after the OTP code, with no
        // sentence punctuation between the OTP code and the context word (i.e. they are in the same
        // sentence)
        String contextWordAfterOtpSameSentence =
                String.format("(%s)[^.!?]{1,50}%s", ALL_OTP, boundedContext);
        return String.format("(%s|%s)", contextWordBeforeOtpInSameOrPreviousSentence,
                contextWordAfterOtpSameSentence);
    }

    static {
        EXTRA_LANG_OTP_REGEX.put(ULocale.ENGLISH.toLanguageTag(), ThreadLocal.withInitial(() ->
                createDictionaryRegex(ENGLISH_CONTEXT_WORDS)));
    }

    private static boolean isPreV() {
        return SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM;
    }

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
     * @param tc If non null, the provided TextClassifier will be used to find the language of the
     *           text, and look for a language-specific regex for it. If checkForFalsePositives is
     *           true will also use the classifier to find flight codes and addresses.
     * @return True if we believe an OTP is in the message, false otherwise.
     */
    public static boolean containsOtp(Notification notification,
            boolean checkForFalsePositives, TextClassifier tc) {
        if (notification == null || notification.extras == null || isPreV()) {
            return false;
        }

        // Get the language of the text once
        ULocale textLocale = getLanguageWithRegex(getTextForDetection(notification), tc);
        // Get all the individual fields
        List<CharSequence> fields = getNotificationTextFields(notification);
        for (CharSequence field : fields) {
            if (field != null
                    && containsOtp(field.toString(), checkForFalsePositives, tc, textLocale)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Checks if a string of text might contain an OTP, based on several
     * regular expressions, and potentially using a textClassifier to eliminate false positives
     *
     * @param sensitiveText The text whose content should be checked
     * @param checkForFalsePositives If true, will ensure the content does not match the date regex.
     *                               If a TextClassifier is provided, it will then try to find a
     *                               language specific regex. If it is successful, it will use that
     *                               regex to check for false positives. If it is not, it will use
     *                               the TextClassifier (if provided), plus the year and three
     *                               lowercase regexes to remove possible false positives.
     * @param tc If non null, the provided TextClassifier will be used to find the language of the
     *           text, and look for a language-specific regex for it. If checkForFalsePositives is
     *           true will also use the classifier to find flight codes and addresses.
     * @param language If non null, then the TextClassifier (if provided), will not perform language
     *                 id, and the system will assume the text is in the specified language
     * @return True if we believe an OTP is in the message, false otherwise.
     */
    public static boolean containsOtp(String sensitiveText,
            boolean checkForFalsePositives, TextClassifier tc, ULocale language) {
        if (sensitiveText == null || isPreV()) {
            return false;
        }

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

        if (tc != null || language != null) {
            if (language == null) {
                language = getLanguageWithRegex(sensitiveText, tc);
            }
            Matcher languageSpecificMatcher = language != null
                    ? EXTRA_LANG_OTP_REGEX.get(language.toLanguageTag()).get() : null;
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

    // Tries to determine the language of the given text. Will return the language with the highest
    // confidence score that meets the minimum threshold, and has a language-specific regex, null
    // otherwise
    private static ULocale getLanguageWithRegex(String text, TextClassifier tc) {
        if (tc == null) {
            return null;
        }

        float highestConfidence = 0;
        ULocale highestConfidenceLocale = null;
        TextLanguage.Request langRequest = new TextLanguage.Request.Builder(text).build();
        TextLanguage lang = tc.detectLanguage(langRequest);
        for (int i = 0; i < lang.getLocaleHypothesisCount(); i++) {
            ULocale locale = lang.getLocale(i);
            float confidence = lang.getConfidenceScore(locale);
            if (confidence >= TC_THRESHOLD && confidence >= highestConfidence
                    && EXTRA_LANG_OTP_REGEX.containsKey(locale.toLanguageTag())) {
                highestConfidence = confidence;
                highestConfidenceLocale = locale;
            }
        }
        return highestConfidenceLocale;
    }

    private static boolean hasFalsePositivesTcCheck(String text, TextClassifier tc) {
        if (tc == null) {
            return false;
        }
        // Use TC to eliminate false positives from a regex match, namely: flight codes, and
        // addresses
        List<String> included = new ArrayList<>(Arrays.asList(TYPE_FLIGHT_NUMBER, TYPE_ADDRESS));
        List<String> excluded = new ArrayList<>(Arrays.asList(TYPE_PHONE));
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
    @VisibleForTesting
    protected static String getTextForDetection(Notification notification) {
        if (notification == null || notification.extras == null || isPreV()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (CharSequence line : getNotificationTextFields(notification)) {
            builder.append(line != null ? line : "").append(" ");
        }
        return builder.length() <= MAX_SENSITIVE_TEXT_LEN ? builder.toString()
                : builder.substring(0, MAX_SENSITIVE_TEXT_LEN);
    }

    protected static List<CharSequence> getNotificationTextFields(Notification notification) {
        if (notification == null || notification.extras == null || isPreV()) {
            return List.of();
        }
        ArrayList<CharSequence> fields = new ArrayList<>();
        Bundle extras = notification.extras;
        fields.add(extras.getCharSequence(EXTRA_TITLE));
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
        // Sort the newest messages (largest timestamp) first
        messages.sort((MessagingStyle.Message lhs, MessagingStyle.Message rhs) ->
                Long.compare(rhs.getTimestamp(), lhs.getTimestamp()));
        for (MessagingStyle.Message message : messages) {
            fields.add(message.getText());
        }
        return fields;
    }

    /**
     * Determines if a notification should be checked for an OTP, based on category, style, and
     * possible otp content (as determined by a regular expression).
     * @param notification The notification whose content should be checked
     * @return true, if further checks for OTP codes should be performed, false otherwise
     */
    public static boolean shouldCheckForOtp(Notification notification) {
        if (notification == null || isPreV()
                || EXCLUDED_STYLES.stream().anyMatch(s -> isStyle(notification, s))) {
            return false;
        }
        return SENSITIVE_NOTIFICATION_CATEGORIES.contains(notification.category)
                || SENSITIVE_STYLES.stream().anyMatch(s -> isStyle(notification, s))
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
