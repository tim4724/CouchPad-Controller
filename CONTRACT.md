# CouchPad Controller Contract — v1

The interface between the **CouchPad launcher** — the Android app (WebView) and iOS app
(WKWebView), identical unless a section says otherwise — and a **game's controller page**.
A game that implements it plugs in with no launcher change beyond its entry in
`games-manifest.json`.

The launcher handles joining and the player's name, and draws nothing over a running game
unless the game asks (§2). The page spans the whole screen and owns everything on it,
including its close button. The launcher's own screens come only before the page shows
or when it fails to load: a join cover and a retry cover, each with its own close.

**Rules for every section:**

- **Feature-detect.** Shell behavior is gated on `window.CouchPadHost`, which only the
  launcher defines; the same deployed controller must keep working in a plain browser.
- **Bundle your contract code.** Game origins typically ship `script-src 'self'`; inline
  scripts never run. The launcher's own injections are exempt.
- **`cp*` is reserved** for query params. Don't mint your own; ignore ones you don't know.
- Everything a page sends is untrusted; the launcher validates it.

## 1. Launcher → game: `CouchPadHost.name`

```js
const name = window.CouchPadHost?.name ?? myBrowserName;
```

The player's name, shared across games: a non-blank string of at most 16 characters.
It is there before the page's first script runs and always current. On first run the
launcher makes one up, so there is no need to ask for one. An Android WebView too old
for document-start scripts has neither `name` nor `editName` — the fallback above covers
it.

## 2. Rename: `CouchPadHost.editName()`

Optional. The launcher owns the name and its rules, so a game that offers a rename opens
the launcher's name sheet instead of its own field:

```js
renameButton.onclick = async () => {
  if (!window.CouchPadHost?.editName) return openOwnNameField();   // browser
  const name = await CouchPadHost.editName();   // null when the player dismisses it
  if (name) applyAndTellTheDisplay(name);
};
```

The resolved name already meets the launcher's rules and is from then on
`CouchPadHost.name`, here and in every later game. Every call gets its own sheet and
answer: one made while a sheet is still closing opens a new sheet once it has gone.
Android back while the sheet is up dismisses it (resolving `null`) and never reaches the
page's `back()` (§9).

The sheet takes its look from standard page styling, read when `editName` is called:

| Source | Sheet |
|---|---|
| `<meta name="color-scheme">` (§4) | light or dark text and controls |
| `<meta name="theme-color">` | surface — keep it readable in that scheme |
| CSS `accent-color` on `:root` | Save button and field accent |

Anything absent falls back to the launcher's own palette.

## 3. Game → launcher: `CouchPadHost.leave()` and `gameEnded(reason)`

Both close the web view and return home; the first call wins. Don't also navigate.
Closing the web view destroys the page and with it its connections, relay socket
included, so there is nothing to clean up first. (§7's lingering socket is a page that
stays alive in the background, not one that is closed.)

