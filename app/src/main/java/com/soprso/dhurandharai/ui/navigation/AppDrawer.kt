package com.soprso.dhurandharai.ui.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * App drawer: new conversation, history, model library, settings.
 * Deliberately flat — no nesting, no badges, no account section.
 */
@Composable
fun AppDrawer(
    onSelect: (route: String) -> Unit,
    currentRoute: String?,
) {
    ModalDrawerSheet(
        drawerContainerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.width(272.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = 12.dp, vertical = 16.dp),
        ) {
            Text(
                "Offline AI",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            Text(
                "local inference",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp),
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(8.dp))

            DrawerItem(
                label = "New conversation",
                glyph = "+",
                selected = false,
                onClick = { onSelect(Routes.CHAT) },
            )
            DrawerItem(
                label = "History",
                glyph = "≡",
                selected = currentRoute == Routes.HISTORY,
                onClick = { onSelect(Routes.HISTORY) },
            )
            DrawerItem(
                label = "Model library",
                glyph = "◈",
                selected = currentRoute == Routes.MODELS,
                onClick = { onSelect(Routes.MODELS) },
            )
            DrawerItem(
                label = "Settings",
                glyph = "⚙",
                selected = currentRoute == Routes.SETTINGS,
                onClick = { onSelect(Routes.SETTINGS) },
            )

            Spacer(Modifier.weight(1f))
            Text(
                "runs entirely on device",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
    }
}

@Composable
private fun DrawerItem(
    label: String,
    glyph: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    NavigationDrawerItem(
        label = {
            Text(
                "$glyph  $label",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        selected = selected,
        onClick = onClick,
        colors = NavigationDrawerItemDefaults.colors(
            selectedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    )
}
