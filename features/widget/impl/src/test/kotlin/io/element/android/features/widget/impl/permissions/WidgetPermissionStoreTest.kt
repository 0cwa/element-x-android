/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.widget.impl.permissions

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.sessionstorage.test.observer.FakeSessionObserver
import io.element.android.tests.testutils.robolectric.RobolectricTest
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.UUID

class WidgetPermissionStoreTest : RobolectricTest() {
    @Test
    fun `approval is scoped to the exact widget event and removed with the session`() = runTest {
        val sessionObserver = FakeSessionObserver()
        val store = WidgetPermissionStore(
            context = ApplicationProvider.getApplicationContext<Context>(),
            appCoroutineScope = backgroundScope,
            sessionObserver = sessionObserver,
        )
        val suffix = UUID.randomUUID().toString()
        val sessionId = UserId("@alice-$suffix:example.org")
        val roomId = RoomId("!room-$suffix:example.org")
        val approvedEventId = "\$approved-$suffix"
        val replacementEventId = "\$replacement-$suffix"

        assertThat(store.isAllowed(sessionId, roomId, approvedEventId)).isFalse()

        store.allow(sessionId, roomId, approvedEventId)

        assertThat(store.isAllowed(sessionId, roomId, approvedEventId)).isTrue()
        assertThat(store.isAllowed(sessionId, roomId, replacementEventId)).isFalse()
        assertThat(store.isAllowed(sessionId, RoomId("!other-$suffix:example.org"), approvedEventId)).isFalse()

        sessionObserver.onSessionDeleted(sessionId.value)

        assertThat(store.isAllowed(sessionId, roomId, approvedEventId)).isFalse()
    }
}
