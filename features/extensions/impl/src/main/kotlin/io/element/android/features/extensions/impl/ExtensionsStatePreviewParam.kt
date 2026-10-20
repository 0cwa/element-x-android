/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.extensions.impl

import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import kotlinx.collections.immutable.toImmutableList

open class ExtensionsStatePreviewParam : PreviewParameterProvider<ExtensionsState> {
    override val values: Sequence<ExtensionsState>
        get() = sequenceOf(
            anExtensionsState(),
            anExtensionsState(
                extensions = listOf(
                    anExtensionItem(widgetId = "alice-bot", name = "Alice's Bot", url = "https://example.com/bot"),
                    anExtensionItem(
                        widgetId = "reminder",
                        name = "Reminder",
                        avatarUrl = "mxc://example.com/reminder",
                        url = "https://example.com/reminder",
                    ),
                    anExtensionItem(widgetId = "poll-bot", name = "Poll Bot", url = "https://example.com/poll"),
                ),
            ),
        )
}

fun anExtensionsState(
    extensions: List<ExtensionItem> = emptyList(),
    isLoading: Boolean = false,
    hasLoadError: Boolean = false,
    eventSink: (ExtensionsEvents) -> Unit = {},
) = ExtensionsState(
    extensions = extensions.toImmutableList(),
    isLoading = isLoading,
    hasLoadError = hasLoadError,
    eventSink = eventSink,
)

fun anExtensionItem(
    widgetId: String = "widget-id",
    eventId: String? = "\$event-id",
    creatorUserId: String = "@alice:example.com",
    type: String = "m.custom",
    name: String = "An extension",
    avatarUrl: String? = null,
    url: String = "https://example.com/widget",
    waitForIframeLoad: Boolean = true,
) = ExtensionItem(
    widgetId = widgetId,
    eventId = eventId,
    creatorUserId = creatorUserId,
    type = type,
    name = name,
    avatarUrl = avatarUrl,
    url = url,
    waitForIframeLoad = waitForIframeLoad,
)
