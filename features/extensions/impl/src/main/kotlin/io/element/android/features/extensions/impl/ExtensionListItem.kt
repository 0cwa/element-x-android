/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.extensions.impl

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.libraries.designsystem.components.avatar.Avatar
import io.element.android.libraries.designsystem.components.avatar.AvatarData
import io.element.android.libraries.designsystem.components.avatar.AvatarSize
import io.element.android.libraries.designsystem.components.avatar.AvatarType
import io.element.android.libraries.designsystem.components.list.ListItemContent
import io.element.android.libraries.designsystem.theme.components.IconSource
import io.element.android.libraries.designsystem.theme.components.ListItem
import io.element.android.libraries.designsystem.theme.components.Text

@Composable
fun ExtensionListItem(
    extension: ExtensionItem,
    name: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val leadingContent = if (extension.avatarUrl != null) {
        ListItemContent.Custom { _ ->
            Avatar(
                avatarData = AvatarData(
                    id = extension.widgetId,
                    name = name,
                    url = extension.avatarUrl,
                    size = AvatarSize.ExtensionsListItem,
                ),
                avatarType = AvatarType.User,
            )
        }
    } else {
        ListItemContent.Icon(IconSource.Vector(CompoundIcons.Extensions()))
    }
    ListItem(
        content = { Text(name) },
        leadingContent = leadingContent,
        onClick = onClick,
        modifier = modifier,
    )
}
