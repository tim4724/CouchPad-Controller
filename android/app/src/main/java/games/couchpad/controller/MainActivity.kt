package games.couchpad.controller

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import games.couchpad.controller.theme.CouchPadTheme
import games.couchpad.controller.ui.components.themeLightBarIcons
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
  // the activity mid-game. Compose re-themes on its own; only the bar icons were
  // resolved at onCreate time, so the bar setup is re-applied for the new configuration.
  override fun onConfigurationChanged(newConfig: Configuration) {
    super.onConfigurationChanged(newConfig)
    applyEdgeToEdge()
  }

  // Hand-rolled, not androidx's enableEdgeToEdge: that hangs a hidden view on the decor
  // that re-runs its setup on every configuration change, after this callback, and its
  // SystemBarStyle.auto turns the nav bar's contrast scrim back on — a 3-button bar went
  // scrimmed from the first rotation or theme flip on.
  @Suppress("DEPRECATION") // bar colors are ignored from API 35 on; below it they matter
  private fun applyEdgeToEdge() {
    // First: installing the decor re-reads the window theme (contrast enforcement on, opaque
    // bar colors) over anything set before it.
    val decor = window.decorView
    WindowCompat.setDecorFitsSystemWindows(window, false)
    window.statusBarColor = Color.TRANSPARENT
    window.navigationBarColor = Color.TRANSPARENT
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      window.isStatusBarContrastEnforced = false
      window.isNavigationBarContrastEnforced = false
    }
    // Lay out under the cutout too, so a landscape game spans the full screen.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      window.attributes.layoutInDisplayCutoutMode =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
          WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else {
          WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }
    val light = themeLightBarIcons(this)
    WindowCompat.getInsetsController(window, decor).run {
      isAppearanceLightStatusBars = light
      isAppearanceLightNavigationBars = light
    }
  }
}
