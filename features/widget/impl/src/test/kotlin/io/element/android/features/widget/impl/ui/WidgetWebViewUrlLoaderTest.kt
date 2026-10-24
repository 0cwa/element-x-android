/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.widget.impl.ui

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.architecture.AsyncData
import org.junit.Test

class WidgetWebViewUrlLoaderTest {
    @Test
    fun `recomposition loads the requested URL only once`() {
        val loads = mutableListOf<String>()
        val loader = WidgetWebViewUrlLoader { loads += it }

        repeat(3) { loader.update(AsyncData.Success(WIDGET_URL)) }

        assertThat(loads).containsExactly(WIDGET_URL)
    }

    @Test
    fun `widget redirects and fragment navigation survive host state updates`() {
        var browserUrl = ""
        val loader = WidgetWebViewUrlLoader { browserUrl = it }
        loader.update(AsyncData.Success(WIDGET_URL))
        assertThat(browserUrl).isEqualTo(WIDGET_URL)
        val navigatedUrl = "https://widget.example/room#document"
        browserUrl = navigatedUrl

        // Opening or dismissing a native permission dialog recomposes AndroidView.
        loader.update(AsyncData.Success(WIDGET_URL))

        assertThat(browserUrl).isEqualTo(navigatedUrl)
    }

    @Test
    fun `an explicit host URL change loads the new URL`() {
        val loads = mutableListOf<String>()
        val loader = WidgetWebViewUrlLoader { loads += it }
        val changedUrl = "$WIDGET_URL?theme=dark"

        loader.update(AsyncData.Success(WIDGET_URL))
        loader.update(AsyncData.Success(changedUrl))
        loader.update(AsyncData.Success(changedUrl))

        assertThat(loads).containsExactly(WIDGET_URL, changedUrl).inOrder()
    }

    @Test
    fun `non-success states never load stale URLs`() {
        val loads = mutableListOf<String>()
        val loader = WidgetWebViewUrlLoader { loads += it }

        loader.update(AsyncData.Uninitialized)
        loader.update(AsyncData.Loading())
        loader.update(AsyncData.Loading(prevData = WIDGET_URL))
        loader.update(AsyncData.Failure(IllegalStateException("Failed"), prevData = WIDGET_URL))

        assertThat(loads).isEmpty()
        loader.update(AsyncData.Success(WIDGET_URL))
        assertThat(loads).containsExactly(WIDGET_URL)
    }

    @Test
    fun `a recreated WebView loads even when the requested URL has not changed`() {
        val firstLoads = mutableListOf<String>()
        val secondLoads = mutableListOf<String>()
        val firstLoader = WidgetWebViewUrlLoader { firstLoads += it }
        val secondLoader = WidgetWebViewUrlLoader { secondLoads += it }

        firstLoader.update(AsyncData.Success(WIDGET_URL))
        secondLoader.update(AsyncData.Success(WIDGET_URL))

        assertThat(firstLoads).containsExactly(WIDGET_URL)
        assertThat(secondLoads).containsExactly(WIDGET_URL)
    }

    private companion object {
        const val WIDGET_URL = "https://widget.example/"
    }
}
