# CouchPad Controller Contract — v1

The interface between the **CouchPad launcher** — the native **Android app** (WebView)
and **iOS app** (WKWebView), which behave identically here — and a **game's controller
page**. A game that implements these touchpoints plugs in with zero launcher-side
changes; new games only declare themselves in `games-manifest.json`.

The launcher owns *identity* (player name) and *session chrome* (joining, leaving).
The game owns everything else: colors, avatars, sound, gameplay, match flow.

## 1. Launcher → game, at load: URL parameters

Appended to the join URL, preserving any existing `?claim=` and `#instance`:

```
https://<game-host>/<ROOMCODE>?cpv=1&cpName=<name>[#<instance>]
```

| Param    | Meaning |
|----------|---------|
| `cpv=1`  | Contract version; presence means "running inside the CouchPad shell". Gate ALL shell behavior on it — the same deployed controller must keep working in a plain browser. |
| `cpName` | The player's name. Guaranteed non-blank and ≤ 16 characters; sanitize defensively anyway. |

When `cpv=1` is present, the game must:

- **Skip the name screen.** Use `cpName` and never offer a path back to name entry —
  the launcher is the identity authority.
- **Not persist the injected name.** It arrives fresh on every launch/rename;
  persisting it would leak into the standalone-browser experience.
- **Neutralize its own back/leave handling.** The shell swallows system back and shows
  its own LEAVE bar; modals relying on `history.back()` need a direct close instead.

## 2. Launcher → game, live rename: `window.CouchPad.setName(name)`

The game *implements*, the launcher *calls* — on rename, and again on every page load
(belt-and-suspenders with `cpName`).

```js
window.CouchPad = {
  setName(name) {
    // Apply live: update local UI AND broadcast to the display,
    // exactly like an in-game rename would.
  },
};
```

The launcher's call is guarded, so not implementing it is a harmless no-op — the URL
param still prefills the name on the next load.

## 3. Game → launcher, session end: `window.CouchPadHost.gameEnded(reason)`

The launcher *implements* (Android `addJavascriptInterface`, iOS `WKScriptMessageHandler`
behind an injected shim), the game *calls* — **only on terminal session end**: room
closed, display gone for good, join rejected. Match-over / "Play again" screens are game
flow and stay in-game.

```js
if (window.CouchPadHost?.gameEnded) {
  window.CouchPadHost.gameEnded(reason);
} else {
  // plain-browser fallback: whatever the game normally does
}
```

The launcher tears down the web view and returns home with a message. The call is
fire-once (extras ignored); the game must **not** also navigate itself.

| `reason`         | Message shown |
|------------------|---------------|
| `game_ended`     | "The party ended" |
| `room_not_found` | "Room not found" |
| `game_full`      | "Room is full" |
| `replaced`       | "You joined from another device" |
| *anything else*  | "The party ended" (unknown values tolerated) |

## 4. Game → launcher, optional theming hints: `<head>` metas

The launcher tints its floating chrome to match, at load and live — an injected observer
watches the metas, so mutating `content` retints mid-session. Pure cosmetics, ignored in
a plain browser.

```html
<meta name="theme-color" content="#0b1020">      <!-- web standard -->
<meta name="cp-accent-color" content="#ffcc00">  <!-- CouchPad custom -->
```

| Meta | Launcher effect |
|------|-----------------|
| `theme-color`     | Tints the top chrome (scrim behind the status bar + LEAVE bar). Text/icons flip black/white by luminance. |
| `cp-accent-color` | Colors launcher accents shown over the game: name chip, joining spinner, rename sheet controls. |

Any sRGB CSS color; alpha ignored (the chrome is opaque); absent or unparseable falls
back to the launcher's dark graphite. `media` attributes are honored (first matching meta
wins) and re-evaluated on system scheme flips. Metas must live in `<head>`.

## 5. Launcher → game, layout: edge-to-edge hosting and the safe zone

The page spans the **full physical screen** with the chrome floating on top. Visuals may
(and should) bleed behind it — **interactive UI must stay out from under it**. The safe
zone is published two ways:

1. **Standard CSS**: with `viewport-fit=cover`, the launcher folds its chrome and the
   display cutout/gutter into `env(safe-area-inset-*)`, so the standard notch machinery
   just works.
