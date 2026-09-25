/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.extensions.impl

import app.cash.molecule.RecompositionMode
import app.cash.molecule.moleculeFlow
import com.google.common.truth.Truth.assertThat
import io.element.android.features.widget.api.WidgetActivityData
import io.element.android.features.widget.api.WidgetEntryPoint
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.room.RoomStateEvent
import io.element.android.libraries.matrix.api.room.RoomStateEventProvider
import io.element.android.libraries.matrix.test.room.FakeJoinedRoom
import io.element.android.tests.testutils.robolectric.RobolectricTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ExtensionsPresenterTest : RobolectricTest() {
    @Test
    fun `widget state parsing mirrors Element Web discovery semantics`() {
        val event = aWidgetStateEvent(
            stateKey = "calendar-widget",
            eventId = "\$event-id",
            content = """{"type":"m.custom","url":"https://widget.example/calendar"}""",
        )

        val result = parseWidgetStateEvent(event)

        assertThat(result).isNotNull()
        assertThat(result!!.widgetId).isEqualTo("calendar-widget")
        assertThat(result.eventId).isEqualTo("\$event-id")
        assertThat(result.creatorUserId).isEqualTo("@alice:example.org")
        assertThat(result.name).isNull()
        assertThat(result.waitForIframeLoad).isTrue()
    }

    @Test
    fun `widget state parsing ignores deletion incomplete and invalid url events`() {
        assertThat(parseWidgetStateEvent(aWidgetStateEvent(content = "{}"))).isNull()
        assertThat(parseWidgetStateEvent(aWidgetStateEvent(content = """{"type":"m.custom"}"""))).isNull()
        assertThat(parseWidgetStateEvent(aWidgetStateEvent(content = """{"url":"https://widget.example"}"""))).isNull()
        assertThat(
            parseWidgetStateEvent(
                aWidgetStateEvent(content = """{"type":"m.custom","url":"javascript:alert(1)"}"""),
            )
        ).isNull()
    }

    @Test
    fun `widget state parsing rejects non string required fields`() {
        assertThat(
            parseWidgetStateEvent(
                aWidgetStateEvent(content = """{"type":null,"url":"https://widget.example"}"""),
            )
        ).isNull()
        assertThat(
            parseWidgetStateEvent(
                aWidgetStateEvent(content = """{"type":123,"url":"https://widget.example"}"""),
            )
        ).isNull()
        assertThat(
            parseWidgetStateEvent(
                aWidgetStateEvent(content = """{"type":"m.custom","url":null}"""),
            )
        ).isNull()
    }

    @Test
    fun `malformed wait for iframe load uses safe default`() {
        val result = parseWidgetStateEvent(
            aWidgetStateEvent(
                content = """{"type":"m.custom","url":"https://widget.example","waitForIframeLoad":"false"}""",
            ),
        )

        assertThat(result!!.waitForIframeLoad).isTrue()
    }

    @Test
    fun `widget state parsing only keeps Matrix media avatars before consent`() {
        val mxcAvatar = parseWidgetStateEvent(
            aWidgetStateEvent(
                content = """{"type":"m.custom","url":"https://widget.example","avatar_url":"mxc://example.org/avatar"}""",
            ),
        )
        val remoteAvatar = parseWidgetStateEvent(
            aWidgetStateEvent(
                content = """{"type":"m.custom","url":"https://widget.example","avatar_url":"https://tracker.example/avatar.png"}""",
            ),
        )

        assertThat(mxcAvatar!!.avatarUrl).isEqualTo("mxc://example.org/avatar")
        assertThat(remoteAvatar!!.avatarUrl).isNull()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `successful empty discovery is not treated as an error`() = runTest {
        val presenter = ExtensionsPresenter(
            room = FakeJoinedRoom(),
            roomStateEventProvider = FakeRoomStateEventProvider(emptyList()),
            widgetEntryPoint = RecordingWidgetEntryPoint(),
        )
        var latestState: ExtensionsState? = null

        val job = launch {
            moleculeFlow(RecompositionMode.Immediate) {
                presenter.present()
            }.collect { latestState = it }
        }

        runCurrent()

        val state = checkNotNull(latestState)
        assertThat(state.isLoading).isFalse()
        assertThat(state.hasLoadError).isFalse()
        assertThat(state.extensions).isEmpty()
        job.cancelAndJoin()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `initial discovery failure is surfaced instead of looking empty`() = runTest {
        val presenter = ExtensionsPresenter(
            room = FakeJoinedRoom(),
            roomStateEventProvider = FailingRoomStateEventProvider(),
            widgetEntryPoint = RecordingWidgetEntryPoint(),
        )
        var latestState: ExtensionsState? = null

        val job = launch {
            moleculeFlow(RecompositionMode.Immediate) {
                presenter.present()
            }.collect { latestState = it }
        }

        runCurrent()

        val state = checkNotNull(latestState)
        assertThat(state.isLoading).isFalse()
        assertThat(state.hasLoadError).isTrue()
        assertThat(state.extensions).isEmpty()
        job.cancelAndJoin()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `present refreshes extensions on room sync updates`() = runTest {
        val room = FakeJoinedRoom()
        val provider = FakeRoomStateEventProvider(
            listOf(aWidgetStateEvent(stateKey = "first", content = """{"type":"m.first","url":"https://first.example"}"""))
        )
        val widgetEntryPoint = RecordingWidgetEntryPoint()
        val presenter = ExtensionsPresenter(
            room = room,
            roomStateEventProvider = provider,
            widgetEntryPoint = widgetEntryPoint,
        )
        var latestState: ExtensionsState? = null

        val job = launch {
            moleculeFlow(RecompositionMode.Immediate) {
                presenter.present()
            }.collect { latestState = it }
        }

        runCurrent()
        assertThat(provider.callCount).isEqualTo(1)
        val initialState = checkNotNull(latestState)
        assertThat(initialState.extensions.map { it.widgetId }).containsExactly("first")

        provider.events = listOf(
            aWidgetStateEvent(stateKey = "second", content = """{"type":"m.second","url":"https://second.example"}""")
        )
        room.emitSyncUpdate()
        room.emitSyncUpdate()
        room.emitSyncUpdate()
        runCurrent()

        assertThat(provider.callCount).isEqualTo(1)
        advanceTimeBy(250)
        runCurrent()

        assertThat(provider.callCount).isEqualTo(2)
        val refreshedState = checkNotNull(latestState)
        assertThat(refreshedState.extensions.map { it.widgetId }).containsExactly("second")

        val extension = refreshedState.extensions.single()
        refreshedState.eventSink(
            ExtensionsEvents.OnExtensionClicked(
                extension = extension,
                widgetName = "Unknown App",
            )
        )

        val startedWidget = checkNotNull(widgetEntryPoint.startedWidget)
        assertThat(startedWidget.widgetId).isEqualTo("second")
        assertThat(startedWidget.eventId).isEqualTo("\$event-id")
        assertThat(startedWidget.creatorUserId).isEqualTo("@alice:example.org")
        assertThat(startedWidget.url).isEqualTo("https://second.example")
        assertThat(startedWidget.widgetName).isEqualTo("Unknown App")
        job.cancelAndJoin()
    }

    private fun aWidgetStateEvent(
        stateKey: String = "widget-id",
        eventId: String? = "\$event-id",
        content: String,
    ) = RoomStateEvent(
        eventType = "im.vector.modular.widgets",
        stateKey = stateKey,
        sender = UserId("@alice:example.org"),
        eventId = eventId?.let(::EventId),
        timestamp = 1L,
        contentJson = content,
    )

    private class FakeRoomStateEventProvider(
        var events: List<RoomStateEvent>,
    ) : RoomStateEventProvider {
        var callCount = 0
            private set

        override suspend fun getStateEvents(
            sessionId: SessionId,
            roomId: RoomId,
            eventType: String,
        ): Result<List<RoomStateEvent>> {
            callCount++
            return Result.success(events)
        }
    }

    private class FailingRoomStateEventProvider : RoomStateEventProvider {
        override suspend fun getStateEvents(
            sessionId: SessionId,
            roomId: RoomId,
            eventType: String,
        ): Result<List<RoomStateEvent>> = Result.failure(IllegalStateException("boom"))
    }

    private class RecordingWidgetEntryPoint : WidgetEntryPoint {
        var startedWidget: WidgetActivityData? = null
            private set

        override fun startWidget(widgetData: WidgetActivityData) {
            startedWidget = widgetData
        }
    }
}
