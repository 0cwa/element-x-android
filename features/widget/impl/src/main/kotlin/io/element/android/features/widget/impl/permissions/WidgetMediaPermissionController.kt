/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.widget.impl.permissions

import android.Manifest
import android.webkit.PermissionRequest
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.widget.widgetOrigin

/** Tracks a WebView request until the Android permission result, including cancellation during the system dialog. */
internal class WidgetMediaPermissionController(private val expectedOrigin: String?) {
    var prompt by mutableStateOf<PermissionRequest?>(null)
        private set
    private var activeRequest: PermissionRequest? = null
    private var androidRequest: PermissionRequest? = null
    private var closed = false

    fun receive(request: PermissionRequest) {
        val canPrompt = !closed && activeRequest == null && androidRequest == null
        val isExpectedOrigin = expectedOrigin != null && widgetOrigin(request.origin.toString()) == expectedOrigin
        if (!canPrompt || !isExpectedOrigin || supportedResources(request).isEmpty()) {
            request.deny()
            return
        }
        activeRequest = request
        prompt = request
    }

    fun approve(request: PermissionRequest, requestPermissions: (Array<String>, (Array<String>) -> Unit) -> Unit) {
        if (activeRequest !== request || prompt !== request || closed) return
        val resources = supportedResources(request)
        val permissions = resources.map { resource ->
            if (resource == PermissionRequest.RESOURCE_AUDIO_CAPTURE) Manifest.permission.RECORD_AUDIO else Manifest.permission.CAMERA
        }.toTypedArray()
        prompt = null
        androidRequest = request
        runCatchingExceptions {
            requestPermissions(permissions) { grantedResources ->
                if (androidRequest === request) {
                    androidRequest = null
                    if (activeRequest === request && !closed) {
                        activeRequest = null
                        val granted = resources.filter { it in grantedResources }.toTypedArray()
                        if (granted.isEmpty()) request.deny() else request.grant(granted)
                    }
                }
            }
        }.onFailure {
            if (androidRequest === request) androidRequest = null
            deny(request)
        }
    }

    fun cancel(request: PermissionRequest) {
        if (activeRequest === request) {
            activeRequest = null
            prompt = null
        }
        // Keep the Android request slot until its callback returns. A new launch must not steal that result.
    }

    fun deny(request: PermissionRequest) {
        if (activeRequest === request) {
            cancel(request)
            request.deny()
        }
    }

    fun detach() {
        activeRequest?.let(::deny)
    }

    fun close() {
        closed = true
        detach()
    }

    private fun supportedResources(request: PermissionRequest): List<String> = request.resources.filter {
        it == PermissionRequest.RESOURCE_AUDIO_CAPTURE || it == PermissionRequest.RESOURCE_VIDEO_CAPTURE
    }.distinct()
}
