/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.widget.impl.ui

import io.element.android.libraries.architecture.AsyncData

/** Tracks host URL changes for one WebView, independently of the widget's own navigation. */
internal class WidgetWebViewUrlLoader(
    private val loadUrl: (String) -> Unit,
) {
    private var requestedUrl: String? = null

    fun update(url: AsyncData<String>) {
        if (url is AsyncData.Success && url.data != requestedUrl) {
            requestedUrl = url.data
            loadUrl(url.data)
        }
    }
}
