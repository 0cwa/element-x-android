/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.widget.impl.permissions

import android.Manifest
import android.net.Uri
import android.webkit.PermissionRequest
import com.google.common.truth.Truth.assertThat
import io.element.android.tests.testutils.robolectric.RobolectricTest
import org.junit.Test

class WidgetMediaPermissionControllerTest : RobolectricTest() {
    private val controller = WidgetMediaPermissionController(ORIGIN)

    @Test
    fun `unknown resources and untrusted origins never prompt`() {
        val unknown = Request(resources = arrayOf("unknown"))
        val foreign = Request(origin = "https://other.example")
        val unpinned = Request()
        controller.receive(unknown)
        controller.receive(foreign)
        WidgetMediaPermissionController(null).receive(unpinned)
        assertThat(controller.prompt).isNull()
        assertThat(listOf(unknown.denials, foreign.denials, unpinned.denials)).containsExactly(1, 1, 1)
    }

    @Test
    fun `approval waits for Android and grants only requested supported resources`() {
        val request = Request(resources = arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE, "unknown"))
        val launcher = Launcher()
        controller.receive(request)
        assertThat(controller.prompt).isSameInstanceAs(request)
        controller.approve(request, launcher::launch)
        assertThat(controller.prompt).isNull()
        assertThat(request.grants).isEmpty()
        assertThat(launcher.permissions.toList()).containsExactly(Manifest.permission.RECORD_AUDIO)
        launcher.complete(PermissionRequest.RESOURCE_AUDIO_CAPTURE, PermissionRequest.RESOURCE_VIDEO_CAPTURE, "unknown")
        assertThat(request.grants).containsExactly(listOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
        assertThat(request.denials).isEqualTo(0)
    }

    @Test
    fun `partial Android grants and denial are respected`() {
        val request = Request(resources = arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE, PermissionRequest.RESOURCE_VIDEO_CAPTURE))
        val launcher = Launcher()
        controller.receive(request)
        controller.approve(request, launcher::launch)
        assertThat(launcher.permissions.toList()).containsExactly(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        launcher.complete(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
        assertThat(request.grants).containsExactly(listOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
        val denied = Request()
        controller.receive(denied)
        controller.approve(denied, launcher::launch)
        launcher.complete()
        assertThat(denied.denials).isEqualTo(1)
        assertThat(denied.grants).isEmpty()
    }

    @Test
    fun `parallel requests do not replace native consent`() {
        val first = Request()
        val second = Request()
        controller.receive(first)
        controller.receive(second)
        assertThat(controller.prompt).isSameInstanceAs(first)
        assertThat(second.denials).isEqualTo(1)
        controller.deny(first)
        assertThat(first.denials).isEqualTo(1)
        assertThat(controller.prompt).isNull()
    }

    @Test
    fun `cancelled native dialog callbacks cannot approve a replacement request`() {
        val first = Request()
        val second = Request()
        val launcher = Launcher()
        controller.receive(first)
        controller.cancel(first)
        controller.receive(second)
        controller.approve(first, launcher::launch)
        controller.deny(first)
        assertThat(launcher.launches).isEqualTo(0)
        assertThat(controller.prompt).isSameInstanceAs(second)
        assertThat(first.denials).isEqualTo(0)
        assertThat(second.denials).isEqualTo(0)
    }

    @Test
    fun `cancelling while Android prompts ignores its late result and reserves the launcher`() {
        val first = Request()
        val launcher = Launcher()
        controller.receive(first)
        controller.approve(first, launcher::launch)
        controller.cancel(first)
        val second = Request()
        controller.receive(second)
        assertThat(second.denials).isEqualTo(1)
        launcher.complete(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
        assertThat(first.grants).isEmpty()
        assertThat(first.denials).isEqualTo(0)
        val third = Request()
        controller.receive(third)
        assertThat(controller.prompt).isSameInstanceAs(third)
    }

    @Test
    fun `destroying a WebView denies its outstanding Android request exactly once`() {
        val request = Request()
        val launcher = Launcher()
        controller.receive(request)
        controller.approve(request, launcher::launch)
        controller.detach()
        controller.detach()
        launcher.complete(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
        assertThat(request.grants).isEmpty()
        assertThat(request.denials).isEqualTo(1)
        assertThat(controller.prompt).isNull()
    }

    @Test
    fun `closing prevents further prompts and ignores late results`() {
        val request = Request()
        val launcher = Launcher()
        controller.receive(request)
        controller.approve(request, launcher::launch)
        controller.close()
        launcher.complete(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
        val next = Request()
        controller.receive(next)
        assertThat(request.grants).isEmpty()
        assertThat(request.denials).isEqualTo(1)
        assertThat(next.denials).isEqualTo(1)
        assertThat(controller.prompt).isNull()
    }

    @Test
    fun `duplicate approvals or Android results cannot grant twice`() {
        val request = Request()
        val launcher = Launcher()
        controller.receive(request)
        controller.approve(request, launcher::launch)
        controller.approve(request, launcher::launch)
        assertThat(launcher.launches).isEqualTo(1)
        launcher.complete(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
        launcher.complete(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
        assertThat(request.grants).hasSize(1)
    }

    @Test
    fun `a failed Android launch denies the request and releases its slot`() {
        val request = Request()
        controller.receive(request)
        controller.approve(request) { _, _ -> error("Launcher unavailable") }
        assertThat(request.denials).isEqualTo(1)
        assertThat(request.grants).isEmpty()
        val next = Request()
        controller.receive(next)
        assertThat(controller.prompt).isSameInstanceAs(next)
    }

    private class Launcher {
        var launches = 0
        var permissions = emptyArray<String>()
        private var callback: ((Array<String>) -> Unit)? = null

        fun launch(permissions: Array<String>, callback: (Array<String>) -> Unit) {
            launches++
            this.permissions = permissions
            this.callback = callback
        }

        fun complete(vararg resources: String) {
            requireNotNull(callback)(arrayOf(*resources))
        }
    }

    private class Request(
        private val origin: String = ORIGIN,
        private val resources: Array<String> = arrayOf(RESOURCE_VIDEO_CAPTURE),
    ) : PermissionRequest() {
        val grants = mutableListOf<List<String>>()
        var denials = 0

        override fun getOrigin(): Uri = Uri.parse(origin)
        override fun getResources(): Array<String> = resources
        override fun grant(resources: Array<String>) {
            grants += resources.toList()
        }

        override fun deny() {
            denials++
        }
    }

    private companion object {
        const val ORIGIN = "https://widget.example"
    }
}
