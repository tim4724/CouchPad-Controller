package games.couchpad.controller.ui.game

import android.content.Context
import android.os.Looper
import android.webkit.WebView
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Warms the WebView engine so the first game join doesn't pay provider load +
 * renderer spawn (commonly 100–300ms+ on mid-range devices) on top of the network
 * load. The first `WebView` instantiation in a process loads the provider APK's
 * classes and native libraries; that stays loaded process-wide, so the throwaway
 * instance is destroyed immediately.
 *
 * Scheduled on a main-looper idle slot: WebView must be built on the main thread,
 * and idle means startup rendering and any pending input have already been served —
 * the one-time cost lands in a gap, not in the launch frames. Mirrors iOS's
 * WebViewWarmer (GameWebView.swift).
 */
object WebViewWarmer {
  private val done = AtomicBoolean(false)

  fun schedule(context: Context) {
    if (done.get()) return
    val appContext = context.applicationContext
    Looper.getMainLooper().queue.addIdleHandler {
      if (done.compareAndSet(false, true)) {
        // Never let a broken/updating WebView provider crash the launcher over an
        // optimization — the game host handles that failure at real use time.
        runCatching { WebView(appContext).destroy() }
      }
      false // one-shot
    }
  }
}
