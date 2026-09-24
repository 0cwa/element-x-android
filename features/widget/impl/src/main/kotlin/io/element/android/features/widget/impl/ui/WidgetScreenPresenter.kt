/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.widget.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import io.element.android.compound.theme.ElementTheme
import io.element.android.features.widget.api.WidgetActivityData
import io.element.android.features.widget.impl.permissions.WidgetPermissionStore
import io.element.android.features.widget.impl.utils.WidgetProvider
import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.architecture.Presenter
import io.element.android.libraries.architecture.runCatchingUpdatingState
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.matrix.api.MatrixClientProvider
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.widget.MatrixWidgetDriver
import io.element.android.libraries.network.useragent.UserAgentProvider
import io.element.android.libraries.widget.WidgetMessage
import io.element.android.libraries.widget.WidgetMessageInterceptor
import io.element.android.libraries.widget.WidgetMessageSerializer
import io.element.android.libraries.widget.isAllowedWidgetNavigation
import io.element.android.libraries.widget.widgetOrigin
import io.element.android.services.appnavstate.api.ActiveRoomsHolder
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import timber.log.Timber
import java.net.URI
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

@AssistedInject
class WidgetScreenPresenter(
    @Assisted private val widgetActivityData: WidgetActivityData,
    @Assisted private val navigator: WidgetScreenNavigator,
    private val widgetProvider: WidgetProvider,
    private val widgetPermissionStore: WidgetPermissionStore,
    private val matrixClientProvider: MatrixClientProvider,
    private val activeRoomsHolder: ActiveRoomsHolder,
    userAgentProvider: UserAgentProvider,
    private val languageTagProvider: LanguageTagProvider,
    private val widgetMessageSerializer: WidgetMessageSerializer,
) : Presenter<WidgetScreenState> {
    @AssistedFactory
    interface Factory {
        fun create(widgetActivityData: WidgetActivityData, navigator: WidgetScreenNavigator): WidgetScreenPresenter
    }

    private val initAfterContentLoad = !widgetActivityData.waitForIframeLoad
    private val userAgent = userAgentProvider.provide()

    @Composable
    override fun present(): WidgetScreenState {
        val coroutineScope = rememberCoroutineScope()
        val urlState = remember { mutableStateOf<AsyncData<String>>(AsyncData.Uninitialized) }
        val widgetDriver = remember { mutableStateOf<MatrixWidgetDriver?>(null) }
        val messageInterceptor = remember { mutableStateOf<WidgetMessageInterceptor?>(null) }
        val driverClosed = remember { AtomicBoolean(false) }

        fun closeDriver(driver: MatrixWidgetDriver?) {
            if (driver != null && driverClosed.compareAndSet(false, true)) {
                driver.close()
            }
        }

        fun closeScreen() {
            closeDriver(widgetDriver.value)
            navigator.close()
        }

        DisposableEffect(Unit) {
            onDispose {
                closeDriver(widgetDriver.value)
            }
        }
        var isWidgetLoaded by rememberSaveable { mutableStateOf(false) }
        var ignoreWebViewError by rememberSaveable { mutableStateOf(false) }
        var webViewError by remember { mutableStateOf<String?>(null) }
        var preloadPermission by remember { mutableStateOf<WidgetPreloadPermission>(WidgetPreloadPermission.Checking) }
        val languageTag = languageTagProvider.provideLanguageTag()
        val theme = if (ElementTheme.isLightTheme) "light" else "dark"
        val widgetOrigin = remember(widgetActivityData.url) { widgetOrigin(widgetActivityData.url) }

        LaunchedEffect(widgetActivityData) {
            if (widgetOrigin == null) {
                webViewError = "Invalid widget URL"
                return@LaunchedEffect
            }
            preloadPermission = resolvePreloadPermission(widgetActivityData)
        }

        LaunchedEffect(preloadPermission, languageTag, theme) {
            val expectedOrigin = widgetOrigin ?: return@LaunchedEffect
            if (preloadPermission == WidgetPreloadPermission.Allowed && urlState.value is AsyncData.Uninitialized) {
                fetchWidgetUrl(
                    inputs = widgetActivityData,
                    urlState = urlState,
                    widgetDriver = widgetDriver,
                    languageTag = languageTag,
                    theme = theme,
                    expectedOrigin = expectedOrigin,
                )
            }
        }

        widgetDriver.value?.let { driver ->
            LaunchedEffect(driver) {
                driver.incomingMessages
                    .onEach {
                        // Relay message to the WebView
                        messageInterceptor.value?.sendMessage(it)
                    }
                    .launchIn(this)

                driver.run()
            }
        }

        messageInterceptor.value?.let { interceptor ->
            LaunchedEffect(interceptor) {
                interceptor.interceptedMessages
                    .onEach {
                        // We are receiving messages from the WebView, consider that the application is loaded
                        ignoreWebViewError = true
                        // Relay message to Widget Driver
                        widgetDriver.value?.send(it)

                        val parsedMessage = parseMessage(it)
                        val loadedIndicatorWidgetAction = if (initAfterContentLoad) {
                            WidgetMessage.Action.ContentLoaded
                        } else {
                            WidgetMessage.Action.SupportedApiVersions
                        }
                        if (parsedMessage?.direction == WidgetMessage.Direction.FromWidget) {
                            if (parsedMessage.action == WidgetMessage.Action.Close) {
                                closeScreen()
                            } else if (parsedMessage.action == loadedIndicatorWidgetAction) {
                                isWidgetLoaded = true
                            }
                        }
                    }
                    .launchIn(this)
            }
        }

        fun handleEvent(event: WidgetScreenEvents) {
            when (event) {
                is WidgetScreenEvents.GrantPreloadPermission -> {
                    coroutineScope.launch {
                        widgetActivityData.eventId?.let { eventId ->
                            widgetPermissionStore.allow(
                                sessionId = widgetActivityData.sessionId,
                                roomId = widgetActivityData.roomId,
                                eventId = eventId,
                            )
                        }
                        preloadPermission = WidgetPreloadPermission.Allowed
                    }
                }
                is WidgetScreenEvents.Close -> {
                    val widgetId = widgetDriver.value?.id
                    val interceptor = messageInterceptor.value
                    if (widgetId != null && interceptor != null && isWidgetLoaded) {
                        // If the widget was loaded, we need to send a close message first.

                        isWidgetLoaded = false

                        sendCloseMessage(widgetId, interceptor)
                        coroutineScope.launch {
                            // Give the widget a short opportunity to acknowledge and clean up.
                            delay(2.seconds)
                            closeScreen()
                        }
                    } else {
                        closeScreen()
                    }
                }
                is WidgetScreenEvents.SetMessageInterceptor -> {
                    messageInterceptor.value = event.interceptor
                }
                is WidgetScreenEvents.OnWebViewError -> {
                    if (!ignoreWebViewError) {
                        webViewError = event.description.orEmpty()
                    }
                    // Else ignore the error, give the widget a chance to recover by itself.
                }
            }
        }

        return WidgetScreenState(
            urlState = urlState.value,
            preloadPermission = preloadPermission,
            widgetOrigin = widgetOrigin,
            webViewError = webViewError,
            userAgent = userAgent,
            isWidgetLoaded = isWidgetLoaded,
            widgetName = widgetActivityData.widgetName,
            eventSink = ::handleEvent,
        )
    }

    private suspend fun resolvePreloadPermission(inputs: WidgetActivityData): WidgetPreloadPermission {
        if (inputs.creatorUserId == inputs.sessionId.value) {
            return WidgetPreloadPermission.Allowed
        }
        if (inputs.eventId?.let { widgetPermissionStore.isAllowed(inputs.sessionId, inputs.roomId, it) } == true) {
            return WidgetPreloadPermission.Allowed
        }

        val matrixClient = matrixClientProvider.getOrRestore(inputs.sessionId).getOrNull()
        val room = activeRoomsHolder.getActiveRoomMatching(inputs.sessionId, inputs.roomId)
            ?: matrixClient?.getJoinedRoom(inputs.roomId)
        val creator = runCatchingExceptions {
            room?.getUpdatedMember(UserId(inputs.creatorUserId))?.getOrNull()
        }.getOrNull()
        val domain = runCatchingExceptions { URI(inputs.url).host }.getOrNull() ?: inputs.url

        return WidgetPreloadPermission.Required(
            creatorUserId = inputs.creatorUserId,
            creatorDisplayName = creator?.displayName?.takeIf { it.isNotBlank() } ?: inputs.creatorUserId,
            creatorAvatarUrl = creator?.avatarUrl,
            widgetDomain = domain,
            isRoomEncrypted = inputs.isRoomEncrypted,
        )
    }

    private suspend fun fetchWidgetUrl(
        inputs: WidgetActivityData,
        urlState: MutableState<AsyncData<String>>,
        widgetDriver: MutableState<MatrixWidgetDriver?>,
        languageTag: String?,
        theme: String?,
        expectedOrigin: String,
    ) {
        urlState.runCatchingUpdatingState {
            val result = widgetProvider.getWidget(
                sessionId = inputs.sessionId,
                roomId = inputs.roomId,
                clientId = UUID.randomUUID().toString(),
                widgetId = inputs.widgetId,
                languageTag = languageTag,
                theme = theme,
                initAfterContentLoad = initAfterContentLoad,
                url = inputs.url,
            ).getOrThrow()

            if (!isAllowedWidgetNavigation(result.url, expectedOrigin)) {
                result.driver.close()
                error("Generated widget URL changed origin")
            }
            widgetDriver.value = result.driver
            Timber.d("Widget driver initialized for widgetId: ${inputs.widgetId}")
            result.url
        }
    }

    private fun parseMessage(message: String): WidgetMessage? {
        return widgetMessageSerializer.deserialize(message).getOrNull()
    }

    private fun sendCloseMessage(widgetId: String, interceptor: WidgetMessageInterceptor) {
        val message = WidgetMessage(
            direction = WidgetMessage.Direction.ToWidget,
            widgetId = widgetId,
            requestId = "widgetapi-${UUID.randomUUID()}",
            action = WidgetMessage.Action.Close,
        )
        interceptor.sendMessage(widgetMessageSerializer.serialize(message))
    }
}
