import SwiftUI

/// Sample rooms for the home-screen card previews (MainScreen.swift). Only states that
/// actually render differently earn an entry.
///
/// These are the states that are painful or impossible to reach by hand: a display that
/// declares its platform, a game whose manifest ships no icon, and a name long enough to
/// truncate. Keep in step with Android `CardSamples.kt`.
enum CardSamples {

    static let hexStacker = Game(
        id: "hexstacker",
        name: "HexStacker",
        status: "live",
        accentColor: Color(red: 1.0, green: 0.42, blue: 0.42),
        art: "artwork/hexstacker-16x9-v2.webp",
        icon: "artwork/hexstacker-icon-v2.png",
        controllerBaseUrl: "https://hexstacker.com",
        hosts: ["hexstacker.com"]
    )

    /// A live game whose manifest carries no icon — falls back to the TV glyph.
    /// Deliberately fictional: every game in the manifest now ships one, so a sample
    /// borrowing a real game would stop describing this state the moment that changes.
    static let iconless = Game(
        id: "example",
        name: "Example Game",
        status: "live",
        accentColor: Color(red: 0.31, green: 0.80, blue: 0.77),
        controllerBaseUrl: "https://example.com",
        hosts: ["example.com"]
    )

    // MARK: - Nearby rooms (contract §8 advertisements)

    /// Both halves of the locator: a named display that also declares its platform.
    static let nearbyFull = nearby(label: "Wohnzimmer", platform: platformTvOS)

    /// An unlabeled Apple TV — the launcher supplies the whole locator.
    static let nearbyDeviceOnly = nearby(label: "", platform: platformTvOS)

    /// A display that named itself but declared no platform.
    static let nearbyLabelOnly = nearby(label: "Spielzimmer", platform: nil)

    /// Neither half — the locator line disappears entirely.
    static let nearbyBare = nearby(label: "", platform: nil)

    /// Android TV, whatever the box actually is — the launcher names the platform.
    static let nearbyAndroidTv = nearby(label: "Küche", platform: platformAndroidTV)

    /// No manifest icon — the TV glyph carries the tile.
    static let nearbyIconless = nearby(label: "Basement", platform: platformTvOS, game: iconless)

    /// Both lines over-long: the title truncates as a unit, code included.
    static let nearbyLongName = nearby(
        label: "Wohnzimmer hinten links am Fenster",
        platform: platformAndroidTV,
        game: Game(
            id: hexStacker.id,
            name: "HexStacker Championship Edition",
            status: "live",
            accentColor: hexStacker.accentColor,
            art: hexStacker.art,
            icon: hexStacker.icon,
            controllerBaseUrl: hexStacker.controllerBaseUrl,
            hosts: hexStacker.hosts
        )
    )

    // MARK: - Recent rooms (the rejoin card)

    /// The manifest icon on its tile — the ordinary case.
    static let rejoinPlain = rejoin()

    /// No manifest icon — the TV glyph.
    static let rejoinIconless = rejoin(game: iconless)

    /// The controller's own page title, once captured, outranks the manifest name.
    static let rejoinPageTitle = rejoin(title: "HexStacker — Runde 3")

    /// A URL that surfaced no room code: the demoted code span is omitted.
    static let rejoinNoCode = rejoin(roomCode: "")

    /// A resolved target naming its box through `cpp` (§6).
    static let rejoinWithDevice = rejoin(joinUrl: "https://hexstacker.com/BiBz3b?cpp=tvos")

    /// A browser display: it can't advertise over mDNS, so `web` only ever reaches a
    /// rejoin card — via the QR or typed code that got the player in.
    static let rejoinWeb = rejoin(joinUrl: "https://hexstacker.com/BiBz3b?cpp=web")

    // MARK: - Builders

    private static func nearby(
        label: String,
        platform: String?,
        game: Game = hexStacker
    ) -> NearbyRoom {
        NearbyRoom(
            label: label,
            game: game,
            roomCode: "BiBz3b",
            joinUrl: "https://hexstacker.com/BiBz3b",
            platform: platform
        )
    }

    private static func rejoin(
        game: Game = hexStacker,
        roomCode: String = "BiBz3b",
        joinUrl: String = "https://hexstacker.com/BiBz3b",
        title: String? = nil
    ) -> RecentRoom {
        RecentRoom(game: game, joinUrl: joinUrl, roomCode: roomCode, title: title)
    }

}
