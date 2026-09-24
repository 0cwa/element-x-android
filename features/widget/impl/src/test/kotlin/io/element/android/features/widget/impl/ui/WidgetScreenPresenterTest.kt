/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.widget.impl.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.test.core.app.ApplicationProvider
import app.cash.molecule.RecompositionMode
import app.cash.molecule.moleculeFlow
import com.google.common.truth.Truth.assertThat
import io.element.android.features.widget.api.WidgetActivityData
import io.element.android.features.widget.impl.permissions.WidgetPermissionStore
import io.element.android.features.widget.impl.utils.WidgetProvider
import io.element.android.libraries.androidutils.json.DefaultJsonProvider
import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.matrix.test.A_ROOM_ID
import io.element.android.libraries.matrix.test.A_SESSION_ID
import io.element.android.libraries.matrix.test.FakeMatrixClientProvider
import io.element.android.libraries.matrix.test.widget.FakeMatrixWidgetDriver
import io.element.android.libraries.network.useragent.UserAgentProvider
import io.element.android.libraries.sessionstorage.test.observer.FakeSessionObserver
import io.element.android.libraries.widget.WidgetMessageInterceptor
import io.element.android.libraries.widget.WidgetMessageSerializer
import io.element.android.services.appnavstate.test.FakeActiveRoomsHolder
import io.element.android.tests.testutils.WarmUpRule
import io.element.android.tests.testutils.robolectric.RobolectricTest
import io.element.android.tests.testutils.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class WidgetScreenPresenterTest : RobolectricTest() {
    @get:Rule
    val warmUpRule = WarmUpRule()

    @Test
    fun `creator bypasses preload consent and widget is loaded`() = runTest {
        val provider = FakeWidgetProvider()
        val presenter = createWidgetScreenPresenter(
            data = aWidgetActivityData(creatorUserId = A_SESSION_ID.value),
            widgetProvider = provider,
        )

        presenter.test {
            var state = awaitItem()
            while (state.urlState !is AsyncData.Success) {
                state = awaitItem()
            }

            assertThat(state.preloadPermission).isEqualTo(WidgetPreloadPermission.Allowed)
            assertThat(provider.callCount).isEqualTo(1)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `unapproved third party widget does not load`() = runTest {
        val provider = FakeWidgetProvider()
        val presenter = createWidgetScreenPresenter(
            data = aWidgetActivityData(creatorUserId = "@bob:example.org"),
            widgetProvider = provider,
            matrixClientProvider = FakeMatrixClientProvider {
                Result.failure(IllegalStateException("No client needed for permission prompt"))
            },
        )

        presenter.test {
            var state = awaitItem()
            while (state.preloadPermission is WidgetPreloadPermission.Checking) {
                state = awaitItem()
            }

            assertThat(state.preloadPermission).isInstanceOf(WidgetPreloadPermission.Required::class.java)
            assertThat(state.urlState).isEqualTo(AsyncData.Uninitialized)
            assertThat(provider.callCount).isEqualTo(0)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `invalid widget url is rejected before widget creation`() = runTest {
        val provider = FakeWidgetProvider()
        val presenter = createWidgetScreenPresenter(
            data = aWidgetActivityData(
                creatorUserId = A_SESSION_ID.value,
                url = "javascript:alert(1)",
            ),
            widgetProvider = provider,
        )

        presenter.test {
            var state = awaitItem()
            while (state.webViewError == null) {
                state = awaitItem()
            }

            assertThat(state.webViewError).isEqualTo("Invalid widget URL")
            assertThat(provider.callCount).isEqualTo(0)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `generated widget url changing origin is rejected and closes driver`() = runTest {
        val driver = FakeMatrixWidgetDriver(id = "widget-id")
        val provider = FakeWidgetProvider(
            driver = driver,
            generatedUrl = "https://other.example/generated",
        )
        val presenter = createWidgetScreenPresenter(
            data = aWidgetActivityData(creatorUserId = A_SESSION_ID.value),
            widgetProvider = provider,
        )

        presenter.test {
            var state = awaitItem()
            while (state.urlState !is AsyncData.Failure) {
                state = awaitItem()
            }

            assertThat(provider.callCount).isEqualTo(1)
            assertThat(driver.closeCalledCount).isEqualTo(1)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `replacing message interceptor moves widget message collection to the new bridge`() = runTest {
        val driver = FakeMatrixWidgetDriver(id = "widget-id")
        val provider = FakeWidgetProvider(driver = driver)
        val presenter = createWidgetScreenPresenter(
            data = aWidgetActivityData(creatorUserId = A_SESSION_ID.value),
            widgetProvider = provider,
        )
        val firstInterceptor = FakeWidgetMessageInterceptor()
        val secondInterceptor = FakeWidgetMessageInterceptor()

        presenter.test {
            var state = awaitItem()
            while (state.urlState !is AsyncData.Success) {
                state = awaitItem()
            }

            state.eventSink(WidgetScreenEvents.SetMessageInterceptor(firstInterceptor))
            runCurrent()
            state = awaitItem()

            state.eventSink(WidgetScreenEvents.SetMessageInterceptor(secondInterceptor))
            runCurrent()
            secondInterceptor.givenInterceptedMessage("A reply")
            runCurrent()

            assertThat(driver.sentMessages).containsExactly("A reply")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `closing a loaded widget sends close message before shutdown`() = runTest {
        val driver = FakeMatrixWidgetDriver(id = "widget-id")
        val provider = FakeWidgetProvider(driver = driver)
        val navigator = FakeWidgetScreenNavigator()
        val presenter = createWidgetScreenPresenter(
            data = aWidgetActivityData(creatorUserId = A_SESSION_ID.value),
            widgetProvider = provider,
            navigator = navigator,
        )
        val interceptor = FakeWidgetMessageInterceptor()

        presenter.test {
            var state = awaitItem()
            while (state.urlState !is AsyncData.Success) {
                state = awaitItem()
            }
            state.eventSink(WidgetScreenEvents.SetMessageInterceptor(interceptor))
            runCurrent()
            interceptor.givenInterceptedMessage(
                """{"action":"supported_api_versions","api":"fromWidget","widgetId":"widget-id","requestId":"1"}"""
            )

            do {
                state = awaitItem()
            } while (!state.isWidgetLoaded)

            state.eventSink(WidgetScreenEvents.Close)

            assertThat(interceptor.sentMessages).hasSize(1)
            assertThat(interceptor.sentMessages.single()).contains("\"action\":\"io.element.close\"")
            assertThat(interceptor.sentMessages.single()).contains("\"api\":\"toWidget\"")

            advanceTimeBy(2.seconds)
            runCurrent()

            assertThat(navigator.closeCalled).isTrue()
            assertThat(driver.closeCalledCount).isEqualTo(1)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `disposing presenter closes widget driver`() = runTest {
        val driver = FakeMatrixWidgetDriver(id = "widget-id")
        val provider = FakeWidgetProvider(driver = driver)
        val presenter = createWidgetScreenPresenter(
            data = aWidgetActivityData(creatorUserId = A_SESSION_ID.value),
            widgetProvider = provider,
        )

        val job = launch {
            moleculeFlow(RecompositionMode.Immediate) {
                presenter.present()
            }.collect { }
        }
        runCurrent()
        assertThat(provider.callCount).isEqualTo(1)

        job.cancelAndJoin()

        assertThat(driver.closeCalledCount).isEqualTo(1)
    }

    private fun TestScope.createWidgetScreenPresenter(
        data: WidgetActivityData,
        widgetProvider: FakeWidgetProvider,
        navigator: WidgetScreenNavigator = FakeWidgetScreenNavigator(),
        matrixClientProvider: FakeMatrixClientProvider = FakeMatrixClientProvider(),
    ): WidgetScreenPresenter {
        val permissionStore = WidgetPermissionStore(
            context = ApplicationProvider.getApplicationContext<Context>(),
            appCoroutineScope = backgroundScope,
            sessionObserver = FakeSessionObserver(),
        )
        return WidgetScreenPresenter(
            widgetActivityData = data,
            navigator = navigator,
            widgetProvider = widgetProvider,
            widgetPermissionStore = permissionStore,
            matrixClientProvider = matrixClientProvider,
            activeRoomsHolder = FakeActiveRoomsHolder(),
            userAgentProvider = object : UserAgentProvider {
                override fun provide(): String = "Test"
            },
            languageTagProvider = object : LanguageTagProvider {
                @Composable
                override fun provideLanguageTag(): String = "en-US"
            },
            widgetMessageSerializer = WidgetMessageSerializer(DefaultJsonProvider()),
        )
    }

    private fun aWidgetActivityData(
        creatorUserId: String,
        url: String = "https://widget.example/path",
    ) = WidgetActivityData(
        sessionId = A_SESSION_ID,
        roomId = A_ROOM_ID,
        widgetId = "widget-id",
        eventId = "\$event-id",
        creatorUserId = creatorUserId,
        url = url,
        widgetName = "Widget",
        waitForIframeLoad = true,
        isRoomEncrypted = true,
    )

    private class FakeWidgetProvider(
        private val driver: FakeMatrixWidgetDriver = FakeMatrixWidgetDriver(id = "widget-id"),
        private val generatedUrl: String = "https://widget.example/generated",
    ) : WidgetProvider {
        var callCount = 0
            private set

        override suspend fun getWidget(
            sessionId: io.element.android.libraries.matrix.api.core.SessionId,
            roomId: io.element.android.libraries.matrix.api.core.RoomId,
            clientId: String,
            widgetId: String,
            url: String,
            initAfterContentLoad: Boolean,
            languageTag: String?,
            theme: String?,
        ): Result<WidgetProvider.GetWidgetResult> {
            callCount++
            return Result.success(WidgetProvider.GetWidgetResult(driver = driver, url = generatedUrl))
        }
    }

    private class FakeWidgetScreenNavigator : WidgetScreenNavigator {
        var closeCalled = false
            private set

        override fun close() {
            closeCalled = true
        }
    }

    private class FakeWidgetMessageInterceptor : WidgetMessageInterceptor {
        override val interceptedMessages = MutableSharedFlow<String>(extraBufferCapacity = 10)
        val sentMessages = mutableListOf<String>()

        override fun sendMessage(message: String) {
            sentMessages += message
        }

        fun givenInterceptedMessage(message: String) {
            interceptedMessages.tryEmit(message)
        }
    }
}
