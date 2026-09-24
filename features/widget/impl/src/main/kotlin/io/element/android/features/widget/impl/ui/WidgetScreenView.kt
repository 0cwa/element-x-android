/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.widget.impl.ui

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.element.android.features.widget.impl.R
import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.designsystem.components.ProgressDialog
import io.element.android.libraries.designsystem.components.avatar.Avatar
import io.element.android.libraries.designsystem.components.avatar.AvatarData
import io.element.android.libraries.designsystem.components.avatar.AvatarSize
import io.element.android.libraries.designsystem.components.avatar.AvatarType
import io.element.android.libraries.designsystem.components.button.BackButton
import io.element.android.libraries.designsystem.components.dialogs.ConfirmationDialog
import io.element.android.libraries.designsystem.components.dialogs.ErrorDialog
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.Button
import io.element.android.libraries.designsystem.theme.components.Scaffold
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.theme.components.TopAppBar
import io.element.android.libraries.ui.strings.CommonStrings
import io.element.android.libraries.widget.WebViewWidgetMessageInterceptor
import io.element.android.libraries.widget.widgetOrigin
import timber.log.Timber

typealias RequestPermissionCallback = (Array<String>) -> Unit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WidgetScreenView(
    state: WidgetScreenState,
    onConsoleMessage: (ConsoleMessage) -> Unit,
    requestPermissions: (Array<String>, RequestPermissionCallback) -> Unit,
    modifier: Modifier = Modifier,
) {
    fun handleBack() {
        state.eventSink(WidgetScreenEvents.Close)
    }

    var pendingPermissionRequest by remember { mutableStateOf<PermissionRequest?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                titleStr = state.widgetName,
                navigationIcon = {
                    BackButton(onClick = ::handleBack)
                },
            )
        },
    ) { padding ->
        BackHandler {
            handleBack()
        }
        when {
            state.webViewError != null -> {
                ErrorDialog(
                    content = buildString {
                        append(stringResource(CommonStrings.error_unknown))
                        state.webViewError.takeIf { it.isNotEmpty() }?.let { append("\n\n").append(it) }
                    },
                    onSubmit = { state.eventSink(WidgetScreenEvents.Close) },
                )
            }
            state.preloadPermission is WidgetPreloadPermission.Checking -> {
                ProgressDialog(text = stringResource(id = CommonStrings.common_please_wait))
            }
            state.preloadPermission is WidgetPreloadPermission.Required -> {
                WidgetPermissionView(
                    permission = state.preloadPermission,
                    onContinue = { state.eventSink(WidgetScreenEvents.GrantPreloadPermission) },
                    modifier = Modifier
                        .padding(padding)
                        .consumeWindowInsets(padding)
                        .fillMaxSize(),
                )
            }
            else -> {
                WidgetWebView(
                    modifier = Modifier
                        .padding(padding)
                        .consumeWindowInsets(padding)
                        .fillMaxSize(),
                    url = state.urlState,
                    userAgent = state.userAgent,
                    onPermissionsRequest = { request ->
                        val requestOrigin = widgetOrigin(request.origin.toString())
                        val androidPermissions = mapWebkitPermissions(request.resources)
                        if (pendingPermissionRequest != null) {
                            request.deny()
                        } else if (state.widgetOrigin != null &&
                            requestOrigin == state.widgetOrigin &&
                            androidPermissions.isNotEmpty()
                        ) {
                            pendingPermissionRequest = request
                        } else {
                            request.deny()
                        }
                    },
                    onPermissionsRequestCancel = { request ->
                        if (pendingPermissionRequest === request) {
                            pendingPermissionRequest = null
                        }
                    },
                    onConsoleMessage = onConsoleMessage,
                    onCreateWebView = { webView ->
                        val interceptor = WebViewWidgetMessageInterceptor(
                            webView = webView,
                            widgetOrigin = state.widgetOrigin,
                            onUrlLoaded = {},
                            onError = { state.eventSink(WidgetScreenEvents.OnWebViewError(it)) },
                        )
                        if (interceptor.isMessageChannelAvailable) {
                            state.eventSink(WidgetScreenEvents.SetMessageInterceptor(interceptor))
                        }
                        interceptor.isMessageChannelAvailable
                    },
                    onDestroyWebView = {
                        pendingPermissionRequest?.deny()
                        pendingPermissionRequest = null
                        state.eventSink(WidgetScreenEvents.SetMessageInterceptor(null))
                    },
                )
                when (state.urlState) {
                    AsyncData.Uninitialized,
                    is AsyncData.Loading ->
                        ProgressDialog(text = stringResource(id = CommonStrings.common_please_wait))
                    is AsyncData.Failure -> {
                        Timber.e(state.urlState.error, "WebView failed to load URL: ${state.urlState.error.message}")
                        ErrorDialog(
                            content = state.urlState.error.message.orEmpty(),
                            onSubmit = { state.eventSink(WidgetScreenEvents.Close) },
                        )
                    }
                    is AsyncData.Success -> Unit
                }
            }
        }
    }

    pendingPermissionRequest?.let { request ->
        val permissionNames = mutableListOf<String>()
        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in request.resources) {
            permissionNames += stringResource(R.string.screen_widget_permission_request_microphone)
        }
        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE in request.resources) {
            permissionNames += stringResource(R.string.screen_widget_permission_request_camera)
        }
        val origin = widgetOrigin(request.origin.toString()) ?: request.origin.toString()
        ConfirmationDialog(
            title = stringResource(R.string.screen_widget_permission_request_title),
            content = stringResource(
                R.string.screen_widget_permission_request_content,
                origin,
                permissionNames.joinToString(", "),
            ),
            submitText = stringResource(CommonStrings.action_continue),
            onSubmitClick = {
                pendingPermissionRequest = null
                val androidPermissions = mapWebkitPermissions(request.resources)
                requestPermissions(androidPermissions.toTypedArray()) { resourcesToGrant ->
                    if (resourcesToGrant.isEmpty()) {
                        request.deny()
                    } else {
                        request.grant(resourcesToGrant)
                    }
                }
            },
            onDismiss = {
                pendingPermissionRequest = null
                request.deny()
            },
        )
    }
}

