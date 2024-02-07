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
import androidx.test.platform.app.InstrumentationRegistry
import com.android.modules.utils.build.SdkLevel
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class NotificationOtpDetectionHelperTest {
    val context = InstrumentationRegistry.getInstrumentation().targetContext!!

    @Before
    fun enableFlag() {
        assumeTrue(SdkLevel.isAtLeastV())
    }

    @Test
    fun testGetTextForDetection_textFieldsIncluded() {
        val text = "text"
        val title = "title"
        val subtext = "subtext"
        val sensitive = NotificationOtpDetectionHelper.getTextForDetection(
            createNotification(text = text, title = title, subtext = subtext))
        assertWithMessage("expected sensitive text to contain $text").that(sensitive).contains(text)
        assertWithMessage("expected sensitive text to contain $title").that(sensitive)
                .contains(title)
        assertWithMessage("expected sensitive text to contain $subtext").that(sensitive)
                .contains(subtext)
    }

    @Test
    fun testGetTextForDetection_nullTextFields() {
        val text = "text"
        val title = "title"
        val subtext = "subtext"
        var sensitive = NotificationOtpDetectionHelper.getTextForDetection(
            createNotification(text = text, title = null, subtext = null))
        assertWithMessage("expected sensitive text to contain $text").that(sensitive).contains(text)
        assertWithMessage("expected sensitive text not to contain $title").that(sensitive)
                .doesNotContain(title)
        assertWithMessage("expected sensitive text not to contain $subtext").that(sensitive)
                .doesNotContain(subtext)
        sensitive = NotificationOtpDetectionHelper.getTextForDetection(
            createNotification(text = null, title = null, subtext = null))
        assertWithMessage("expected to get a nonnull string").that(sensitive).isNotNull()
        val nullExtras = createNotification(text = null, title = null, subtext = null).apply {
            this.extras = null
        }
       sensitive = NotificationOtpDetectionHelper.getTextForDetection(nullExtras)
        assertWithMessage("expected to get a nonnull string").that(sensitive).isNotNull()
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
        assertWithMessage("expected sensitive text to contain $messageText1").that(sensitive)
                .contains(messageText1)
        assertWithMessage("expected sensitive text to contain $messageText2").that(sensitive)
                .contains(messageText2)
        assertWithMessage("expected sensitive text to contain $messageText3").that(sensitive)
                .contains(messageText3)

        // MessagingStyle notifications get their main text set automatically to their first
        // message, so we should skip to the end of that to find the message text
        val notifText = notif.extras.getCharSequence(EXTRA_TEXT)?.toString() ?: ""
        val messagesSensitiveStartIdx = sensitive.indexOf(notifText) + notifText.length
        val sensitiveSub = sensitive.substring(messagesSensitiveStartIdx)
        val text1Position = sensitiveSub.indexOf(messageText1)
        val text2Position = sensitiveSub.indexOf(messageText2)
        val text3Position = sensitiveSub.indexOf(messageText3)
        // The messages should be sorted by timestamp, newest first, so 2 -> 3 -> 1
        assertWithMessage("expected the newest message (2) to be first in \"$sensitiveSub\"")
                .that(text2Position).isLessThan(text1Position)
        assertWithMessage("expected the newest message (2) to be first in \"$sensitiveSub\"")
                .that(text2Position).isLessThan(text3Position)
        assertWithMessage("expected the middle message (3) to be center in \"$sensitiveSub\"")
                .that(text3Position).isLessThan(text1Position)
    }

    @Test
    fun testGetTextForDetection_textLinesIncluded() {
        val style = Notification.InboxStyle()
        val extraLine = "extra line"
        style.addLine(extraLine)
        val sensitive = NotificationOtpDetectionHelper
                .getTextForDetection(createNotification(style = style))
        assertWithMessage("expected sensitive text to contain $extraLine").that(sensitive)
                .contains(extraLine)
    }

    @Test
    fun testGetTextForDetection_maxLen() {
        val text = "0123456789".repeat(70) // 700 chars
        val sensitive =
            NotificationOtpDetectionHelper.getTextForDetection(createNotification(text = text))
        assertWithMessage("Expected to be 600 chars or fewer").that(sensitive.length).isAtMost(600)
    }

    @Test
    fun testShouldCheckForOtp_styles() {
        val style = Notification.InboxStyle()
        var shouldCheck = NotificationOtpDetectionHelper
                .shouldCheckForOtp(createNotification(style = style))
        assertWithMessage("InboxStyle should be checked").that(shouldCheck).isTrue()
        val empty = Person.Builder().setName("test").build()
        val style2 = Notification.MessagingStyle(empty)
        val style3 = Notification.BigPictureStyle()
        shouldCheck = NotificationOtpDetectionHelper
                .shouldCheckForOtp(createNotification(style = style2))
        assertWithMessage("MessagingStyle should be checked").that(shouldCheck).isTrue()
        shouldCheck = NotificationOtpDetectionHelper
                .shouldCheckForOtp(createNotification())
        assertWithMessage("No style should not be checked").that(shouldCheck).isFalse()
        shouldCheck = NotificationOtpDetectionHelper
                .shouldCheckForOtp(createNotification(style = style3))
        assertWithMessage("Valid non-messaging non-inbox style should not be checked")
                .that(shouldCheck).isFalse()
    }
    @Test
    fun testShouldCheckForOtp_categories() {
        var shouldCheck = NotificationOtpDetectionHelper
                .shouldCheckForOtp(createNotification(category = CATEGORY_MESSAGE))
        assertWithMessage("$CATEGORY_MESSAGE should be checked").that(shouldCheck).isTrue()
        shouldCheck = NotificationOtpDetectionHelper
                .shouldCheckForOtp(createNotification(category = CATEGORY_SOCIAL))
        assertWithMessage("$CATEGORY_SOCIAL should be checked").that(shouldCheck).isTrue()
        shouldCheck = NotificationOtpDetectionHelper
                .shouldCheckForOtp(createNotification(category = CATEGORY_EMAIL))
        assertWithMessage("$CATEGORY_EMAIL should be checked").that(shouldCheck).isTrue()
        shouldCheck = NotificationOtpDetectionHelper
                .shouldCheckForOtp(createNotification(category = ""))
        assertWithMessage("Empty string category should not be checked").that(shouldCheck).isFalse()
    }

    @Test
    fun testShouldCheckForOtp_publicVersion() {
        var publicVersion = createNotification(category = CATEGORY_MESSAGE)
        var shouldCheck = NotificationOtpDetectionHelper
                .shouldCheckForOtp(createNotification(publicVersion = publicVersion))
        assertWithMessage("notifications with a checked category in their public version should " +
                "be checked").that(shouldCheck).isTrue()
        publicVersion = createNotification(style = Notification.InboxStyle())
        shouldCheck = NotificationOtpDetectionHelper
            .shouldCheckForOtp(createNotification(publicVersion = publicVersion))
        assertWithMessage("notifications with a checked style in their public version should " +
                "be checked").that(shouldCheck).isTrue()
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

    private fun createTestPendingIntent(): PendingIntent {
        val intent = Intent(Intent.ACTION_MAIN)
        intent.setFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    or Intent.FLAG_ACTIVITY_CLEAR_TOP
        )
        intent.setAction(Intent.ACTION_MAIN)
        intent.setPackage(context.getPackageName())

        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_MUTABLE)
    }

    companion object {
        private const val OTP_MESSAGE_BASIC = "your one time code is 123645"
    }
}