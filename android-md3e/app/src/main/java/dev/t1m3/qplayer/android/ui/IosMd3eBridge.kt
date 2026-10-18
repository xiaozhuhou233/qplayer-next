package dev.t1m3.qplayer.android.ui
import androidx.compose.ui.Modifier
import dev.t1m3.qplayer.android.md3eui.md3ePlayerChromeExit
internal typealias PlayerExpansionState = dev.t1m3.qplayer.android.md3eui.Md3ePlayerExpansionState
internal fun Modifier.playerChromeExit(state: PlayerExpansionState) = md3ePlayerChromeExit(state)
