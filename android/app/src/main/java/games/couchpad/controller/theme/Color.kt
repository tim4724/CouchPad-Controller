package games.couchpad.controller.theme

import androidx.compose.ui.graphics.Color

// MONO — graphite chrome, console-shell pattern (PS5/Switch/Netflix): the game
// posters carry ALL the color, so neutral chrome never fights a game's palette.
// The container ladders derive from the two surface bases in Theme.kt.
val PrimaryLight = Color(0xFF202024)
val OnPrimaryLight = Color(0xFFFFFFFF)
val PrimaryContainerLight = Color(0xFFE5E1E9)
val OnPrimaryContainerLight = Color(0xFF1B1B1F)

val PrimaryDark = Color(0xFFE6E1E9)
val OnPrimaryDark = Color(0xFF1C1B20)
val PrimaryContainerDark = Color(0xFF47444D)
val OnPrimaryContainerDark = Color(0xFFE6E1E9)

// Neutral secondary / variant / outline roles: Material 3 fills any UNSET role
// with its stock lavender, which renders purple against the graphite chrome.
// Deeper than surfaceContainerHigh so a tonal button ON the Join card still
// reads as a button rather than blending in.
val SecondaryContainerLight = Color(0xFFD7D6DD)
val OnSecondaryContainerLight = Color(0xFF1B1B1F)
val SurfaceVariantLight = Color(0xFFE4E3E7)
val OnSurfaceVariantLight = Color(0xFF46464B)
val OutlineLight = Color(0xFF77767C)
val OutlineVariantLight = Color(0xFFCAC9CE)

val SecondaryContainerDark = Color(0xFF35353B)
val OnSecondaryContainerDark = Color(0xFFE6E1E9)
val SurfaceVariantDark = Color(0xFF46464B)
val OnSurfaceVariantDark = Color(0xFFC7C6CC)
val OutlineDark = Color(0xFF90909A)
val OutlineVariantDark = Color(0xFF44444A)

// Deep neutral dark (living-room dark, deeper than stock M3) and a hair-off-white light.
val SurfaceDarkBase = Color(0xFF0F0F11)
val SurfaceLightBase = Color(0xFFFAFAFB)
