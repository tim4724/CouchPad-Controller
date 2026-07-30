package games.couchpad.controller

import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import games.couchpad.controller.data.RecentRoomStore
import games.couchpad.controller.ui.about.AboutScreen
import games.couchpad.controller.ui.about.LicensesScreen
import games.couchpad.controller.ui.game.GameHostScreen
import games.couchpad.controller.ui.legal.LegalLinks
import games.couchpad.controller.ui.legal.WebDocScreen
import games.couchpad.controller.ui.main.MainScreen

@Composable
fun MainNavigation(deepLink: String? = null, onDeepLinkConsumed: () -> Unit = {}) {
  val backStack = rememberNavBackStack(Main)
  val context = LocalContext.current
  // Config-aware resources for string lookups in callbacks (context.getString on
  // LocalContext.current is flagged by lint as not tracking config changes).
  val resources = LocalResources.current
  // The game-end notice is set on the GameHost→Main pop and read by MainScreen, which
  // shows it as a banner in the home rejoin slot (survives the pop because it lives
  // here, above the destroyed GameHost entry).
  var gameEndBanner by remember { mutableStateOf<String?>(null) }

  // An external App Link routes through MainScreen (which owns the name gate + join).
  // Pop back to Main first so it's the active entry and can handle it.
  LaunchedEffect(deepLink) {
    if (deepLink != null) while (backStack.size > 1) backStack.removeLastOrNull()
  }

  NavDisplay(
    backStack = backStack,
    onBack = { backStack.removeLastOrNull() },
    // Nav3's default predictive-back spec scales the outgoing screen to 0.7 with no
    // fade, so an edge-swipe close looked different from a button/pop close (a plain
    // fade). Override it to the same crossfade so every back animates identically.
    predictivePopTransitionSpec = {
      ContentTransform(
        fadeIn(animationSpec = tween(700)),
        fadeOut(animationSpec = tween(700)),
      )
    },
    entryProvider = entryProvider {
      entry<Main> {
        MainScreen(
          deepLink = deepLink,
          onDeepLinkConsumed = onDeepLinkConsumed,
          onJoin = { joinUrl, title, allowedHosts ->
            backStack.add(GameHost(joinUrl, title, allowedHosts))
          },
          // A privacy/imprint App Link opens the in-app doc viewer over Main,
          // rather than routing the URL through the join resolver.
          onOpenLegalDoc = { url -> backStack.add(WebDoc(url)) },
          onOpenAbout = { backStack.add(About) },
          gameEndBanner = gameEndBanner,
          onDismissGameEndBanner = { gameEndBanner = null },
        )
      }
      entry<About> {
        AboutScreen(
          onOpenPrivacy = { backStack.add(WebDoc(LegalLinks.PRIVACY_URL)) },
          onOpenImprint = { backStack.add(WebDoc(LegalLinks.IMPRINT_URL)) },
          onOpenLicenses = { backStack.add(Licenses) },
          // The marketing site is a full website, not a legal doc, so it opens in
          // the system browser rather than the in-app WebDocScreen viewer.
          onOpenWebsite = {
            val language = resources.configuration.locales[0].language
            context.startActivity(
              Intent(Intent.ACTION_VIEW, LegalLinks.websiteUrl(language).toUri())
            )
          },
          onBack = { backStack.removeLastOrNull() },
        )
      }
      entry<Licenses> {
        LicensesScreen(onBack = { backStack.removeLastOrNull() })
      }
      entry<WebDoc> { key ->
        // Both titles are resolved here (re-localizing with the device language); the
        // screen shows the one matching its URL. A cross-link opens the other doc as
        // its own entry so the back button returns here.
        WebDocScreen(
          url = key.url,
          privacyTitle = stringResource(R.string.privacy_policy),
          imprintTitle = stringResource(R.string.imprint),
          onOpenDoc = { backStack.add(WebDoc(it)) },
          onBack = { backStack.removeLastOrNull() },
        )
      }
      entry<GameHost> { key ->
        GameHostScreen(
          joinUrl = key.joinUrl,
          title = key.title,
          allowedHosts = key.allowedHosts,
          onLeave = { backStack.removeLastOrNull() },
          // Back home like LEAVE, plus a banner in the rejoin slot — the player didn't
          // choose to leave, so silence would read as a crash. (A load failure isn't a
          // session end: the host shows a retry overlay in place, not a pop.)
          onGameEnd = { reason ->
            backStack.removeLastOrNull()
            // A room that's gone can't be rejoined — drop the saved room now so the
            // home rejoin card clears immediately instead of lingering until the
            // liveness poll's relay record independently catches up (10s + relay lag).
            if (reason == "room_not_found") RecentRoomStore.clear()
            gameEndBanner = resources.getString(gameEndMessage(reason))
          },
        )
      }
    },
  )
}

// Player-facing copy for the contract's session-end reasons. The string comes from
// web content (untrusted), so anything unrecognized falls back to the generic line.
private fun gameEndMessage(reason: String?): Int = when (reason) {
  "room_not_found" -> R.string.game_end_room_not_found
  "game_full" -> R.string.game_end_room_full
  "replaced" -> R.string.game_end_replaced
  else -> R.string.game_end_generic
}
