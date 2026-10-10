import SwiftUI
import Foundation

/// The page's own colors for the launcher's name sheet (CONTRACT.md §2).
struct SheetColors {
    var surface: Color? = nil
    var accent: Color? = nil
}

/// The readSheetColors result is untrusted page data: strict shape, nil on anything odd.
func parseSheetColors(_ result: Any?) -> SheetColors {
    let object = result as? [String: Any]
    return SheetColors(
        surface: parseCssRgb(object?["surface"] as? String),
        accent: parseCssRgb(object?["accent"] as? String)
    )
}

// Computed styles serialize sRGB colors as rgb(r, g, b) / rgba(r, g, b, a).
// Wide-gamut serializations (color(display-p3 …), lab(…)) deliberately fail.
private let cssRgbRegex = try! NSRegularExpression(
    pattern: "^rgba?\\((\\d{1,3}), (\\d{1,3}), (\\d{1,3})(?:, [0-9.]+)?\\)$"
)

/// The ENTIRE trimmed string must match the computed-style rgb()/rgba() format
/// (single space after commas). Components > 255 → nil. Alpha is deliberately
/// dropped: the sheet surface must be opaque.
func parseCssRgb(_ value: String?) -> Color? {
    guard let value else { return nil }
    let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
    let fullRange = NSRange(trimmed.startIndex..., in: trimmed)
    guard let match = cssRgbRegex.firstMatch(in: trimmed, options: [], range: fullRange),
          match.range == fullRange
    else { return nil }

    func component(_ index: Int) -> Int? {
        guard let range = Range(match.range(at: index), in: trimmed) else { return nil }
        return Int(trimmed[range])
    }

    guard let r = component(1), let g = component(2), let b = component(3),
          r <= 255, g <= 255, b <= 255
    else { return nil }
    return Color(.sRGB, red: Double(r) / 255.0, green: Double(g) / 255.0, blue: Double(b) / 255.0, opacity: 1.0)
}

/// Maps the untrusted, web-supplied session-end reason to player-facing copy.
/// Unknown values must be tolerated, never crash/ignore.
func gameEndMessage(_ reason: String?) -> String {
    switch reason {
    case "room_not_found": return String(localized: "Room not found")
    case "game_full": return String(localized: "Room is full")
    case "replaced": return String(localized: "You joined from another device")
    default: return String(localized: "The party ended")
    }
}

/// Bridge input is untrusted page data, and the shim's own clamping is no guarantee —
/// a page can post to `cpHost` directly. Comma-separated milliseconds, alternating
/// vibrate/pause (the Vibration API's pattern shape); empty is its cancel call.
/// A malformed entry keeps the valid prefix rather than dropping the buzz entirely.
/// Both the entry count and the total run time are capped: nothing a page sends may
/// leave the phone shaking after the tap that caused it. The entry cap is Chromium's,
/// so any pattern Android's WebView plays in full plays in full here too.
func parseVibrationPattern(_ csv: String?) -> [Int] {
    guard let csv, !csv.isEmpty, csv.utf16.count <= 1024 else { return [] }
    var pattern: [Int] = []
    var total = 0
    for field in csv.split(separator: ",", omittingEmptySubsequences: false) {
        guard pattern.count < 128, total < 5000, let milliseconds = Int(field), milliseconds >= 0
        else { break }
        let clamped = min(milliseconds, 5000 - total)
        pattern.append(clamped)
        total += clamped
    }
    return pattern
}

/// `CouchPadHost.haptic(primitive, scale)` as the shim posts it: `<primitive>,<scale>`.
/// Untrusted like the rest: an unknown primitive or a NaN/infinite scale plays nothing,
/// and the scale is clamped to 0–1 (CONTRACT.md §12).
func parseHaptic(_ value: String?) -> (primitive: HapticPrimitive, scale: Double)? {
    guard let value, value.utf16.count <= 64 else { return nil }
    let fields = value.split(separator: ",", omittingEmptySubsequences: false)
    guard fields.count == 2, let primitive = HapticPrimitive(rawValue: String(fields[0])),
          let scale = Double(fields[1]), scale.isFinite
    else { return nil }
    return (primitive, min(max(scale, 0), 1))
}

