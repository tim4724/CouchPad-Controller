import XCTest

/// Smoke test + store-screenshot capture in one pass, run once per theme (light/dark):
/// walks home → game info sheet → profile sheet → manual code entry → About, asserting
/// each screen's key content (manifest load, live/soon status, code gating, version
/// label), then deep-links into the HexStacker controller's `?scenario=playing` preview
/// (the game's own gallery harness, shipped in its production bundle — renders the
/// in-game touchpad with a stubbed connection, no live room needed). One full-screen
/// PNG per stop is attached to the result bundle.
///
/// CI extracts the PNGs with `xcrun xcresulttool export attachments`. The flow
/// deliberately avoids the QR scanner (no camera in the simulator) and never talks to
/// a live relay — only the in-game step needs network, to fetch the controller page.
final class StoreScreenshotTests: XCTestCase {

    private let playerName = "Alex"
    private let roomCode = "A3KX9p"

    /// The controller's own gallery/test harness (ControllerTestHarness.js, shipped in
    /// the production bundle): renders the playing screen with a stubbed connection.
    /// Injected via the app's DEBUG `-uitest.deepLink` hook — Universal Links need a
    /// live AASA + Safari round-trip that a simulator test can't provide.
    private var inGameURL: String {
        "https://hexstacker.com/\(roomCode)?scenario=playing&name=\(playerName)&color=0"
    }

    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    func testStoreFlowLight() throws {
        runStoreFlow(dark: false)
    }

    func testStoreFlowDark() throws {
        runStoreFlow(dark: true)
    }

    private func runStoreFlow(dark: Bool) {
        let suffix = dark ? "dark" : "light"
        let app = XCUIApplication()
        // First launch mints a random FunnyName — the NSArgumentDomain override pins
        // ProfileStore's UserDefaults key so assertions and screenshots are
        // deterministic. English and the interface style are forced the same way.
        app.launchArguments += [
            "-cp_profile.name", playerName,
            "-AppleLanguages", "(en)",
            "-AppleLocale", "en_US",
            // Window-level override via the app's DEBUG hook — the documented
            // -UIUserInterfaceStyle launch arg proved unreliable on CI simulators.
            "-uitest.appearance", dark ? "dark" : "light",
        ]
        app.launch()

        // ---- Home: catalog, live status, join card, profile chip ----
        XCTAssertTrue(app.staticTexts["HexStacker"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["Tiny Track"].firstMatch.exists)
        XCTAssertTrue(app.staticTexts["Powder"].firstMatch.exists)
        XCTAssertTrue(app.staticTexts["Live"].firstMatch.exists)
        XCTAssertEqual(app.staticTexts.matching(identifier: "Coming soon").count, 2)
        XCTAssertTrue(app.staticTexts["Play"].firstMatch.exists)
        XCTAssertTrue(app.buttons["Scan code"].firstMatch.exists)
        XCTAssertTrue(app.buttons[playerName].firstMatch.exists)
        snap("01-home-\(suffix)")

        // ---- Game info sheet: manifest copy + join actions for the live game ----
        app.staticTexts["HexStacker"].firstMatch.tap()
        let players = app.staticTexts["1–8 players"]
        XCTAssertTrue(players.waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["Scan the room code it shows to play."].exists)
        // Give the muted gameplay trailer time to download (first locale only — it's
        // cached after) and render real frames; cover art shows if it isn't ready.
        Thread.sleep(forTimeInterval: 4)
        snap("02-game-info-\(suffix)")
        // Tap the dimmed area above the content-height sheet to dismiss it.
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.12)).tap()
        XCTAssertTrue(players.waitForNonExistence(timeout: 5))

        // ---- Profile sheet: seeded name round-trips through the field ----
        app.buttons[playerName].firstMatch.tap()
        let nameField = app.textFields.firstMatch
        XCTAssertTrue(nameField.waitForExistence(timeout: 5))
        XCTAssertEqual(nameField.value as? String, playerName)
        snap("03-profile-\(suffix)")
        app.buttons["Save"].firstMatch.tap()
        XCTAssertTrue(nameField.waitForNonExistence(timeout: 5))
        XCTAssertTrue(app.buttons[playerName].firstMatch.exists)

        // ---- Manual code entry: empty-code gating; never submitted (no relay dependency) ----
        app.buttons["Enter code manually"].firstMatch.tap()
        let alert = app.alerts["Enter room code"]
        XCTAssertTrue(alert.waitForExistence(timeout: 5))
        XCTAssertFalse(alert.buttons["Play"].isEnabled)
        let codeField = alert.textFields.firstMatch
        codeField.tap()
        codeField.typeText(roomCode)
        XCTAssertTrue(alert.buttons["Play"].isEnabled)
        snap("04-enter-code-\(suffix)")
        alert.buttons["Cancel"].tap()
        XCTAssertTrue(alert.waitForNonExistence(timeout: 5))

        // ---- About: legal hub reachable, version label present ----
        app.buttons["About"].firstMatch.tap()
        XCTAssertTrue(app.staticTexts["Privacy Policy"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["Impressum"].exists)
        XCTAssertTrue(app.staticTexts["Version 1.0.0"].exists)
        snap("05-about-\(suffix)")

        // ---- In-game: relaunch straight into the controller's scenario harness ----
        app.launchArguments += ["-uitest.deepLink", inGameURL]
        app.launch()
        // The leave bar (native chrome) carries the game title; the "Joining…" cover
        // may already have faded by the time launch() returns, so only require its
        // absence, not its appearance.
        XCTAssertTrue(app.staticTexts["HexStacker"].firstMatch.waitForExistence(timeout: 15))
        let joining = app.staticTexts.matching(
            NSPredicate(format: "label BEGINSWITH 'Joining'")
        ).firstMatch
        // The shell fades the cover on its injected __firstFrame signal, so the
        // cover's absence means the page has actually painted (not merely loaded).
        XCTAssertTrue(joining.waitForNonExistence(timeout: 30))
        Thread.sleep(forTimeInterval: 1) // fonts + touchpad canvas settle
        snap("06-in-game-\(suffix)")
    }

    /// Full-screen capture attached to the result bundle; extract with
    /// `xcrun xcresulttool export attachments --path <xcresult> --output-path <dir>`.
    private func snap(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
