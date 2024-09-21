/**
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

package android.ext.services.notification

import android.app.Notification
import android.app.Notification.CATEGORY_EMAIL
import android.app.Notification.CATEGORY_MESSAGE
import android.app.Notification.CATEGORY_SOCIAL
import android.app.Notification.EXTRA_TEXT
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.content.Intent
import android.icu.util.ULocale
import android.os.Build
import android.os.Build.VERSION.SDK_INT
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextClassifier
import android.view.textclassifier.TextLanguage
import android.view.textclassifier.TextLinks
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertWithMessage
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito

@RunWith(AndroidJUnit4::class)
class NotificationOtpDetectionHelperTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val localeWithRegex = ULocale.ENGLISH
    private val invalidLocale = ULocale.ROOT

    private data class TestResult(
        val expected: Boolean,
        val actual: Boolean,
        val failureMessage: String
    )

    private val results = mutableListOf<TestResult>()

    @Before
    fun enableFlag() {
        assumeTrue(SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM)
        results.clear()
    }

    @After
    fun verifyResults() {
        val allFailuresMessage = StringBuilder("")
        var numFailures = 0;
        for ((expected, actual, failureMessage) in results) {
            if (expected != actual) {
                numFailures += 1
                allFailuresMessage.append("$failureMessage\n")
            }
        }
        assertWithMessage("Found $numFailures failures:\n$allFailuresMessage")
            .that(numFailures).isEqualTo(0)
    }

    private fun addResult(expected: Boolean, actual: Boolean, failureMessage: String) {
        results.add(TestResult(expected, actual, failureMessage))
    }

    @Test
    fun testGetTextForDetection_textFieldsIncluded() {
        val text = "text"
        val title = "title"
        val subtext = "subtext"
        val sensitive = NotificationOtpDetectionHelper.getTextForDetection(
            createNotification(text = text, title = title, subtext = subtext))
        addResult(expected = true, sensitive.contains(text),"expected sensitive text to contain $text")
        addResult(expected = true, sensitive.contains(title), "expected sensitive text to contain $title")
        addResult(expected = true, sensitive.contains(subtext), "expected sensitive text to contain $subtext")
    }

    @Test
    fun testGetTextForDetection_nullTextFields() {
        val text = "text"
        val title = "title"
        val subtext = "subtext"
        var sensitive = NotificationOtpDetectionHelper.getTextForDetection(
            createNotification(text = text, title = null, subtext = null))
        addResult(expected = true, sensitive.contains(text), "expected sensitive text to contain $text")
        addResult(expected = false, sensitive.contains(title), "expected sensitive text not to contain $title")
        addResult(expected = false, sensitive.contains("subtext"), "expected sensitive text not to contain $subtext")
        sensitive = NotificationOtpDetectionHelper.getTextForDetection(
            createNotification(text = null, title = null, subtext = null))
        addResult(expected = true, sensitive != null, "expected to get a nonnull string")
        val nullExtras = createNotification(text = null, title = null, subtext = null).apply {
            this.extras = null
        }
        sensitive = NotificationOtpDetectionHelper.getTextForDetection(nullExtras)
        addResult(expected = true, sensitive != null, "expected to get a nonnull string")
    }

    @Test
    fun testGetTextForDetection_messagesIncludedSorted() {
        val empty = Person.Builder().setName("test name").build()
        val messageText1 = "message text 1"
        val messageText2 = "message text 2"
        val messageText3 = "message text 3"
        val timestamp1 = 0L
        val timestamp2 = 1000L
        val timestamp3 = 50L
        val message1 =
            Notification.MessagingStyle.Message(messageText1,
                timestamp1,
                empty)
        val message2 =
            Notification.MessagingStyle.Message(messageText2,
                timestamp2,
                empty)
        val message3 =
            Notification.MessagingStyle.Message(messageText3,
                timestamp3,
                empty)
        val style = Notification.MessagingStyle(empty).apply {
            addMessage(message1)
            addMessage(message2)
            addMessage(message3)
        }
        val notif = createNotification(style = style)
        val sensitive = NotificationOtpDetectionHelper.getTextForDetection(notif)
        addResult(expected = true, sensitive.contains(messageText1), "expected sensitive text to contain $messageText1")
        addResult(expected = true, sensitive.contains(messageText2), "expected sensitive text to contain $messageText2")
        addResult(expected = true, sensitive.contains(messageText3), "expected sensitive text to contain $messageText3")

        // MessagingStyle notifications get their main text set automatically to their first
        // message, so we should skip to the end of that to find the message text
        val notifText = notif.extras.getCharSequence(EXTRA_TEXT)?.toString() ?: ""
        val messagesSensitiveStartIdx = sensitive.indexOf(notifText) + notifText.length
        val sensitiveSub = sensitive.substring(messagesSensitiveStartIdx)
        val text1Position = sensitiveSub.indexOf(messageText1)
        val text2Position = sensitiveSub.indexOf(messageText2)
        val text3Position = sensitiveSub.indexOf(messageText3)
        // The messages should be sorted by timestamp, newest first, so 2 -> 3 -> 1
        addResult(expected = true, text2Position < text1Position, "expected the newest message (2) to be first in \"$sensitiveSub\"")
        addResult(expected = true, text2Position < text3Position, "expected the newest message (2) to be first in \"$sensitiveSub\"")
        addResult(expected = true, text3Position < text1Position, "expected the middle message (3) to be center in \"$sensitiveSub\"")
    }

    @Test
    fun testGetTextForDetection_textLinesIncluded() {
        val style = Notification.InboxStyle()
        val extraLine = "extra line"
        style.addLine(extraLine)
        val sensitive = NotificationOtpDetectionHelper
                .getTextForDetection(createNotification(style = style))
        addResult(expected = true, sensitive.contains(extraLine), "expected sensitive text to contain $extraLine")
    }

    @Test
    fun testGetTextForDetection_bigTextStyleTextsIncluded() {
        val style = Notification.BigTextStyle()
        val bigText = "BIG TEXT"
        val bigTitleText = "BIG TITLE TEXT"
        val summaryText = "summary text"
        style.bigText(bigText)
        style.setBigContentTitle(bigTitleText)
        style.setSummaryText(summaryText)
        val sensitive = NotificationOtpDetectionHelper
            .getTextForDetection(createNotification(style = style))
        addResult(expected = true, sensitive.contains(bigText), "expected sensitive text to contain $bigText")
        addResult(expected =
            true,
            sensitive.contains(bigTitleText),
            "expected sensitive text to contain $bigTitleText"
        )
        addResult(expected =
            true,
            sensitive.contains(summaryText),
            "expected sensitive text to contain $summaryText"
        )
    }

    @Test
    fun testGetTextForDetection_maxLen() {
        val text = "0123456789".repeat(70) // 700 chars
        val sensitive =
            NotificationOtpDetectionHelper.getTextForDetection(createNotification(text = text))
        addResult(expected = true, sensitive.length <= 600, "Expected to be 600 chars or fewer")
    }

    @Test
    fun testShouldCheckForOtp_styles() {
        val style = Notification.InboxStyle()
        var shouldCheck = NotificationOtpDetectionHelper
                .shouldCheckForOtp(createNotification(style = style))
        addResult(expected = true, shouldCheck, "InboxStyle should be checked")
        val empty = Person.Builder().setName("test").build()
        val style2 = Notification.MessagingStyle(empty)
        val style3 = Notification.BigPictureStyle()
        val rejectedStyle = Notification.MediaStyle()
        shouldCheck = NotificationOtpDetectionHelper
                .shouldCheckForOtp(createNotification(style = style2))
        addResult(expected = true, shouldCheck, "MessagingStyle should be checked")
        shouldCheck = NotificationOtpDetectionHelper
                .shouldCheckForOtp(createNotification())
        addResult(expected = false, shouldCheck, "No style should not be checked")
        shouldCheck = NotificationOtpDetectionHelper
                .shouldCheckForOtp(createNotification(style = style3))
        addResult(expected = false, shouldCheck, "Valid non-messaging non-inbox style should not be checked")
        shouldCheck = NotificationOtpDetectionHelper
            .shouldCheckForOtp(createNotification(text = "your one time code is 4343434",
                style = rejectedStyle))
        addResult(expected = false, shouldCheck, "MediaStyle should always be rejected")
    }

    @Test
    fun testShouldCheckForOtp_categories() {
        var shouldCheck = NotificationOtpDetectionHelper
                .shouldCheckForOtp(createNotification(category = CATEGORY_MESSAGE))
        addResult(expected = true, shouldCheck, "$CATEGORY_MESSAGE should be checked")
        shouldCheck = NotificationOtpDetectionHelper
            .shouldCheckForOtp(createNotification(category = CATEGORY_SOCIAL))
        addResult(expected = true, shouldCheck, "$CATEGORY_SOCIAL should be checked")
        shouldCheck = NotificationOtpDetectionHelper
            .shouldCheckForOtp(createNotification(category = CATEGORY_EMAIL))
        addResult(expected = true, shouldCheck, "$CATEGORY_EMAIL should be checked")
        shouldCheck = NotificationOtpDetectionHelper
            .shouldCheckForOtp(createNotification(category = ""))
        addResult(expected = false, shouldCheck, "Empty string category should not be checked")
    }

    @Test
    fun testShouldCheckForOtp_regex() {
        val shouldCheck = NotificationOtpDetectionHelper
                .shouldCheckForOtp(createNotification(text = "45454", category = ""))
        assertWithMessage("Regex matches should be checked").that(shouldCheck).isTrue()
    }

    @Test
    fun testShouldCheckForOtp_publicVersion() {
        var publicVersion = createNotification(category = CATEGORY_MESSAGE)
        var shouldCheck = NotificationOtpDetectionHelper
                .shouldCheckForOtp(createNotification(publicVersion = publicVersion))

        addResult(expected = true, shouldCheck, "notifications with a checked category in their public version should " +
                "be checked")
        publicVersion = createNotification(style = Notification.InboxStyle())
        shouldCheck = NotificationOtpDetectionHelper
            .shouldCheckForOtp(createNotification(publicVersion = publicVersion))
        addResult(expected = true, shouldCheck, "notifications with a checked style in their public version should " +
                "be checked")
    }


    @Test
    fun testContainsOtp_length() {
        val tooShortAlphaNum = "123G"
        val tooShortNumOnly = "123"
        val minLenAlphaNum = "123G5"
        val minLenNumOnly = "1235"
        val twoTriplets = "123 456"
        val tooShortTriplets = "12 345"
        val maxLen = "123456F8"
        val tooLong = "123T56789"

        addMatcherTestResult(expected = true, minLenAlphaNum)
        addMatcherTestResult(expected = true, minLenNumOnly)
        addMatcherTestResult(expected = true, maxLen)
        addMatcherTestResult(expected = false, tooShortAlphaNum, customFailureMessage = "is too short")
        addMatcherTestResult(expected = false, tooShortNumOnly, customFailureMessage = "is too short")
        addMatcherTestResult(expected = false, tooLong, customFailureMessage = "is too long")
        addMatcherTestResult(expected = true, twoTriplets)
        addMatcherTestResult(expected = false, tooShortTriplets, customFailureMessage = "is too short")
    }

    @Test
    fun testContainsOtp_acceptsNonRomanAlphabeticalChars() {
        val lowercase = "123ķ4"
        val uppercase = "123Ŀ4"
        val ideographicInMiddle = "123码456"
        addMatcherTestResult(expected = true, lowercase)
        addMatcherTestResult(expected = true, uppercase)
        addMatcherTestResult(expected = false, ideographicInMiddle)
    }

    @Test
    fun testContainsOtp_mustHaveNumber() {
        val noNums = "TEFHXES"
        addMatcherTestResult(expected = false, noNums)
    }

    @Test
    fun testContainsOtp_dateExclusion() {
        val date = "01-01-2001"
        val singleDigitDate = "1-1-2001"
        val twoDigitYear = "1-1-01"
        val dateWithOtpAfter = "1-1-01 is the date of your code T3425"
        val dateWithOtpBefore = "your code 54-234-3 was sent on 1-1-01"
        val otpWithDashesButInvalidDate = "34-58-30"
        val otpWithDashesButInvalidYear = "12-1-3089"

        addMatcherTestResult(
            expected = true,
            date,
            checkForFalsePositives = false,
            customFailureMessage = "should match if checkForFalsePositives is false"
        )
        addMatcherTestResult(
            expected = false,
            date,
            customFailureMessage = "should not match if checkForFalsePositives is true"
        )
        addMatcherTestResult(expected = false, singleDigitDate)
        addMatcherTestResult(expected = false, twoDigitYear)
        addMatcherTestResult(expected = true, dateWithOtpAfter)
        addMatcherTestResult(expected = true, dateWithOtpBefore)
        addMatcherTestResult(expected = true, otpWithDashesButInvalidDate)
        addMatcherTestResult(expected = true, otpWithDashesButInvalidYear)
    }

    @Test
    fun testContainsOtp_phoneExclusion() {
        val parens = "(888) 8888888"
        val allSpaces = "888 888 8888"
        val withDash = "(888) 888-8888"
        val allDashes = "888-888-8888"
        val allDashesWithParen = "(888)-888-8888"
        addMatcherTestResult(
            expected = true,
            parens,
            checkForFalsePositives = false,
            customFailureMessage = "should match if checkForFalsePositives is false"
        )
        addMatcherTestResult(expected = false, parens)
        addMatcherTestResult(expected = false, allSpaces)
        addMatcherTestResult(expected = false, withDash)
        addMatcherTestResult(expected = false, allDashes)
        addMatcherTestResult(expected = false, allDashesWithParen)
    }

    @Test
    fun testContainsOtp_dashes() {
        val oneDash = "G-3d523"
        val manyDashes = "G-FD-745"
        val tooManyDashes = "6--7893"
        val oopsAllDashes = "------"
        addMatcherTestResult(expected = true, oneDash)
        addMatcherTestResult(expected = true, manyDashes)
        addMatcherTestResult(expected = false, tooManyDashes)
        addMatcherTestResult(expected = false, oopsAllDashes)
    }

    @Test
    fun testContainsOtp_startAndEnd() {
        val noSpaceStart = "your code isG-345821"
        val noSpaceEnd = "your code is G-345821for real"
        val numberSpaceStart = "your code is 4 G-345821"
        val numberSpaceEnd = "your code is G-345821 3"
        val colonStart = "your code is:G-345821"
        val newLineStart = "your code is \nG-345821"
        val quote = "your code is 'G-345821'"
        val doubleQuote = "your code is \"G-345821\""
        val bracketStart = "your code is [G-345821"
        val ideographicStart = "your code is码G-345821"
        val colonStartNumberPreceding = "your code is4:G-345821"
        val periodEnd = "you code is G-345821."
        val parens = "you code is (G-345821)"
        val squareBrkt = "you code is [G-345821]"
        val dashEnd = "you code is 'G-345821-'"
        val randomSymbolEnd = "your code is G-345821$"
        val underscoreEnd = "you code is 'G-345821_'"
        val ideographicEnd = "your code is码G-345821码"
        addMatcherTestResult(expected = false, noSpaceStart)
        addMatcherTestResult(expected = false, noSpaceEnd)
        addMatcherTestResult(expected = false, numberSpaceStart)
        addMatcherTestResult(expected = false, numberSpaceEnd)
        addMatcherTestResult(expected = false, colonStartNumberPreceding)
        addMatcherTestResult(expected = false, dashEnd)
        addMatcherTestResult(expected = false, underscoreEnd)
        addMatcherTestResult(expected = false, randomSymbolEnd)
        addMatcherTestResult(expected = true, colonStart)
        addMatcherTestResult(expected = true, newLineStart)
        addMatcherTestResult(expected = true, quote)
        addMatcherTestResult(expected = true, doubleQuote)
        addMatcherTestResult(expected = true, bracketStart)
        addMatcherTestResult(expected = true, ideographicStart)
        addMatcherTestResult(expected = true, periodEnd)
        addMatcherTestResult(expected = true, parens)
        addMatcherTestResult(expected = true, squareBrkt)
        addMatcherTestResult(expected = true, ideographicEnd)
    }

    @Test
    fun testContainsOtp_lookaheadMustBeOtpChar() {
        val validLookahead = "g4zy75"
        val spaceLookahead = "GVRXY 2"
        addMatcherTestResult(expected = true, validLookahead)
        addMatcherTestResult(expected = false, spaceLookahead)
    }

    @Test
    fun testContainsOtp_threeDontMatch_withoutLanguageSpecificRegex() {
        val tc = getTestTextClassifier(invalidLocale)
        val threeLowercase = "34agb"
        addMatcherTestResult(expected = false, threeLowercase, textClassifier = tc)
    }

    @Test
    fun testContainsOtp_commonYearsDontMatch_withoutLanguageSpecificRegex() {
        val tc = getTestTextClassifier(invalidLocale)
        val twentyXX = "2009"
        val twentyOneXX = "2109"
        val thirtyXX = "3035"
        val nineteenXX = "1945"
        val eighteenXX = "1899"
        val yearSubstring = "20051"
        addMatcherTestResult(expected = false, twentyXX, textClassifier = tc)
        // Behavior should be the same for an invalid language, and null TextClassifier
        addMatcherTestResult(expected = false, twentyXX, textClassifier = null)
        addMatcherTestResult(expected = true, twentyOneXX, textClassifier = tc)
        addMatcherTestResult(expected = true, thirtyXX, textClassifier = tc)
        addMatcherTestResult(expected = false, nineteenXX, textClassifier = tc)
        addMatcherTestResult(expected = true, eighteenXX, textClassifier = tc)
        // A substring of a year should not trigger a false positive
        addMatcherTestResult(expected = true, yearSubstring, textClassifier = tc)
    }

    @Test
    fun testContainsOtp_englishSpecificRegex() {
        val tc = getTestTextClassifier(ULocale.ENGLISH)
        val englishFalsePositive = "This is a false positive 4543"
        val englishContextWords = listOf("login", "log in", "2fa", "authenticate", "auth",
            "authentication", "tan", "password", "passcode", "two factor", "two-factor", "2factor",
            "2 factor", "pin", "one time")
        val englishContextWordsCase = listOf("LOGIN", "logIn", "LoGiN")
        // Strings with a context word somewhere in the substring
        val englishContextSubstrings = listOf("pins", "gaping", "backspin")
        val codeInNextSentence = "context word: code. This sentence has the actual value of 434343"
        val codeInNextSentenceTooFar =
            "context word: code. ${"f".repeat(60)} This sentence has the actual value of 434343"
        val codeTwoSentencesAfterContext = "context word: code. One sentence. actual value 34343"
        val codeInSentenceBeforeContext = "34343 is a number. This number is a code"
        val codeInSentenceAfterNewline = "your code is \n 34343"
        val codeTooFarBeforeContext = "34343 ${"f".repeat(60)} code"

        addMatcherTestResult(expected = false, englishFalsePositive, textClassifier = tc)
        for (context in englishContextWords) {
            val englishTruePositive = "$context $englishFalsePositive"
            addMatcherTestResult(expected = true, englishTruePositive, textClassifier = tc)
        }
        for (context in englishContextWordsCase) {
            val englishTruePositive = "$context $englishFalsePositive"
            addMatcherTestResult(expected = true, englishTruePositive, textClassifier = tc)
        }
        for (falseContext in englishContextSubstrings) {
            val anotherFalsePositive = "$falseContext $englishFalsePositive"
            addMatcherTestResult(expected = false, anotherFalsePositive, textClassifier = tc)
        }
        addMatcherTestResult(expected = true, codeInNextSentence, textClassifier = tc)
        addMatcherTestResult(expected = true, codeInSentenceAfterNewline, textClassifier = tc)
        addMatcherTestResult(expected = false, codeTwoSentencesAfterContext, textClassifier = tc)
        addMatcherTestResult(expected = false, codeInSentenceBeforeContext, textClassifier = tc)
        addMatcherTestResult(expected = false, codeInNextSentenceTooFar, textClassifier = tc)
        addMatcherTestResult(expected = false, codeTooFarBeforeContext, textClassifier = tc)
    }

    @Test
    fun testContainsOtp_notificationFieldsCheckedIndividually() {
        val tc = getTestTextClassifier(ULocale.ENGLISH)
        // Together, the title and text will match the language-specific regex and the main regex,
        // but apart, neither are enough
        val notification = createNotification(text = "code", title = "434343")
        addMatcherTestResult(expected = true, "code 434343")
        addResult(expected = false, NotificationOtpDetectionHelper.containsOtp(notification, true,
            tc), "Expected text of 'code' and title of '434343' not to match")
    }

    @Test
    fun testContainsOtp_multipleFalsePositives() {
        val otp = "code 1543 code"
        val longFp = "888-777-6666"
        val shortFp = "34ess"
        val multipleLongFp = "$longFp something something $longFp"
        val multipleLongFpWithOtpBefore = "$otp $multipleLongFp"
        val multipleLongFpWithOtpAfter = "$multipleLongFp $otp"
        val multipleLongFpWithOtpBetween = "$longFp $otp $longFp"
        val multipleShortFp = "$shortFp something something $shortFp"
        val multipleShortFpWithOtpBefore = "$otp $multipleShortFp"
        val multipleShortFpWithOtpAfter = "$otp $multipleShortFp"
        val multipleShortFpWithOtpBetween = "$shortFp $otp $shortFp"
        addMatcherTestResult(expected = false, multipleLongFp)
        addMatcherTestResult(expected = false, multipleShortFp)
        addMatcherTestResult(expected = true, multipleLongFpWithOtpBefore)
        addMatcherTestResult(expected = true, multipleLongFpWithOtpAfter)
        addMatcherTestResult(expected = true, multipleLongFpWithOtpBetween)
        addMatcherTestResult(expected = true, multipleShortFpWithOtpBefore)
        addMatcherTestResult(expected = true, multipleShortFpWithOtpAfter)
        addMatcherTestResult(expected = true, multipleShortFpWithOtpBetween)
    }

    @Test
    fun testContainsOtpCode_usesTcForFalsePositivesIfNoLanguageSpecificRegex() {
        var tc = getTestTextClassifier(invalidLocale, listOf(TextClassifier.TYPE_ADDRESS))
        val address = "this text doesn't actually matter, but meet me at 6353 Juan Tabo, Apt. 6"
        addMatcherTestResult(expected = false, address, textClassifier = tc)
        tc = getTestTextClassifier(invalidLocale, listOf(TextClassifier.TYPE_FLIGHT_NUMBER))
        val flight = "your flight number is UA1234"
        addMatcherTestResult(expected = false, flight, textClassifier = tc)
    }

    @Test
    fun testContainsOtpCode_languageSpecificOverridesFalsePositivesExceptDate() {
        // TC will detect an address, but the language-specific regex will be preferred
        val tc = getTestTextClassifier(localeWithRegex, listOf(TextClassifier.TYPE_ADDRESS))
        val date = "1-1-01"
        // Dates should still be checked
        addMatcherTestResult(expected = false, date, textClassifier = tc)
        // A string with a code with three lowercase letters, and an excluded year
        val withOtherFalsePositives = "your login code is abd4f 1985"
        // Other false positive regular expressions should not be checked
        addMatcherTestResult(expected = true, withOtherFalsePositives, textClassifier = tc)
    }

    private fun createNotification(
        text: String? = "",
        title: String? = "",
        subtext: String? = "",
        category: String? = "",
        style: Notification.Style? = null,
        publicVersion: Notification? = null
    ): Notification {
        val intent = Intent(Intent.ACTION_MAIN)
        intent.setFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    or Intent.FLAG_ACTIVITY_CLEAR_TOP
        )
        intent.setAction(Intent.ACTION_MAIN)
        intent.setPackage(context.packageName)

        val nb = Notification.Builder(context, "")
        nb.setContentText(text)
        nb.setContentTitle(title)
        nb.setSubText(subtext)
        nb.setCategory(category)
        nb.setContentIntent(createTestPendingIntent())
        if (style != null) {
            nb.setStyle(style)
        }
        if (publicVersion != null) {
            nb.setPublicVersion(publicVersion)
        }
        return nb.build()
    }

    private fun addMatcherTestResult(
        expected: Boolean,
        text: String,
        checkForFalsePositives: Boolean = true,
        textClassifier: TextClassifier? = null,
        customFailureMessage: String? = null
    ) {
        val failureMessage = if (customFailureMessage != null) {
            "$text $customFailureMessage"
        } else if (expected) {
            "$text should match"
        } else {
            "$text should not match"
        }
        addResult(expected = expected, NotificationOtpDetectionHelper.containsOtp(
            createNotification(text), checkForFalsePositives, textClassifier), failureMessage)
    }

    private fun createTestPendingIntent(): PendingIntent {
        val intent = Intent(Intent.ACTION_MAIN)
        intent.setFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    or Intent.FLAG_ACTIVITY_CLEAR_TOP
        )
        intent.setAction(Intent.ACTION_MAIN)
        intent.setPackage(context.packageName)

        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_MUTABLE)
    }

    // Creates a mock TextClassifier that will report back that text provided to it matches the
    // given language codes (for language requests) and textClassifier entities (for links request)
    private fun getTestTextClassifier(
        locale: ULocale?,
        tcEntities: List<String>? = null
    ): TextClassifier {
        val tc = Mockito.mock(TextClassifier::class.java)
        if (locale != null) {
            Mockito.doReturn(
                TextLanguage.Builder().putLocale(locale, 0.9f).build()
            ).`when`(tc).detectLanguage(any(TextLanguage.Request::class.java))
        }

        val entityMap = mutableMapOf<String, Float>()
        // to build the TextLinks, the entity map must have at least one item
        entityMap[TextClassifier.TYPE_URL] = 0.01f
        for (entity in tcEntities ?: emptyList()) {
            entityMap[entity] = 0.9f
        }
        Mockito.doReturn(
            TextLinks.Builder("").addLink(0, 1, entityMap)
                .build()
        ).`when`(tc).generateLinks(any(TextLinks.Request::class.java))
        return tc
    }
}
