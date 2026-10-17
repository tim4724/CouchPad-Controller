package games.couchpad.controller.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

// The couchpad.games design tokens (Couch-Games assets/theme.css) mapped onto M3 roles.
// Neutral ink and paper carry the structure and the game posters carry the color; the
// light surfaces are faintly warm paper, the dark ones violet-tinted near-black. From
// the site's rules: coral fills a screen's one primary button and nothing else, and
// cards are flat with a hairline border. Interactive controls stay the platform's own
// (the secondary button is M3's tonal button). Container roles the site doesn't define
// are steps on the site's surface ladder — except the tonal-button fill
// (secondaryContainer), which is deliberately off-ladder; see its comment below.
val PrimaryLight = Color(0xFF26242C) // --on-surface (the site's ink pills)
val OnPrimaryLight = Color(0xFFFFFFFF)
val PrimaryContainerLight = Color(0xFFE7E4DF)
val OnPrimaryContainerLight = Color(0xFF26242C)

val PrimaryDark = Color(0xFFF1EFEC)
val OnPrimaryDark = Color(0xFF26242C)
val PrimaryContainerDark = Color(0xFF3D3849)
val OnPrimaryContainerDark = Color(0xFFF1EFEC)

// Tonal-button fill: a near-neutral grey (the surface blended toward the ink), NOT a step
// on the surface ladder — the ladder's warm cast reads muddy as a fill.
val SecondaryContainerLight = Color(0xFFDAD8D6)
val OnSecondaryContainerLight = Color(0xFF26242C)
val SurfaceVariantLight = Color(0xFFE7E4DF)
val OnSurfaceVariantLight = Color(0xFF5E5A66)
val OutlineLight = Color(0xFF77717F)
val OutlineVariantLight = Color(0xFFDCD9D3)

val SecondaryContainerDark = Color(0xFF332F3F) // --surface-highest
val OnSecondaryContainerDark = Color(0xFFF1EFEC)
val SurfaceVariantDark = Color(0xFF332F3F)
val OnSurfaceVariantDark = Color(0xFFA8A3B1)
val OutlineDark = Color(0xFF8D8798)
val OutlineVariantDark = Color(0xFF2F2B3A)

// Faintly warm paper light, violet-tinted near-black dark (site --bg values).
val SurfaceDarkBase = Color(0xFF110F17)
val SurfaceLightBase = Color(0xFFFAF9F7)

// --action-solid: the fill of a screen's one primary button, behind white label text
// (4.6:1, AA). Nothing else is coral.
val ActionCoral = Color(0xFFD63C43)

// --card: see ColorScheme.card (Theme.kt).
internal val CardLight = Color(0xFFFFFFFF)
internal val CardDark = Color(0xFF1C1A24)

/**
 * Black or white over [color], whichever wins on WCAG contrast — accents and
 * game-supplied colors arrive without a paired "on" color. A naive luminance > 0.5
 * split picks white on saturated mid-tones (a coral like #FF6B6B sits at ~0.33) even
 * though black reads far better there; the real black/white crossover is at luminance
 * ≈ 0.179, so compare the two contrasts instead.
 */
fun contentColorOn(color: Color): Color {
  val l = color.luminance()
  val blackContrast = (l + 0.05f) / 0.05f   // black L = 0
  val whiteContrast = 1.05f / (l + 0.05f)   // white L = 1
  return if (blackContrast >= whiteContrast) Color.Black else Color.White
}
