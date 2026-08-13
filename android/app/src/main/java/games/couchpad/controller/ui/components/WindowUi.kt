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
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * House-style modal sheet: opens fully expanded, no drag handle (its tap ripple
 * reads as broken), and mirrors the host window's bar state so a sheet over
 * the in-game host never brings the hidden nav bar back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSheet(
  onDismiss: () -> Unit,
  // A game's theme-color, used as the sheet surface so an in-game sheet reads as part
  // of the game. Null (the default) keeps the neutral surface. Callers gate on
  // luminance so the surface stays dark enough for the sheet's light text.
  surfaceTint: Color? = null,
  content: @Composable ColumnScope.() -> Unit,
) {
  // The default surfaceContainerLow is one step above our darkened dark background —
  // the sheet edge vanished at night. High keeps it legible.
  val container = surfaceTint ?: MaterialTheme.colorScheme.surfaceContainerHigh
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    dragHandle = null,
    containerColor = container,
  ) {
    MirrorHostSystemBars()
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

/**
 * A sheet/dialog is its OWN window: opened over a host with hidden bars, it would
 * bring them back. Mirror the host's state per bar type — in-game the hidden set is
 * the nav bar (unless a 3-button player's page armed system back) plus, in
 * landscape, the status bar,
 * so an all-or-nothing probe would rarely match. Probes the live insets on every call,
 * so it tracks both mid-game. No-op when the host shows its bars normally, so every
 * overlay can call this unconditionally.
 */
@Composable
fun MirrorHostSystemBars() {
  val view = LocalView.current
  val context = LocalContext.current
  SideEffect {
    val hostDecor = context.findActivity()?.window?.decorView ?: return@SideEffect
    val insets = ViewCompat.getRootWindowInsets(hostDecor) ?: return@SideEffect
    // Probe status + nav bars individually. Type.systemBars() also covers the
    // caption bar, which phones never report visible — the combined isVisible()
    // was false even over the plain home screen, so every sheet went immersive.
    var hidden = 0
    if (!insets.isVisible(WindowInsetsCompat.Type.statusBars())) hidden = hidden or WindowInsetsCompat.Type.statusBars()
    if (!insets.isVisible(WindowInsetsCompat.Type.navigationBars())) hidden = hidden or WindowInsetsCompat.Type.navigationBars()
    if (hidden == 0) return@SideEffect
    val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
    WindowCompat.getInsetsController(window, view).run {
      systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
      hide(hidden)
    }
  }
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
