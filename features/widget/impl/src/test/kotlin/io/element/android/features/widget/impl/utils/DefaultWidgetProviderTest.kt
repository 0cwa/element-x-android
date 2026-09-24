/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.widget.impl.utils

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.matrix.api.room.JoinedRoom
import io.element.android.libraries.matrix.api.widget.MatrixWidgetCapabilitiesPolicy
import io.element.android.libraries.matrix.test.A_ROOM_ID
import io.element.android.libraries.matrix.test.A_SESSION_ID
import io.element.android.libraries.matrix.test.FakeMatrixClientProvider
import io.element.android.libraries.matrix.test.room.FakeJoinedRoom
import io.element.android.libraries.matrix.test.widget.FakeMatrixWidgetDriver
import io.element.android.services.appnavstate.api.ActiveRoomsHolder
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DefaultWidgetProviderTest {
    @Test
    fun `generic widgets use deny all capability policy`() = runTest {
        var capturedPolicy: MatrixWidgetCapabilitiesPolicy? = null
        val driver = FakeMatrixWidgetDriver(id = "widget-id")
        val room = FakeJoinedRoom(
            generateWidgetWebViewUrlResult = { _, _, _, _ ->
                Result.success("https://widget.example/generated")
            },
            getWidgetDriverResult = {
                Result.success(driver)
            },
            onGetWidgetDriver = { _, policy ->
                capturedPolicy = policy
            },
        )
        val provider = DefaultWidgetProvider(
            matrixClientsProvider = FakeMatrixClientProvider(),
            activeRoomsHolder = SingleRoomHolder(room),
        )

        val result = provider.getWidget(
            sessionId = A_SESSION_ID,
            roomId = A_ROOM_ID,
            clientId = "client-id",
            widgetId = "widget-id",
            url = "https://widget.example",
            initAfterContentLoad = false,
            languageTag = "en-US",
            theme = "light",
        )

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrThrow().driver).isSameInstanceAs(driver)
        assertThat(capturedPolicy).isEqualTo(MatrixWidgetCapabilitiesPolicy.DenyAll)
    }

    private class SingleRoomHolder(
        private val room: JoinedRoom,
    ) : ActiveRoomsHolder {
        override fun addRoom(room: JoinedRoom) = Unit

        override fun getActiveRoom(sessionId: SessionId): JoinedRoom = room

        override fun getActiveRoomMatching(sessionId: SessionId, roomId: RoomId): JoinedRoom = room

        override fun removeRoom(sessionId: SessionId, roomId: RoomId) = Unit

        override fun clear(sessionId: SessionId) = Unit
    }
}
