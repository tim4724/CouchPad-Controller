package games.couchpad.controller.ui.game

import androidx.compose.ui.graphics.Color
import org.json.JSONObject

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
 * Delivers a system back press to the page (CONTRACT.md §9), evaluated only while
 * the page has armed system back.
 *
 * Evaluates to `true` — the ONLY result that keeps the player in the game — when the
 * game both implements `back()` and returns a literal `true` from it. A missing
 * handler, a falsy/absent return (including a Promise, which is why §9 requires a
 * synchronous decision) or a throw all evaluate to `false`, and the launcher leaves.
 */
internal const val DELIVER_BACK_JS =
  "(() => { try { return window.CouchPad && typeof window.CouchPad.back === 'function' &&" +
    " window.CouchPad.back() === true; } catch (e) { return false; } })()"

/**
 * The launcher-injected observer for the page's `color-scheme` meta (CONTRACT.md §4),
 * evaluated after each page load. Pushes whether the page is dark through the
 * launcher-internal `__cpScheme` interface (see SchemeBridge) immediately and on every
 * change — head mutations via MutationObserver, plus system scheme flips (`light dark`
 * follows the system). Pushes are deduped. No meta reads as light, the web's own
 * default. Re-running on a document that already has the observer just re-pushes.
 * evaluateJavascript is exempt from the page's CSP, so `script-src 'self'` game origins
 * keep working.
 */
internal val WATCH_PAGE_SCHEME_JS = """
  (() => {
    if (window.__cpSchemePush) { window.__cpSchemePush(); return; }
    const systemDark = matchMedia('(prefers-color-scheme: dark)');
    let last;
    const push = () => {
      const meta = document.querySelector('meta[name="color-scheme"]');
      const schemes = (meta?.getAttribute('content') ?? '').toLowerCase().split(/\s+/);
      const dark = schemes.includes('dark') && (!schemes.includes('light') || systemDark.matches);
      if (dark === last) return;
      last = dark;
      window.__cpScheme?.changed(dark);
    };
    window.__cpSchemePush = push;
    new MutationObserver(push).observe(document.head, {
      childList: true, subtree: true, attributes: true, attributeFilter: ['content', 'name'],
    });
    systemDark.addEventListener('change', push);
    push();
  })()
""".trimIndent()

/** The page's own colors for the launcher's name sheet (CONTRACT.md §2). */
internal data class SheetColors(val surface: Color? = null, val accent: Color? = null)

/**
 * Read once, when the page opens the name sheet: `<meta name="theme-color">` (first one
 * whose `media` matches) for the surface, and the CSS `accent-color` of `<html>` for the
 * accent — `auto` (unset) reads as none. Both normalized to rgb() by computed style;
 * CSS.supports() first, because an invalid color would fall back to the inherited one.
 */
internal val READ_SHEET_COLORS_JS = """
  (() => {
    const rgb = (value) => {
      if (!value || !CSS.supports('color', value)) return null;
      const probe = document.createElement('div');
      probe.style.color = value;
      document.documentElement.appendChild(probe);
      const color = getComputedStyle(probe).color;
      probe.remove();
      return color;
    };
    const meta = [...document.querySelectorAll('meta[name="theme-color"]')]
      .find((m) => !m.getAttribute('media') || matchMedia(m.getAttribute('media')).matches);
    const accent = getComputedStyle(document.documentElement).accentColor;
    return { surface: rgb(meta?.getAttribute('content')), accent: accent === 'auto' ? null : rgb(accent) };
  })()
""".trimIndent()

// Computed styles serialize sRGB colors as rgb(r, g, b) / rgba(r, g, b, a).
// Wide-gamut serializations (color(display-p3 …), lab(…)) deliberately fail.
private val CSS_RGB = Regex("""rgba?\((\d{1,3}), (\d{1,3}), (\d{1,3})(?:, [0-9.]+)?\)""")

/** The script's result is untrusted page data: strict shape, fallback on anything odd. */
internal fun parseSheetColors(json: String?): SheetColors = runCatching {
  val obj = JSONObject(json.orEmpty())
  SheetColors(parseCssRgb(obj.optString("surface")), parseCssRgb(obj.optString("accent")))
}.getOrDefault(SheetColors())

private fun parseCssRgb(value: String): Color? {
  val match = CSS_RGB.matchEntire(value.trim()) ?: return null
  val (r, g, b) = match.destructured.toList().map { it.toInt() }
  if (r > 255 || g > 255 || b > 255) return null
  // Alpha is deliberately dropped: the sheet surface must be opaque.
  return Color(r, g, b)
}

/**
 * Document-start script: defines `window.CouchPadHost` (CONTRACT.md) over the raw
 * `__cpHost` and `__cpName` Java interfaces, for the two members a Java interface can't
 * express — a `name` property and an `editName()` that returns a Promise. Everything else
 * passes straight through, so the Java bridge's own argument conversions still apply. Every
 * `editName()` reaches the launcher and gets its own Promise; the launcher answers them in
 * order through `__cpNameResult(name | null)`.
 */
internal val HOST_SHIM_JS = """
  (() => {
    const host = window.__cpHost;
    const names = window.__cpName;
    if (!host || !names || window.CouchPadHost) return;
    const waiting = [];
    window.__cpNameResult = (name) => {
      const done = waiting.shift();
      if (done) done(typeof name === 'string' ? name : null);
    };
    window.CouchPadHost = {
      get name() { return names.getName(); },
      editName() {
        return new Promise((resolve) => {
          waiting.push(resolve);
          names.editName();
        });
      },
      gameEnded: (reason) => host.gameEnded(reason),
      leave: () => host.leave(),
      enableSystemBack: (on) => host.enableSystemBack(on),
      setOrientation: (mode) => host.setOrientation(mode),
      haptic: (primitive, scale) => host.haptic(primitive, scale),
    };
  })();
""".trimIndent()
