package dev.t1m3.qplayer.android.md3eui

/** Move the entire feather beyond the final ink before reaching full color. */
internal fun lyricHighlightStart(width: Float, progress: Float, feather: Float, glyphOffset: Float): Float =
    (width + feather) * progress.coerceIn(0f, 1f) - feather - glyphOffset
