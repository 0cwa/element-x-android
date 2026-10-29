/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.widget.impl.utils

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.matrix.api.MatrixClientProvider
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.matrix.api.widget.MatrixWidgetCapabilitiesPolicy
import io.element.android.libraries.matrix.api.widget.MatrixWidgetSettings
import io.element.android.services.appnavstate.api.ActiveRoomsHolder
import timber.log.Timber

@ContributesBinding(AppScope::class)
class DefaultWidgetProvider(
    private val matrixClientsProvider: MatrixClientProvider,
    private val activeRoomsHolder: ActiveRoomsHolder,
) : WidgetProvider {
    override suspend fun getWidget(
        sessionId: SessionId,
        roomId: RoomId,
        clientId: String,
        widgetId: String,
        url: String,
        initAfterContentLoad: Boolean,
        languageTag: String?,
        theme: String?,
    ): Result<WidgetProvider.GetWidgetResult> = runCatchingExceptions {
        val widgetSettings = MatrixWidgetSettings(
            id = widgetId,
            initAfterContentLoad = initAfterContentLoad,
            rawUrl = url,
        )

        Timber.v(
            "Provided widget settings: initAfterContentLoad ${widgetSettings.initAfterContentLoad} " +
                "widgetId ${widgetSettings.id}"
        )

        val matrixClient = matrixClientsProvider.getOrRestore(sessionId).getOrThrow()
        val room = activeRoomsHolder.getActiveRoomMatching(sessionId, roomId)
            ?: matrixClient.getJoinedRoom(roomId)
            ?: error("Room not found")

        val widgetUrl = room.generateWidgetWebViewUrl(
            widgetSettings = widgetSettings,
            clientId = clientId,
            languageTag = languageTag,
            theme = theme,
        ).getOrThrow()

        Timber.v("Generated widget URL for widgetId: ${widgetSettings.id}")
        val driver = room.getWidgetDriver(
            widgetSettings = widgetSettings,
            capabilitiesPolicy = MatrixWidgetCapabilitiesPolicy.DenyAll,
        ).getOrThrow()

        WidgetProvider.GetWidgetResult(
            driver = driver,
            url = widgetUrl,
        )
    }
}
