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
import android.content.Intent
import android.icu.util.ULocale
import androidx.test.platform.app.InstrumentationRegistry
import com.android.modules.utils.build.SdkLevel
import android.platform.test.flag.junit.SetFlagsRule
import android.service.notification.Flags.FLAG_REDACT_SENSITIVE_NOTIFICATIONS_FROM_UNTRUSTED_LISTENERS
import android.view.textclassifier.TextClassifier
import android.view.textclassifier.TextLanguage
import android.view.textclassifier.TextLinks
import com.google.common.truth.Truth.assertWithMessage
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito

@RunWith(JUnit4::class)
class NotificationOtpDetectionHelperTest {
    val context = InstrumentationRegistry.getInstrumentation().targetContext!!
    val localeWithRegex = ULocale.ENGLISH
    val invalidLocale = ULocale.ROOT

    @get:Rule
    val setFlagsRule = if (SdkLevel.isAtLeastV()) {
        SetFlagsRule()
    } else {
        // On < V, have a test rule that does nothing
        TestRule { statement, _ -> statement}
    }

    private data class TestResult(
        val expected: Boolean,
        val actual: Boolean,
        val failureMessage: String
    )

    private val results = mutableListOf<TestResult>()

    @Before
    fun enableFlag() {
        assumeTrue(SdkLevel.isAtLeastV())
        (setFlagsRule as SetFlagsRule).enableFlags(
            FLAG_REDACT_SENSITIVE_NOTIFICATIONS_FROM_UNTRUSTED_LISTENERS)
        results.clear()
    }

