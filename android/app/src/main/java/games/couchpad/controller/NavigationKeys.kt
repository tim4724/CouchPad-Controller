package games.couchpad.controller

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable data object Main : NavKey

@Serializable data object About : NavKey

@Serializable data object Licenses : NavKey

// A plain in-app web viewer for a hosted legal document (privacy / imprint). The
// URL is loaded read-only in a WebView; the app bar title is derived from whichever
// document is currently shown (the two pages cross-link), so it re-localizes with
// the device language and follows in-page navigation.
@Serializable
data class WebDoc(val url: String) : NavKey

@Serializable
data class GameHost(
  val joinUrl: String,
  val title: String,
  // Domains (subdomains included) the in-game WebView may navigate to; everything
  // else is bounced to the browser. The launcher's own domain is always added by
  // the host screen. This is the trust boundary for relay-supplied URLs.
  val allowedHosts: List<String>,
) : NavKey
