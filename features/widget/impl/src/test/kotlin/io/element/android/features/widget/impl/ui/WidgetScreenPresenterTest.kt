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
import io.element.android.libraries.matrix.api.widget.MatrixWidgetCapabilitiesPolicy
import io.element.android.libraries.matrix.api.widget.MatrixWidgetPermission
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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
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
            assertThat(state.urlState.dataOrNull()).isEqualTo(
                "https://widget.example/generated" +
                    "?widgetId=widget-id&parentUrl=https%3A%2F%2Fwidget.example"
            )
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
    fun `widget consent never loads external creator avatars`() {
        assertThat(avatarUrlBeforeWidgetConsent("https://tracker.example/avatar.png")).isNull()
        assertThat(avatarUrlBeforeWidgetConsent("data:image/png;base64,example")).isNull()
        assertThat(avatarUrlBeforeWidgetConsent(null)).isNull()
        assertThat(avatarUrlBeforeWidgetConsent("mxc://example.org/image")).isEqualTo("mxc://example.org/image")
        assertThat(avatarUrlBeforeWidgetConsent("MXC://example.org/image")).isEqualTo("MXC://example.org/image")
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
    fun `iframe-load widget driver starts only after the secure bridge and page are ready`() = runTest {
        val driver = FakeMatrixWidgetDriver(id = "widget-id")
        val provider = FakeWidgetProvider(driver = driver)
        val presenter = createWidgetScreenPresenter(
            data = aWidgetActivityData(
                creatorUserId = A_SESSION_ID.value,
                waitForIframeLoad = true,
            ),
            widgetProvider = provider,
        )
        val interceptor = FakeWidgetMessageInterceptor()

        presenter.test {
            var state = awaitItem()
            while (state.urlState !is AsyncData.Success) {
                state = awaitItem()
            }

            assertThat(driver.runCalledCount).isEqualTo(0)

            state.eventSink(WidgetScreenEvents.SetMessageInterceptor(interceptor))
            runCurrent()
            assertThat(driver.runCalledCount).isEqualTo(0)

            state.eventSink(WidgetScreenEvents.OnWebViewLoaded)
            runCurrent()
            assertThat(driver.runCalledCount).isEqualTo(1)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `content-loaded widget driver starts as soon as the secure bridge is ready`() = runTest {
        val driver = FakeMatrixWidgetDriver(id = "widget-id")
        val provider = FakeWidgetProvider(driver = driver)
        val presenter = createWidgetScreenPresenter(
            data = aWidgetActivityData(
                creatorUserId = A_SESSION_ID.value,
                waitForIframeLoad = false,
            ),
            widgetProvider = provider,
        )
        val interceptor = FakeWidgetMessageInterceptor()

        presenter.test {
            var state = awaitItem()
            while (state.urlState !is AsyncData.Success) {
                state = awaitItem()
            }

            assertThat(driver.runCalledCount).isEqualTo(0)

            state.eventSink(WidgetScreenEvents.SetMessageInterceptor(interceptor))
            runCurrent()
            assertThat(driver.runCalledCount).isEqualTo(1)

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
            state.eventSink(WidgetScreenEvents.OnWebViewLoaded)
            runCurrent()
            state = awaitItem()

            state.eventSink(WidgetScreenEvents.SetMessageInterceptor(secondInterceptor))
            runCurrent()
            secondInterceptor.givenInterceptedMessage("A reply")
            runCurrent()

            assertThat(driver.sentMessages).containsExactly("A reply")
            assertThat(driver.runCalledCount).isEqualTo(1)
            driver.givenIncomingMessage("SDK response")
            runCurrent()
            assertThat(secondInterceptor.sentMessages).containsExactly("SDK response")
            assertThat(firstInterceptor.sentMessages).isEmpty()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `openid request for another widget cannot trigger identity consent or reach the SDK`() = runTest {
        val driver = FakeMatrixWidgetDriver(id = "widget-id")
        val presenter = createWidgetScreenPresenter(
            data = aWidgetActivityData(creatorUserId = A_SESSION_ID.value),
            widgetProvider = FakeWidgetProvider(driver = driver),
        )
        val interceptor = FakeWidgetMessageInterceptor()
        val request = """{"api":"fromWidget","widgetId":"other-widget","requestId":"openid-other","action":"get_openid","data":{}}"""

        presenter.test {
            var state = awaitItem()
            while (state.urlState !is AsyncData.Success) {
                state = awaitItem()
            }

            state.eventSink(WidgetScreenEvents.SetMessageInterceptor(interceptor))
            state.eventSink(WidgetScreenEvents.OnWebViewLoaded)
            runCurrent()
            interceptor.givenInterceptedMessage(request)
            runCurrent()

            assertThat(state.openIdRequestId).isNull()
            assertThat(interceptor.sentMessages).isEmpty()
            assertThat(driver.sentMessages).isEmpty()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `malformed openid commands cannot bypass consent through the SDK relay`() = runTest {
        val driver = FakeMatrixWidgetDriver(id = "widget-id")
        val presenter = createWidgetScreenPresenter(
            data = aWidgetActivityData(creatorUserId = A_SESSION_ID.value),
            widgetProvider = FakeWidgetProvider(driver = driver),
        )
        val interceptor = FakeWidgetMessageInterceptor()
        val validRequest = """{"api":"fromWidget","widgetId":"widget-id","requestId":"openid-valid","action":"get_openid","data":{}}"""
        val malformedRequests = buildList {
            listOf<Any>(JSONObject.NULL, false, "", JSONObject()).forEach { response ->
                add(JSONObject(validRequest).put("response", response).toString())
            }
            listOf<Any>("", " ", JSONObject.NULL, 7).forEach { requestId ->
                add(JSONObject(validRequest).put("requestId", requestId).toString())
            }
            add(JSONObject(validRequest).apply { remove("requestId") }.toString())
        }

        presenter.test {
            var state = awaitItem()
            while (state.urlState !is AsyncData.Success) {
                state = awaitItem()
            }

            state.eventSink(WidgetScreenEvents.SetMessageInterceptor(interceptor))
            state.eventSink(WidgetScreenEvents.OnWebViewLoaded)
            runCurrent()
            malformedRequests.forEach { request ->
                interceptor.givenInterceptedMessage(request)
                runCurrent()
            }

            assertThat(driver.sentMessages).isEmpty()
            assertThat(interceptor.sentMessages).isEmpty()

            interceptor.givenInterceptedMessage(validRequest)
            runCurrent()
            while (state.openIdRequestId == null) {
                state = awaitItem()
            }
            assertThat(driver.sentMessages).isEmpty()
            state.eventSink(WidgetScreenEvents.RespondToOpenId(requireNotNull(state.openIdRequestId), true))
            runCurrent()
            assertThat(driver.sentMessages).containsExactly(validRequest)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `openid request waits for consent before reaching the SDK and approval resumes it`() = runTest {
        val driver = FakeMatrixWidgetDriver(id = "widget-id")
        val provider = FakeWidgetProvider(driver = driver)
        val presenter = createWidgetScreenPresenter(
            data = aWidgetActivityData(creatorUserId = A_SESSION_ID.value),
            widgetProvider = provider,
        )
        val interceptor = FakeWidgetMessageInterceptor()
        val request = """{"api":"fromWidget","widgetId":"widget-id","requestId":"openid-1","action":"get_openid","data":{}}"""

        presenter.test {
            var state = awaitItem()
            while (state.urlState !is AsyncData.Success) {
                state = awaitItem()
            }

            state.eventSink(WidgetScreenEvents.SetMessageInterceptor(interceptor))
            state.eventSink(WidgetScreenEvents.OnWebViewLoaded)
            runCurrent()
            interceptor.givenInterceptedMessage(request)
            runCurrent()

            while (state.openIdRequestId == null) {
                state = awaitItem()
            }
            assertThat(driver.sentMessages).isEmpty()
            assertThat(interceptor.sentMessages).hasSize(1)
            assertThat(JSONObject(interceptor.sentMessages.single()).getJSONObject("response").getString("state"))
                .isEqualTo("request")

            state.eventSink(WidgetScreenEvents.RespondToOpenId(requireNotNull(state.openIdRequestId), true))
            runCurrent()

            assertThat(driver.sentMessages).containsExactly(request)
            val duplicatePending = """
                {
                    "api":"fromWidget",
                    "widgetId":"widget-id",
                    "requestId":"openid-1",
                    "action":"get_openid",
                    "data":{},
                    "response":{"state":"request"}
                }
            """.trimIndent()
            driver.givenIncomingMessage(duplicatePending)
            runCurrent()
            assertThat(interceptor.sentMessages).hasSize(1)

            val credentials = """
                {
                    "api":"toWidget",
                    "widgetId":"widget-id",
                    "requestId":"sdk-openid-1",
                    "action":"openid_credentials",
                    "data":{
                        "state":"allowed",
                        "original_request_id":"openid-1",
                        "access_token":"secret-token",
                        "expires_in":3600,
                        "matrix_server_name":"example.org",
                        "token_type":"Bearer"
                    }
                }
            """.trimIndent()
            driver.givenIncomingMessage(credentials)
            runCurrent()

            assertThat(interceptor.sentMessages).containsExactly(
                interceptor.sentMessages.first(),
                credentials,
            ).inOrder()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `denying openid consent blocks identity without sending the request to the SDK`() = runTest {
        val driver = FakeMatrixWidgetDriver(id = "widget-id")
        val provider = FakeWidgetProvider(driver = driver)
        val presenter = createWidgetScreenPresenter(
            data = aWidgetActivityData(creatorUserId = A_SESSION_ID.value),
            widgetProvider = provider,
        )
        val interceptor = FakeWidgetMessageInterceptor()
        val request = """{"api":"fromWidget","widgetId":"widget-id","requestId":"openid-2","action":"get_openid","data":{}}"""

        presenter.test {
            var state = awaitItem()
            while (state.urlState !is AsyncData.Success) {
                state = awaitItem()
            }

            state.eventSink(WidgetScreenEvents.SetMessageInterceptor(interceptor))
            state.eventSink(WidgetScreenEvents.OnWebViewLoaded)
            runCurrent()
            interceptor.givenInterceptedMessage(request)
            runCurrent()

            while (state.openIdRequestId == null) {
                state = awaitItem()
            }
            state.eventSink(WidgetScreenEvents.RespondToOpenId(requireNotNull(state.openIdRequestId), false))
            runCurrent()

            assertThat(driver.sentMessages).isEmpty()
            assertThat(interceptor.sentMessages).hasSize(2)

            val blocked = JSONObject(interceptor.sentMessages.last())
            assertThat(blocked.getString("api")).isEqualTo("toWidget")
            assertThat(blocked.getString("action")).isEqualTo("openid_credentials")
            assertThat(blocked.getJSONObject("data").getString("state")).isEqualTo("blocked")
            assertThat(blocked.getJSONObject("data").getString("original_request_id")).isEqualTo("openid-2")

            blocked.put("response", JSONObject())
            interceptor.givenInterceptedMessage(blocked.toString())
            runCurrent()

            assertThat(driver.sentMessages).isEmpty()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `stale OpenID dialog responses cannot answer a replacement bridge request`() = runTest {
        val driver = FakeMatrixWidgetDriver(id = "widget-id")
        val presenter = createWidgetScreenPresenter(
            data = aWidgetActivityData(creatorUserId = A_SESSION_ID.value),
            widgetProvider = FakeWidgetProvider(driver = driver),
        )
        val firstInterceptor = FakeWidgetMessageInterceptor()
        val secondInterceptor = FakeWidgetMessageInterceptor()
        // Reusing the widget's protocol ID must not reuse its native approval.
        val request = """{"api":"fromWidget","widgetId":"widget-id","requestId":"openid-1","action":"get_openid","data":{}}"""

        presenter.test {
            var state = awaitItem()
            while (state.urlState !is AsyncData.Success) state = awaitItem()
            state.eventSink(WidgetScreenEvents.SetMessageInterceptor(firstInterceptor))
            runCurrent()
            firstInterceptor.givenInterceptedMessage(request)
            runCurrent()
            while (state.openIdRequestId == null) state = awaitItem()
            val oldApprovalId = requireNotNull(state.openIdRequestId)

            state.eventSink(WidgetScreenEvents.SetMessageInterceptor(secondInterceptor))
            runCurrent()
            secondInterceptor.givenInterceptedMessage(request)
            runCurrent()
            do {
                state = awaitItem()
            } while (state.openIdRequestId == null || state.openIdRequestId == oldApprovalId)
            val newApprovalId = requireNotNull(state.openIdRequestId)

            state.eventSink(WidgetScreenEvents.RespondToOpenId(oldApprovalId, true))
            state.eventSink(WidgetScreenEvents.RespondToOpenId(oldApprovalId, false))
            runCurrent()
            assertThat(driver.sentMessages).isEmpty()
            assertThat(secondInterceptor.sentMessages).hasSize(1)

            state.eventSink(WidgetScreenEvents.RespondToOpenId(newApprovalId, true))
            runCurrent()
            assertThat(driver.sentMessages).containsExactly(request)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `Matrix capabilities wait for the matching native approval and are cancelled on close`() = runTest {
        val provider = FakeWidgetProvider()
        val presenter = createWidgetScreenPresenter(
            data = aWidgetActivityData(creatorUserId = A_SESSION_ID.value),
            widgetProvider = provider,
        )
        presenter.test {
            var state = awaitItem()
            while (state.urlState !is AsyncData.Success) state = awaitItem()
            state.eventSink(WidgetScreenEvents.SetMessageInterceptor(FakeWidgetMessageInterceptor()))
            state.eventSink(WidgetScreenEvents.OnWebViewLoaded)
            runCurrent()
            val policy = provider.lastCapabilitiesPolicy as MatrixWidgetCapabilitiesPolicy.AskUser
            val permissions = listOf(MatrixWidgetPermission(MatrixWidgetPermission.Kind.ReadState, "m.room.name"))
            val first = async(start = CoroutineStart.UNDISPATCHED) { policy.approve(permissions) }
            while (state.capabilityRequest == null) state = awaitItem()
            assertThat(first.isCompleted).isFalse()
            state.eventSink(WidgetScreenEvents.RespondToCapabilities(requireNotNull(state.capabilityRequest).id, true))
            runCurrent()
            assertThat(first.await()).isTrue()
            val second = async(start = CoroutineStart.UNDISPATCHED) { policy.approve(permissions) }
            state.eventSink(WidgetScreenEvents.Close)
            runCurrent()
            assertThat(second.await()).isFalse()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `closing a loaded widget cancels identity consent before graceful shutdown`() = runTest {
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

            val openIdRequest = """{"api":"fromWidget","widgetId":"widget-id","requestId":"openid-close","action":"get_openid","data":{}}"""
            interceptor.givenInterceptedMessage(openIdRequest)
            runCurrent()
            while (state.openIdRequestId == null) state = awaitItem()
            val approvalId = requireNotNull(state.openIdRequestId)
            state.eventSink(WidgetScreenEvents.Close)
            runCurrent()
            while (state.openIdRequestId != null) state = awaitItem()

            assertThat(interceptor.sentMessages).hasSize(2)
            assertThat(interceptor.sentMessages.last()).contains("\"action\":\"io.element.close\"")
            assertThat(interceptor.sentMessages.last()).contains("\"api\":\"toWidget\"")
            assertThat(navigator.closeCalled).isFalse()

            state.eventSink(WidgetScreenEvents.RespondToOpenId(approvalId, true))
            interceptor.givenInterceptedMessage(openIdRequest)
            runCurrent()
            assertThat(driver.sentMessages).doesNotContain(openIdRequest)
            assertThat(JSONObject(interceptor.sentMessages.last()).getJSONObject("response").getString("state"))
                .isEqualTo("blocked")

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
        waitForIframeLoad: Boolean = true,
    ) = WidgetActivityData(
        sessionId = A_SESSION_ID,
        roomId = A_ROOM_ID,
        widgetId = "widget-id",
        eventId = "\$event-id",
        creatorUserId = creatorUserId,
        url = url,
        widgetName = "Widget",
        waitForIframeLoad = waitForIframeLoad,
        isRoomEncrypted = true,
    )

    private class FakeWidgetProvider(
        private val driver: FakeMatrixWidgetDriver = FakeMatrixWidgetDriver(id = "widget-id"),
        private val generatedUrl: String = "https://widget.example/generated",
    ) : WidgetProvider {
        var lastCapabilitiesPolicy: MatrixWidgetCapabilitiesPolicy? = null
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
            capabilitiesPolicy: MatrixWidgetCapabilitiesPolicy,
        ): Result<WidgetProvider.GetWidgetResult> {
            callCount++
            lastCapabilitiesPolicy = capabilitiesPolicy
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
