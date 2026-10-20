/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.widget

import io.element.android.libraries.matrix.api.widget.MatrixWidgetSettings
import org.matrix.rustcomponents.sdk.ClientProperties
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.WidgetSettings
import org.matrix.rustcomponents.sdk.generateWebviewUrl

fun MatrixWidgetSettings.toRustWidgetSettings() = WidgetSettings(
    widgetId = this.id,
    initAfterContentLoad = this.initAfterContentLoad,
    // matrix-widget-api and Element Web use the MSC3819 device-id placeholder, while
    // the Rust widget URL generator currently only recognizes the MSC2873 spelling.
    rawUrl = this.rawUrl.replace(MSC3819_DEVICE_ID_PLACEHOLDER, RUST_DEVICE_ID_PLACEHOLDER),
)

fun MatrixWidgetSettings.Companion.fromRustWidgetSettings(widgetSettings: WidgetSettings) = MatrixWidgetSettings(
    id = widgetSettings.widgetId,
    initAfterContentLoad = widgetSettings.initAfterContentLoad,
    rawUrl = widgetSettings.rawUrl,
)

suspend fun MatrixWidgetSettings.generateWidgetWebViewUrl(
    room: Room,
    clientId: String,
    languageTag: String? = null,
    theme: String? = null
) = generateWebviewUrl(
    widgetSettings = this.toRustWidgetSettings(),
    room = room,
    props = ClientProperties(
        clientId = clientId,
        languageTag = languageTag,
        theme = theme,
    )
)

private const val MSC3819_DEVICE_ID_PLACEHOLDER = "\$org.matrix.msc3819.matrix_device_id"
private const val RUST_DEVICE_ID_PLACEHOLDER = "\$org.matrix.msc2873.matrix_device_id"
