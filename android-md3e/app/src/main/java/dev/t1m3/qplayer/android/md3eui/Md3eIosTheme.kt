// Adapted from MusicOne (MIT), Copyright (c) 2026 ShuyunR.
// See assets/licenses/musicone-LICENSE.txt.
package dev.t1m3.qplayer.android.md3eui

import androidx.compose.material3.*
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

internal val LocalIosDesign = staticCompositionLocalOf { false }

internal fun iosColorScheme(dark: Boolean): ColorScheme =
    dev.t1m3.qplayer.android.ui.iosDesignColorScheme(dark)
