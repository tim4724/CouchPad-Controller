package games.couchpad.controller.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.view.View
import android.view.Window
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * House-style modal sheet: opens fully expanded, no drag handle (its tap ripple
 * reads as broken).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSheet(
  onDismiss: () -> Unit,
  content: @Composable ColumnScope.() -> Unit,
) {
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    dragHandle = null,
    // The default surfaceContainerLow is one step above our darkened dark background —
    // the sheet edge vanished at night. High keeps it legible.
    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
  ) {
    // Scrolls only when the content outgrows the screen (small displays,
    // large font scale) — ModalBottomSheet clips a plain Column otherwise.
    Column(Modifier.verticalScroll(rememberScrollState()), content = content)
  }
}

/**
 * The insets stance for everything outside a game: bars + display cutout,
 * IGNORING bar visibility — returning from the immersive game host the bars are
 * still animating back in, and visibility-tracking insets would lay out
 * full-bleed and then shift content as the bars fade back. Deliberately no IME
 * (in-sheet inputs handle that themselves via imePadding).
 */
val stableScreenInsets: WindowInsets
  @OptIn(ExperimentalLayoutApi::class)
  @Composable get() = WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout)

/**
 * Hide the navigation bar only (revealable with a transient swipe). Nav hidden +
 * transient-by-swipe is exactly the state that lifts the system's 200dp-per-edge cap
 * on gesture-exclusion rects (the status bar is not part of that condition), which
 * the game host depends on — see GameHostScreen.
 */
fun hideNavigationBar(window: Window, view: View) {
  WindowCompat.getInsetsController(window, view).run {
    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    hide(WindowInsetsCompat.Type.navigationBars())
  }
}

/**
 * True when the system is in full gesture navigation (no bar buttons). Reads the
 * framework's own interaction-mode resource (0 = 3-button, 1 = 2-button,
 * 2 = gestural). Missing on some OEM skins — that reads as not-gesture, which
 * errs toward showing the nav bar: a visible back beats a hidden one.
 */
fun gestureNavEnabled(context: Context): Boolean {
  val res = context.resources
  val id = res.getIdentifier("config_navBarInteractionMode", "integer", "android")
  return id != 0 && res.getInteger(id) == 2
}

/** The bar types (status, nav) the activity's window currently hides; 0 when none. */
fun hostHiddenBars(context: Context): Int {
  val hostDecor = context.findActivity()?.window?.decorView ?: return 0
  val insets = ViewCompat.getRootWindowInsets(hostDecor) ?: return 0
  // Probe status + nav bars individually. Type.systemBars() also covers the
  // caption bar, which phones never report visible — the combined isVisible()
  // was false even over the plain home screen, so every sheet went immersive.
  var hidden = 0
  if (!insets.isVisible(WindowInsetsCompat.Type.statusBars())) hidden = hidden or WindowInsetsCompat.Type.statusBars()
  if (!insets.isVisible(WindowInsetsCompat.Type.navigationBars())) hidden = hidden or WindowInsetsCompat.Type.navigationBars()
  return hidden
}

// LocalContext under Compose can be a ContextWrapper, not the Activity directly.
tailrec fun Context.findActivity(): Activity? = when (this) {
  is Activity -> this
  is ContextWrapper -> baseContext.findActivity()
  else -> null
}

/**
 * The bar-icon appearance the current theme wants (what enableEdgeToEdge's auto
 * style picks): dark icons on the light theme, light icons on the dark theme.
 * Screens that force their own appearance (game host, scanner) restore THIS on
 * exit rather than a captured value — uiMode no longer recreates the activity,
 * so a value captured before a mid-overlay theme flip would be stale.
 */
fun themeLightBarIcons(context: Context): Boolean =
  (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) !=
    Configuration.UI_MODE_NIGHT_YES
