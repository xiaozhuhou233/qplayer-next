// BiliPai optics with a live local luminance sample for foreground/surface contrast.
package dev.t1m3.qplayer.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.kyant.backdrop.Backdrop

internal val LocalGlassContentColor = compositionLocalOf<Color?> { null }
// Hosts can suspend the bounded readback while their render layers move.
internal val LocalGlassSamplingEnabled = compositionLocalOf { true }
internal val LocalGlassPerPageSampling = compositionLocalOf { false }
// Color adaptation is independent of the live backdrop used for refraction.
internal val LocalGlassAdaptiveColors = compositionLocalOf { true }

@Stable
internal class IosAdaptiveGlassState(
    private val themeLuminance: State<Float>,
    private val ink: State<Color>,
    val modifier: Modifier = Modifier,
    val restoredDialog: Boolean = false,
) {
    val luminance: Float get() = themeLuminance.value
    val contentColor: Color get() = ink.value
}

@Composable
internal fun rememberRegionAdaptiveGlass(backdrop: Backdrop): IosAdaptiveGlassState =
    rememberIosAdaptiveGlass(backdrop)

@Composable
internal fun rememberIosAdaptiveGlass(backdrop: Backdrop): IosAdaptiveGlassState {
    if (!LocalGlassAdaptiveColors.current) {
        val luminance = rememberUpdatedState(if (LocalGlassDark.current) 0f else 1f)
        val ink = rememberUpdatedState(MaterialTheme.colorScheme.onSurface)
        val restoredDialog = LocalRestoredIosDialogGlass.current
        return remember(restoredDialog) {
            IosAdaptiveGlassState(luminance, ink, restoredDialog = restoredDialog)
        }
    }
    if (LocalRestoredIosDialogGlass.current) return rememberRestoredIosDialogGlass(backdrop)
    return rememberLiveIosGlass(backdrop)
}

@Composable
internal fun adaptiveGlassInk(): Color =
    LocalGlassContentColor.current ?: MaterialTheme.colorScheme.onSurface
