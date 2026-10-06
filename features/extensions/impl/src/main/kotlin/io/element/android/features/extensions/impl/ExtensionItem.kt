/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.extensions.impl

data class ExtensionItem(
    val widgetId: String,
    val eventId: String?,
    val creatorUserId: String,
    val type: String,
    val name: String?,
    val avatarUrl: String?,
    val url: String,
    val waitForIframeLoad: Boolean,
)
