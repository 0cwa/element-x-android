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
     * This is the default used by existing trusted call flows, not by generic widgets.
     */
    ElementCall,

    /**
     * Deny every capability requested by the widget.
     *
     * Generic widgets use this policy until an interactive capability permission flow is implemented.
     * The SDK's suspending callback alone does not imply user approval.
     *
     * This only denies negotiated Matrix capabilities. The SDK handles core Widget API requests such
     * as `get_openid` separately; the generic host must obtain identity consent before forwarding them.
     */
    DenyAll,
}
