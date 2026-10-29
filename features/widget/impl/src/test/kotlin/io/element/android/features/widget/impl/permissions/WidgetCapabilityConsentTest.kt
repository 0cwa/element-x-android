/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.widget.impl.permissions

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.widget.MatrixWidgetPermission
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.Test

class WidgetCapabilityConsentTest {
    private val permissions = listOf(MatrixWidgetPermission(MatrixWidgetPermission.Kind.ReadState, "m.room.name"))

    @Test
    fun `a detached or closed view cannot request permissions`() = runTest {
        val consent = WidgetCapabilityConsent()
        assertThat(consent.approve(permissions)).isFalse()
        consent.attach()
        consent.close()
        consent.attach()
        assertThat(consent.approve(permissions)).isFalse()
        assertThat(consent.request.value).isNull()
    }

    @Test
    fun `request suspends until its exact native prompt is approved`() = runTest {
        val consent = WidgetCapabilityConsent().apply { attach() }
        val result = async(start = CoroutineStart.UNDISPATCHED) { consent.approve(permissions) }
        val request = requireNotNull(consent.request.value)
        assertThat(request.permissions).containsExactlyElementsIn(permissions)
        assertThat(result.isCompleted).isFalse()
        consent.respond(request.id + 1, true)
        assertThat(result.isCompleted).isFalse()
        consent.respond(request.id, true)
        assertThat(result.await()).isTrue()
        assertThat(consent.request.value).isNull()
    }

    @Test
    fun `denial and dismissal return no grant`() = runTest {
        val consent = WidgetCapabilityConsent().apply { attach() }
        val result = async(start = CoroutineStart.UNDISPATCHED) { consent.approve(permissions) }
        consent.respond(requireNotNull(consent.request.value).id, false)
        assertThat(result.await()).isFalse()
        assertThat(consent.request.value).isNull()
    }

    @Test
    fun `parallel requests cannot replace the visible permission prompt`() = runTest {
        val consent = WidgetCapabilityConsent().apply { attach() }
        val first = async(start = CoroutineStart.UNDISPATCHED) { consent.approve(permissions) }
        val request = requireNotNull(consent.request.value)
        assertThat(consent.approve(emptyList())).isFalse()
        assertThat(consent.request.value).isEqualTo(request)
        consent.close()
        assertThat(first.await()).isFalse()
    }

    @Test
    fun `detach cancels consent and stale callbacks cannot approve the next view`() = runTest {
        val consent = WidgetCapabilityConsent().apply { attach() }
        val first = async(start = CoroutineStart.UNDISPATCHED) { consent.approve(permissions) }
        val oldId = requireNotNull(consent.request.value).id
        consent.detach()
        assertThat(first.await()).isFalse()
        consent.attach()
        val second = async(start = CoroutineStart.UNDISPATCHED) { consent.approve(permissions) }
        val newId = requireNotNull(consent.request.value).id
        consent.respond(oldId, true)
        assertThat(second.isCompleted).isFalse()
        consent.respond(newId, false)
        assertThat(second.await()).isFalse()
    }

    @Test
    fun `closing after approval but before callback resumes still denies access`() = runTest {
        val consent = WidgetCapabilityConsent().apply { attach() }
        val result = async(start = CoroutineStart.UNDISPATCHED) { consent.approve(permissions) }
        consent.respond(requireNotNull(consent.request.value).id, true)
        consent.close()
        assertThat(result.await()).isFalse()
    }

    @Test
    fun `cancelling the requesting coroutine removes its prompt`() = runTest {
        val consent = WidgetCapabilityConsent().apply { attach() }
        val result = async(start = CoroutineStart.UNDISPATCHED) { consent.approve(permissions) }
        result.cancelAndJoin()
        assertThat(consent.request.value).isNull()
        val next = async(start = CoroutineStart.UNDISPATCHED) { consent.approve(permissions) }
        consent.respond(requireNotNull(consent.request.value).id, true)
        assertThat(next.await()).isTrue()
    }
}
