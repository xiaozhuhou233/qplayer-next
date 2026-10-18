package dev.t1m3.qplayer.android.md3eui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Only the Material glyphs used by phase one; avoids packaging the full icon catalog. */
internal object Md3eIcons {
    val PlayArrow = icon("Play", "M8,5v14l11,-7z")
    val Pause = icon("Pause", "M6,5h4v14H6zM14,5h4v14h-4z")
    val SkipNext = icon("Next", "M6,5v14l9,-7zM16,5h2v14h-2z")
    val SkipPrevious = icon("Previous", "M18,5v14l-9,-7zM6,5h2v14H6z")
    val MusicNote = icon("Music", "M12,3v10.55A4,4 0,1 0,14,17V7h4V3z")
    val AutoAwesome = icon("AutoAwesome", "M19,11l1.25,-2.75L23,7l-2.75,-1.25L19,3l-1.25,2.75L15,7l2.75,1.25zM9,14l1.8,-3.8L14.5,8.5l-3.7,-1.7L9,3L7.2,6.8L3.5,8.5l3.7,1.7zM19,15l-1.25,2.75L15,19l2.75,1.25L19,23l1.25,-2.75L23,19l-2.75,-1.25z")
    val Person = icon("Person", "M12,12a4,4 0,1 0,0,-8a4,4 0,0 0,0,8zM4,20v-2c0,-2.67 5.33,-4 8,-4s8,1.33 8,4v2z")
    val Video = icon("Video", "M4,5h11a2,2 0,0 1,2 2v2.5l4,-2.5v10l-4,-2.5V17a2,2 0,0 1,-2 2H4a2,2 0,0 1,-2,-2V7a2,2 0,0 1,2,-2z")
    val Headphones = icon("Headphones", "M12,3a9,9 0,0 0,-9,9v7a2,2 0,0 0,2,2h3v-8H5v-1a7,7 0,0 1,14,0v1h-3v8h3a2,2 0,0 0,2,-2v-7a9,9 0,0 0,-9,-9z")
    val Refresh = icon("Refresh", "M17.65,6.35A7.95,7.95 0,0 0,12,4a8,8 0,1 0,7.75,10h-2.09A6,6 0,1 1,16.24,7.76L13,11h7V4z")
    val CheckCircle = icon("Check", "M12,2a10,10 0,1 0,0,20a10,10 0,0 0,0,-20zM10,17l-5,-5l1.41,-1.41L10,14.17l7.59,-7.59L19,8z")
    val Home = icon("Home", "M12,3L2,12h3v9h5v-6h4v6h5v-9h3z")
    val Playlist = icon("Playlist", "M3,5h14v2H3zM3,9h14v2H3zM3,13h10v2H3zM17,12v5.2a2.5,2.5 0,1 0,2,2.45V14h3v-2z")
    val Library = icon("Library", "M4,4h16v16H4zM6,6v12h12V6zM10,8h5v2h-3v4.5a1.5,1.5 0,1 1,-2,-1.41z")
    val Search = icon("Search", "M9.5,3a6.5,6.5 0,1 0,4.04,11.6L19.94,21l1.42,-1.42l-6.4,-6.4A6.5,6.5 0,0 0,9.5,3zM9.5,5a4.5,4.5 0,1 1,0,9a4.5,4.5 0,0 1,0,-9z")
    val Settings = icon("Settings", "M19.43,12.98c.04,-.32 .07,-.65 .07,-.98s-.03,-.66 -.08,-.98l2.11,-1.65l-2,-3.46l-2.49,1a7.24,7.24 0,0 0,-1.7,-.99L15,3h-4l-.4,2.92c-.62,.24 -1.19,.57 -1.7,.99l-2.49,-1l-2,3.46l2.11,1.65a8,8 0,0 0,0,1.96l-2.11,1.65l2,3.46l2.49,-1c.51,.42 1.08,.75 1.7,.99L11,21h4l.4,-2.92a7.24,7.24 0,0 0,1.7,-.99l2.49,1l2,-3.46zM13,16a4,4 0,1 1,0,-8a4,4 0,0 1,0,8z")
    val Back = icon("Back", "M20,11H7.83l5.59,-5.59L12,4l-8,8l8,8l1.41,-1.41L7.83,13H20z")
    val ChevronRight = icon("ChevronRight", "M9,6l6,6l-6,6l-1.4,-1.4l4.6,-4.6l-4.6,-4.6z")
    val Close = icon("Close", "M18.3,5.7L12,12l6.3,6.3l-1.4,1.4L10.6,13.4L4.3,19.7l-1.4,-1.4L9.2,12L2.9,5.7l1.4,-1.4l6.3,6.3l6.3,-6.3z")
    val Delete = icon("Delete", "M6,7h12v13H6zM9,3h6l1,2h4v2H4V5h4z")
    val DragHandle = icon("DragHandle", "M4,9h16v2H4zM4,13h16v2H4z")
    val History = icon("History", "M13,3a9,9 0,1 1,-8.49,12H6.7A7,7 0,1 0,8,7.1L11,10H3V2l3.6,3.6A8.96,8.96 0,0 1,13,3zM12,7h2v5.4l3.3,2l-1,1.7L12,13z")

    private fun icon(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(addPathNodes(path), fill = SolidColor(Color.Black)).build()
}
