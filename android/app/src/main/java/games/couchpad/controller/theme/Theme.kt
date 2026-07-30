package games.couchpad.controller.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

private val DarkColors = darkColorScheme(
  primary = PrimaryDark,
  onPrimary = OnPrimaryDark,
  primaryContainer = PrimaryContainerDark,
  onPrimaryContainer = OnPrimaryContainerDark,
  secondaryContainer = SecondaryContainerDark,
  onSecondaryContainer = OnSecondaryContainerDark,
  surfaceVariant = SurfaceVariantDark,
  onSurfaceVariant = OnSurfaceVariantDark,
  outline = OutlineDark,
  outlineVariant = OutlineVariantDark,
  background = SurfaceDarkBase,
  surface = SurfaceDarkBase,
  surfaceDim = SurfaceDarkBase,
  surfaceBright = lerp(SurfaceDarkBase, Color.White, 0.20f),
  surfaceContainerLowest = lerp(SurfaceDarkBase, Color.Black, 0.35f),
  surfaceContainerLow = lerp(SurfaceDarkBase, Color.White, 0.035f),
  surfaceContainer = lerp(SurfaceDarkBase, Color.White, 0.055f),
  surfaceContainerHigh = lerp(SurfaceDarkBase, Color.White, 0.10f),
  surfaceContainerHighest = lerp(SurfaceDarkBase, Color.White, 0.14f),
)

private val LightColors = lightColorScheme(
  primary = PrimaryLight,
  onPrimary = OnPrimaryLight,
  primaryContainer = PrimaryContainerLight,
  onPrimaryContainer = OnPrimaryContainerLight,
  secondaryContainer = SecondaryContainerLight,
  onSecondaryContainer = OnSecondaryContainerLight,
  surfaceVariant = SurfaceVariantLight,
  onSurfaceVariant = OnSurfaceVariantLight,
  outline = OutlineLight,
  outlineVariant = OutlineVariantLight,
  background = SurfaceLightBase,
  surface = SurfaceLightBase,
  surfaceContainerLowest = Color.White,
  surfaceContainerLow = lerp(SurfaceLightBase, PrimaryLight, 0.04f),
  surfaceContainer = lerp(SurfaceLightBase, PrimaryLight, 0.06f),
  surfaceContainerHigh = lerp(SurfaceLightBase, PrimaryLight, 0.09f),
  surfaceContainerHighest = lerp(SurfaceLightBase, PrimaryLight, 0.12f),
)

/**
 * Standard Material 3 theming: follows the system light/dark setting; both
 * schemes are the brand Mono (graphite) palette.
 */
@Composable
fun CouchPadTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  content: @Composable () -> Unit,
) {
  MaterialTheme(
    colorScheme = if (darkTheme) DarkColors else LightColors,
    typography = Typography,
    content = content,
  )
}
