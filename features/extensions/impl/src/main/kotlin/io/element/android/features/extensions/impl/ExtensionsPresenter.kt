/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.extensions.impl

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import dev.zacsweers.metro.Inject
import io.element.android.features.widget.api.WidgetActivityData
import io.element.android.features.widget.api.WidgetEntryPoint
import io.element.android.libraries.matrix.api.room.JoinedRoom
import io.element.android.libraries.matrix.api.room.RoomStateEvent
import io.element.android.libraries.matrix.api.room.RoomStateEventProvider
import io.element.android.libraries.widget.widgetOrigin
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import org.json.JSONObject
import timber.log.Timber

@Inject
class ExtensionsPresenter(
    private val room: JoinedRoom,
    private val roomStateEventProvider: RoomStateEventProvider,
    private val widgetEntryPoint: WidgetEntryPoint,
) {
    @Composable
    fun present(): ExtensionsState {
        val extensions = remember { mutableStateOf(emptyList<ExtensionItem>()) }

        LaunchedEffect(room) {
            var isInitialEmission = true
            room.syncUpdateFlow.collectLatest {
                if (isInitialEmission) {
                    isInitialEmission = false
                } else {
                    delay(EXTENSIONS_REFRESH_DEBOUNCE_MILLIS)
                }
                roomStateEventProvider.getStateEvents(
                    sessionId = room.sessionId,
                    roomId = room.roomId,
                    eventType = WIDGET_EVENT_TYPE,
                )
                    .onSuccess { stateEvents ->
                        Timber.v("Fetched widget state events: ${stateEvents.size}")
                        extensions.value = stateEvents.mapNotNull { event ->
                            parseWidgetStateEvent(event)
                        }
                    }
                    .onFailure { error ->
                        Timber.e(error, "Failed to fetch widget state events")
                    }
            }
        }

        fun handleEvent(event: ExtensionsEvents) {
            when (event) {
                is ExtensionsEvents.OnExtensionClicked -> {
                    widgetEntryPoint.startWidget(
                        WidgetActivityData(
                            sessionId = room.sessionId,
                            roomId = room.roomId,
                            widgetId = event.extension.widgetId,
                            eventId = event.extension.eventId,
                            creatorUserId = event.extension.creatorUserId,
                            url = event.extension.url,
                            widgetName = event.widgetName,
                            waitForIframeLoad = event.extension.waitForIframeLoad,
                            isRoomEncrypted = room.info().isEncrypted == true,
                        ),
                    )
                }
            }
        }

        return ExtensionsState(
            extensions = extensions.value.toImmutableList(),
            eventSink = ::handleEvent,
        )
    }

    private companion object {
        const val WIDGET_EVENT_TYPE = "im.vector.modular.widgets"
        const val EXTENSIONS_REFRESH_DEBOUNCE_MILLIS = 250L
    }
}

internal fun parseWidgetStateEvent(
    event: RoomStateEvent,
): ExtensionItem? {
    return try {
        val jsonObject = JSONObject(event.contentJson)
        val type = jsonObject.nonBlankString("type") ?: return null
        val url = jsonObject.nonBlankString("url") ?: return null
        if (widgetOrigin(url) == null) return null
        val name = jsonObject.nonBlankString("name")
        val avatarUrl = jsonObject.nonBlankString("avatar_url")
            ?.takeIf { it.startsWith("mxc://", ignoreCase = true) }
        ExtensionItem(
            widgetId = event.stateKey,
            eventId = event.eventId?.value,
            creatorUserId = event.sender.value,
            type = type,
            name = name,
            avatarUrl = avatarUrl,
            url = url,
            waitForIframeLoad = jsonObject.opt("waitForIframeLoad") as? Boolean ?: true,
        )
    } catch (e: Exception) {
        Timber.e(e, "Failed to parse widget state event")
        null
    }
}

private fun JSONObject.nonBlankString(name: String): String? {
    return (opt(name) as? String)?.trim()?.takeIf { it.isNotEmpty() }
}
