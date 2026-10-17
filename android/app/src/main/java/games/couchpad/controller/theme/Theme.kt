package games.couchpad.controller.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

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
  // (--surface-low, the facts panel) appears as a large fill; the deeper steps are
  // hover/detail tones and read muddy when spread across a card or button.
  surfaceContainerLowest = Color.White,
  surfaceContainerLow = Color(0xFFF7F5F2),
  surfaceContainer = Color(0xFFF2F0EC),
  surfaceContainerHigh = Color(0xFFEFEDEA),
  surfaceContainerHighest = Color(0xFFE7E4DF),
)

/**
 * The site's --card fill (white on paper, one step up from the dark background), which
 * no M3 role matches in both schemes. Keyed on the scheme in force rather than the
 * system setting, so a forced palette gets the right one.
 */
val ColorScheme.card: Color
  get() = if (background.luminance() < 0.5f) CardDark else CardLight

// The site's radii: 18 for cards, tiles and media, 28 for panels and sheets; buttons
// and fields are pills (M3's default button shape already).
private val AppShapes = Shapes(
  large = RoundedCornerShape(18.dp),
  extraLarge = RoundedCornerShape(28.dp),
)

/**
 * Standard Material 3 theming: follows the system light/dark setting; both
 * schemes are the brand palette (see Color.kt).
 */
@Composable
fun CouchPadTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  content: @Composable () -> Unit,
) {
  MaterialTheme(
    colorScheme = if (darkTheme) DarkColors else LightColors,
    typography = Typography,
    shapes = AppShapes,
    content = content,
  )
}
