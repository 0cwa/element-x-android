/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.extensions.impl

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewParameter
import io.element.android.libraries.designsystem.components.ProgressDialog
import io.element.android.libraries.designsystem.components.button.BackButton
import io.element.android.libraries.designsystem.components.dialogs.ErrorDialog
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.Scaffold
import io.element.android.libraries.designsystem.theme.components.TopAppBar
import io.element.android.libraries.ui.strings.CommonStrings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionsView(
    state: ExtensionsState,
    goBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                titleStr = stringResource(R.string.screen_extensions_title),
                navigationIcon = {
                    BackButton(onClick = goBack)
                },
            )
        },
    ) { padding ->
        when {
            state.isLoading -> {
                ProgressDialog(text = stringResource(CommonStrings.common_please_wait))
            }
            state.hasLoadError -> {
                ErrorDialog(
                    content = stringResource(CommonStrings.error_unknown),
                    onSubmit = goBack,
                )
            }
            state.extensions.isEmpty() -> {
                ExtensionsEmptyState(
                    modifier = Modifier
                        .padding(padding)
                        .consumeWindowInsets(padding),
                )
            }
            else -> {
                LazyColumn(
                    modifier = Modifier
                        .padding(padding)
                        .consumeWindowInsets(padding)
                ) {
                    items(
                        items = state.extensions,
                        key = { extension -> extension.widgetId },
                    ) { extension ->
                        val displayName = extension.name ?: stringResource(R.string.screen_extensions_unknown_widget_name)
                        ExtensionListItem(
                            extension = extension,
                            name = displayName,
                            onClick = {
                                state.eventSink(
                                    ExtensionsEvents.OnExtensionClicked(
                                        extension = extension,
                                        widgetName = displayName,
                                    )
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

@PreviewsDayNight
@Composable
internal fun ExtensionsViewPreview(
    @PreviewParameter(ExtensionsStatePreviewParam::class) state: ExtensionsState,
) = ElementPreview {
    ExtensionsView(
        state = state,
        goBack = {},
    )
}