2. **Launcher vars** (authoritative): `--cp-safe-top/-left/-right/-bottom` on
   `document.documentElement` — CSS px, live-updated, re-set on every navigation. These
   don't rely on the engine's cutout plumbing, which bails in some cases (e.g. Android
   split-screen).

Horizontal insets align with the chrome's *content*, not just the cutout, so a top row
anchored to the safe zone lines up with the launcher's controls — expect a small non-zero
value even with no notch.

Recommended pattern — correct in the shell AND in a plain browser:

```css
#hud {
  padding-top: max(var(--cp-safe-top, 0px), env(safe-area-inset-top, 0px));
}
```

## 6. Game display → relay, at room create: controller-URL template

A scanned QR carries the full controller URL; a **typed room code** carries nothing. So
the display registers a *controller-URL template* with the shared relay at room create,
and the relay tells the launcher where the code lives.

```
Client → relay:  create { clientId, maxClients, url? }
```

- `url` is an absolute **https** template of the join-URL shape with `{room}` and
  `{instance}` placeholders — e.g. `https://play.example.com/{room}#{instance}`. It must
  match what a scanned QR produces: room code as the first path segment, instance in the
  fragment (kept out of request logs).
- The relay **rejects the whole create** on an invalid template, so plain-http origins
  (local dev, E2E) must pass no `url`.
- Optional: a display that registers none but is served from a CouchPad-owned origin is
  still findable via that `origin`.

The launcher resolves a typed code through `GET {relayBase}/room/{code}`:

```
200 → { url?, origin? }   404 → not found
```

- `url` — the stored template with `{room}`/`{instance}` **already substituted** (the
  launcher never sees raw placeholders).
- `origin` — fallback when no template was registered; the launcher resolves the bare
  code against it (`<origin>/<code>`).
- **Both are host-declared and UNTRUSTED.** The launcher re-validates the host against the
  `games-manifest.json` allow-list before loading, so a relay entry can't redirect a code
  to an arbitrary origin.

Registering a `url` is what makes typed-code join deterministic; without it a code only
resolves when the game is the sole live one or is disambiguated by `origin`.

## 7. Launcher → game, app lifecycle: synthetic `pagehide` on background

When the player leaves the launcher (home, app switch, lock), the shell dispatches a
synthetic persisted `pagehide` on `window` — the same event a browser fires when freezing
a page into the back/forward cache:

```js
window.dispatchEvent(new PageTransitionEvent('pagehide', { persisted: true }));
```

Close the relay socket in the `pagehide` handler so the display sees the player leave
*immediately*. Without it, disconnect timing is platform luck: Android drops the socket
only when the OS freezes the cached process (OEM-dependent), and iOS never drops it —
WKWebView's out-of-process network stack keeps answering pings while suspended, leaving a
zombie player on the display.

There is no synthetic counterpart on return: the engine fires the standard
`visibilitychange` → `visible`, and the controller should reconnect there. Both events are
ordinary web behavior, so the same code is correct in a plain browser. Additive in v1 — a
game without a `pagehide` handler keeps today's behavior.

## Checklist for a new game

1. Read `cpv` + `cpName`; when `cpv=1`: skip name entry, don't persist the name,
   suppress own back/leave affordances.
2. Implement `window.CouchPad.setName(name)`: apply locally + broadcast.
3. Call `window.CouchPadHost.gameEnded(reason)` at the terminal-session-end chokepoint
   when available, else fall back to normal web behavior.
4. Keep interactive UI inside the safe zone (§5).
5. Close the relay socket on `pagehide`; reconnect on `visibilitychange` → `visible` (§7).
6. *(Optional)* Declare `theme-color` / `cp-accent-color` metas (§4).
7. Register the controller-URL template on room create so typed codes resolve (§6).
8. Declare the game in `games-manifest.json` (hosts, controllerBaseUrl, room-code format).

Keep all contract code in the game's own bundle — game origins typically ship
`script-src 'self'`, and the launcher only injects the guarded `setName` call and a
self-contained meta observer (both platforms inject via their `evaluateJavaScript`
equivalent, which is exempt from the page's CSP).

## Versioning

`cpv` is bumped only for breaking changes. Games should treat an unknown higher version as
"shell present, behave per the highest version you know".
