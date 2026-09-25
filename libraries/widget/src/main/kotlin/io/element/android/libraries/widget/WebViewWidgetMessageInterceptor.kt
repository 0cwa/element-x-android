/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.widget

import android.graphics.Bitmap
import android.net.http.SslError
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.net.toUri
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.flow.MutableSharedFlow
import org.json.JSONObject
import timber.log.Timber

/**
 * Bridges widget API postMessage traffic between a WebView and the Matrix widget driver.
 *
 * [widgetOrigin] should be supplied for arbitrary third-party widgets so messages are accepted from,
 * and sent to, only that origin. A null value preserves the existing Element Call wildcard behavior.
 *
 * Third-party widget message payloads are never written to the JavaScript console. Responses can contain
 * short-lived credentials such as Matrix OpenID tokens, so even debug builds must keep those payloads out of logs.
 */
class WebViewWidgetMessageInterceptor(
    private val webView: WebView,
    private val onUrlLoaded: (String) -> Unit,
    private val onError: (String?) -> Unit,
    private val widgetOrigin: String? = null,
) : WidgetMessageInterceptor {
    companion object {
        // We call both the WebMessageListener and the JavascriptInterface objects in JS with this
        // 'listenerName' so they can both receive the data from the WebView when
        // `${LISTENER_NAME}.postMessage(...)` is called
        const val LISTENER_NAME = "elementX"
    }

    // It's important to have extra capacity here to make sure we don't drop any messages
    override val interceptedMessages = MutableSharedFlow<String>(extraBufferCapacity = 10)

    val isMessageChannelAvailable: Boolean

    init {
        val assetLoader = if (widgetOrigin == null) {
            WebViewAssetLoader.Builder()
                .addPathHandler("/", WebViewAssetLoader.AssetsPathHandler(webView.context))
                .build()
        } else {
            null
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)

                // Due to https://github.com/element-hq/element-x-android/issues/4097
                // we need to supply a logging implementation that correctly includes
                // objects in log lines.
                view.evaluateJavascript(
                    """
                        // Removing any parts that result in a circular structure. Circular structures crash JSON.stringify.
                        function safeStringify(object) {
                          const simpleObject = {};
                          for (const prop in object) {
                              if (!object.hasOwnProperty(prop)) {
                                  continue;
                              }
                              if (typeof(object[prop]) == 'object') {
                                  continue;
                              }
                              if (typeof(object[prop]) == 'function') {
                                  continue;
                              }
                              simpleObject[prop] = object[prop];
                          }
                          try {
                            return JSON.stringify(simpleObject);
                          } catch {
                            return "{Failed to stringify object}";
                          }
                        }

                        function logFn(consoleLogFn, ...args) {
                            consoleLogFn(
                                args.map(
                                    a => typeof a === "string" ? a : safeStringify(a)
                                ).join(' ')
                            );
                        };
                        globalThis.console.debug = logFn.bind(null, console.debug);
                        globalThis.console.log = logFn.bind(null, console.log);
                        globalThis.console.info = logFn.bind(null, console.info);
                        globalThis.console.warn = logFn.bind(null, console.warn);
                        globalThis.console.error = logFn.bind(null, console.error);
                    """.trimIndent(),
                    null
                )

                // We inject this JS code when the page starts loading to attach a message listener to the window.
                // This listener will receive both messages:
                // - Widget API -> Element X (message.data.api == "fromWidget")
                // - Element X -> Widget API (message.data.api == "toWidget"), we should ignore these
                val originGuard = widgetOrigin
                    ?.let { "if (event.origin !== ${JSONObject.quote(it)}) return;" }
                    .orEmpty()
                view.evaluateJavascript(
                    """
                        window.addEventListener('message', function(event) {
                            $originGuard
                            let message = {data: event.data, origin: event.origin}
                            if (message.data.response && message.data.api == "toWidget"
                                || !message.data.response && message.data.api == "fromWidget") {
                                let json = JSON.stringify(event.data) 
                                ${"console.log('message sent: ' + json);".takeIf {
                                    BuildConfig.DEBUG && widgetOrigin == null
                                }}
                                $LISTENER_NAME.postMessage(json);
                            } else {
                                ${"console.log('message received (ignored): ' + JSON.stringify(event.data));".takeIf {
                                    BuildConfig.DEBUG && widgetOrigin == null
                                }}
                            }
                        });
                    """.trimIndent(),
                    null
                )
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (widgetOrigin != null && request.isForMainFrame &&
                    !isAllowedWidgetNavigation(request.url.toString(), widgetOrigin)
                ) {
                    Timber.w("Blocked widget navigation outside expected origin")
                    onError("Blocked navigation outside the widget origin")
                    return true
                }
                return false
            }

            @Suppress("OVERRIDE_DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                if (widgetOrigin != null && !isAllowedWidgetNavigation(url, widgetOrigin)) {
                    Timber.w("Blocked widget navigation outside expected origin")
                    onError("Blocked navigation outside the widget origin")
                    return true
                }
                return false
            }

            override fun onPageFinished(view: WebView, url: String) {
                onUrlLoaded(url)
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                // No network for instance, transmit the error
                Timber.e("onReceivedError error: ${error?.errorCode} ${error?.description}")

                // Only propagate the error if it happens while loading the current page
                if (view?.url == request?.url.toString()) {
                    onError(error?.description.toString())
                }

                super.onReceivedError(view, request, error)
            }

            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                Timber.e("onReceivedHttpError error: ${errorResponse?.statusCode} ${errorResponse?.reasonPhrase}")

                // Only propagate the error if it happens while loading the current page
                if (view?.url == request?.url.toString()) {
                    onError(errorResponse?.statusCode.toString())
                }

                super.onReceivedHttpError(view, request, errorResponse)
            }

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                Timber.e("onReceivedSslError error: ${error?.primaryError}")

                // Only propagate the error if it happens while loading the current page
                if (view?.url == error?.url.toString()) {
                    onError(error?.toString())
                }

                super.onReceivedSslError(view, handler, error)
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse? {
                return assetLoader?.shouldInterceptRequest(request.url)
            }

            @Suppress("OVERRIDE_DEPRECATION")
            override fun shouldInterceptRequest(view: WebView?, url: String): WebResourceResponse? {
                return assetLoader?.shouldInterceptRequest(url.toUri())
            }
        }

        // The JavascriptInterface fallback has no origin information, so it is only safe for the
        // trusted Element Call flow. Generic third-party widgets rely on the origin-aware
        // WebMessageListener below.
        if (widgetOrigin == null) {
            webView.addJavascriptInterface(object {
                @JavascriptInterface
                fun postMessage(json: String?) {
                    onMessageReceived(json)
                }
            }, LISTENER_NAME)
        }

        // Additionally register WebMessageListener on WebViews that reliably support it.
        // Huawei WebView (Chromium < 119) reports WEB_MESSAGE_LISTENER as supported
        // but silently drops messages, so we only trust it on Chromium 119+.
        // See: https://github.com/element-hq/element-x-android/issues/6632
        val webViewVersionName = WebViewCompat.getCurrentWebViewPackage(webView.context)?.versionName.orEmpty()
        Timber.d("Using WebView version: $webViewVersionName")
        val webViewVersionCode = webViewVersionName.split(".").firstOrNull()?.toIntOrNull() ?: 0

        val supportsOriginAwareMessaging = webViewVersionCode >= 119 &&
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)

        isMessageChannelAvailable = widgetOrigin == null || supportsOriginAwareMessaging
        if (!isMessageChannelAvailable) {
            onError("This WebView version does not support secure widget messaging")
        }

        if (supportsOriginAwareMessaging) {
            WebViewCompat.addWebMessageListener(
                webView,
                LISTENER_NAME,
                setOf(widgetOrigin ?: "*"),
                WebViewCompat.WebMessageListener { _, message, _, _, _ ->
                    onMessageReceived(message.data)
                }
            )
        }
    }

    override fun sendMessage(message: String) {
        val targetOrigin = widgetOrigin?.let(JSONObject::quote) ?: "'*'"
        webView.evaluateJavascript("postMessage($message, $targetOrigin)", null)
    }

    private fun onMessageReceived(json: String?) {
        // Here is where we would handle the messages from the WebView, passing them to the Rust SDK
        json?.let { interceptedMessages.tryEmit(it) }
    }
}
