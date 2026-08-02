package games.couchpad.controller.ui.preview

import androidx.compose.ui.graphics.Color
import games.couchpad.controller.data.Game
import games.couchpad.controller.data.NearbyRoom
import games.couchpad.controller.data.PLATFORM_ANDROID_TV
import games.couchpad.controller.data.PLATFORM_TVOS
import games.couchpad.controller.data.RecentRoom

/**
 * Sample rooms for the home-screen card previews (MainScreen.kt). Only states that
 * actually render differently earn an entry.
 *
 * These are the states that are painful or impossible to reach by hand: a display that
 * declares its platform, a game whose manifest ships no icon, and a name long enough to
 * truncate. Keep in step with iOS `CardSamples.swift`.
 */
internal object CardSamples {

  val hexStacker = Game(
    id = "hexstacker",
    name = "HexStacker",
    status = "live",
    accentColor = Color(0xFFFF6B6B),
    art = "artwork/hexstacker-16x9-v2.webp",
    icon = "artwork/hexstacker-icon-v2.png",
    controllerBaseUrl = "https://hexstacker.com",
    hosts = listOf("hexstacker.com"),
  )

  /**
   * A live game whose manifest carries no icon — falls back to the TV glyph.
   * Deliberately fictional: every game in the manifest now ships one, so a sample
   * borrowing a real game would stop describing this state the moment that changes.
   */
  val iconless = Game(
    id = "example",
    name = "Example Game",
    status = "live",
    accentColor = Color(0xFF4ECDC4),
    art = null,
    controllerBaseUrl = "https://example.com",
    hosts = listOf("example.com"),
  )

  // MARK: nearby rooms (contract §8 advertisements)

  /** Both halves of the locator: a named display that also declares its platform. */
  val nearbyFull = nearby(label = "Wohnzimmer", platform = PLATFORM_TVOS)

  /** An unlabeled Apple TV — the launcher supplies the whole locator. */
  val nearbyDeviceOnly = nearby(label = "", platform = PLATFORM_TVOS)

  /** A display that named itself but declared no platform. */
  val nearbyLabelOnly = nearby(label = "Spielzimmer", platform = null)

  /** Neither half — the locator line disappears entirely. */
  val nearbyBare = nearby(label = "", platform = null)

  /** Android TV, whatever the box actually is — the launcher names the platform. */
  val nearbyAndroidTv = nearby(label = "Küche", platform = PLATFORM_ANDROID_TV)

  /** No manifest icon — the TV glyph carries the tile. */
  val nearbyIconless = nearby(label = "Basement", platform = PLATFORM_TVOS, game = iconless)

  /** Both lines over-long: the title truncates as a unit, code included. */
  val nearbyLongName = nearby(
    label = "Wohnzimmer hinten links am Fenster",
    platform = PLATFORM_ANDROID_TV,
    game = hexStacker.copy(name = "HexStacker Championship Edition"),
  )

  // MARK: recent rooms (the rejoin card)

  /** The manifest icon on its tile — the ordinary case. */
  val rejoinPlain = rejoin()

  /** No manifest icon — the TV glyph. */
  val rejoinIconless = rejoin(game = iconless)

  /** The controller's own page title, once captured, outranks the manifest name. */
  val rejoinPageTitle = rejoin(title = "HexStacker — Runde 3")

  /** A URL that surfaced no room code: the demoted code span is omitted. */
  val rejoinNoCode = rejoin(roomCode = "")

  /** A resolved target naming its box through `cpp` (§6). */
  val rejoinWithDevice = rejoin(joinUrl = "https://hexstacker.com/BiBz3b?cpp=tvos")

  /** A browser display: it can't advertise over mDNS, so `web` only ever reaches a
   *  rejoin card — via the QR or typed code that got the player in. */
  val rejoinWeb = rejoin(joinUrl = "https://hexstacker.com/BiBz3b?cpp=web")

  private fun nearby(
    label: String,
    platform: String?,
    game: Game = hexStacker,
  ) = NearbyRoom(
    label = label,
    game = game,
    roomCode = "BiBz3b",
    joinUrl = "https://hexstacker.com/BiBz3b",
    platform = platform,
  )

  private fun rejoin(
    game: Game = hexStacker,
    roomCode: String = "BiBz3b",
    joinUrl: String = "https://hexstacker.com/BiBz3b",
    title: String? = null,
  ) = RecentRoom(
    game = game,
    joinUrl = joinUrl,
    roomCode = roomCode,
    title = title,
  )
}
