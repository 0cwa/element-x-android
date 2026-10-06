/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.widget

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.widget.MatrixWidgetSettings
import org.junit.Test

class MatrixWidgetSettingsTest {
    @Test
    fun `standard device id placeholder is mapped to the Rust SDK spelling`() {
        val settings = MatrixWidgetSettings(
            id = "widget-id",
            initAfterContentLoad = true,
            rawUrl = "https://widget.example/#/?deviceId=\$org.matrix.msc3819.matrix_device_id&keep=value",
        )

        val result = settings.toRustWidgetSettings()

        assertThat(result.rawUrl).isEqualTo(
            "https://widget.example/#/?deviceId=\$org.matrix.msc2873.matrix_device_id&keep=value"
        )
    }
}
