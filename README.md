# CouchPad Controller

Native **controller** apps for the CouchPad party-game suite. The
TV/computer is the display; phones are the controllers: scan the room code the
display shows and your phone becomes the gamepad.

The launcher (home screen) is fully native per platform. Each game's
controller is a **remote web page** loaded in a hardened top-level web view
under launcher-owned chrome — games ship controller changes without an app
update.

[CONTRACT.md](CONTRACT.md) is the launcher⇄game contract: join-URL identity
params, live rename, session end, theming hints, the safe zone, and the
`_couchpad._tcp` room advertisement that puts a one-tap join card on home with no
scan at all: a **native** display app (tvOS / Android TV) announces its room code,
and a controller already in a room relays it so browser-hosted rooms are findable
too. Both apps implement it identically.

## Layout

- `android/` — Jetpack Compose / Material 3 app (Kotlin)
- `ios/` — SwiftUI app (Swift, project generated with XcodeGen; see
  [ios/README.md](ios/README.md))

## Android: build & run

Standard Gradle project — open `android/` in Android Studio, or:

```sh
cd android
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties   # once per checkout
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

QR scanning is an in-app CameraX + zxing-cpp scanner — fully on-device, no
telemetry (CAMERA permission, with manual room-code entry as the fallback).

## iOS: build & run

```sh
cd ios
xcodegen generate    # brew install xcodegen, once
open CouchPad.xcodeproj
```

QR scanning uses AVFoundation (camera permission) and degrades to manual
room-code entry in the simulator.

## Localization

Both apps ship the 11 HexStacker locales (en + de, es, fr, it, ja, ko, pt, ru,
tr, zh): Android in `res/values[-XX]/strings.xml`, iOS in
`CouchPad/Resources/*.xcstrings`. Per-game display copy (e.g. player counts)
lives in those same string resources under `game_<id>_*` keys, resolved by game
id at load time — `games-manifest.json` itself is purely structural and holds no
translated text.

```sh
python3 tools/check_l10n_sync.py
```

verifies the three stay in sync: locale coverage on both platforms, identical
translations for every text both apps share (matched via the English source),
and byte-identical manifest copies. Platform-only strings are declared in the
script's `ANDROID_ONLY` / `IOS_ONLY` lists — an undeclared one-sided string
fails the check. Run it after any string change.

## Where things live

- `android/app/src/main/assets/games-manifest.json` — the bundled games list;
  drives the home screen, scan/typed-code resolution, and relay probing. Cover
  art (`art`, 16:9) and the square brand mark (`icon`, used by the nearby-room
  card) both sit next to it in `assets/artwork/`. The iOS app bundles copies under
  `ios/CouchPad/Resources/` — keep them in sync when the manifest changes.
  The bundled copy is only the first-run seed: once per launch both apps fetch
  `couchpad.games/games-manifest.json` (served no-cache from the site
  repo), persist it, and render from it — so a new game or status flip is one
  site deploy, no app update. Art the current build didn't ship is downloaded
  into a URL-keyed cache that is never revalidated, and shipped art is matched
  by file name — so a **changed image must get a new file name** (e.g.
  `hexstacker-16x9-v2.webp`) in the served manifest and in both bundles; changed
  bytes under an unchanged name go unnoticed by design. Not a `?v=` bump: the
  query string lands inside the file name both bundle lookups use, so every app
  would re-download art it already ships.
- `android/app/src/main/java/games/couchpad/controller/` — `data/` (manifest
  model, join resolution, relay probe, LAN room discovery, prefs), `ui/main/`
  (home), `ui/game/` (WebView game host), `theme/`.
- `ios/CouchPad/` — `Data/` (same responsibilities as Android `data/`),
  `UI/Main/`, `GameHost/` (WKWebView game host), `Theme/`.
