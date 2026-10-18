// Dialog material adapted from AndroidLiquidGlass-kmp DialogContent.kt.
// Original Copyright 2025 Kyant, Apache-2.0 (assets/licenses/Backdrop-Apache-2.0.txt).
package dev.t1m3.qplayer.android.ui

import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog as MaterialAlertDialog
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.isRenderEffectSupported

internal enum class IosDialogAction { CONFIRM, DISMISS }
internal val LocalIosDialogAction = staticCompositionLocalOf<IosDialogAction?> { null }
internal val LocalIosDialogGlassBackdrop = staticCompositionLocalOf<Backdrop?> { null }

@Stable
internal class DialogBackdropState(val backdrop: LayerBackdrop) {
    var clients by mutableIntStateOf(0)
    var movingClients by mutableIntStateOf(0)
}
private val LocalDialogBackdrop = staticCompositionLocalOf<DialogBackdropState?> { null }

/** Record only while a dialog is present. Native Dialog windows are not recorded. */
@Composable
internal fun QPlayerDialogBackdropHost(enabled: Boolean, content: @Composable () -> Unit) {
    val layer = rememberLayerBackdrop()
    val state = remember(layer) { DialogBackdropState(layer) }
    val tilt = if (enabled) rememberBiliPaiDeviceTilt() else null
    CompositionLocalProvider(LocalDialogBackdrop provides state, LocalBiliPaiDeviceTilt provides tilt,
        LocalGlassMotionActive provides (LocalGlassMotionActive.current || state.movingClients > 0)) {
        Box(Modifier.fillMaxSize().then(
            if (enabled && state.clients > 0) Modifier.layerBackdrop(layer) else Modifier
        )) { content() }
    }
}

