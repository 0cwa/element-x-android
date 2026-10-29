/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.widget

import android.content.pm.PackageInfo
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ApplicationProvider
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.common.truth.Truth.assertThat
import io.element.android.tests.testutils.robolectric.RobolectricTest
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.After
import org.junit.Before
import org.junit.Test

class WebViewWidgetMessageInterceptorTest : RobolectricTest() {
    @Before
    fun setUp() {
        mockkStatic(WebViewCompat::class, WebViewFeature::class)
        every { WebViewCompat.getCurrentWebViewPackage(any()) } returns PackageInfo().apply {
            versionName = "119.0.0"
        }
        every { WebViewFeature.isFeatureSupported(any()) } returns true
        every { WebViewCompat.addWebMessageListener(any(), any(), any(), any()) } just Runs
        every { WebViewCompat.addDocumentStartJavaScript(any(), any(), any()) } returns mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        unmockkStatic(WebViewCompat::class, WebViewFeature::class)
    }

    @Test
    fun `generic bridge is installed before loading any page and after its native listener`() {
        val webView = createWebView()
        val interceptor = WebViewWidgetMessageInterceptor(webView, {}, {}, WIDGET_ORIGIN)

        assertThat(interceptor.isMessageChannelAvailable).isTrue()
        verifyOrder {
            WebViewCompat.addWebMessageListener(webView, WebViewWidgetMessageInterceptor.LISTENER_NAME, setOf(WIDGET_ORIGIN), any())
            WebViewCompat.addDocumentStartJavaScript(webView, widgetMessageBridgeScript(WIDGET_ORIGIN), setOf(WIDGET_ORIGIN))
        }
        verify(exactly = 0) { webView.addJavascriptInterface(any(), any()) }
    }

    @Test
    fun `generic page callbacks do not inject duplicate asynchronous message listeners`() {
        val webView = createWebView()
        val client = slot<WebViewClient>()
        val loadedUrls = mutableListOf<String>()
        every { webView.webViewClient = capture(client) } just Runs
        WebViewWidgetMessageInterceptor(webView, { loadedUrls += it }, {}, WIDGET_ORIGIN)

        repeat(2) {
            client.captured.onPageStarted(webView, WIDGET_ORIGIN, null)
            client.captured.onPageFinished(webView, WIDGET_ORIGIN)
        }

        assertThat(loadedUrls).containsExactly(WIDGET_ORIGIN, WIDGET_ORIGIN)
        verify(exactly = 0) { webView.evaluateJavascript(any(), any()) }
        verify(exactly = 1) { WebViewCompat.addDocumentStartJavaScript(webView, any(), setOf(WIDGET_ORIGIN)) }
    }

    @Test
    fun `generic widgets fail closed without document start support`() {
        every { WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) } returns false
        val webView = createWebView()
        val errors = mutableListOf<String?>()
        val interceptor = WebViewWidgetMessageInterceptor(webView, {}, { errors += it }, WIDGET_ORIGIN)

        assertThat(interceptor.isMessageChannelAvailable).isFalse()
        assertThat(errors).hasSize(1)
        verify(exactly = 0) { WebViewCompat.addDocumentStartJavaScript(any(), any(), any()) }
        verify(exactly = 0) { webView.addJavascriptInterface(any(), any()) }
    }

    @Test
    fun `trusted Call retains its legacy bridge without document start support`() {
        every { WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) } returns false
        val webView = createWebView()
        val client = slot<WebViewClient>()
        every { webView.webViewClient = capture(client) } just Runs
        val interceptor = WebViewWidgetMessageInterceptor(webView, {}, {})

        assertThat(interceptor.isMessageChannelAvailable).isTrue()
        client.captured.onPageStarted(webView, WIDGET_ORIGIN, null)

        verify(exactly = 1) { webView.addJavascriptInterface(any(), WebViewWidgetMessageInterceptor.LISTENER_NAME) }
        verify(exactly = 2) { webView.evaluateJavascript(any(), any()) }
        verify(exactly = 0) { WebViewCompat.addDocumentStartJavaScript(any(), any(), any()) }
    }

    private fun createWebView(): WebView = mockk(relaxed = true) {
        every { context } returns ApplicationProvider.getApplicationContext()
    }

    private companion object {
        const val WIDGET_ORIGIN = "https://widget.example"
    }
}
