/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.widget

import io.element.android.libraries.core.extensions.runCatchingExceptions
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

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


/**
 * Adds the legacy Widget API bootstrap parameters expected by matrix-widget-api based widgets.
 *
 * Native clients host widgets in a top-level WebView rather than an iframe, so [parentOrigin]
 * is the pinned widget origin itself. Existing values are replaced to prevent room state from
 * spoofing the widget id or message target. Fragment parameters are preserved unchanged.
 */
fun withWidgetApiBootstrapParameters(
    rawUrl: String,
    widgetId: String,
    parentOrigin: String,
): String {
    val uri = URI(rawUrl)
    val retainedQuery = uri.rawQuery
        ?.split("&")
        .orEmpty()
        .filter { parameter ->
            val rawName = parameter.substringBefore("=")
            val name = URLDecoder.decode(rawName, StandardCharsets.UTF_8)
            name != WIDGET_ID_QUERY_PARAMETER && name != PARENT_URL_QUERY_PARAMETER
        }
        .filter { it.isNotEmpty() }

    val query = buildList {
        addAll(retainedQuery)
        add("${WIDGET_ID_QUERY_PARAMETER}=${encodeQueryParameter(widgetId)}")
        add("${PARENT_URL_QUERY_PARAMETER}=${encodeQueryParameter(parentOrigin)}")
    }.joinToString("&")

    return buildString {
        append(uri.scheme)
        append("://")
        append(uri.rawAuthority)
        append(uri.rawPath.orEmpty())
        append("?")
        append(query)
        uri.rawFragment?.let { fragment ->
            append("#")
            append(fragment)
        }
    }
}

private fun encodeQueryParameter(value: String): String {
    return URLEncoder.encode(value, StandardCharsets.UTF_8)
}

private const val WIDGET_ID_QUERY_PARAMETER = "widgetId"
private const val PARENT_URL_QUERY_PARAMETER = "parentUrl"