enum GameHostJS {
    /// Document-start user script (all frames): defines
    /// `window.CouchPadHost.{name,editName,gameEnded,leave,enableSystemBack,setOrientation,haptic}`
    /// posting `{type, value}` to `window.webkit.messageHandlers.cpHost`, and polyfills
    /// `navigator.vibrate` over the same channel (CONTRACT.md §11) — WebKit ships no
    /// Vibration API, which is why a game's haptics are silent on iOS but not on
    /// Android. Idempotent — the shim must exist on every page load/navigation, but
    /// never redefine an already-installed bridge.
    ///
    /// `enableSystemBack` is an empty function: iOS has no system back (CONTRACT.md §9),
    /// but games call the same API on both platforms. `setOrientation` narrows to the two legal
    /// keywords here, so the native side sees the contract's strict comparison (Android
    /// gets the same for free from its typed JS bridge) rather than JS truthiness.
    /// `vibrate` likewise normalizes to the spec's millisecond array and returns the
    /// spec's boolean, so a page sees the same shape and return type as the real API.
    ///
    /// `name` is baked in — WebKit has no synchronous page→app call — so the host
    /// rebuilds this script after a rename. Every `editName()` reaches the launcher and
    /// gets its own Promise; the launcher answers them in order through
    /// `__cpNameResult(name | null)`, which also updates `name` in place.
    static func bridgeShim(name: String) -> String {
        """
    (function () {
      if (window.CouchPadHost) { return; }
      var name = \(jsString(name));
      var waiting = [];
      window.__cpNameResult = function (result) {
        var done = waiting.shift();
        if (typeof result === 'string') { name = result; }
        if (done) { done(typeof result === 'string' ? result : null); }
      };
      function post(type, value) {
        try {
          window.webkit.messageHandlers.cpHost.postMessage({
            type: type,
            value: value == null ? null : String(value)
          });
        } catch (e) {}
      }
      window.CouchPadHost = {
        get name() { return name; },
        editName: function () {
          return new Promise(function (resolve) {
            waiting.push(resolve);
            post('editName', null);
          });
        },
        gameEnded: function (reason) { post('gameEnded', reason); },
        leave: function () { post('leave', null); },
        // A hint that back is welcome (CONTRACT.md §9). iOS has no system back to
        // hand it to, so it does nothing here — but it exists, so games call one API.
        enableSystemBack: function () {},
        setOrientation: function (mode) {
          post('setOrientation', mode === 'landscape' ? 'landscape' : 'portrait');
        },
        haptic: function (primitive, scale) {
          // Android's typed JS bridge turns a non-number into 0 but passes NaN through;
          // mirror it so the same call plays (or doesn't) on both.
          post('haptic', String(primitive) + ',' + (typeof scale === 'number' ? scale : 0));
        }
      };
      try {
        navigator.vibrate = function (pattern) {
          var list = Array.isArray(pattern) ? pattern : [pattern];
          var out = [];
          // Entry count and per-entry milliseconds mirror parseVibrationPattern's caps
          // (CONTRACT.md §11). Two languages, no shared constant: the native side is
          // authoritative and re-checks everything — keep these in step with it.
          for (var i = 0; i < list.length && i < 128; i++) {
            var ms = Math.round(Number(list[i]));
            if (!isFinite(ms) || ms < 0) { return false; }
            out.push(Math.min(ms, 5000));
          }
          post('vibrate', out.join(','));
          return true;
        };
      } catch (e) {}
    })();
    """
    }

    /// Document-start user script (main frame only): posts one `__firstFrame` after
    /// DOMContentLoaded plus two rAF turns — i.e. once the compositor has actually
    /// produced a frame with the page's content. Drives the "Joining…" cover fade
    /// (WKWebView has no native first-paint callback). DOMContentLoaded, not load: a
    /// hanging subresource must not keep the cover up after the UI has rendered. The
    /// handler reference is captured before any page code runs, so a page clobbering
    /// `window.webkit` can't break the signal. Launcher-injected and NOT part of the
    /// contract: games never send it, and a page spoofing it merely fades the cover
    /// early — the pre-fix behavior.
    static let firstFrameSignal = """
    (function () {
      var handler = window.webkit.messageHandlers.cpHost;
      function signal() {
        requestAnimationFrame(function () {
          requestAnimationFrame(function () {
            try { handler.postMessage({ type: '__firstFrame' }); } catch (e) {}
          });
        });
      }
      if (document.readyState !== 'loading') { signal(); }
      else { document.addEventListener('DOMContentLoaded', signal, { once: true }); }
    })();
    """

