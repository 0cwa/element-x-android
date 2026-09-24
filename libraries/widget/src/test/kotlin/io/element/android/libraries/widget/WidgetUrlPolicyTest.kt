/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.widget

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WidgetUrlPolicyTest {
    @Test
    fun `widget origin accepts and normalizes http urls`() {
        assertThat(widgetOrigin("https://EXAMPLE.org/path?x=1")).isEqualTo("https://example.org")
        assertThat(widgetOrigin("https://example.org:443/path")).isEqualTo("https://example.org")
        assertThat(widgetOrigin("http://example.org:80/path")).isEqualTo("http://example.org")
        assertThat(widgetOrigin("https://example.org:8443/path")).isEqualTo("https://example.org:8443")
    }

    @Test
    fun `widget origin rejects unsafe or ambiguous urls`() {
        assertThat(widgetOrigin("javascript:alert(1)")).isNull()
        assertThat(widgetOrigin("file:///tmp/widget.html")).isNull()
        assertThat(widgetOrigin("/relative/widget")).isNull()
        assertThat(widgetOrigin("https://user:pass@example.org/widget")).isNull()
    }

    @Test
    fun `navigation remains pinned to the original widget origin`() {
        val origin = "https://example.org"
        assertThat(isAllowedWidgetNavigation("https://example.org/next?x=1", origin)).isTrue()
        assertThat(isAllowedWidgetNavigation("https://example.org:443/next", origin)).isTrue()
        assertThat(isAllowedWidgetNavigation("https://evil.example/next", origin)).isFalse()
        assertThat(isAllowedWidgetNavigation("http://example.org/next", origin)).isFalse()
        assertThat(isAllowedWidgetNavigation("https://example.org:8443/next", origin)).isFalse()
    }
}
