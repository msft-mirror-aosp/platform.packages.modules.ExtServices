/**
 * Copyright (C) 2024 The Android Open Source Project
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
import android.app.Notification.CATEGORY_MESSAGE
import android.app.NotificationChannel
import android.app.NotificationManager.IMPORTANCE_DEFAULT
import android.app.PendingIntent
import android.content.Intent
import android.os.Process
import android.service.notification.Adjustment.KEY_SENSITIVE_CONTENT
import android.service.notification.StatusBarNotification
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextClassifier
import android.view.textclassifier.TextClassifier.TYPE_OTP_CODE
import android.view.textclassifier.TextLinks
import androidx.test.platform.app.InstrumentationRegistry
import com.android.modules.utils.build.SdkLevel
import com.android.textclassifier.notification.SmartSuggestions
import com.android.textclassifier.notification.SmartSuggestionsHelper
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.spy
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Stubber

@RunWith(JUnit4::class)
class AssistantTest {
    val context = InstrumentationRegistry.getInstrumentation().targetContext!!
    lateinit var mockSuggestions: SmartSuggestionsHelper
    lateinit var mockTc: TextClassifier
    lateinit var assistant: Assistant
    val EXECUTOR_AWAIT_TIME = 200L

    private fun <T> Stubber.whenKt(mock: T): T = `when`(mock)

    @Before
    fun setUpMocks() {
        assumeTrue(SdkLevel.isAtLeastV())
        assistant = spy(Assistant())
        mockSuggestions = mock(SmartSuggestionsHelper::class.java)
        mockTc = mock(TextClassifier::class.java)
        assistant.mSmartSuggestionsHelper = mockSuggestions
        doReturn(SmartSuggestions(emptyList(), emptyList()))
                .whenKt(mockSuggestions).onNotificationEnqueued(any())
        assistant.mTcm = context.getSystemService(TextClassificationManager::class.java)!!
        assistant.mTcm.setTextClassifier(mockTc)
        doReturn(TextLinks.Builder("").build()).whenKt(mockTc).generateLinks(any())
    }

    @Test
    fun onNotificationEnqueued_callsTextClassifierForOtpAndSuggestions() {
        val sbn = createSbn()
        var entities: List<String> = emptyList()
        doAnswer { invocation: InvocationOnMock ->
            val request = invocation.getArgument<TextLinks.Request>(0)
            assertWithMessage("Expected a non-null entity config")
                .that(request.entityConfig).isNotNull()
            entities = request.entityConfig!!.resolveEntityListModifications(emptyList()).toList()
            return@doAnswer TextLinks.Builder(request.text.toString()).build()
        }.whenKt(mockTc).generateLinks(any())
        assistant.onNotificationEnqueued(sbn, NotificationChannel("0", "", IMPORTANCE_DEFAULT))
        Thread.sleep(EXECUTOR_AWAIT_TIME)
        assertWithMessage("Expected entities to contain otp_code").that(entities).contains(
            TYPE_OTP_CODE)
        verify(mockTc, times(1)).generateLinks(any())
        verify(assistant.mSmartSuggestionsHelper, times(1)).onNotificationEnqueued(eq(sbn))
    }

    @Test
    fun onNotificationEnqueued_usesHelperToGetText() {
        var sensitiveString: String? = null
        doAnswer { invocation: InvocationOnMock ->
            val request = invocation.getArgument<TextLinks.Request>(0)
            sensitiveString = request.text.toString()
            return@doAnswer TextLinks.Builder(request.text.toString())

        }.whenKt(mockTc).generateLinks(any())
        val sbn = createSbn(text = "text", title = "title", subtext = "subtext")
        assistant.onNotificationEnqueued(sbn, NotificationChannel("0", "", IMPORTANCE_DEFAULT))
        Thread.sleep(EXECUTOR_AWAIT_TIME)
        val expectedText = NotificationOtpDetectionHelper.getTextForDetection(sbn.notification)
        assertWithMessage("Expected sensitive text to be $expectedText, but was $sensitiveString")
            .that(sensitiveString).isEqualTo(expectedText)
    }

    @Test
    fun onNotificationEnqueued_checksHelperBeforeClassifying() {
        // Category, Style, Regex all don't match
        var sbn = createSbn(text = "text", title = "title", subtext = "subtext", category = "")
        assistant.onNotificationEnqueued(sbn, NotificationChannel("0", "", IMPORTANCE_DEFAULT))
        Thread.sleep(EXECUTOR_AWAIT_TIME)
        verify(mockTc, times(0)).generateLinks(any())
        // Category matching is checked implicitly in other tests
        // Style matches
        sbn = createSbn(text = "text", title = "title", subtext = "subtext", category = "",
            style = Notification.InboxStyle())
        assistant.onNotificationEnqueued(sbn, NotificationChannel("0", "", IMPORTANCE_DEFAULT))
        Thread.sleep(EXECUTOR_AWAIT_TIME)
        verify(mockTc, times(1)).generateLinks(any())
        // Regex matches
        sbn = createSbn(text = "87THF4", title = "title", subtext = "subtext", category = "")
        assistant.onNotificationEnqueued(sbn, NotificationChannel("0", "", IMPORTANCE_DEFAULT))
        Thread.sleep(EXECUTOR_AWAIT_TIME)
        verify(mockTc, times(2)).generateLinks(any())
    }

    @Test
    fun onNotificationEnqueued_noSensitiveAdjustmentIfConfidenceLow() {
        val sbn = createSbn()
        doReturn(TextLinks.Builder("   ").addLink(0, 1, mapOf(TYPE_OTP_CODE to 0.59f)).build())
            .whenKt(mockTc).generateLinks(any(TextLinks.Request::class.java))
        assistant.onNotificationEnqueued(sbn, NotificationChannel("0", "", IMPORTANCE_DEFAULT))
        Thread.sleep(EXECUTOR_AWAIT_TIME)
        verify(assistant).createEnqueuedNotificationAdjustment(any(StatusBarNotification::class.java), any(), any(), eq(false))
    }

    @Test
    fun onNotificationEnqueued_sensitiveAdjustmentIfConfidenceHigh() {
        val sbn = createSbn()
        doReturn(TextLinks.Builder("   ").addLink(0, 1, mapOf(TYPE_OTP_CODE to 0.7f)).build())
            .whenKt(mockTc).generateLinks(any(TextLinks.Request::class.java))
        assistant.onNotificationEnqueued(sbn, NotificationChannel("0", "", IMPORTANCE_DEFAULT))
        Thread.sleep(EXECUTOR_AWAIT_TIME)
        verify(assistant).createEnqueuedNotificationAdjustment(eq(sbn),
            eq(ArrayList<Notification.Action>()), eq(ArrayList<CharSequence>()), eq(true))
    }

    @Test
    fun createEnqueuedNotificationAdjustment_hasAdjustmentIfContainsOtpCode() {
        val adjustment = assistant.createEnqueuedNotificationAdjustment(
            createSbn(),
            arrayListOf<Notification.Action>(),
            arrayListOf<CharSequence>(),
            true)
        assertThat(adjustment.signals.getBoolean(KEY_SENSITIVE_CONTENT)).isTrue()
        val adjustment2 = assistant.createEnqueuedNotificationAdjustment(
            createSbn(),
            arrayListOf<Notification.Action>(),
            arrayListOf<CharSequence>(),
            false)
        assertThat(adjustment2.signals.containsKey(KEY_SENSITIVE_CONTENT)).isFalse()
    }

    private fun createSbn(
        text: String = "",
        title: String = "",
        subtext: String = "",
        category: String = CATEGORY_MESSAGE,
        style: Notification.Style? = null
    ): StatusBarNotification {
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
        return StatusBarNotification(context.packageName, context.packageName, 0, "",
            Process.myUid(), 0, 0, nb.build(), Process.myUserHandle(), System.currentTimeMillis())
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

}