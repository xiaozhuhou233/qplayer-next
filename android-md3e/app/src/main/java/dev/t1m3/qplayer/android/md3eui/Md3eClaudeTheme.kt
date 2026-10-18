package dev.t1m3.qplayer.android.md3eui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp


internal val LocalClaudeDesign = staticCompositionLocalOf { false }

// The legacy Claude fonts apply only when Claude mode is enabled.
internal val ClaudeSans = FontFamily(
    Font(R.font.claude_sans_regular),
    Font(R.font.claude_sans_medium, FontWeight.Medium),
    Font(R.font.claude_sans_semibold, FontWeight.SemiBold),
    Font(R.font.claude_sans_bold, FontWeight.Bold),
)
internal val ClaudeSerif = FontFamily(
    Font(R.font.claude_serif_regular),
    Font(R.font.claude_serif_semibold, FontWeight.SemiBold),
    Font(R.font.claude_serif_bold, FontWeight.Bold),
    Font(R.font.claude_serif_italic, style = FontStyle.Italic),
)
internal val ClaudeMono = FontFamily(Font(R.font.claude_mono_regular))

// Latin handwriting is reserved for recommendation badges.
internal val RecommendationHandwriting = FontFamily(Font(R.font.recommendation_handwriting))

internal val ClaudeTypography = Typography(
    displayLarge = TextStyle(fontFamily = ClaudeSerif, fontWeight = FontWeight.Normal, fontSize = 48.sp, lineHeight = 56.sp),
    displayMedium = TextStyle(fontFamily = ClaudeSerif, fontWeight = FontWeight.Normal, fontSize = 40.sp, lineHeight = 48.sp),
    displaySmall = TextStyle(fontFamily = ClaudeSerif, fontWeight = FontWeight.Normal, fontSize = 34.sp, lineHeight = 42.sp),
    headlineLarge = TextStyle(fontFamily = ClaudeSerif, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 38.sp),
    headlineMedium = TextStyle(fontFamily = ClaudeSerif, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 34.sp),
    headlineSmall = TextStyle(fontFamily = ClaudeSerif, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 30.sp),
    titleLarge = TextStyle(fontFamily = ClaudeSerif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = ClaudeSans, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = ClaudeSans, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = ClaudeSans, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = ClaudeSans, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = ClaudeSans, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = ClaudeSans, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = ClaudeMono, fontWeight = FontWeight.Normal, fontSize = 11.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = ClaudeMono, fontWeight = FontWeight.Normal, fontSize = 10.sp, lineHeight = 14.sp),
)

internal val ClaudeShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp), small = RoundedCornerShape(20.dp),
    medium = RoundedCornerShape(24.dp), large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

internal fun claudeColorScheme(dark: Boolean): ColorScheme {
    val paper = if (dark) Color(0xFF1F1E1B) else Color(0xFFFAF9F5)
    val surface = if (dark) Color(0xFF292823) else Color(0xFFF4F2EC)
    val card = if (dark) Color(0xFF302E29) else Color.White
    val ink = if (dark) Color(0xFFF1EEE5) else Color(0xFF141413)
    val muted = if (dark) Color(0xFFBAB5A9) else Color(0xFF6B6960)
    val border = if (dark) Color(0xFF464239) else Color(0xFFE8E6DC)
    val accent = if (dark) Color(0xFFE99B7D) else Color(0xFFD97757)
    val soft = if (dark) Color(0xFF493027) else Color(0xFFFBF3EE)
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent, onPrimary = Color(0xFF201710), primaryContainer = soft,
        onPrimaryContainer = if (dark) Color(0xFFFFCDBB) else Color(0xFF8B402B),
        secondary = muted, onSecondary = paper, secondaryContainer = surface, onSecondaryContainer = ink,
        tertiary = if (dark) Color(0xFFB5C59B) else Color(0xFF637547),
        onTertiary = paper, tertiaryContainer = surface, onTertiaryContainer = ink,
        background = paper, onBackground = ink, surface = paper, onSurface = ink,
        surfaceVariant = surface, onSurfaceVariant = muted, surfaceTint = Color.Transparent,
        surfaceContainerLowest = paper, surfaceContainerLow = card,
        surfaceContainer = surface, surfaceContainerHigh = surface,
        surfaceContainerHighest = if (dark) Color(0xFF38352F) else Color(0xFFEDE7DF),
        surfaceBright = card, surfaceDim = surface,
        outline = if (dark) Color(0xFF827B6F) else Color(0xFFA09E96), outlineVariant = border,
        inverseSurface = ink, inverseOnSurface = paper, inversePrimary = accent,
        primaryFixed = Color(0xFFFFDBC9), primaryFixedDim = Color(0xFFF2B183),
        onPrimaryFixed = Color(0xFF482719), onPrimaryFixedVariant = Color(0xFF713C27),
        secondaryFixed = Color(0xFFE8E1D5), secondaryFixedDim = Color(0xFFCCC3B5),
        onSecondaryFixed = Color(0xFF292823), onSecondaryFixedVariant = Color(0xFF514C42),
        tertiaryFixed = Color(0xFFD8E6BF), tertiaryFixedDim = Color(0xFFB5C59B),
        onTertiaryFixed = Color(0xFF253018), onTertiaryFixedVariant = Color(0xFF455337),
    )
}
