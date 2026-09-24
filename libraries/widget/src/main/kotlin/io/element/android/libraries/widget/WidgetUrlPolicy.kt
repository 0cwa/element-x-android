/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.widget

import io.element.android.libraries.core.extensions.runCatchingExceptions
import java.net.URI

/**
 * Returns the normalized HTTP(S) origin for a widget URL, or null when the URL is not suitable
 * for an embedded third-party widget.
 */
fun widgetOrigin(rawUrl: String): String? = runCatchingExceptions {
    val uri = URI(rawUrl)
    val scheme = uri.scheme?.lowercase() ?: return@runCatchingExceptions null
    if (scheme != "https" && scheme != "http") return@runCatchingExceptions null
    if (uri.userInfo != null) return@runCatchingExceptions null
    val host = uri.host?.lowercase() ?: return@runCatchingExceptions null
    val normalizedHost = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
    val port = uri.port
    val isDefaultPort = when (scheme) {
        "https" -> port == 443
        "http" -> port == 80
        else -> false
    }
    val portSuffix = if (port == -1 || isDefaultPort) "" else ":$port"
    "$scheme://$normalizedHost$portSuffix"
}.getOrNull()

fun isAllowedWidgetNavigation(rawUrl: String, expectedOrigin: String): Boolean {
    return widgetOrigin(rawUrl) == expectedOrigin
}
