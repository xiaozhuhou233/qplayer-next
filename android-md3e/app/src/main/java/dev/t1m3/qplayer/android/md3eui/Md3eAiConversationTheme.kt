package dev.t1m3.qplayer.android.md3eui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import dev.t1m3.qplayer.android.ui.IosAwareIconButton
import dev.t1m3.qplayer.android.ui.IosAwareButton
import dev.t1m3.qplayer.android.ui.IosLiquidGlass

internal enum class AiConversationDesign { Sonic, Material, Claude, Ios }

@Immutable
internal data class AiFluidPalette(val base: Color, val first: Color, val second: Color,
    val third: Color, val fourth: Color, val travel: Float = 1f)

@Immutable
internal data class AiConversationAppearance(
    val design: AiConversationDesign, val dark: Boolean,
    val ink: Color, val muted: Color, val paper: Color,
    val user: Color, val onUser: Color, val accent: Color, val onAccent: Color,
    val border: Color, val shape: RoundedCornerShape, val fluid: AiFluidPalette,
)

internal val LocalAiConversationAppearance = staticCompositionLocalOf<AiConversationAppearance> {
    error("AI conversation appearance must be provided by its page")
}
internal val LocalAiConversationBackdrop = staticCompositionLocalOf<Backdrop?> { null }

@Composable
internal fun aiConversationAppearance(): AiConversationAppearance {
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < .5f
    val design = when {
        LocalSonicDesign.current -> AiConversationDesign.Sonic
        LocalIosDesign.current -> AiConversationDesign.Ios
        LocalClaudeDesign.current -> AiConversationDesign.Claude
        else -> AiConversationDesign.Material
    }
    return when (design) {
        AiConversationDesign.Sonic -> AiConversationAppearance(design, dark,
            if (dark) Color(0xFFF1EFFC) else Color(0xFF28233D),
            if (dark) Color(0xFFBCB8CD) else Color(0xFF625C76),
            if (dark) Color(0xE62B2A40) else Color.White.copy(alpha = .87f),
            if (dark) Color(0xFF48405E) else Color(0xFFDFD8F3),
            if (dark) Color(0xFFF1EFFC) else Color(0xFF28233D),
            if (dark) Color(0xFFB6E4EE) else Color(0xFFB4E6F6), Color(0xFF243F4A),
            Color.Transparent, RoundedCornerShape(24.dp),
            AiFluidPalette(if (dark) Color(0xFF141624) else Color(0xFFE9F1FE),
                if (dark) Color(0xFF383057) else Color(0xFFDAC7FB),
                if (dark) Color(0xFF20454D) else Color(0xFFAEE8F5),
                if (dark) Color(0xFF4F334C) else Color(0xFFF8DBEB),
                if (dark) Color(0x553C4C78) else Color(0xAAEBEDFF)))
        AiConversationDesign.Claude -> AiConversationAppearance(design, dark,
            colors.onSurface, colors.onSurfaceVariant, colors.surfaceContainerLow,
            colors.surfaceContainerHighest, colors.onSurface, colors.primary, colors.onPrimary,
            colors.outlineVariant, RoundedCornerShape(20.dp),
            AiFluidPalette(colors.background, colors.primary.copy(alpha = .09f),
                colors.tertiary.copy(alpha = .045f), colors.primary.copy(alpha = .035f),
                Color.Transparent, .28f))
        AiConversationDesign.Ios -> AiConversationAppearance(design, dark,
            colors.onSurface, colors.onSurfaceVariant, colors.surface,
            Color(0xFF0067D9), Color.White,
            if (dark) Color(0xFF69B3FF) else Color(0xFF0067D9),
            if (dark) Color(0xFF001C39) else Color.White,
            colors.outlineVariant.copy(alpha = .55f), RoundedCornerShape(26.dp),
            AiFluidPalette(colors.surfaceContainerLow, colors.primary.copy(alpha = .13f),
                colors.primary.copy(alpha = .055f), colors.surface.copy(alpha = .7f),
                colors.surface.copy(alpha = .5f), .55f))
        AiConversationDesign.Material -> AiConversationAppearance(design, dark,
            colors.onSurface, colors.onSurfaceVariant, colors.surfaceContainerLow,
            colors.primaryContainer, colors.onPrimaryContainer, colors.primary, colors.onPrimary,
            Color.Transparent, RoundedCornerShape(24.dp),
            AiFluidPalette(colors.surface, colors.primaryContainer.copy(alpha = .65f),
                colors.secondaryContainer.copy(alpha = .45f), colors.tertiaryContainer.copy(alpha = .32f),
                colors.surfaceContainerLowest.copy(alpha = .6f), .45f))
    }
}

