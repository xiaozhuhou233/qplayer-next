@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package dev.t1m3.qplayer.android.md3eui

import android.content.Context
import android.graphics.Paint
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import kotlin.math.roundToInt

internal enum class SonicDisplayFace { Kablammo, Playpen, CherryBomb, Arizonia, Lavishly, Unkempt }
internal val LocalSonicDisplayFonts = compositionLocalOf { true }

/** A bounded, process-owned cache: variable outlines are resolved before any animated heading uses them. */
internal object SonicDisplayFonts {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false
    lateinit var resolver: FontFamily.Resolver
        private set
    var ready by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set
    private val kablammo = (0..12).map { step ->
        FontFamily(Font(R.font.kablammo_variable, variationSettings =
            FontVariation.Settings(FontVariation.Setting("MORF", step * 5f))))
    }
    private val playpen = (0..8).map { step ->
        FontFamily(Font(R.font.playpen_sans_variable, variationSettings =
            FontVariation.Settings(FontVariation.weight(400 + step * 50))))
    }
    private val staticFaces = mapOf(
        SonicDisplayFace.CherryBomb to FontFamily(Font(R.font.cherry_bomb_one)),
        SonicDisplayFace.Arizonia to FontFamily(Font(R.font.arizonia)),
        SonicDisplayFace.Lavishly to FontFamily(Font(R.font.lavishly_yours)),
        SonicDisplayFace.Unkempt to FontFamily(Font(R.font.unkempt_bold)),
    )

    @Synchronized fun warmUp(context: Context) {
        if (started) return
        started = true
        resolver = createFontFamilyResolver(context.applicationContext)
        scope.launch {
            try {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 48f }
                (kablammo + playpen + staticFaces.values).forEach { family ->
                    val native = resolver.resolve(family, fontSynthesis = FontSynthesis.None).value
                    if (native is android.graphics.Typeface) {
                        paint.typeface = native
                        paint.measureText("QPlayer Explore Library Artist Spotlight")
                    }
                }
            } catch (_: Exception) { withContext(Dispatchers.Main) { failed = true } }
            finally { withContext(Dispatchers.Main) { ready = true } }
        }
    }

    fun variants(face: SonicDisplayFace): List<FontFamily> = if (failed) listOf(FontFamily.SansSerif)
        else when (face) {
            SonicDisplayFace.Kablammo -> kablammo
            SonicDisplayFace.Playpen -> playpen
            else -> listOf(staticFaces.getValue(face))
        }
}

internal fun sonicArtistFace(name: String): SonicDisplayFace {
    if (name.length > 28 || name.none { it in 'A'..'Z' || it in 'a'..'z' }) return SonicDisplayFace.Playpen
    return when ((name.hashCode() and Int.MAX_VALUE) % 4) {
        0 -> SonicDisplayFace.Kablammo
        1 -> SonicDisplayFace.Lavishly
        2 -> SonicDisplayFace.Arizonia
        else -> SonicDisplayFace.Unkempt
    }
}

@Composable
internal fun SonicDisplayText(text: String, face: SonicDisplayFace, modifier: Modifier = Modifier,
    maxSize: TextUnit = 30.sp, minSize: TextUnit = 18.sp, maxLines: Int = 1,
    color: Color = MaterialTheme.colorScheme.onSurface, centered: Boolean = false) {
    val decorative = LocalSonicDisplayFonts.current
    if (decorative && !SonicDisplayFonts.ready) {
        // Keep a quiet reserved slot on cold start; do not flash a substitute display font.
        val reservedHeight = with(LocalDensity.current) { maxSize.toDp() * 1.55f }
        Spacer(modifier.height(reservedHeight))
        return
    }
    val resolver = if (decorative) SonicDisplayFonts.resolver else LocalFontFamilyResolver.current
    CompositionLocalProvider(LocalFontFamilyResolver provides resolver) {
        SonicLoadedDisplayText(text, face, modifier, maxSize, minSize, maxLines, color, centered, decorative)
    }
}

@Composable
private fun SonicLoadedDisplayText(text: String, face: SonicDisplayFace, modifier: Modifier,
    maxSize: TextUnit, minSize: TextUnit, maxLines: Int, color: Color, centered: Boolean, decorative: Boolean) {
    val density = LocalDensity.current
    val fonts = if (decorative) SonicDisplayFonts.variants(face)
        else listOf(MaterialTheme.typography.titleLarge.fontFamily ?: FontFamily.Default)
    val animate = fonts.size > 1 && LocalMd3eRenderingActive.current && !LocalMd3eMotionActive.current &&
        !Md3eLowSpecMode.current && android.animation.ValueAnimator.areAnimatorsEnabled()
    val sample = if (animate) {
        val transition = rememberInfiniteTransition(label = "display_axis")
        val axis = transition.animateFloat(0f, (fonts.size - 1).toFloat(),
            infiniteRepeatable(tween(if (face == SonicDisplayFace.Kablammo) 3800 else 2800,
                easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "display_variation")
        val step by remember(axis, fonts) { derivedStateOf { axis.value.roundToInt().coerceIn(fonts.indices) } }
        step
    } else fonts.lastIndex / 2
    val measurer = rememberTextMeasurer(cacheSize = 128)
    BoxWithConstraints(modifier.padding(horizontal = 5.dp, vertical = 3.dp),
        contentAlignment = if (centered) Alignment.Center else Alignment.CenterStart) {
        val widthPx = with(density) { maxWidth.roundToPx().coerceAtLeast(1) }
        val fit = remember(text, face, widthPx, density.density, density.fontScale, maxSize, minSize, maxLines, fonts) {
            var size = maxSize.value
            var height = 0
            while (true) {
                var overflow = false
                height = 0
                fonts.forEach { family ->
                    val result = measurer.measure(text, style = displayStyle(family, size),
                        maxLines = maxLines, softWrap = maxLines > 1, overflow = TextOverflow.Ellipsis,
                        constraints = Constraints(maxWidth = widthPx))
                    overflow = overflow || result.hasVisualOverflow
                    height = maxOf(height, result.size.height)
                }
                if (!overflow || size <= minSize.value) break
                size = (size - 1f).coerceAtLeast(minSize.value)
            }
            size to height
        }
        val height = with(density) { fit.second.toDp() }
        // All axis samples use the same box and size: no clipping or toolbar jumps during morphing.
        Text(text, Modifier.fillMaxWidth().height(height), style = displayStyle(fonts[sample], fit.first),
            color = color, maxLines = maxLines, softWrap = maxLines > 1, overflow = TextOverflow.Ellipsis,
            textAlign = if (centered) TextAlign.Center else TextAlign.Start)
    }
}

private fun displayStyle(family: FontFamily, size: Float) = TextStyle(
    fontFamily = family, fontWeight = FontWeight.Normal, fontSynthesis = FontSynthesis.None,
    fontSize = size.sp, lineHeight = (size * 1.45f).sp,
    platformStyle = PlatformTextStyle(includeFontPadding = true),
)
