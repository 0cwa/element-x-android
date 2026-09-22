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
     * Grant the capabilities requested by the widget.
     *
     * Callers must only use this after the requested capabilities have been explicitly approved by the user.
     */
    UserApproved,
}
