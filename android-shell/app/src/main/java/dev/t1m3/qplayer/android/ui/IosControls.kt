package dev.t1m3.qplayer.android.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import com.kyant.backdrop.catalog.components.LiquidButton
import com.kyant.backdrop.catalog.components.LiquidSlider
import com.kyant.backdrop.catalog.components.LiquidToggle
import kotlin.math.roundToInt

// PlayerDetailScreen opts out, including its queue/lyric controls.
internal val LocalIosControls = staticCompositionLocalOf { true }

@Composable
private fun useIosControls() = LocalIosDesign.current && LocalIosControls.current

@Composable
private fun controlBackdrop(): com.kyant.backdrop.Backdrop {
    // Sample the dialog's exported material, which excludes its foreground
    // controls and therefore cannot capture a button into its own source.
    LocalIosDialogGlassBackdrop.current?.let { return it }
    LocalPlayerGlassBackdrop.current?.let { return it }
    LocalIosTopBarBackdrop.current?.let { return it }
    val color = MaterialTheme.colorScheme.surfaceContainer
    // Dialog windows have a separate root and must not sample the Activity layer.
    return rememberCanvasBackdrop { drawRect(color) }
}

@Composable
internal fun IosAwareButton(onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    if (LocalIosDialogAction.current != null) { DialogAction(onClick, modifier, enabled, content); return }
    if (!useIosControls()) { Button(onClick, modifier, enabled, content = content); return }
    GlassAction(onClick, modifier, enabled, true, content)
}

/** Neutral white playback capsule; deliberately independent of the theme accent. */
@Composable
internal fun IosNeutralButton(onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, contentColor: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color(0xFF18181A),
    content: @Composable RowScope.() -> Unit) {
    val dark = LocalGlassDark.current
    val ink = (if (dark) androidx.compose.ui.graphics.Color.White else androidx.compose.ui.graphics.Color(0xFF18181A))
        .copy(alpha = if (enabled) 1f else .4f)
    val view = LocalView.current
    LiquidButton(onClick = {
        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        onClick()
    }, backdrop = controlBackdrop(), modifier = modifier, enabled = enabled,
        isInteractive = enabled, contentColor = ink,
        surfaceColor = iosGlassSurface(dark, LocalGlassTint.current),
        content = content)
}

@Composable
internal fun IosAwareOutlinedButton(onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    if (LocalIosDialogAction.current != null) { DialogAction(onClick, modifier, enabled, content); return }
    if (!useIosControls()) { OutlinedButton(onClick, modifier, enabled, content = content); return }
    GlassAction(onClick, modifier, enabled, false, content)
}

@Composable
private fun GlassAction(onClick: () -> Unit, modifier: Modifier, enabled: Boolean,
    filled: Boolean, content: @Composable RowScope.() -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val view = LocalView.current
    CompositionLocalProvider(LocalContentColor provides
        (if (filled) scheme.onPrimary else scheme.primary).copy(alpha = if (enabled) 1f else 0.4f)) {
        LiquidButton(onClick = {
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            onClick()
        }, backdrop = controlBackdrop(), modifier = modifier,
            enabled = enabled, isInteractive = enabled,
            contentColor = (if (filled) scheme.onPrimary else scheme.primary)
                .copy(alpha = if (enabled) 1f else 0.4f),
            tint = if (filled) scheme.primary else androidx.compose.ui.graphics.Color.Unspecified,
            surfaceColor = if (filled) androidx.compose.ui.graphics.Color.Unspecified
            else if (LocalRestoredIosDialogGlass.current) androidx.compose.ui.graphics.Color.Transparent
            else iosGlassSurface(
                scheme.background.luminance() < 0.5f,
                LocalGlassTint.current,
                LocalGlassLuminance.current
            ),
            content = content)
    }
}

@Composable
internal fun IosAwareTextButton(onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    if (LocalIosDialogAction.current != null) { DialogAction(onClick, modifier, enabled, content); return }
    if (!useIosControls()) { TextButton(onClick, modifier, enabled, content = content); return }
    GlassAction(onClick, modifier, enabled, false, content)
}

/** Reference dialog actions are equal-width 48dp capsules, not MD text actions. */
@Composable
private fun DialogAction(onClick: () -> Unit, modifier: Modifier, enabled: Boolean,
    content: @Composable RowScope.() -> Unit) {
    // Keep dialog actions on the same Backdrop node as every other iOS control.
    // The old Button + graphicsLayer path transformed the content after the
    // surface had been laid out, which made its icon/text and shadow drift apart.
    GlassAction(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
        enabled = enabled,
        filled = LocalIosDialogAction.current == IosDialogAction.CONFIRM,
        content = content,
    )
}

@Composable
internal fun IosAwareIconButton(onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, content: @Composable () -> Unit) {
    if (!useIosControls()) { IconButton(onClick, modifier, enabled, content = content); return }
    val backdrop = controlBackdrop()
    val scope = rememberCoroutineScope()
    val highlight = remember(scope) { com.kyant.backdrop.catalog.utils.InteractiveHighlight(scope) }
    DisposableEffect(highlight) { onDispose { highlight.cancel() } }
    val view = LocalView.current
    IosLiquidGlass(
        backdrop = backdrop,
        dark = MaterialTheme.colorScheme.background.luminance() < 0.5f,
        interaction = highlight,
        modifier = modifier.size(48.dp).clickable(
            enabled = enabled,
            interactionSource = null,
            indication = null,
            role = Role.Button,
        ) {
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            onClick()
        },
    ) {
        CompositionLocalProvider(LocalContentColor provides adaptiveGlassInk()
            .copy(alpha = if (enabled) 1f else 0.38f), content = content)
    }
}

@Composable
internal fun IosAwareSlider(value: Float, onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true, valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0, onValueChangeFinished: (() -> Unit)? = null) {
    if (!useIosControls() || !enabled || valueRange.start >= valueRange.endInclusive) {
        Slider(value, onValueChange, modifier, enabled, valueRange = valueRange, steps = steps,
            onValueChangeFinished = onValueChangeFinished)
        return
    }
    val view = LocalView.current
    val snap: (Float) -> Float = { proposed ->
        val clamped = proposed.coerceIn(valueRange)
        if (steps <= 0) clamped else {
            val increment = (valueRange.endInclusive - valueRange.start) / (steps + 1)
            (valueRange.start + ((clamped - valueRange.start) / increment).roundToInt() * increment).coerceIn(valueRange)
        }
    }
    LiquidSlider(value = { value }, onValueChange = { onValueChange(snap(it)) },
        valueRange = valueRange, visibilityThreshold = (valueRange.endInclusive - valueRange.start) * 0.0001f,
        backdrop = controlBackdrop(), modifier = modifier,
        dark = MaterialTheme.colorScheme.background.luminance() < 0.5f, steps = steps,
        onValueChangeFinished = {
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            onValueChangeFinished?.invoke()
        })
}

@Composable
internal fun IosAwareSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier) {
    if (!useIosControls() || onCheckedChange == null) { Switch(checked, onCheckedChange, modifier); return }
    val view = LocalView.current
    Box(modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
        LiquidToggle(selected = { checked }, onSelect = {
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            onCheckedChange(it)
        }, backdrop = controlBackdrop(), dark = MaterialTheme.colorScheme.background.luminance() < 0.5f)
    }
}
