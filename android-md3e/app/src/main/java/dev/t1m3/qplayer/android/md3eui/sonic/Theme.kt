package dev.t1m3.qplayer.android.md3eui.sonic

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

val SonicTonalLightColorScheme = lightColorScheme(
  primary = Primary,
  onPrimary = OnPrimary,
  primaryContainer = PrimaryContainer,
  onPrimaryContainer = OnPrimaryContainer,
  secondary = Secondary,
  onSecondary = OnSecondary,
  secondaryContainer = SecondaryContainer,
  onSecondaryContainer = OnSecondaryContainer,
  tertiary = Tertiary,
  onTertiary = OnTertiary,
  tertiaryContainer = TertiaryContainer,
  onTertiaryContainer = OnTertiaryContainer,
  background = Surface,
  onBackground = OnSurface,
  surface = Surface,
  onSurface = OnSurface,
  surfaceVariant = SurfaceContainerHigh,
  onSurfaceVariant = OnSurfaceVariant,
  outline = Outline,
  outlineVariant = OutlineVariant,
  error = Error,
  onError = OnError,
  errorContainer = ErrorContainer,
  onErrorContainer = OnErrorContainer,
  inverseSurface = InverseSurface,
  inverseOnSurface = InverseOnSurface
)

val NocturneVelvetDarkColorScheme = darkColorScheme(
  primary = NocturnePrimary,
  onPrimary = NocturneOnPrimary,
  primaryContainer = NocturnePrimaryContainer,
  onPrimaryContainer = NocturneOnPrimary,
  secondary = NocturneSecondary,
  onSecondary = NocturneOnSecondary,
  secondaryContainer = NocturneSecondaryContainer,
  onSecondaryContainer = NocturneSecondary,
  tertiary = NocturneTertiary,
  onTertiary = NocturneOnPrimary,
  background = NocturneSurface,
  onBackground = NocturneOnSurface,
  surface = NocturneSurface,
  onSurface = NocturneOnSurface,
  surfaceVariant = NocturneSurfaceHigh,
  onSurfaceVariant = NocturneOnSurfaceVariant,
  outline = NocturneMuted,
  outlineVariant = NocturneGlassBorder
)

@Composable
fun SonicTonalTheme(
  isDarkTheme: Boolean = false,
  content: @Composable () -> Unit
) {
  val colorScheme = if (isDarkTheme) NocturneVelvetDarkColorScheme else SonicTonalLightColorScheme
  MaterialTheme(
    colorScheme = colorScheme,
    typography = AppTypography,
    content = content
  )
}
