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
- **Neutralize its own back/leave handling.** The shell shows its own LEAVE bar and
  swallows system back by default; modals relying on `history.back()` need a direct
  close instead — or opt into the gesture explicitly (§9).

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
   works where the engine cooperates.
2. **Launcher vars** (authoritative): `--cp-safe-top/-left/-right/-bottom` on
   `document.documentElement` — CSS px, live-updated, re-set on every navigation. These
   don't rely on the engine's cutout plumbing, which bails in more cases than you'd
   hope — Android's WebView can read all-zero `env()` even full-screen. The vars, not
   `env()`, are the values to trust; the `max()` pattern below folds both in.

Horizontal insets align with the chrome's *content*, not just the cutout, so a top row
anchored to the safe zone lines up with the launcher's controls — expect a small non-zero
value even with no notch.

**Left and right are always equal**, on both platforms. A landscape cutout sits on one
side only, but the launcher levels the pair to the larger, so a layout centered in the
safe box is centered on the physical screen. Don't try to recover which side the camera
is on from these — that difference is deliberately not published.

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
- It must name the **controller's own origin**. A template on the launcher domain
  (`couchpad.games/{room}`) declares nothing — that link is the launcher asking this same
  directory who owns the code — so the launcher ignores it and falls back to the room's
  `origin`. Register the host that actually serves your controller, or register nothing.
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
  scan may not, and the URL remembered from one won't either. The launcher therefore takes
  `cpp` from wherever it first appears — including the template returned by the liveness
  probe below — and keeps it for as long as it offers the room, rather than re-reading it
  off a URL that may never have carried it.
- The value is machine-readable and fixed-vocabulary; there is no free-text field for a
  model or browser name. The launcher renders the wording ("Apple TV") itself, localized,
  so it stays consistent and translatable without a display update, and no display can
  put arbitrary text on a launcher card. An unknown or absent value degrades to no name
  at all. `cpp` is `cp`-prefixed to stay clear of a game's own query params, and is inert
  in a browser.

The launcher resolves every **origin-less** input through `GET {relayBase}/room/{code}` —
a typed code, a §8 nearby tap, and a canonical `couchpad.games/<code>` link however it
arrives (scanned, tapped as a link). Only a URL that already names a controller origin
skips the directory and loads as-is. So a display's registered template decides where the
player lands no matter which way they joined:

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
(iOS Local Network, Android `ACCESS_LOCAL_NETWORK`). That permission governs ALL of a
game's LAN traffic, including any direct peer connection a game negotiates on its own
beside the relay — so the launcher asks at the player's first join, holding the
controller page load until the dialog is answered: the verdict is then in force before
the page's first connection attempt instead of racing it. A deny loads the page anyway,
which must treat the LAN as hostile exactly as on an AP-isolated network. The launcher
also asks when the player asks for discovery; never at launch. A display that
advertises must still show its QR and room code.

The card is branded from the manifest, not from the advertisement: the resolved game's
`icon` (a square brand mark, distinct from the 16:9 `art`) sits on the leading tile, and
carries the branding alone — the card itself is neutral chrome. A game with no `icon`
falls back to a generic glyph.

## 9. Game ⇄ launcher, system back: `enableSystemBack` + `back()`

By default the shell owns the screen edges: the whole controller surface is opted out of
the system back gesture, so edge swipes are gameplay input and a stray one can't drop a
player out of a live match. A game that wants back — to close a dialog, or because the
player is somewhere leaving is harmless — asks for it, moment by moment.

```js
window.CouchPadHost?.enableSystemBack?.(true);   // dialog opened / entered the lobby
window.CouchPadHost?.enableSystemBack?.(false);  // dialog closed / match resumed
```

The launcher *implements* `enableSystemBack`, the game *calls* it. **Default false**,
including before the first call and after every page load — arming never outlives the page
that meant it. Only a literal `true` arms; every other value disarms.

| State | Screen edges | A back gesture |
|-------|--------------|----------------|
| `false` *(default)* | opted out — edge swipes reach the game | can't start; LEAVE is the only exit |
| `true` | yielded to the system, with its own back affordance | goes to `back()` below |

**Arming costs the game its screen edges** — that is the trade, not a side effect. A game
that arms for a dialog and forgets to disarm when it closes plays the rest of the match
without edge swipes, with nothing on screen to explain why. Disarm is not optional.

The game *implements* `back()`, the launcher *calls* it — once per gesture, only while
armed:

```js
window.CouchPad = window.CouchPad || {};
window.CouchPad.back = () => {
  if (!dialogOpen) return false;   // not ours → the launcher leaves the game
  closeDialog();
  return true;                     // consumed → the player stays
};
```

- Returning a literal `true` consumes the gesture. **Anything else** — a falsy return, no
  return, no `back` at all, or a throw — leaves the game, through the same exit as the
  LEAVE bar.
- **Decide synchronously.** A Promise is not awaited and counts as unconsumed; start async
  work if you need to, but return the boolean now.
- So a lobby or results screen that just wants back to leave arms and implements nothing.

Both halves are inert in a plain browser: `CouchPadHost` doesn't exist, so the optional
call is a no-op, and nothing ever calls `back()`. The browser's own back button keeps
doing whatever it did.

Platform notes, for behavior a game can observe: how much of the screen edge is yielded
differs. iOS yields only the *leading* edge — the right one under RTL. Android hands the
whole surface back to the system, so back can start from either edge, as it does everywhere
else on the platform. Budget for both: don't put a drag-from-the-very-edge control anywhere
while armed. Android draws its system back arrow during the gesture and
also routes the hardware/3-button back here; iOS has no equivalent system affordance
during the swipe, so a game arming for a non-obvious reason should say so in its own UI.

