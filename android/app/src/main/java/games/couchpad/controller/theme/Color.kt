package games.couchpad.controller.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

// INK & CONFETTI — the couchpad.games design tokens (Couch-Games assets/theme.css)
// mapped onto M3 roles. Neutral charcoal chrome still carries the structure and the
// game posters still carry the color (console-shell pattern); the change from the old
// Mono palette is warmth: paper-tinted light surfaces, violet-tinted near-black dark
// ones, and the coral CTA. Container roles the site doesn't define are steps on the
// site's surface ladder — except the tonal-button fill (secondaryContainer), which is
// deliberately off-ladder; see its comment below.
val PrimaryLight = Color(0xFF26242C) // --chrome
val OnPrimaryLight = Color(0xFFFFFFFF)
val PrimaryContainerLight = Color(0xFFE9E4D9) // --surface-container
val OnPrimaryContainerLight = Color(0xFF26242C)

val PrimaryDark = Color(0xFFF2EFE9)
val OnPrimaryDark = Color(0xFF26242C)
val PrimaryContainerDark = Color(0xFF3D3849)
val OnPrimaryContainerDark = Color(0xFFF2EFE9)

// Tonal-button fill: ink-tinted greige (paper blended toward --chrome), NOT a
// step on the beige ladder — the beiges' yellow cast reads khaki as a fill.
// Deep enough to read as a button on the surfaceContainerHigh Join card.
val SecondaryContainerLight = Color(0xFFDCD8D3)
val OnSecondaryContainerLight = Color(0xFF26242C)
val SurfaceVariantLight = Color(0xFFE9E4D9) // --surface-container
val OnSurfaceVariantLight = Color(0xFF5E5A66)
val OutlineLight = Color(0xFF77717F)
val OutlineVariantLight = Color(0xFFDFD9CD)

val SecondaryContainerDark = Color(0xFF332F3F) // --surface-highest
val OnSecondaryContainerDark = Color(0xFFF2EFE9)
val SurfaceVariantDark = Color(0xFF332F3F)
val OnSurfaceVariantDark = Color(0xFFA8A3B1)
val OutlineDark = Color(0xFF8D8798)
val OutlineVariantDark = Color(0xFF2F2B3A)

// Warm paper light, violet-tinted near-black dark (site --bg values).
val SurfaceDarkBase = Color(0xFF110F17)
val SurfaceLightBase = Color(0xFFFBF9F4)

// --action. CTA container only, never small text: it is 3.4:1 against the light
// background, so anything below large/bold sizes must use onSurface instead.
val ActionCoral = Color(0xFFF04A50)

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
