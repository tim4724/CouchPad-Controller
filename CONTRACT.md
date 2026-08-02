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
https://<game-host>/<ROOMCODE>?cpName=<name>[#<instance>]
```

| Param    | Meaning |
|----------|---------|
| `cpName` | The player's name. Guaranteed non-blank and ≤ 16 characters; sanitize defensively anyway. Its **presence is the shell gate** — the launcher is the only thing that sends it. Gate ALL shell behavior on it: the same deployed controller must keep working in a plain browser. |

The `cp`-prefix is **reserved** across this contract — query params (`cpName`, `cpp`) and
metas (`cp-accent-color`). A game must not mint its own `cp*` params, and must ignore any
it doesn't recognize: they may be addressed to the launcher, not to it, and more can be
added in a later revision.

When `cpName` is present, the game must:

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
- The template may carry a `cpp` query arg naming the display: `tvos`, `androidtv`, or
  `web` (a browser-based display, which by definition can't advertise over mDNS but can
  register a template). A join URL is the only place a display declares itself, and the
  **template** is the only URL guaranteed to carry it — a display may deliberately keep
  its QR clean, on the grounds that whoever scans it is already looking at the box. So a
  typed code and a §8 nearby tap see `cpp` whenever the relay answers with a template; a
  scan may not, and the URL remembered from one won't either. The launcher therefore
  prefers the live advertisement's resolved URL for a rejoin card and falls back to the
  remembered one, which is what still names the box once the room is gone.
- The value is machine-readable and fixed-vocabulary; there is no free-text field for a
  model or browser name. The launcher renders the wording ("Apple TV") itself, localized,
  so it stays consistent and translatable without a display update, and no display can
  put arbitrary text on a launcher card. An unknown or absent value degrades to no name
  at all. `cpp` is `cp`-prefixed to stay clear of a game's own query params, and is inert
  in a browser.

The launcher resolves a typed code through `GET {relayBase}/room/{code}`:

```
200 → { url?, origin?, clients, maxClients }   404 → not found
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

## 8. Native display app → local network: room advertisement

A **native** display app (tvOS, Android TV) advertises the room it is hosting over
DNS-SD/mDNS, so the launcher can offer one-tap join with no QR scan and no typed code.
A browser-based display can't do this — browsers cannot advertise mDNS — so §6 remains
the universal path.

Service type `_couchpad._tcp` in the `.local` domain. The **instance name is the
display's human label** ("Living Room"); the launcher shows it verbatim so a player with
two TVs can tell them apart.

| TXT key | Required | Value |
|---------|----------|-------|
| `c`   | yes | The room code, exactly as shown on screen. Nothing else. |
| `cpr` | never (launcher-only) | Marks a record published by a **controller relaying a room it is in**, rather than by the display. A display must never set it. Launchers relay so that a browser-based display — which cannot advertise at all — still becomes discoverable, and so a room survives a native display whose own record is missing. A relaying phone may never have learned the room's label, so `cpr=1` tells the launcher its instance name is not one. Launcher↔launcher only; no game or display work involved. |

- The code is the **whole payload**. The launcher resolves it through
  `GET {relayBase}/room/{code}` (§6) — the same probe a typed code takes — and that
  response supplies the join URL, the display's `cpp`, whether the room still exists, and
  its occupancy. A display therefore declares itself in exactly one place, the template it
  registered at create, regardless of how a player arrives.
- Nothing on the LAN is trusted beyond the code. An advertisement can name a room but
  **cannot propose an origin**, so there is no host to re-validate and no way to point the
  launcher at an arbitrary page. That is the reason the record carries no URL.
- Discovery therefore needs the internet, like joining does: a code no relay can resolve
  produces no card.
- The SRV port is never dialed. The launcher reads the TXT record and nothing else.
  Advertise any listening port your responder needs; it stays unused.
- Advertise at room create, withdraw at room close (an mDNS goodbye — records at TTL 0).
  Withdrawing when the room **fills**, and re-publishing when a slot frees, is
  recommended: the launcher already hides a full room (it compares `clients` against
  `maxClients` on resolve), but it only re-checks when a record appears, so a display that
  goes quiet is what keeps a full room off the list promptly.
- A record that outlives its room is harmless — resolution 404s and no card appears.
- Two displays hosting two rooms produce two records; the launcher lists both. One room
  announced by its display *and* by every controller in it also produces several records;
  the launcher collapses them on `c` before resolving, so a room costs one probe however
  many devices announce it.
- The record carries no version field: `_couchpad._tcp` plus a code some relay knows *is*
  the gate, and a record without a usable `c` is ignored. Later revisions add keys, which
  old launchers skip; a shape old launchers must not read at all takes a new service type.

Discovery is an accelerator, never the only route — mDNS is blocked on AP-isolated and
guest networks, and both platforms gate it behind a permission the player must grant
(iOS Local Network, Android `ACCESS_LOCAL_NETWORK`; the launcher asks only when the
player asks for it, never at launch). A display that advertises must still show
its QR and room code.

The card is branded from the manifest, not from the advertisement: the resolved game's
`icon` (a square brand mark, distinct from the 16:9 `art`) sits on the leading tile, and
carries the branding alone — the card itself is neutral chrome. A game with no `icon`
falls back to a generic glyph.

## Checklist for a new game

1. Read `cpName`; when it's there: skip name entry, don't persist the name, suppress own
   back/leave affordances.
2. Implement `window.CouchPad.setName(name)`: apply locally + broadcast.
3. Call `window.CouchPadHost.gameEnded(reason)` at the terminal-session-end chokepoint
   when available, else fall back to normal web behavior.
4. Keep interactive UI inside the safe zone (§5).
5. Close the relay socket on `pagehide`; reconnect on `visibilitychange` → `visible` (§7).
6. *(Optional)* Declare `theme-color` / `cp-accent-color` metas (§4).
7. Register the controller-URL template on room create so typed codes resolve (§6).
8. Declare the game in `games-manifest.json` (hosts, controllerBaseUrl, room-code format).
9. *(Native display apps only)* Advertise the room over `_couchpad._tcp` so the launcher
   can offer one-tap join (§8).

Keep all contract code in the game's own bundle — game origins typically ship
`script-src 'self'`, and the launcher only injects the guarded `setName` call and a
self-contained meta observer (both platforms inject via their `evaluateJavaScript`
equivalent, which is exempt from the page's CSP).

## Versioning

There is no version number on the wire. Every touchpoint is feature-detected — a param
that is there or isn't, a bridge object that exists or doesn't — so a game implements
what it recognizes and ignores the rest, and the launcher can add capabilities without a
coordinated release.

That makes additions free and breaks expensive by construction: a change old games cannot
survive can't be signalled by a version bump, so it ships as a **new param or bridge
name** that only updated games look for, leaving everyone else on today's behavior. The
`— v1` in this document's title names the document, not a handshake.
