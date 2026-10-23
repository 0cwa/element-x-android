/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.widget.impl.ui

import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.element.android.features.widget.impl.R
import io.element.android.features.widget.impl.permissions.WidgetCapabilityConsent
import io.element.android.libraries.designsystem.components.dialogs.ListDialog
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.matrix.api.widget.MatrixWidgetPermission
import io.element.android.libraries.matrix.api.widget.MatrixWidgetPermission.Kind
import io.element.android.libraries.ui.strings.CommonStrings
import org.json.JSONObject

@Composable
internal fun WidgetCapabilityDialog(
    request: WidgetCapabilityConsent.Request,
    origin: String,
    onRespond: (Boolean) -> Unit,
) {
    ListDialog(
        title = stringResource(R.string.screen_widget_capabilities_title),
        submitText = stringResource(CommonStrings.action_continue),
        onSubmit = { onRespond(true) },
        onDismissRequest = { onRespond(false) },
    ) {
        item { Text(stringResource(R.string.screen_widget_capabilities_warning, origin)) }
        items(request.permissions) { permission -> Text(permission.description()) }
    }
}

@Composable
private fun MatrixWidgetPermission.description(): String {
    val label = when (kind) {
        Kind.ReadMessages -> R.string.screen_widget_capability_read_messages
        Kind.SendMessages -> R.string.screen_widget_capability_send_messages
        Kind.ReadState -> R.string.screen_widget_capability_read_state
        Kind.SendState -> R.string.screen_widget_capability_send_state
        Kind.ReadToDevice -> R.string.screen_widget_capability_read_to_device
        Kind.SendToDevice -> R.string.screen_widget_capability_send_to_device
        Kind.SendDelayedEvents -> R.string.screen_widget_capability_send_delayed
        Kind.UpdateDelayedEvents -> R.string.screen_widget_capability_update_delayed
        Kind.DownloadFiles -> R.string.screen_widget_capability_download_files
        Kind.DiscoverRtcTransports -> R.string.screen_widget_capability_rtc_transports
        Kind.ObtainLivekitToken -> R.string.screen_widget_capability_livekit_token
        Kind.DelegateLivekitLeave -> R.string.screen_widget_capability_livekit_leave
    }
    val details = buildList {
        // Quoting keeps untrusted event identifiers and control characters visibly separate from native text.
        eventType?.let { add(JSONObject.quote(it)) }
        messageType?.let { add(stringResource(R.string.screen_widget_capability_message_type, JSONObject.quote(it))) }
        if (kind == Kind.ReadState || kind == Kind.SendState) {
            add(
                stateKey?.let { stringResource(R.string.screen_widget_capability_state_key, JSONObject.quote(it)) }
                    ?: stringResource(R.string.screen_widget_capability_all_state_keys)
            )
        }
    }
    return (listOf(stringResource(label)) + details).joinToString("\n")
}

@PreviewsDayNight
@Composable
internal fun WidgetCapabilityDialogPreview() = ElementPreview {
    WidgetCapabilityDialog(
        request = WidgetCapabilityConsent.Request(
            id = 1,
            permissions = listOf(
                MatrixWidgetPermission(Kind.ReadState, "m.room.name", stateKey = ""),
                MatrixWidgetPermission(Kind.SendMessages, "m.room.message", messageType = "m.text"),
            ),
        ),
        origin = "https://widget.example.org",
        onRespond = {},
    )
}
