package games.couchpad.controller.ui.game

import androidx.compose.ui.graphics.Color
import org.json.JSONObject

/**
 * Optional theming hints from the controller page's `<head>` (CONTRACT.md §4):
 * `theme-color` tints the launcher's top chrome, `cp-accent-color` tints launcher
 * accents shown over the game. Purely cosmetic — absent or unparseable metas fall
 * back to the stock dark chrome.
 */
internal data class PageTheme(val bar: Color? = null, val accent: Color? = null)

/**
 * Evaluated when the hosting activity STOPs — home, app switch, lock (CONTRACT.md §7).
 *
 * A synthetic persisted `pagehide` tells the game to close its relay socket NOW,
 * so the display drops the player the moment they leave instead of whenever the
 * OS freezes the cached process (OEM/timing dependent — and on iOS never, which
 * is what made this deterministic dispatch necessary; Android mirrors it for
 * parity). No foreground counterpart: the engine fires the real
 * `visibilitychange` → visible on return, which is the game's reconnect trigger.
 */
internal const val DISPATCH_PAGE_HIDE_JS =
  "window.dispatchEvent(new PageTransitionEvent('pagehide', { persisted: true }));"

/**
 * The launcher-injected observer, evaluated after each page load. Pushes the
 * metas' state through `CouchPadHost.themeChanged` immediately and on every
 * change — head mutations via MutationObserver, plus scheme flips (a `media`
 * attribute can start/stop matching). Pushes are deduped.
 *
 * Reads honor `media` (first match wins) and let the browser normalize any CSS
 * color to rgb()/rgba() via computed style — CSS.supports() first, because an
 * invalid color would fall back to the inherited computed color and read as a
 * false positive. The probe div hangs off documentElement, outside the observed
 * `<head>`, so probing can't self-trigger. Re-running on a document that already
 * has the observer just re-pushes. evaluateJavascript is exempt from the page's
 * CSP, so `script-src 'self'` game origins keep working.
 */
internal val WATCH_PAGE_THEME_JS = """
  (() => {
    if (window.__cgThemePush) { window.__cgThemePush(); return; }
    const read = (name) => {
      const metas = [...document.querySelectorAll('meta[name="' + name + '"]')];
      const m = metas.find((x) => !x.media || matchMedia(x.media).matches) ?? metas[0];
      if (!m || !m.content || !CSS.supports('color', m.content)) return null;
      const el = document.createElement('div');
      el.style.color = m.content;
      document.documentElement.appendChild(el);
      const c = getComputedStyle(el).color;
      el.remove();
      return c;
    };
    let last;
    const push = () => {
      const t = JSON.stringify({ bar: read('theme-color'), accent: read('cp-accent-color') });
      if (t === last) return;
      last = t;
      if (window.CouchPadHost && window.CouchPadHost.themeChanged) window.CouchPadHost.themeChanged(t);
    };
    window.__cgThemePush = push;
    new MutationObserver(push).observe(document.head, {
      childList: true, subtree: true, attributes: true, attributeFilter: ['content', 'media', 'name'],
    });
    matchMedia('(prefers-color-scheme: dark)').addEventListener('change', push);
    push();
  })()
""".trimIndent()

// Computed styles serialize sRGB colors as rgb(r, g, b) / rgba(r, g, b, a).
// Wide-gamut serializations (color(display-p3 …), lab(…)) deliberately fail.
private val CSS_RGB = Regex("""rgba?\((\d{1,3}), (\d{1,3}), (\d{1,3})(?:, [0-9.]+)?\)""")

/** Bridge input is untrusted page data: strict shape, hard length cap, fallback on anything odd. */
internal fun parsePageTheme(json: String?): PageTheme {
  if (json == null || json.length > 256) return PageTheme()
  return runCatching {
    val obj = JSONObject(json)
    PageTheme(bar = parseCssRgb(obj.optString("bar")), accent = parseCssRgb(obj.optString("accent")))
  }.getOrDefault(PageTheme())
}

private fun parseCssRgb(value: String): Color? {
  val match = CSS_RGB.matchEntire(value.trim()) ?: return null
  val (r, g, b) = match.destructured.toList().map { it.toInt() }
  if (r > 255 || g > 255 || b > 255) return null
  // Alpha is deliberately dropped: the chrome behind the status bar must be opaque.
  return Color(r, g, b)
}
