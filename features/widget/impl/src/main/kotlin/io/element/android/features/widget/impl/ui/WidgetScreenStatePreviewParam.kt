/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.widget.impl.ui

import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import io.element.android.libraries.architecture.AsyncData

open class WidgetScreenStatePreviewParam : PreviewParameterProvider<WidgetScreenState> {
    override val values: Sequence<WidgetScreenState>
        get() = sequenceOf(
            aWidgetScreenState(),
            aWidgetScreenState(
                preloadPermission = WidgetPreloadPermission.Required(
                    creatorUserId = "@alice:example.org",
                    creatorDisplayName = "Alice",
                    creatorAvatarUrl = null,
                    widgetDomain = "widget.example.org",
                    isRoomEncrypted = true,
                ),
                urlState = AsyncData.Uninitialized,
            ),
            aWidgetScreenState(urlState = AsyncData.Loading()),
            aWidgetScreenState(urlState = AsyncData.Failure(Exception("An error occurred"))),
            aWidgetScreenState(webViewError = "Error details from WebView"),
        )
}

internal fun aWidgetScreenState(
    urlState: AsyncData<String> = AsyncData.Success("https://widget.element.io/some-widget?with=parameters"),
    preloadPermission: WidgetPreloadPermission = WidgetPreloadPermission.Allowed,
    widgetOrigin: String? = "https://widget.element.io",
    webViewError: String? = null,
    userAgent: String = "",
    isWidgetLoaded: Boolean = true,
    isOpenIdPermissionRequired: Boolean = false,
    widgetName: String = "Widget",
    eventSink: (WidgetScreenEvents) -> Unit = {},
): WidgetScreenState {
    return WidgetScreenState(
        urlState = urlState,
        preloadPermission = preloadPermission,
        widgetOrigin = widgetOrigin,
        webViewError = webViewError,
        userAgent = userAgent,
        isWidgetLoaded = isWidgetLoaded,
        isOpenIdPermissionRequired = isOpenIdPermissionRequired,
        widgetName = widgetName,
        eventSink = eventSink,
    )
}
