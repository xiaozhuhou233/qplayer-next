// MusicOne entity anchors, reveal geometry and flying-cover handoff adapted to QPlayer routes.
package dev.t1m3.qplayer.android.md3eui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.*

internal class IosCoverIdentity(val key: String) : Modifier.Element
internal class IosCollectionTransition(private val factory: () -> PageMotion) {
    var motion by mutableStateOf(factory())
        private set
    val images = mutableStateMapOf<String, ImageBitmap>()
    val sources = mutableStateMapOf<String, MotionAnchor>()
    fun open(key: String) {
        val host = motion.hostBounds
        motion.cancel()
        motion = factory().apply {
            updateHost(host)
            coverKey = key
            sources.putAll(this@IosCollectionTransition.sources)
            beforeShownHandoff = ::settlePlaylistCoverHandoff
            request(true)
        }
    }
    fun close() { motion.sources.putAll(sources); motion.request(false) }
}
internal val LocalIosCollectionTransition = staticCompositionLocalOf<IosCollectionTransition?> { null }
private val LocalIosArtworkTarget = staticCompositionLocalOf { false }

@Composable
internal fun rememberIosCollectionTransition(): IosCollectionTransition {
    val scope = rememberCoroutineScope()
    return remember(scope) { IosCollectionTransition {
        PageMotion(scope, false, 420, 320, PlaylistReturnAnimation, PlaylistEnterAnimation,
            PlaylistGestureSettlementAnimation)
    } }
}

@Composable
internal fun Modifier.iosArtworkAnchor(key: String): Modifier {
    val transition = LocalIosCollectionTransition.current ?: return this
    val motion = transition.motion
    val target = LocalIosArtworkTarget.current
    val anchors = if (target) motion.targets else transition.sources
    val owned = remember(key, target) { arrayOfNulls<MotionAnchor>(1) }
    DisposableEffect(motion, key, target) { onDispose { if (anchors[key] === owned[0]) anchors.remove(key) } }
    return then(IosCoverIdentity(key)).onGloballyPositioned {
        val bounds = it.boundsInRoot()
        if (bounds.width >= it.size.width - 1 && bounds.height >= it.size.height - 1 && !bounds.isEmpty) {
            val anchor = MotionAnchor(bounds, if (target) 0f else 16f, bottomFade = if (target && key.startsWith("cover:playlist:")) 1f else 0f)
            owned[0] = anchor; anchors[key] = anchor
        } else if (anchors[key] === owned[0]) anchors.remove(key)
    }.drawWithContent {
        if (!(motion.coverKey == key && motion.moving && motion.hasSharedCover && transition.images[key] != null)) drawContent()
    }
}

private class IosCollectionReveal(private val motion: PageMotion) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val source = motion.sourceSnapshot[motion.coverKey]
        if (!motion.moving || !motion.hasSharedCover || source == null) return Outline.Rectangle(Rect(Offset.Zero, size))
        val bounds = motionRect(source.bounds, motion.hostBounds, motion.value).translate(-motion.hostBounds.topLeft)
        return Outline.Rounded(RoundRect(bounds, CornerRadius(with(density) { (source.corner * (1f - motion.value)).dp.toPx() })))
    }
}

@Composable
internal fun IosCollectionContainer(key: String?, detail: Boolean, modifier: Modifier, content: @Composable () -> Unit) {
    val transition = LocalIosCollectionTransition.current
    val motion = transition?.motion
    val shape = remember(motion) { motion?.let(::IosCollectionReveal) }
    CompositionLocalProvider(LocalIosArtworkTarget provides detail) {
        Box(modifier) {
            if (detail && motion != null && motion.mounted && key == motion.coverKey) {
                Box(Modifier.fillMaxSize().graphicsLayer {
                    this.shape = shape!!; clip = true
                    alpha = if (motion.phase == MotionPhase.PREPARING) 0f else contentReveal(motion.value)
                }) { content() }
            } else content()
        }
    }
}

@Composable
internal fun IosCollectionMotionHost(state: IosCollectionTransition, content: @Composable () -> Unit) {
    val motion = state.motion
    Box(Modifier.fillMaxSize().onGloballyPositioned { motion.updateHost(it.boundsInRoot()) }) {
        content()
        // Always mounted: ownership changes on the same draw frame as the real anchors.
        Canvas(Modifier.fillMaxSize()) {
            if (!motion.moving || !motion.hasSharedCover) return@Canvas
            val image = state.images[motion.coverKey] ?: return@Canvas
            val source = motion.sourceSnapshot[motion.coverKey] ?: return@Canvas
            val target = motion.targetSnapshot[motion.coverKey] ?: return@Canvas
            val bounds = motionRect(source.bounds, target.bounds, motion.value).translate(-motion.hostBounds.topLeft)
            val corner = motionLerp(source.corner, target.corner, motion.value).dp.toPx()
            val path = Path().apply { addRoundRect(RoundRect(bounds, CornerRadius(corner))) }
            clipPath(path) {
                val scale = maxOf(bounds.width / image.width, bounds.height / image.height)
                withTransform({ translate(bounds.center.x - image.width * scale / 2, bounds.center.y - image.height * scale / 2); scale(scale, scale, Offset.Zero) }) { drawImage(image) }
            }
        }
    }
}
