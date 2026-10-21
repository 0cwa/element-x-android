/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.widget

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.widget.MatrixWidgetCapabilitiesPolicy
import io.element.android.libraries.matrix.api.widget.MatrixWidgetPermission
import io.element.android.libraries.matrix.api.widget.MatrixWidgetPermission.Kind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.matrix.rustcomponents.sdk.WidgetEventFilter

class RustWidgetCapabilitiesProviderTest {
    @Test
    fun `deny all never grants event or privileged capabilities`() = runTest {
        val requested = allCapabilities()
        val result = provider(MatrixWidgetCapabilitiesPolicy.DenyAll).acquireCapabilities(requested)
        assertThat(result).isEqualTo(deniedWidgetCapabilities())
    }

    @Test
    fun `native consent is awaited and grants only the exact requested filters`() = runTest {
        val answer = CompletableDeferred<Boolean>()
        var prompt: List<MatrixWidgetPermission>? = null
        val policy = MatrixWidgetCapabilitiesPolicy.AskUser { permissions ->
            prompt = permissions
            answer.await()
        }
        val requested = allCapabilities()
        val result = async(start = CoroutineStart.UNDISPATCHED) { provider(policy).acquireCapabilities(requested) }
        assertThat(result.isCompleted).isFalse()
        assertThat(prompt).containsExactlyElementsIn(requested.permissions())
        answer.complete(true)
        assertThat(result.await()).isEqualTo(requested)
    }

    @Test
    fun `denying native consent returns no negotiated permissions`() = runTest {
        val result = provider(MatrixWidgetCapabilitiesPolicy.AskUser { false }).acquireCapabilities(allCapabilities())
        assertThat(result).isEqualTo(deniedWidgetCapabilities())
    }

    @Test
    fun `a failed consent callback fails closed`() = runTest {
        val result = provider(MatrixWidgetCapabilitiesPolicy.AskUser { error("View unavailable") }).acquireCapabilities(allCapabilities())
        assertThat(result).isEqualTo(deniedWidgetCapabilities())
    }

    @Test
    fun `a transport-only requirement does not need a data permission prompt`() = runTest {
        val requested = deniedWidgetCapabilities().copy(requiresClient = true)
        val result = provider(MatrixWidgetCapabilitiesPolicy.AskUser { error("No data access requested") }).acquireCapabilities(requested)
        assertThat(result).isEqualTo(requested)
    }

    @Test
    fun `all event filters preserve type message subtype and exact state key in consent`() {
        val filters = listOf(
            WidgetEventFilter.MessageLikeWithType("m.reaction"),
            WidgetEventFilter.RoomMessageWithMsgtype("m.text"),
            WidgetEventFilter.StateWithType("m.room.member"),
            WidgetEventFilter.StateWithTypeAndStateKey("m.room.name", ""),
            WidgetEventFilter.ToDevice("org.example.device"),
        )
        val requested = deniedWidgetCapabilities().copy(read = filters, send = filters)
        assertThat(requested.permissions()).containsExactly(
            MatrixWidgetPermission(Kind.ReadMessages, eventType = "m.reaction"),
            MatrixWidgetPermission(Kind.ReadMessages, eventType = "m.room.message", messageType = "m.text"),
            MatrixWidgetPermission(Kind.ReadState, eventType = "m.room.member"),
            MatrixWidgetPermission(Kind.ReadState, eventType = "m.room.name", stateKey = ""),
            MatrixWidgetPermission(Kind.ReadToDevice, eventType = "org.example.device"),
            MatrixWidgetPermission(Kind.SendMessages, eventType = "m.reaction"),
            MatrixWidgetPermission(Kind.SendMessages, eventType = "m.room.message", messageType = "m.text"),
            MatrixWidgetPermission(Kind.SendState, eventType = "m.room.member"),
            MatrixWidgetPermission(Kind.SendState, eventType = "m.room.name", stateKey = ""),
            MatrixWidgetPermission(Kind.SendToDevice, eventType = "org.example.device"),
        ).inOrder()
    }

    @Test
    fun `every non-event privileged capability is explicitly described`() {
        assertThat(allCapabilities().copy(read = emptyList(), send = emptyList()).permissions().map { it.kind })
            .containsExactly(
                Kind.SendDelayedEvents,
                Kind.UpdateDelayedEvents,
                Kind.DownloadFiles,
                Kind.DiscoverRtcTransports,
                Kind.ObtainLivekitToken,
                Kind.DelegateLivekitLeave,
            )
    }

    private fun provider(policy: MatrixWidgetCapabilitiesPolicy) = RustWidgetCapabilitiesProvider(policy, "@alice:example.org", "DEVICE")

    private fun allCapabilities() = deniedWidgetCapabilities().copy(
        read = listOf(WidgetEventFilter.StateWithTypeAndStateKey("m.room.name", "")),
        send = listOf(WidgetEventFilter.RoomMessageWithMsgtype("m.text")),
        requiresClient = true,
        updateDelayedEvent = true,
        sendDelayedEvent = true,
        downloadFiles = true,
        rtcTransports = true,
        rtcLivekitGetToken = true,
        rtcLivekitDelegateDelayedLeave = true,
    )
}
