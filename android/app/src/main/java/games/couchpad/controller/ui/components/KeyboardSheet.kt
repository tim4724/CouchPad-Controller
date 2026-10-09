package games.couchpad.controller.ui.components

import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationSource
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import games.couchpad.controller.R
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * A modal sheet for typing, moved by the keyboard alone. Material's sheet pads its whole
 * area by the keyboard, so a keyboard arriving mid-slide re-measured it and it stopped
 * short; Compose Unstyled's offsets it by whatever the keyboard reports, so when Android
 * reports the keyboard in one step instead of animating it, the sheet jumped. Here:
 *
 * - Our own window, configured before it is shown: adjustResize (Android animates the
 *   keyboard's insets frame by frame only to such a window) and the host's hidden bars
 *   already hidden (no flash of the nav bar).
 * - One position: the sheet's slide minus the keyboard's lift. While Android animates the
 *   keyboard the lift follows it frame by frame; a height it reports without animating is
 *   eased to instead, so a jump becomes a short slide. Nothing waits on anything.
 *
 * Content-height sheets sit on the keyboard; [fullHeight] ones (the landscape rename) fill
 * the screen at the keyboard's width and ignore it, the keyboard sliding in over their
 * lower part. Closing — by [content]'s close, the backdrop or back — slides the sheet out
 * with the keyboard, then [onClosed] tells the caller to drop it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KeyboardSheet(
  fullHeight: Boolean,
  color: Color,
  onClosed: () -> Unit,
  content: @Composable (close: () -> Unit) -> Unit,
) {
  val progress = remember { Animatable(0f) }
  val scope = rememberCoroutineScope()
  var closing by remember { mutableStateOf(false) }
  val currentOnClosed by rememberUpdatedState(onClosed)
  val close: () -> Unit = {
    if (!closing) {
      closing = true
      scope.launch {
        progress.animateTo(0f, tween(200))
        currentOnClosed()
      }
    }
  }
  LaunchedEffect(Unit) { progress.animateTo(1f, tween(250)) }

  SheetWindow {
    BackHandler(onBack = close)
    // The keyboard leaves with the sheet rather than after it. Hidden, not unfocused:
    // clearing focus first drops the field's connection, and the keyboard flashes its
    // no-field layout on the way down.
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(closing) { if (closing) keyboard?.hide() }

    // The keyboard's lift, eased whenever Android reports a height without animating it.
    val density = LocalDensity.current
    val ime = WindowInsets.ime
    val imeSource = WindowInsets.imeAnimationSource
    val imeTarget = WindowInsets.imeAnimationTarget
    val nav = WindowInsets.navigationBarsIgnoringVisibility
    val keyboardSides = WindowInsets.displayCutout.union(nav).only(WindowInsetsSides.Horizontal)
    val eased = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
      snapshotFlow { ime.getBottom(density) to (imeSource.getBottom(density) != imeTarget.getBottom(density)) }
        .collectLatest { (height, animating) ->
          // Short and leaving at full speed: the keyboard is already there, so the sheet
          // should visibly follow at once rather than ease out of a standstill.
          if (animating) eased.snapTo(height.toFloat())
          else eased.animateTo(height.toFloat(), tween(150, easing = LinearOutSlowInEasing))
        }
    }

    Box(Modifier.fillMaxSize()) {
      Box(
        Modifier.fillMaxSize()
          .graphicsLayer { alpha = progress.value }
          .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
          .pointerInput(Unit) { detectTapGestures { close() } },
      )
      Surface(
        Modifier.align(if (fullHeight) Alignment.TopCenter else Alignment.BottomCenter)
          .then(
            // Full height: as wide as the keyboard below it, which keeps clear of the
            // cutout and a side nav bar (ignoring visibility, so the bar the keyboard
            // forces back doesn't narrow the sheet). Otherwise Material's sheet width;
            // a tap beside it lands on the backdrop.
            if (fullHeight) Modifier.windowInsetsPadding(keyboardSides) else Modifier.widthIn(max = 640.dp),
          )
          .fillMaxWidth()
          .then(if (fullHeight) Modifier.fillMaxHeight() else Modifier)
          .graphicsLayer {
            val lift = if (fullHeight) {
              0f
            } else {
              // Direct while Android animates, so the sheet never trails the keyboard.
              val animating = imeSource.getBottom(this) != imeTarget.getBottom(this)
              val imeHeight = if (animating) ime.getBottom(this).toFloat() else eased.value
              // The sheet pads for the nav bar (below); a keyboard covers that first.
              (imeHeight - nav.getBottom(this)).coerceAtLeast(0f)
            }
            translationY = size.height * (1 - progress.value) - lift
          },
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        color = color,
        // Explicit: contentColorFor() only knows scheme colors, and on a game's tint
        // would fall back to the caller's — the dark game host's light text.
        contentColor = MaterialTheme.colorScheme.onSurface,
      ) {
        // Ignoring visibility: the bar a game hides is hidden on this window too, but a
        // padding that came and went with it would move the sheet.
        Box(if (fullHeight) Modifier else Modifier.windowInsetsPadding(nav)) { content(close) }
      }
    }
  }
}

/** A full-screen window over the activity, set up before it shows (see [KeyboardSheet]). */
@Composable
private fun SheetWindow(content: @Composable () -> Unit) {
  val context = LocalContext.current
  val composition = rememberCompositionContext()
  val currentContent by rememberUpdatedState(content)
  DisposableEffect(Unit) {
    val dialog = ComponentDialog(context, R.style.KeyboardSheetWindow)
    val window = requireNotNull(dialog.window)
    val view = ComposeView(context).apply {
      setParentCompositionContext(composition)
      setContent { currentContent() }
    }
    dialog.setContentView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    dialog.setCancelable(false) // back is the content's BackHandler
    window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    window.setBackgroundDrawable(ColorDrawable(AndroidColor.TRANSPARENT))
    WindowCompat.setDecorFitsSystemWindows(window, false)
    @Suppress("DEPRECATION") // the only mode that animates keyboard insets to the window
    window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      window.attributes = window.attributes.apply {
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
      }
    }
    val host = context.findActivity()?.window
    WindowCompat.getInsetsController(window, window.decorView).run {
      if (host != null) {
        val hostController = WindowCompat.getInsetsController(host, host.decorView)
        isAppearanceLightStatusBars = hostController.isAppearanceLightStatusBars
        isAppearanceLightNavigationBars = hostController.isAppearanceLightNavigationBars
      }
      val hidden = hostHiddenBars(context)
      if (hidden != 0) {
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        hide(hidden)
      }
    }
    dialog.show()
    onDispose {
      view.disposeComposition()
      dialog.dismiss()
    }
  }
}
