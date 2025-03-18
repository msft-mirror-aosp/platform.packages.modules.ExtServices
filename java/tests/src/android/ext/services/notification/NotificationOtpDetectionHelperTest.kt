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
import android.view.textclassifier.TextClassifier
import android.view.textclassifier.TextLanguage
import android.view.textclassifier.TextLinks
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.modules.utils.build.SdkLevel
import com.google.common.collect.ImmutableMap
import com.google.common.truth.Truth.assertWithMessage
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.stubbing.Stubber
import org.testng.Assert

@RunWith(AndroidJUnit4::class)
class NotificationOtpDetectionHelperTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun <T> Stubber.whenKt(mock: T): T = `when`(mock)

    private data class TestResult(
        val expected: Boolean,
        val actual: Boolean,
        val failureMessage: String
    )

    private val results = mutableListOf<TestResult>()

    @Before
    fun enableFlag() {
        assumeTrue(SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM)
        Assistant.sUseTcForOtpDetection = false
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

    @After
    fun reset() {
        Assistant.sUseTcForOtpDetection = Assistant.useTcForOtpDetection()
    }

    private fun addResult(expected: Boolean, actual: Boolean, failureMessage: String) {
        results.add(TestResult(expected, actual, failureMessage))
    }

    @Test
    fun testContainsOtp_otpDetectedUsingTc() {
        assumeTrue(SdkLevel.isAtLeastB())
        Assistant.sUseTcForOtpDetection = true
        val notification = createNotification("Your otp code is 123456")
        val mockTc: TextClassifier = mock(TextClassifier::class.java)
        doReturn(TextLinks.Builder("")
            .addLink(0, 0, ImmutableMap.of("otp", 1f))
            .build()).whenKt(mockTc).generateLinks(any())
        val actual = NotificationOtpDetectionHelper.containsOtp(notification, true, mockTc)
        verify(mockTc, times(1)).generateLinks(any())
        Assert.assertEquals(actual, true);
    }

    @Test
    fun testContainsOtp_otpDetectedUsingLocalImpl() {
        Assistant.sUseTcForOtpDetection = false
        val notification = createNotification("Your otp code is 123456")
        val mockTc: TextClassifier = mock(TextClassifier::class.java)
        doReturn(TextLinks.Builder("")
            .addLink(0, 0, ImmutableMap.of("otp", 1f))
            .build()).whenKt(mockTc).generateLinks(any())
        doReturn(TextLanguage.Builder().putLocale(ULocale.ENGLISH, 0.9f).build())
            .whenKt(mockTc).detectLanguage(any())
        val actual = NotificationOtpDetectionHelper.containsOtp(notification, true, mockTc)
        verify(mockTc, never()).generateLinks(any())
        Assert.assertEquals(actual, true);
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