- **`leave()`** — the player's own exit. Draw a visible close button that calls it: it is
  the only way out (iOS has no system back; Android's is off by default, §9). Whether to
  confirm first is the game's call. Never call `leave` when it doesn't exist; in a
  browser the button is yours to repurpose or hide.
- **`gameEnded(reason)`** — a terminal end the player didn't choose: room closed, display
  gone, join rejected. Not for match-over or "play again". Home shows a message:

| `reason` | Message |
|---|---|
| `game_ended` | "The party ended" |
| `room_not_found` | "Room not found" |
| `game_full` | "Room is full" |
| `replaced` | "You joined from another device" |
| anything else | "The party ended" |

## 4. Game → launcher: `<meta name="color-scheme">`

The status bar stays visible in portrait, drawn over the page; its icon color follows the
page's `color-scheme` meta, watched live.

| `content` | Icons |
|---|---|
| `dark` | light |
| `light`, or no meta | dark |
| `light dark` | follow the system |

Keep the band under the status bar (`env(safe-area-inset-top)`) calm and in that scheme.

## 5. Layout: full screen and the safe area

The page covers the whole physical screen. Let visuals bleed to the edges; keep
interactive UI inside `env(safe-area-inset-*)`, which requires `viewport-fit=cover`.

- The insets are the platform's own: status bar, display cutout, iOS home indicator, and
  the Android navigation bar while it is shown (§9). Each side is its own value.
- They change with rotation (§10) and §9 — read them live.
- The keyboard never resizes the layout viewport or moves the insets; a focused field
  shrinks `visualViewport` as in a browser.
- On an Android WebView older than Chromium 136, which can't report system bars, the
  launcher keeps the page below the bars instead and the insets read 0.

```css
#hud { padding: env(safe-area-inset-top, 0px) env(safe-area-inset-right, 0px) 0
                env(safe-area-inset-left, 0px); }
/* Centered on the physical screen: level the pair yourself. */
#wheel { padding-inline: max(env(safe-area-inset-left, 0px), env(safe-area-inset-right, 0px)); }
```

## 6. Display → relay: controller-URL template

A typed room code carries no origin, so the display registers where its controller lives
when it creates the room:

```
Client → relay:  create { clientId, maxClients, url? }
```

- `url` is an https template of the join-URL shape — room code as the first path
  segment, instance in the fragment: `https://play.example.com/{room}#{instance}`.
- It must name the controller's own origin; a template on the launcher domain is ignored.
- An invalid template rejects the whole create, so plain-http dev setups pass none — their
  rooms are then joinable only by scanning.
- It may carry `cpp=tvos|androidtv|web` naming the display. Put it in the template: the
  launcher reads it wherever it first appears and renders the device name itself.

The launcher resolves every origin-less input — typed code, §8 nearby tap, a
`couchpad.games/<code>` link — through `GET {relayBase}/room/{code}`:

```
200 → { url?, origin?, clients, maxClients }   404 → not found
```

`url` arrives with placeholders filled in. It is untrusted: its host must be in the
manifest allow-list. Without a registered template, a code can't be joined that way.

## 7. Launcher → game: synthetic `pagehide` on background

When the app goes to the background, the launcher dispatches
`new PageTransitionEvent('pagehide', { persisted: true })` on `window`. Close the relay
socket there, or the display keeps a zombie player (iOS never drops the socket on its own).
Reconnect on the standard `visibilitychange` → `visible`. Both are ordinary web events,
so the same code is right in a browser.

## 8. Native display → LAN: room advertisement

A native display (tvOS, Android TV) may advertise its room over DNS-SD as
`_couchpad._tcp.local`, for one-tap join. The instance name is the display's label
("Living Room"), shown verbatim.

| TXT key | Value |
|---|---|
| `c` | Required. The room code as shown on screen. |
| `cpr` | Launcher-only: marks a controller relaying a room it is in. Displays never set it. |

- The code is the whole payload: the launcher resolves it through §6, so the record
  carries no URL and can't point anywhere. The SRV port is never dialed.
- Advertise at create, send a goodbye at close. Withdrawing while full is optional.
- New TXT keys are fine; a shape old launchers must not read needs a new service type.
- Discovery is optional: it needs the Local Network permission and fails on isolated
  networks, so keep showing the QR and code. The launcher asks for that permission before
  loading the page on a first join; a deny still loads it, so treat the LAN as unreliable.

## 9. System back: `enableSystemBack` + `back()`

Call it on both platforms; only Android acts on it. iOS has no system back, so there
`enableSystemBack` does nothing and `back()` is never called.

By default the controller surface is opted out of the back gesture, so edge swipes are
gameplay. Ask for back when it is welcome, and give it up the moment it isn't:

```js
window.CouchPadHost?.enableSystemBack?.(true);    // dialog opened / in the lobby
window.CouchPadHost?.enableSystemBack?.(false);   // dialog closed / match resumed
```

Only a literal `true` arms; it resets on every page load. Armed, the edges belong to the
system (no drag-from-the-edge controls), and each back — gesture or 3-button — calls:

```js
window.CouchPad = window.CouchPad || {};
window.CouchPad.back = () => {
  if (!dialogOpen) return false;   // not consumed → the launcher leaves the game
  closeDialog();
  return true;                     // consumed → the player stays
};
```

Only a synchronous literal `true` consumes; anything else (no handler, a Promise, a
throw) leaves like `leave()`. While armed, the navigation bar comes back — in portrait,
and in landscape too for 3-button navigation — and grows that edge's inset.

## 10. Game → launcher: `CouchPadHost.setOrientation(mode)`

The launcher is portrait. `setOrientation('landscape')` turns to landscape and follows the
sensor between its two sides; anything else means portrait. It resets to portrait on any
page that finishes loading without asking — a reload of a landscape page holds landscape
if it asks again from an external `<head>` script. Asked that early, the launcher keeps
its join cover up until the turn is done, so the page is never seen portrait-shaped.

A rotation keeps the page and its socket running; the game sees `resize`, the orientation
media query, and new insets. In landscape the status bar is hidden, so the top inset
usually drops to 0 and the side insets carry the cutout and any navigation bar.

## 11. Game → device: `navigator.vibrate(pattern)`

The standard Vibration API. Android's WebView has it; the iOS launcher defines it (WebKit
doesn't), so the usual `if (navigator.vibrate)` guard works in both apps. Keep haptics
decorative — mobile Safari still has none.

The motors differ (Android coasts through short gaps, iPhone starts and stops dead), so
stay within patterns that feel alike on both:

- **Tap:** one pulse of 10–40 ms.
- **Rhythm:** pauses of 50 ms or more, e.g. `[10, 50, 10]`.
- **Held buzz:** a pulse of 100 ms or more, re-issued before it ends, stopped with
  `vibrate(0)`.
- **Lighter** means shorter or sparser pulses — never pauses under 50 ms.

Patterns are capped at 128 entries and 5 s.

## 12. Game → launcher: `CouchPadHost.haptic(primitive, scale)`

A weaker or shaped tap than §11 can make: one of Android's composition primitives at a
strength — the vocabulary AirConsole's composition vibrate takes.

```js
if (window.CouchPadHost?.haptic) CouchPadHost.haptic('click', 0.7);
else navigator.vibrate?.(14);
```

| `primitive` | Feel |
|---|---|
| `click` | strong, crisp tap |
| `tick` | light, sharp tap, for rapid repeats |
| `low_tick` | soft, low tap, for rapid repeats |
| `thud` | low knock that rings out (~300 ms) |
| `spin` | wobble (~150 ms); best two or three in a row |
| `quick_rise`, `slow_rise` | builds to a peak (~150 / ~500 ms) |
| `quick_fall` | drops from a peak (~100 ms) |

- `scale` is clamped to 0–1; 0 is the faintest buzz, not silence.
- One primitive per call; each call replaces whatever is playing.
- Always plays something — the closest available effect where the device lacks one.
- An unknown name, or a `NaN`/infinite scale, plays nothing.
- On Android these follow the media-vibration setting, not touch feedback.

## Platform note: tilt controls

Motion and orientation events are plain web APIs; the launcher grants iOS's sensor gate
for allow-listed origins, so no dialog appears in either app. iOS still requires the
request to come from a user gesture:

```js
// In the tap that enables tilt — not at load.
if (typeof DeviceOrientationEvent?.requestPermission === 'function') {
  if (await DeviceOrientationEvent.requestPermission() !== 'granted') return showTiltUnavailable();
}
addEventListener('deviceorientation', onTilt);
```

A missing `requestPermission` means no gate (Android), not a missing sensor.

## Checklist for a new game

1. Use `CouchPadHost.name` as the player's name (§1); offer a rename through
   `editName()` (§2).
2. A close button calling `leave()`; `gameEnded(reason)` on terminal end (§3).
3. A `color-scheme` meta (§4).
4. `viewport-fit=cover`, interactive UI inside `env(safe-area-inset-*)` (§5).
5. Close the socket on `pagehide`, reconnect on `visibilitychange` (§7).
6. A controller-URL template on room create (§6) and an entry in `games-manifest.json`.
7. *Optional:* Android back (§9), landscape (§10), haptics (§11–12), mDNS for native
   displays (§8).

<https://test.couchpad.games/CPTEST> is a reference controller exercising every
touchpoint; its source, `controller-test.html` in the couchpad.games site repo, changes
together with this document.

## Versioning

There is no version on the wire: every touchpoint is feature-detected, so additions need
no coordinated release. A change old games can't survive ships under a **new** param or
bridge name, never as a redefinition. "v1" names this document, not a handshake.