@Composable
private fun WidgetPermissionView(
    permission: WidgetPreloadPermission.Required,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.screen_widget_permission_added_by))
        Spacer(Modifier.height(12.dp))
        Avatar(
            avatarData = AvatarData(
                id = permission.creatorUserId,
                name = permission.creatorDisplayName,
                url = permission.creatorAvatarUrl,
                size = AvatarSize.UserListItem,
            ),
            avatarType = AvatarType.User,
        )
        Spacer(Modifier.height(8.dp))
        Text(permission.creatorDisplayName)
        if (permission.creatorDisplayName != permission.creatorUserId) {
            Text(permission.creatorUserId)
        }
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.screen_widget_permission_shared_data_warning, permission.widgetDomain))
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.screen_widget_permission_cookie_warning))
        if (permission.isRoomEncrypted) {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.screen_widget_permission_unencrypted_warning))
        }
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.screen_widget_permission_shared_data_title))
        sharedDataItems().forEach { item ->
            Text("• $item", modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(24.dp))
        Button(
            text = stringResource(CommonStrings.action_continue),
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun sharedDataItems(): List<String> = listOf(
    stringResource(R.string.screen_widget_permission_shared_data_name),
    stringResource(R.string.screen_widget_permission_shared_data_avatar),
    stringResource(R.string.screen_widget_permission_shared_data_user_id),
    stringResource(R.string.screen_widget_permission_shared_data_device_id),
    stringResource(R.string.screen_widget_permission_shared_data_language),
    stringResource(R.string.screen_widget_permission_shared_data_theme),
    stringResource(R.string.screen_widget_permission_shared_data_element_url),
    stringResource(R.string.screen_widget_permission_shared_data_room_id),
    stringResource(R.string.screen_widget_permission_shared_data_widget_id),
)

@Composable
private fun WidgetWebView(
    url: AsyncData<String>,
    userAgent: String,
    onPermissionsRequest: (PermissionRequest) -> Unit,
    onPermissionsRequestCancel: (PermissionRequest) -> Unit,
    onConsoleMessage: (ConsoleMessage) -> Unit,
    onCreateWebView: (WebView) -> Boolean,
    onDestroyWebView: (WebView) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (LocalInspectionMode.current) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text("WebView - can't be previewed")
        }
    } else {
        AndroidView(
            modifier = modifier,
            factory = { context ->
                WebView(context).apply {
                    val canLoadWidget = onCreateWebView(this)
                    tag = canLoadWidget
                    if (canLoadWidget) {
                        setup(
                            userAgent = userAgent,
                            onPermissionsRequested = onPermissionsRequest,
                            onPermissionsRequestCanceled = onPermissionsRequestCancel,
                            onConsoleMessage = onConsoleMessage,
                        )
                    }
                }
            },
            update = { webView ->
                if (webView.tag == true && url is AsyncData.Success && webView.url != url.data) {
                    webView.loadUrl(url.data)
                }
            },
            onRelease = { webView ->
                onDestroyWebView(webView)
                webView.destroy()
            }
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun WebView.setup(
    userAgent: String,
    onPermissionsRequested: (PermissionRequest) -> Unit,
    onPermissionsRequestCanceled: (PermissionRequest) -> Unit,
    onConsoleMessage: (ConsoleMessage) -> Unit,
) {
    layoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
    )

    with(settings) {
        javaScriptEnabled = true
        allowContentAccess = false
        allowFileAccess = false
        domStorageEnabled = true
        mediaPlaybackRequiresUserGesture = false
        loadsImagesAutomatically = true
        userAgentString = userAgent
    }

    webChromeClient = object : WebChromeClient() {
        override fun onPermissionRequest(request: PermissionRequest) {
            onPermissionsRequested(request)
        }

        override fun onPermissionRequestCanceled(request: PermissionRequest) {
            onPermissionsRequestCanceled(request)
        }

        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
            onConsoleMessage(consoleMessage)
            return true
        }
    }
}

internal fun mapWebkitPermissions(permissions: Array<String>): List<String> {
    return permissions.mapNotNull { permission ->
        when (permission) {
            PermissionRequest.RESOURCE_AUDIO_CAPTURE -> android.Manifest.permission.RECORD_AUDIO
            PermissionRequest.RESOURCE_VIDEO_CAPTURE -> android.Manifest.permission.CAMERA
            else -> null
        }
    }
}

@PreviewsDayNight
@Composable
internal fun WidgetScreenViewPreview(
    @PreviewParameter(WidgetScreenStatePreviewParam::class) state: WidgetScreenState,
) = ElementPreview {
    WidgetScreenView(
        state = state,
        requestPermissions = { _, _ -> },
        onConsoleMessage = {},
    )
}
