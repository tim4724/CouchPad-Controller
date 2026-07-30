package games.couchpad.controller

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import games.couchpad.controller.theme.CouchPadTheme
import games.couchpad.controller.ui.game.WebViewWarmer

class MainActivity : ComponentActivity() {
  // The URL of a pending App Link, observed by the nav host. Null on a normal launch.
  private var pendingDeepLink by mutableStateOf<String?>(null)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // Only on a fresh start — don't replay the launching VIEW intent across rotation.
    if (savedInstanceState == null) pendingDeepLink = intent?.dataString

    applyEdgeToEdge()
    // Engine warm-up for the first game join; runs on a post-launch idle slot.
    WebViewWarmer.schedule(this)
    setContent {
      CouchPadTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
          MainNavigation(
            deepLink = pendingDeepLink,
            onDeepLinkConsumed = { pendingDeepLink = null },
          )
        }
      }
    }
  }

  // A new App Link while we're already running (launchMode=singleTop).
  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    pendingDeepLink = intent.dataString
  }

  // uiMode is handled (manifest configChanges) so a dark-mode flip doesn't recreate
  // the activity mid-game. Compose re-themes on its own; only the bar icon contrast
  // was resolved at onCreate time and must be re-derived from the new configuration.
  override fun onConfigurationChanged(newConfig: Configuration) {
    super.onConfigurationChanged(newConfig)
    applyEdgeToEdge()
  }

  // Fully transparent system bars; icon contrast follows the system light/dark
  // theme. The no-arg default only makes the STATUS bar transparent — the nav bar
  // keeps a ~50% scrim (DefaultDarkScrim), so pass a transparent nav style too.
  private fun applyEdgeToEdge() {
    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
      navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
    )
    // enableEdgeToEdge disables the status bar's contrast scrim but leaves the nav
    // bar's on (isNavigationBarContrastEnforced defaults true) — so in 3-button mode
    // the nav bar tints as content scrolls under it while the status bar stays clear.
    // Match them: neither bar draws a scrim, so content shows through both cleanly.
    // (Our layouts keep the bar edges over dark chrome, so no legibility scrim needed.)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      window.isNavigationBarContrastEnforced = false
    }
  }
}
