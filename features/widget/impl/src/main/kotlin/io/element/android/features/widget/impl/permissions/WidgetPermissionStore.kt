/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.widget.impl.permissions

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.androidutils.hash.hash
import io.element.android.libraries.di.annotations.AppCoroutineScope
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.sessionstorage.api.observer.SessionListener
import io.element.android.libraries.sessionstorage.api.observer.SessionObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first

private val allowedWidgetsKey = stringSetPreferencesKey("allowedWidgets")

@SingleIn(AppScope::class)
@Inject
class WidgetPermissionStore(
    @ApplicationContext context: Context,
    @AppCoroutineScope appCoroutineScope: CoroutineScope,
    sessionObserver: SessionObserver,
) {
    private val store = PreferenceDataStoreFactory.create(scope = appCoroutineScope) {
        context.preferencesDataStoreFile("widget_permissions")
    }

    init {
        sessionObserver.addListener(object : SessionListener {
            override suspend fun onSessionDeleted(userId: String, wasLastSession: Boolean) {
                clearSession(SessionId(userId))
            }
        })
    }

    suspend fun isAllowed(sessionId: SessionId, roomId: RoomId, eventId: String): Boolean {
        return permissionKey(sessionId, roomId, eventId) in store.data.first()[allowedWidgetsKey].orEmpty()
    }

    suspend fun allow(sessionId: SessionId, roomId: RoomId, eventId: String) {
        val key = permissionKey(sessionId, roomId, eventId)
        store.edit { preferences ->
            preferences[allowedWidgetsKey] = preferences[allowedWidgetsKey].orEmpty() + key
        }
    }

    private suspend fun clearSession(sessionId: SessionId) {
        val prefix = sessionPrefix(sessionId)
        store.edit { preferences ->
            preferences[allowedWidgetsKey] = preferences[allowedWidgetsKey]
                .orEmpty()
                .filterNot { it.startsWith(prefix) }
                .toSet()
        }
    }

    private fun permissionKey(sessionId: SessionId, roomId: RoomId, eventId: String): String {
        return sessionPrefix(sessionId) + "${roomId.value}|$eventId".hash()
    }

    private fun sessionPrefix(sessionId: SessionId): String {
        return sessionId.value.hash().take(16) + ":"
    }
}
