/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.widget.impl.ui

sealed interface WidgetPreloadPermission {
    data object Checking : WidgetPreloadPermission
    data object Allowed : WidgetPreloadPermission

    data class Required(
        val creatorUserId: String,
        val creatorDisplayName: String,
        val creatorAvatarUrl: String?,
        val widgetDomain: String,
        val isRoomEncrypted: Boolean,
    ) : WidgetPreloadPermission
}
