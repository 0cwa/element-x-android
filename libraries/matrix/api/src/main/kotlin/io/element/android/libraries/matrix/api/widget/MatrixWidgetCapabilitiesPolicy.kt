/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.api.widget

/**
 * Defines which capabilities a widget driver is allowed to grant.
 */
enum class MatrixWidgetCapabilitiesPolicy {
    /**
     * Restrict capabilities to the set required by Element Call.
     *
     * This is the safe default used by existing call flows.
     */
    ElementCall,

    /**
     * Deny every capability requested by the widget.
     *
     * This is suitable for generic widgets until the SDK exposes asynchronous capability acquisition,
     * which is required to implement an interactive permission prompt safely.
     *
     * This only denies negotiated Matrix read/send capabilities. The SDK handles core Widget API
     * requests such as `get_openid` separately, so widgets can still authenticate the current user.
     */
    DenyAll,
}