Arming does **not** move the safe zone for a gesture-navigation player: the launcher
keeps the navigation bar hidden, and the transient bars Android shows over a hidden-bar
app are an overlay, not an inset. The cost sits in the gesture itself — after a few quiet
seconds the system spends the first edge swipe *revealing* those transient bars instead
of going back, and it is the follow-up swipe that lands here. Budget for the occasional
double swipe, not for lost space. A **3-button-nav** player has no back without the bar's
buttons, so for them arming still brings the bar back for as long as it lasts —
`--cp-safe-bottom` grows in portrait, and in landscape (where a 3-button bar sits on a
*side*) it is the (levelled, §5) side insets that grow — shrinking again on disarm. One
more reason to treat the safe zone as live rather than reading it once at startup. iOS is
unaffected — its back gesture is the launcher's own recognizer, not a system one, and its
home indicator is always in the safe area.

## 10. Game → launcher, screen orientation: `setOrientation(mode)`

The launcher is portrait. A controller whose layout wants the long edge across — a
steering wheel, a wide track pad, a landscape mini-map — asks for landscape, moment by
moment.

```js
window.CouchPadHost?.setOrientation?.('landscape');   // match started
window.CouchPadHost?.setOrientation?.('portrait');    // back to the lobby
```

The launcher *implements* `setOrientation`, the game *calls* it. **Default `'portrait'`**,
including before the first call and after every page load — an orientation never outlives
the page that asked for it. Only the literal `'landscape'` rotates; every other value,
including a non-string, means portrait.

| Mode | The device |
|------|-----------|
| `'portrait'` *(default)* | locked portrait — a controller that never asks can't be rotated out from under the player |
| `'landscape'` | turns to landscape and follows the sensor **between the two landscape orientations**, so either hand works; it will not fall back to portrait |

**Call it as early as you can.** The bridge exists before your first script runs, so a
landscape-only controller that calls from a `<head>` script rotates while the launcher's
own "Joining…" cover is still up — the player never sees the turn. Deciding later (after
the socket connects, say) is fine and supported; it just rotates in view.

Make that an **external** `<head>` script (`<script src="…">`, not deferred), not an
inline one. Game origins typically ship `script-src 'self'`, which blocks inline script
outright — an inline early call silently never runs, and the page comes up portrait with
nothing to say why. Same reason the checklist tells you to keep contract code in your own
bundle.

**The safe zone changes shape, not just size** (§5). In landscape the launcher's bar
disappears entirely — the chrome collapses to two floating controls (leave, rename)
stacked in one side strip — so `--cp-safe-top` typically drops to ~0 and the game gets
the full height. The (levelled) *side* insets become the large ones, carrying the display
cutout and the launcher's controls alike; which side the camera — and the controls — sit
on is still deliberately not published. A layout that hard-codes "the notch is on top"
breaks here. The vars are re-published on every rotation, so read them live rather than
at startup.

Inert in a plain browser: `CouchPadHost` doesn't exist, so the optional call is a no-op
and the page keeps whatever the browser and the user's rotation lock were doing. A game
that needs landscape in the browser too should keep its own CSS/`screen.orientation`
handling — this bridge does not replace it.

Platform notes, for behavior a game can observe: a rotation does **not** reload the page
or drop the relay socket on either platform — the same document keeps running, so state
in JS survives. What the game does see is a `resize`, a changed
`window.matchMedia('(orientation: landscape)')`, and re-published `--cp-safe-*` vars.
Android additionally rotates on the sensor even with the system's own rotation lock on —
the launcher's request outranks it. On either platform, a player holding the phone flat
may see the turn settle a beat later, since there is no gravity vector to pick a side
from. In Android split-screen the system ignores orientation
requests entirely — the page keeps the shape it has, and the request takes effect when
the app is full-screen again.

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
9. *(Optional)* Arm the system back gesture with `enableSystemBack(true)` where back is
   welcome, disarm the moment it isn't, and implement `window.CouchPad.back()` if there's
   something to close (§9).
10. *(Optional)* Ask for landscape with `setOrientation('landscape')` if the controller
    wants it — as early as possible — and handle the side-moving safe zone (§10).
11. *(Native display apps only)* Advertise the room over `_couchpad._tcp` so the launcher
    can offer one-tap join (§8).

Every touchpoint above has a live reference implementation — a stand-in controller
that arms and disarms system back, answers `back()` three different ways, swaps its
theme metas and draws its safe zone. Open <https://test.couchpad.games/CPTEST> from
the launcher to watch each one behave; the source is `controller-test.html` in the
couchpad.games site repo, and it is updated in the same change as this document.

Keep all contract code in the game's own bundle — game origins typically ship
`script-src 'self'`, and what the launcher injects is only ever glue: the guarded
`setName` call, a self-contained meta observer, and the guarded `back()` call (both
platforms inject via their `evaluateJavaScript` equivalent, which is exempt from the
page's CSP).

## Versioning

There is no version number on the wire. Every touchpoint is feature-detected — a param
that is there or isn't, a bridge object that exists or doesn't — so a game implements
what it recognizes and ignores the rest, and the launcher can add capabilities without a
coordinated release.

That makes additions free and breaks expensive by construction: a change old games cannot
survive can't be signalled by a version bump, so it ships as a **new param or bridge
name** that only updated games look for, leaving everyone else on today's behavior. The
`— v1` in this document's title names the document, not a handshake.