/** Glass samples only the separate background layer, never this panel or its controls. */
@Composable
internal fun AiConversationPanel(modifier: Modifier = Modifier, glass: Boolean = false,
    content: @Composable () -> Unit) {
    val appearance = LocalAiConversationAppearance.current
    val backdrop = LocalAiConversationBackdrop.current
    if (appearance.design == AiConversationDesign.Ios && glass && backdrop != null) {
        IosLiquidGlass(backdrop, appearance.dark, modifier, shape = appearance.shape,
            lite = Md3eLowSpecMode.current) {
            CompositionLocalProvider(LocalContentColor provides appearance.ink, content = content)
        }
    } else Surface(modifier, shape = appearance.shape, color = appearance.paper,
        contentColor = appearance.ink,
        border = if (appearance.design == AiConversationDesign.Claude) BorderStroke(1.dp, appearance.border) else null,
        content = content)
}

@Composable
internal fun AiConversationIconButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    val appearance = LocalAiConversationAppearance.current
    if (appearance.design == AiConversationDesign.Ios) IosAwareIconButton(onClick, content = content)
    else FilledTonalIconButton(onClick, shape = if (appearance.design == AiConversationDesign.Claude)
        RoundedCornerShape(16.dp) else CircleShape,
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = appearance.paper, contentColor = appearance.ink), content = content)
}

@Composable
internal fun AiConversationSuggestion(text: String, onClick: () -> Unit) {
    val appearance = LocalAiConversationAppearance.current
    SuggestionChip(onClick, label = { Text(text, color = appearance.ink) },
        shape = if (appearance.design == AiConversationDesign.Sonic) CircleShape else appearance.shape,
        colors = SuggestionChipDefaults.suggestionChipColors(containerColor = if (appearance.design == AiConversationDesign.Sonic)
            appearance.paper.copy(alpha = .55f) else appearance.paper),
        border = if (appearance.design == AiConversationDesign.Claude || appearance.design == AiConversationDesign.Ios)
            BorderStroke(1.dp, appearance.border) else null)
}

@Composable
internal fun AiConversationLoading(modifier: Modifier = Modifier) {
    val appearance = LocalAiConversationAppearance.current
    if (appearance.design == AiConversationDesign.Ios) {
        Box(modifier, contentAlignment = androidx.compose.ui.Alignment.Center) {
            CircularProgressIndicator(Modifier.size(20.dp), color = appearance.muted, strokeWidth = 2.dp)
        }
    } else Md3eLoadingIndicator(modifier, color = if (appearance.design == AiConversationDesign.Sonic)
        (if (appearance.dark) Color(0xFFCFBCFF) else Color(0xFF7661B3)) else appearance.accent)
}

@Composable
internal fun AiConversationNewButton(onClick: () -> Unit, enabled: Boolean, modifier: Modifier,
    content: @Composable RowScope.() -> Unit) {
    val appearance = LocalAiConversationAppearance.current
    if (appearance.design == AiConversationDesign.Ios) IosAwareButton(onClick, modifier, enabled, content)
    else FilledTonalButton(onClick, modifier, enabled,
        shape = if (appearance.design == AiConversationDesign.Sonic) CircleShape else appearance.shape,
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = if (appearance.design == AiConversationDesign.Claude) appearance.user
                else MaterialTheme.colorScheme.secondaryContainer,
            contentColor = appearance.ink), content = content)
}