    @After
    fun verifyResults() {
        val allFailuresMessage = StringBuilder("")
        var numFailures = 0;
        results.forEach { (expected, actual, failureMessage) ->
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
    fun testGetTextForDetection_emptyIfFlagDisabled() {
        (setFlagsRule as SetFlagsRule)
            .disableFlags(FLAG_REDACT_SENSITIVE_NOTIFICATIONS_FROM_UNTRUSTED_LISTENERS)
        val text = "text"
        val title = "title"
        val subtext = "subtext"
        val sensitive = NotificationOtpDetectionHelper.getTextForDetection(
            createNotification(text = text, title = title, subtext = subtext))
        assertWithMessage("expected sensitive text to be empty").that(sensitive).isEmpty()
    }


    @Test
    fun testGetTextForDetection_textFieldsIncluded() {
        val text = "text"
        val title = "title"
        val subtext = "subtext"
        val sensitive = NotificationOtpDetectionHelper.getTextForDetection(
            createNotification(text = text, title = title, subtext = subtext))
        addResult(true, sensitive.contains(text),"expected sensitive text to contain $text")
        addResult(true, sensitive.contains(title), "expected sensitive text to contain $title")
        addResult(true, sensitive.contains(subtext), "expected sensitive text to contain $subtext")
    }

    @Test
    fun testGetTextForDetection_nullTextFields() {
        val text = "text"
        val title = "title"
        val subtext = "subtext"
        var sensitive = NotificationOtpDetectionHelper.getTextForDetection(
            createNotification(text = text, title = null, subtext = null))
        addResult(true, sensitive.contains(text), "expected sensitive text to contain $text")
        addResult(false, sensitive.contains(title), "expected sensitive text not to contain $title")
        addResult(false, sensitive.contains("subtext"), "expected sensitive text not to contain $subtext")
        sensitive = NotificationOtpDetectionHelper.getTextForDetection(
            createNotification(text = null, title = null, subtext = null))
        addResult(true, sensitive != null, "expected to get a nonnull string")
        val nullExtras = createNotification(text = null, title = null, subtext = null).apply {
            this.extras = null
        }
       sensitive = NotificationOtpDetectionHelper.getTextForDetection(nullExtras)
        addResult(true, sensitive != null, "expected to get a nonnull string")
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
        addResult(true, sensitive.contains(messageText1), "expected sensitive text to contain $messageText1")
        addResult(true, sensitive.contains(messageText2), "expected sensitive text to contain $messageText2")
        addResult(true, sensitive.contains(messageText3), "expected sensitive text to contain $messageText3")

        // MessagingStyle notifications get their main text set automatically to their first
        // message, so we should skip to the end of that to find the message text
        val notifText = notif.extras.getCharSequence(EXTRA_TEXT)?.toString() ?: ""
        val messagesSensitiveStartIdx = sensitive.indexOf(notifText) + notifText.length
        val sensitiveSub = sensitive.substring(messagesSensitiveStartIdx)
        val text1Position = sensitiveSub.indexOf(messageText1)
        val text2Position = sensitiveSub.indexOf(messageText2)
        val text3Position = sensitiveSub.indexOf(messageText3)
        // The messages should be sorted by timestamp, newest first, so 2 -> 3 -> 1
        addResult(true, text2Position < text1Position, "expected the newest message (2) to be first in \"$sensitiveSub\"")
        addResult(true, text2Position < text3Position, "expected the newest message (2) to be first in \"$sensitiveSub\"")
        addResult(true, text3Position < text1Position, "expected the middle message (3) to be center in \"$sensitiveSub\"")
    }

    @Test
    fun testGetTextForDetection_textLinesIncluded() {
        val style = Notification.InboxStyle()
        val extraLine = "extra line"
        style.addLine(extraLine)
        val sensitive = NotificationOtpDetectionHelper
                .getTextForDetection(createNotification(style = style))
        addResult(true, sensitive.contains(extraLine), "expected sensitive text to contain $extraLine")
    }

    @Test
    fun testGetTextForDetection_maxLen() {
        val text = "0123456789".repeat(70) // 700 chars
        val sensitive =
            NotificationOtpDetectionHelper.getTextForDetection(createNotification(text = text))
        addResult(true, sensitive.length <= 600, "Expected to be 600 chars or fewer")
    }

    @Test
    fun testShouldCheckForOtp_falseIfFlagDisabled() {
        (setFlagsRule as SetFlagsRule)
            .disableFlags(FLAG_REDACT_SENSITIVE_NOTIFICATIONS_FROM_UNTRUSTED_LISTENERS)
        val shouldCheck = NotificationOtpDetectionHelper
            .shouldCheckForOtp(createNotification(category = CATEGORY_MESSAGE))
        addResult(false, shouldCheck, "$CATEGORY_MESSAGE should not be checked")
    }


    @Test
    fun testShouldCheckForOtp_styles() {
        val style = Notification.InboxStyle()
        var shouldCheck = NotificationOtpDetectionHelper
                .shouldCheckForOtp(createNotification(style = style))
        addResult(true, shouldCheck, "InboxStyle should be checked")
        val empty = Person.Builder().setName("test").build()
        val style2 = Notification.MessagingStyle(empty)
        val style3 = Notification.BigPictureStyle()
        shouldCheck = NotificationOtpDetectionHelper
                .shouldCheckForOtp(createNotification(style = style2))
        addResult(true, shouldCheck, "MessagingStyle should be checked")
        shouldCheck = NotificationOtpDetectionHelper
                .shouldCheckForOtp(createNotification())
        addResult(false, shouldCheck, "No style should not be checked")
        shouldCheck = NotificationOtpDetectionHelper
                .shouldCheckForOtp(createNotification(style = style3))
        addResult(false, shouldCheck, "Valid non-messaging non-inbox style should not be checked")
    }

    @Test
    fun testShouldCheckForOtp_categories() {
        var shouldCheck = NotificationOtpDetectionHelper
                .shouldCheckForOtp(createNotification(category = CATEGORY_MESSAGE))
        addResult(true, shouldCheck, "$CATEGORY_MESSAGE should be checked")
        shouldCheck = NotificationOtpDetectionHelper
            .shouldCheckForOtp(createNotification(category = CATEGORY_SOCIAL))
        addResult(true, shouldCheck, "$CATEGORY_SOCIAL should be checked")
        shouldCheck = NotificationOtpDetectionHelper
            .shouldCheckForOtp(createNotification(category = CATEGORY_EMAIL))
        addResult(true, shouldCheck, "$CATEGORY_EMAIL should be checked")
        shouldCheck = NotificationOtpDetectionHelper
            .shouldCheckForOtp(createNotification(category = ""))
        addResult(false, shouldCheck, "Empty string category should not be checked")
    }

    @Test
    fun testShouldCheckForOtp_regex() {
        var shouldCheck = NotificationOtpDetectionHelper
                .shouldCheckForOtp(createNotification(text = "45454", category = ""))
        assertWithMessage("Regex matches should be checked").that(shouldCheck).isTrue()
    }

    @Test
    fun testShouldCheckForOtp_publicVersion() {
        var publicVersion = createNotification(category = CATEGORY_MESSAGE)
        var shouldCheck = NotificationOtpDetectionHelper
                .shouldCheckForOtp(createNotification(publicVersion = publicVersion))

        addResult(true, shouldCheck, "notifications with a checked category in their public version should " +
                "be checked")
        publicVersion = createNotification(style = Notification.InboxStyle())
        shouldCheck = NotificationOtpDetectionHelper
            .shouldCheckForOtp(createNotification(publicVersion = publicVersion))
        addResult(true, shouldCheck, "notifications with a checked style in their public version should " +
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

        addMatcherTestResult(true, minLenAlphaNum)
        addMatcherTestResult(true, minLenNumOnly)
        addMatcherTestResult(true, maxLen)
        addMatcherTestResult(false, tooShortAlphaNum, customFailureMessage = "is too short")
        addMatcherTestResult(false, tooShortNumOnly, customFailureMessage = "is too short")
        addMatcherTestResult(false, tooLong, customFailureMessage = "is too long")
        addMatcherTestResult(true, twoTriplets)
        addMatcherTestResult(false, tooShortTriplets, customFailureMessage = "is too short")
    }

    @Test
    fun testContainsOtp_acceptsNonRomanAlphabeticalChars() {
        val lowercase = "123ķ4"
        val uppercase = "123Ŀ4"
        val ideographicInMiddle = "123码456"
        addMatcherTestResult(true, lowercase)
        addMatcherTestResult(true, uppercase)
        addMatcherTestResult(false, ideographicInMiddle)
    }

    @Test
    fun testContainsOtp_mustHaveNumber() {
        val noNums = "TEFHXES"
        addMatcherTestResult(false, noNums)
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
            true,
            date,
            checkForFalsePositives = false,
            customFailureMessage = "should match if checkForFalsePositives is false"
        )
        addMatcherTestResult(
            false,
            date,
            customFailureMessage = "should not match if checkForFalsePositives is true"
        )
        addMatcherTestResult(false, singleDigitDate)
        addMatcherTestResult(false, twoDigitYear)
        addMatcherTestResult(true, dateWithOtpAfter)
        addMatcherTestResult(true, dateWithOtpBefore)
        addMatcherTestResult(true, otpWithDashesButInvalidDate)
        addMatcherTestResult(true, otpWithDashesButInvalidYear)
    }

    @Test
    fun testContainsOtp_dashes() {
        val oneDash = "G-3d523"
        val manyDashes = "G-FD-745"
        val tooManyDashes = "6--7893"
        val oopsAllDashes = "------"
        addMatcherTestResult(true, oneDash)
        addMatcherTestResult(true, manyDashes)
        addMatcherTestResult(false, tooManyDashes)
        addMatcherTestResult(false, oopsAllDashes)
    }

    @Test
    fun testContainsOtp_startAndEnd() {
        val noSpaceStart = "your code isG-345821"
        val noSpaceEnd = "your code is G-345821for real"
        val colonStart = "your code is:G-345821"
        val parenStart = "your code is (G-345821"
        val newLineStart = "your code is \nG-345821"
        val quoteStart = "your code is 'G-345821"
        val doubleQuoteStart = "your code is \"G-345821"
        val bracketStart = "your code is [G-345821"
        val ideographicStart = "your code is码G-345821"
        val colonStartNumberPreceding = "your code is4:G-345821"
        val periodEnd = "you code is G-345821."
        val parenEnd = "you code is (G-345821)"
        val quoteEnd = "you code is 'G-345821'"
        val ideographicEnd = "your code is码G-345821码"
        addMatcherTestResult(false, noSpaceStart)
        addMatcherTestResult(false, noSpaceEnd)
        addMatcherTestResult(false, colonStartNumberPreceding)
        addMatcherTestResult(true, colonStart)
        addMatcherTestResult(true, parenStart)
        addMatcherTestResult(true, newLineStart)
        addMatcherTestResult(true, quoteStart)
        addMatcherTestResult(true, doubleQuoteStart)
        addMatcherTestResult(true, bracketStart)
        addMatcherTestResult(true, ideographicStart)
        addMatcherTestResult(true, periodEnd)
        addMatcherTestResult(true, parenEnd)
        addMatcherTestResult(true, quoteEnd)
        addMatcherTestResult(true, ideographicEnd)
    }

    @Test
    fun testContainsOtp_lookaheadMustBeOtpChar() {
        val validLookahead = "g4zy75"
        val spaceLookahead = "GVRXY 2"
        addMatcherTestResult(true, validLookahead)
        addMatcherTestResult(false, spaceLookahead)
    }

    @Test
    fun testContainsOtp_threeDontMatch_withoutLanguageSpecificRegex() {
        val tc = getTestTextClassifier(invalidLocale)
        val threeLowercase = "34agb"
        addMatcherTestResult(false, threeLowercase, textClassifier = tc)
    }

    @Test
    fun testContainsOtp_commonYearsDontMatch_withoutLanguageSpecificRegex() {
        val tc = getTestTextClassifier(invalidLocale)
        val twentyXX = "2009"
        val twentyOneXX = "2109"
        val thirtyXX = "3035"
        val nineteenXX = "1945"
        val eighteenXX = "1899"
        addMatcherTestResult(false, twentyXX, textClassifier = tc)
        // Behavior should be the same for an invalid language, and null TextClassifier
        addMatcherTestResult(false, twentyXX, textClassifier = null)
        addMatcherTestResult(true, twentyOneXX, textClassifier = tc)
        addMatcherTestResult(true, thirtyXX, textClassifier = tc)
        addMatcherTestResult(false, nineteenXX, textClassifier = tc)
        addMatcherTestResult(true, eighteenXX, textClassifier = tc)
    }

    @Test
    fun testContainsOtp_engishSpecificRegex() {
        val tc = getTestTextClassifier(ULocale.ENGLISH)
        val englishFalsePositive = "This is a false positive 4543"
        val englishContextWords = listOf("login", "log in", "2fa", "authenticate", "auth",
            "authentication", "tan", "password", "passcode", "two factor", "two-factor", "2factor",
            "2 factor", "pin")
        val englishContextWordsCase = listOf("LOGIN", "logIn", "LoGiN")
        // Strings with a context word somewhere in the substring
        val englishContextSubstrings = listOf("pins", "gaping", "backspin")

        addMatcherTestResult(false, englishFalsePositive, textClassifier = tc)
        for (context in englishContextWords) {
            val englishTruePositive = "$englishFalsePositive $context"
            addMatcherTestResult(true, englishTruePositive, textClassifier = tc)
        }
        for (context in englishContextWordsCase) {
            val englishTruePositive = "$englishFalsePositive $context"
            addMatcherTestResult(true, englishTruePositive, textClassifier = tc)
        }
        for (falseContext in englishContextSubstrings) {
            val anotherFalsePositive = "$englishFalsePositive $falseContext"
            addMatcherTestResult(false, anotherFalsePositive, textClassifier = tc)
        }
    }

    @Test
    fun testContainsOtpCode_usesTcForFalsePositivesIfNoLanguageSpecificRegex() {
        var tc = getTestTextClassifier(invalidLocale, listOf(TextClassifier.TYPE_ADDRESS))
        val address = "this text doesn't actually matter, but meet me at 6353 Juan Tabo, Apt. 6"
        addMatcherTestResult(false, address, textClassifier = tc)
        tc = getTestTextClassifier(invalidLocale, listOf(TextClassifier.TYPE_FLIGHT_NUMBER))
        val flight = "your flight number is UA1234"
        addMatcherTestResult(false, flight, textClassifier = tc)
    }

    @Test
    fun testContainsOtpCode_languageSpecificOverridesFalsePositivesExceptDate() {
        // TC will detect an address, but the language-specific regex will be preferred
        val tc = getTestTextClassifier(localeWithRegex, listOf(TextClassifier.TYPE_ADDRESS))
        val date = "1-1-01"
        // Dates should still be checked
        addMatcherTestResult(false, date, textClassifier = tc)
        // A string with a code with three lowercase letters, and an excluded year
        val withOtherFalsePositives = "your login code is abd3 1985"
        // Other false positive regular expressions should not be checked
        addMatcherTestResult(true, withOtherFalsePositives, textClassifier = tc)
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
        addResult(expected, NotificationOtpDetectionHelper.containsOtp(
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