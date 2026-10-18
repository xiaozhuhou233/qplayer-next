// MusicOne, MIT, Copyright 2026 ShuyunR.
package dev.t1m3.qplayer.android.md3eui
import androidx.compose.runtime.*
import androidx.compose.animation.core.AnimationSpec

@Composable
internal fun rememberPageMotion(initialOpen: Boolean = false, enterMillis: Int = 320, exitMillis: Int = 280,
    exitAnimation: AnimationSpec<Float>? = null,
    enterAnimation: AnimationSpec<Float>? = null,
    dragAnimation: AnimationSpec<Float>? = null,
    immediateGestureHandoff: Boolean = false): PageMotion {
    val scope = rememberCoroutineScope()
    return remember { PageMotion(scope, initialOpen, enterMillis, exitMillis, exitAnimation, enterAnimation,
        dragAnimation, immediateGestureHandoff) }
}

internal val LocalPlaylistMotion = staticCompositionLocalOf<PageMotion?> { null }
internal val LocalPlayerMotion = staticCompositionLocalOf<PageMotion?> { null }
