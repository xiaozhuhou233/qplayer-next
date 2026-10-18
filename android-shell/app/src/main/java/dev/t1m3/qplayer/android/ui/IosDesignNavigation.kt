package dev.t1m3.qplayer.android.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.catalog.components.LiquidBottomTab
import com.kyant.backdrop.catalog.components.LiquidBottomTabs

/** Android-only style; routes and playback remain owned by QPlayerComposeApp. */
internal enum class IosNavigationDestination { HOME, LIBRARY, LOCAL, SEARCH }

@Composable
internal fun IosDesignNavigation(
    destination: IosNavigationDestination,
    showLocal: Boolean,
    dark: Boolean,
    backdrop: Backdrop,
    onDestination: (IosNavigationDestination) -> Unit,
    modifier: Modifier = Modifier,
    includeSearch: Boolean = true,
) {
    val view = LocalView.current
    val tabs = buildList {
        add(Triple(IosNavigationDestination.HOME, Icons.Default.Home, "主页"))
        add(Triple(IosNavigationDestination.LIBRARY, Icons.Default.LibraryMusic, "歌单"))
        if (showLocal) add(Triple(IosNavigationDestination.LOCAL, Icons.Default.Folder, "本地"))
    }
    val selectedIndex = tabs.indexOfFirst { it.first == destination }.coerceAtLeast(0)
    val select: (Int) -> Unit = { index ->
        val target = tabs[index].first
        if (target != destination) {
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            onDestination(target)
        }
    }
    // Recreate gesture state when a tab disappears; never retain an old range.
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
    key(showLocal) {
        LiquidBottomTabs(
            selectedTabIndex = { selectedIndex },
            onTabSelected = select,
            backdrop = backdrop,
            tabsCount = tabs.size,
            dark = dark,
            // Ⓜ The reference derives the accent and the plate from the theme inside the
            // component, so no accent is passed from here any more.
            modifier = Modifier.weight(1f),
            selectionActive = destination != IosNavigationDestination.SEARCH,
        ) {
            tabs.forEachIndexed { index, (_, icon, label) ->
                LiquidBottomTab(
                    onClick = { select(index) },
                    modifier = Modifier.semantics { selected = tabs[index].first == destination },
                ) {
                    // Do not hard-code tint: the optical foreground duplicate
                    // uses LocalContentColor to provide the lens accent colour.
                    Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp))
                    Text(label, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    if (includeSearch) IosLiquidSearchButton(
        backdrop = backdrop,
        dark = dark,
        selected = destination == IosNavigationDestination.SEARCH,
        onClick = {
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            if (destination != IosNavigationDestination.SEARCH) {
                onDestination(IosNavigationDestination.SEARCH)
            }
        },
    )
    }
}
