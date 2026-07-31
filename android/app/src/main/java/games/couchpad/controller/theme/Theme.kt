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
  // Site --surface-* ladder verbatim, not lerps: the tokens are the contract.
  surfaceContainerLowest = Color(0xFF0B0A10),
  surfaceContainerLow = Color(0xFF1C1A24),
  surfaceContainer = Color(0xFF211E2B),
  surfaceContainerHigh = Color(0xFF2A2735),
  surfaceContainerHighest = Color(0xFF332F3F),
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
  // Explicit: the M3 default is lavender-white #FEF7FF, which clashes with paper
  // (and iOS mirrors this value).
  surfaceBright = SurfaceLightBase,
  // Much paler than the site's raw ladder: on the site only --surface-low
  // (#F0EDE6, the facts panel) appears as a large fill; the deeper beige steps
  // are hover/detail tones and read khaki when spread across a card or button.
  surfaceContainerLowest = Color.White,
  surfaceContainerLow = Color(0xFFF8F5EF),
  surfaceContainer = Color(0xFFF3F0E8),
  surfaceContainerHigh = Color(0xFFF0EDE6),
  surfaceContainerHighest = Color(0xFFE9E4D9),
)

/**
 * Standard Material 3 theming: follows the system light/dark setting; both
 * schemes are the brand Ink & Confetti palette (see Color.kt).
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