    /// Evaluated on UIApplication.didEnterBackground, inside the grace window
    /// before the web content process suspends (CONTRACT.md §7).
    ///
    /// Why (iOS-only quirk, no Android mirror of the underlying problem):
    /// WKWebView's networking runs in a separate system process that keeps the
    /// game's relay WebSocket alive — answering protocol-level pings — for as long
    /// as the suspended app lives, so the relay sees a connected player long after
    /// the user went home. On Android the OS freezes the whole app process and the
    /// relay times the player out. No engine fires `pagehide` on backgrounding, but
    /// games already close their socket in a `pagehide` handler (the bfcache path
    /// this event exists for), so a synthetic persisted pagehide is the
    /// standards-shaped way to say "stop now". The reconnect half needs no launcher
    /// help: the engine delivers the real `visibilitychange` → visible on
    /// foreground, and games reconnect off that.
    static let dispatchPageHide =
        "window.dispatchEvent(new PageTransitionEvent('pagehide', { persisted: true }));"

    /// Settles the page's pending `editName()` Promise (CONTRACT.md §2): the saved name, or
    /// null when the sheet was dismissed.
    static func nameResult(_ name: String?) -> String {
        "window.__cpNameResult && window.__cpNameResult(\(name.map(jsString) ?? "null"));"
    }

    /// A Swift string as a JS string literal (JSON-encoded, so quotes and escapes are safe).
    private static func jsString(_ value: String) -> String {
        String(data: (try? JSONEncoder().encode(value)) ?? Data(), encoding: .utf8) ?? "\"\""
    }

    /// Read once, when the page opens the name sheet (CONTRACT.md §2):
    /// `<meta name="theme-color">` (first one whose `media` matches) for the surface, and
    /// the CSS `accent-color` of `<html>` for the accent — `auto` (unset) reads as none.
    /// Both normalized to rgb() by computed style; CSS.supports() first, because an
    /// invalid color would fall back to the inherited one.
    static let readSheetColors = """
    (function () {
      function rgb(value) {
        if (!value || !CSS.supports('color', value)) return null;
        var probe = document.createElement('div');
        probe.style.color = value;
        document.documentElement.appendChild(probe);
        var color = getComputedStyle(probe).color;
        probe.remove();
        return color;
      }
      var metas = document.querySelectorAll('meta[name="theme-color"]');
      var meta = null;
      for (var i = 0; i < metas.length && !meta; i++) {
        var media = metas[i].getAttribute('media');
        if (!media || matchMedia(media).matches) meta = metas[i];
      }
      var accent = getComputedStyle(document.documentElement).accentColor;
      return { surface: rgb(meta && meta.getAttribute('content')),
               accent: accent === 'auto' ? null : rgb(accent) };
    })();
    """

    /// The color-scheme meta observer (CONTRACT.md §4), evaluated after each page load.
    /// Posts whether the page is dark straight to the `cpHost` handler — not through
    /// `CouchPadHost`, which is the games' API — immediately
    /// and on every change — head mutations via MutationObserver, plus system scheme
    /// flips (`light dark` follows the system). Pushes are deduped. No meta reads as
    /// light, the web's own default. Idempotent: re-running on a document that already
    /// has the observer just re-pushes.
    static let watchPageScheme = """
    (function () {
      if (window.__cpSchemePush) { window.__cpSchemePush(); return; }
      var handler = window.webkit.messageHandlers.cpHost;
      var systemDark = window.matchMedia('(prefers-color-scheme: dark)');
      var last = null;
      function push() {
        var meta = document.querySelector('meta[name="color-scheme"]');
        var schemes = ((meta && meta.getAttribute('content')) || '').toLowerCase().split(/\\s+/);
        var dark = schemes.indexOf('dark') >= 0 && (schemes.indexOf('light') < 0 || systemDark.matches);
        if (dark === last) return;
        last = dark;
        handler.postMessage({ type: '__scheme', value: String(dark) });
      }
      window.__cpSchemePush = push;
      if (document.head) new MutationObserver(push).observe(document.head,
        { childList: true, subtree: true, attributes: true, attributeFilter: ['content', 'name'] });
      systemDark.addEventListener('change', push);
      push();
    })();
    """
}