/** Common entry for every app-owned dialog; MD mode keeps the framework dialog. */
@Composable
internal fun IosAwareAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    scrollableContent: Boolean = true,
    showActions: Boolean = true,
) {
    if (!LocalIosDesign.current) {
        MaterialAlertDialog(onDismissRequest = onDismissRequest, confirmButton = confirmButton,
            modifier = modifier, dismissButton = dismissButton, icon = icon, title = title, text = text)
        return
    }
    val inherited = MaterialTheme.colorScheme
    val light = inherited.onBackground.luminance() < 0.5f
    // Layout/scrim remain the dialog's; its glass and ink use the adaptive APK.
    val plate = if (light) Color(0xFFFAFAFA) else Color(0xFF121212)
    val glassPlate = if (light) Color.White else Color(0xFF1C1C1C)
    val renderEffects = isRenderEffectSupported()
    val dim = Color.Black.copy(alpha = if (light) 0.36f else 0.64f)
    val source = LocalDialogBackdrop.current
    val fallback = rememberCanvasBackdrop { drawRect(inherited.background) }
    val backdrop: Backdrop = source?.backdrop ?: fallback
    DisposableEffect(source) {
        if (source != null) source.clients++
        onDispose { if (source != null) source.clients = (source.clients - 1).coerceAtLeast(0) }
    }
    val refraction = LocalGlassRefraction.current
    Dialog(onDismissRequest, properties = DialogProperties(
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false
    )) {
        SystemBarAppearance(dark = !light)
        // Create the sampler in the dialog's own window, not its Activity parent.
        val bottomSheetBackdrop = rememberLayerBackdrop()
        val reveal = remember { Animatable(0f) }
        var entering by remember { mutableStateOf(true) }
        DisposableEffect(source, entering) {
            // Pause readbacks in the underlying dock too, not just the dialog.
            val registered = source != null && entering
            if (registered) source?.let { it.movingClients++ }
            onDispose {
                if (registered) source?.let { it.movingClients = (it.movingClients - 1).coerceAtLeast(0) }
            }
        }
        LaunchedEffect(Unit) {
            try {
                // Let the initial layout/material recording precede the timed motion.
                withFrameNanos { }
                reveal.animateTo(1f, tween(300, easing = FastOutSlowInEasing))
            } finally {
                entering = false
            }
        }
        // Match ink to the tinted plate, not a changing MV/cover behind the dialog.
        val ink = if (light) Color(0xFF161618) else Color(0xFFF5F5F7)
        val dialogScheme = iosDesignColorScheme(!light).copy(
            background = plate, surface = plate, surfaceContainerHigh = plate,
            onBackground = ink, onSurface = ink, onSurfaceVariant = ink,
        )
        val view = LocalView.current
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                window.setDimAmount(0f) // The reference uses a coloured scrim below.
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }
        }
        MaterialTheme(colorScheme = dialogScheme) {
            CompositionLocalProvider(LocalContentColor provides ink, LocalIosControls provides true,
                LocalGlassContentColor provides ink, LocalGlassDark provides !light,
                LocalIosDialogGlassBackdrop provides bottomSheetBackdrop,
                LocalRestoredIosDialogGlass provides false,
                LocalGlassMotionActive provides (LocalGlassMotionActive.current || entering),
                LocalGlassTint provides plate, LocalGlassLuminance provides plate.luminance()) {
                // Read animation state only in draw/layer scopes: reading it in
                // background() rebuilt the dialog and its effect lambdas every frame.
                Box(Modifier.fillMaxSize().drawBehind {
                    drawRect(dim.copy(alpha = dim.alpha * reveal.value))
                }.clickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null,
                    onClick = onDismissRequest
                ), contentAlignment = Alignment.Center) {
                    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
                        .imePadding().padding(horizontal = 24.dp, vertical = 16.dp),
                        contentAlignment = Alignment.Center) {
                        val bodyMaxHeight = (maxHeight - 176.dp).coerceAtLeast(48.dp)
                        val shape = remember { RoundedCornerShape(48.dp) }
                        Column(modifier.widthIn(max = 400.dp).fillMaxWidth().heightIn(max = maxHeight)
                            .graphicsLayer {
                                alpha = reveal.value
                                scaleX = .82f + .18f * reveal.value
                                scaleY = scaleX
                            }
                            .drawBackdrop(backdrop = backdrop, exportedBackdrop = bottomSheetBackdrop, shape = { shape }, effects = {
                                apiGlassEffects(plate.luminance(), refraction)
                            }, highlight = { RestoredIosDialogHighlight }, shadow = { RestoredIosDialogShadow },
                                innerShadow = null,
                                onDrawSurface = {
                                    drawRect(glassPlate.copy(alpha = if (renderEffects) .5f else .98f))
                                })
                            .clip(shape).clickable(interactionSource = remember { MutableInteractionSource() },
                                indication = null, onClick = {})) {
                            if (icon != null) Box(Modifier.fillMaxWidth().padding(top = 20.dp),
                                contentAlignment = Alignment.Center) { icon() }
                            if (title != null) Box(Modifier.fillMaxWidth().padding(28.dp, 24.dp, 28.dp, 12.dp)) {
                                ProvideTextStyle(MaterialTheme.typography.titleLarge.copy(
                                    color = ink, fontSize = 24.sp, fontWeight = FontWeight.SemiBold), title)
                            }
                            if (text != null) Box(Modifier.weight(1f, fill = false).heightIn(max = bodyMaxHeight)
                                .then(if (scrollableContent) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                                .padding(24.dp, 12.dp, 24.dp, 12.dp)) {
                                ProvideTextStyle(MaterialTheme.typography.bodyMedium.copy(
                                    color = ink, fontSize = 16.sp, lineHeight = 24.sp), text)
                            }
                            if (showActions) Row(Modifier.fillMaxWidth().padding(24.dp, 12.dp, 24.dp, 24.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                if (dismissButton != null) Box(Modifier.weight(1f)) {
                                    CompositionLocalProvider(LocalIosDialogAction provides IosDialogAction.DISMISS, content = dismissButton)
                                }
                                Box(Modifier.weight(1f)) {
                                    CompositionLocalProvider(LocalIosDialogAction provides IosDialogAction.CONFIRM, content = confirmButton)
                                }
                            } else Spacer(Modifier.height(20.dp))
                        }
                    }
                }
            }
        }
    }
}
