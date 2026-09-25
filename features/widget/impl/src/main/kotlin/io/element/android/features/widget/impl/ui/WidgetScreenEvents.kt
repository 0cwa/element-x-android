/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.widget.impl.ui

import io.element.android.libraries.widget.WidgetMessageInterceptor

sealed interface WidgetScreenEvents {
    data object Close : WidgetScreenEvents
    data object GrantPreloadPermission : WidgetScreenEvents
    data object GrantOpenIdPermission : WidgetScreenEvents
    data object DenyOpenIdPermission : WidgetScreenEvents
    data class SetMessageInterceptor(val interceptor: WidgetMessageInterceptor?) : WidgetScreenEvents
    data object OnWebViewLoaded : WidgetScreenEvents
    data class OnWebViewError(val description: String?) : WidgetScreenEvents
}
