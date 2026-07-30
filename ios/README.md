# CouchPad Controller — iOS

Native SwiftUI port of the Android launcher (functional parity; see
[../CONTRACT.md](../CONTRACT.md) for the launcher⇄game contract).

The Xcode project is generated, not committed:

```sh
brew install xcodegen   # once
cd ios
xcodegen generate
open CouchPad.xcodeproj
```

iOS 17+, no third-party dependencies. QR scanning uses AVFoundation and
degrades to manual room-code entry in the simulator (no camera).

`CouchPad/Resources/games-manifest.json` and `Resources/artwork/` are copies
of the Android assets (`../android/app/src/main/assets/`) — keep them in sync
when the manifest changes.
