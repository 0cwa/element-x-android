/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.widget.impl.permissions

import io.element.android.libraries.matrix.api.widget.MatrixWidgetPermission
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One request at a time, scoped to a live widget view. No approval is stored across widget sessions. */
class WidgetCapabilityConsent {
    data class Request(val id: Long, val permissions: List<MatrixWidgetPermission>)

    private val lock = Any()
    private val mutableRequest = MutableStateFlow<Request?>(null)
    val request = mutableRequest.asStateFlow()
    private var answer: CompletableDeferred<Boolean>? = null
    private var nextId = 0L
    private var attached = false
    private var closed = false

    fun attach() = synchronized(lock) {
        attached = !closed
    }

    fun detach() = synchronized(lock) {
        attached = false
        denyPending()
    }

    fun close() = synchronized(lock) {
        closed = true
        attached = false
        denyPending()
    }

    suspend fun approve(permissions: List<MatrixWidgetPermission>): Boolean {
        val pending = synchronized(lock) {
            if (!attached || closed || answer != null) return false
            val deferred = CompletableDeferred<Boolean>()
            answer = deferred
            mutableRequest.value = Request(++nextId, permissions.toList())
            deferred
        }
        return try {
            val allowed = pending.await()
            synchronized(lock) { allowed && attached && !closed && answer === pending }
        } finally {
            synchronized(lock) {
                if (answer === pending) {
                    answer = null
                    mutableRequest.value = null
                }
            }
        }
    }

    fun respond(requestId: Long, allowed: Boolean) = synchronized(lock) {
        // An old dialog callback must not approve a subsequent request or a recreated view.
        if (mutableRequest.value?.id == requestId) {
            answer?.complete(allowed && attached && !closed)
        }
    }

    private fun denyPending() {
        answer?.complete(false)
        answer = null
        mutableRequest.value = null
    }
}
