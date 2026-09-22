/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.api.room

/**
 * A room state event's state key and raw JSON content.
 */
data class RoomStateEventContent(
    val stateKey: String,
    val contentJson: String,
)
