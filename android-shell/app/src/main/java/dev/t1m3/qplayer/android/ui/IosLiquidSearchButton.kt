// Adapted from Kyant Backdrop catalog LiquidButton (Copyright 2025 Kyant,
// Apache-2.0). QPlayer: circular search action and route semantics.
package dev.t1m3.qplayer.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.catalog.utils.InteractiveHighlight

@Composable
internal fun IosLiquidSearchButton(
    backdrop: Backdrop,
    dark: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector = Icons.Default.Search,
    contentDescription: String = "搜索",
    diameter: Dp = 64.dp,
    compactProgress: () -> Float = { 0f },
) {
    val scope = rememberCoroutineScope()
    val highlight = remember(scope) { InteractiveHighlight(scope) }
    DisposableEffect(highlight) { onDispose { highlight.cancel() } }
    IosLiquidGlass(
        backdrop = backdrop,
        dark = dark,
        modifier = Modifier
            .layout { measurable, constraints ->
                val side = (diameter + (44.dp - diameter) * compactProgress().coerceIn(0f, 1f)).roundToPx()
                val child = measurable.measure(Constraints.fixed(
                    side.coerceIn(constraints.minWidth, constraints.maxWidth),
                    side.coerceIn(constraints.minHeight, constraints.maxHeight)))
                layout(child.width, child.height) { child.placeRelative(0, 0) }
            }
            .semantics { this.selected = selected }
            .clickable(
                interactionSource = null,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
        interaction = highlight,
        strongRefraction = true,
        surfaceTint = if (icon == Icons.Default.Search) Color(0xFF0088FF) else Color.Unspecified,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(28.dp),
            tint = if (icon == Icons.Default.Search) Color.White else adaptiveGlassInk(),
        )
    }
}
