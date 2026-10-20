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
        assertThat(widgetOrigin("https://example.org:65536/widget")).isNull()
        assertThat(widgetOrigin("https://example.org:-1/widget")).isNull()
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

    @Test
    fun `generic messages require the main frame and pinned origin`() {
        val origin = "https://example.org"
        assertThat(isAllowedWidgetMessageSource(origin, true, origin)).isTrue()
        assertThat(isAllowedWidgetMessageSource("https://example.org:443", true, origin)).isTrue()
        assertThat(isAllowedWidgetMessageSource(origin, false, origin)).isFalse()
        assertThat(isAllowedWidgetMessageSource("https://other.example", true, origin)).isFalse()
        assertThat(isAllowedWidgetMessageSource("http://example.org", true, origin)).isFalse()
        assertThat(isAllowedWidgetMessageSource("https://example.org:8443", true, origin)).isFalse()
        assertThat(isAllowedWidgetMessageSource("null", true, origin)).isFalse()
    }

    @Test
    fun `trusted call messages retain wildcard compatibility`() {
        assertThat(isAllowedWidgetMessageSource("https://call.example", true, null)).isTrue()
        assertThat(isAllowedWidgetMessageSource("https://call.example", false, null)).isTrue()
        assertThat(isAllowedWidgetMessageSource("null", false, null)).isTrue()
    }

    @Test
    fun `widget api bootstrap parameters use the pinned origin and preserve fragments`() {
        val url = withWidgetApiBootstrapParameters(
            rawUrl = "https://widget.example/calendar?existing=value#/?matrix_room_id=%21room%3Aexample.org",
            widgetId = "calendar/widget id",
            parentOrigin = "https://widget.example",
        )

        assertThat(url).isEqualTo(
            "https://widget.example/calendar" +
                "?existing=value&widgetId=calendar%2Fwidget+id&parentUrl=https%3A%2F%2Fwidget.example" +
                "#/?matrix_room_id=%21room%3Aexample.org"
        )
    }

    @Test
    fun `widget api bootstrap parameters replace values supplied by room state`() {
        val url = withWidgetApiBootstrapParameters(
            rawUrl = "https://widget.example/?widgetId=spoofed&parentUrl=https%3A%2F%2Fevil.example&keep=1",
            widgetId = "real-widget",
            parentOrigin = "https://widget.example",
        )

        assertThat(url).isEqualTo(
            "https://widget.example/" +
                "?keep=1&widgetId=real-widget&parentUrl=https%3A%2F%2Fwidget.example"
        )
    }
}
