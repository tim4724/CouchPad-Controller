package games.couchpad.controller

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.core.net.toUri
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import games.couchpad.controller.data.Profile
import games.couchpad.controller.data.ProfileStore
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke test + store-screenshot capture in one pass, run once per theme (light/dark):
 * walks home → game info sheet → profile sheet → manual code entry → About, asserting
 * each screen's key content (manifest load, live/soon status, name gating, version
 * label), then deep-links into the HexStacker controller's `?scenario=playing` preview
 * (the game's own gallery harness, shipped in its production bundle — renders the
 * in-game touchpad with a stubbed connection, no live room needed). One full-device
 * PNG per stop goes to shared storage (`Pictures/couchpad-screenshots`, via
 * MediaStore so no storage permission is needed).
 *
 * Shared storage outlives the automatic post-test uninstall, so CI simply
 * `adb pull /sdcard/Pictures/couchpad-screenshots` afterwards (and `rm -rf`s the
 * directory beforehand — MediaStore dedupes names as "x (1).png" across runs). The
 * flow deliberately avoids the QR scanner (camera) and never talks to a live relay —
 * only the in-game step needs network, to fetch the controller page itself.
 */
@RunWith(AndroidJUnit4::class)
class StoreScreenshotTest {

  @get:Rule val compose = createEmptyComposeRule()

  private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
  private val appContext get() = instrumentation.targetContext

  private fun str(id: Int, vararg args: Any): String = appContext.getString(id, *args)

  private fun plural(id: Int, quantity: Int, vararg args: Any): String =
    appContext.resources.getQuantityString(id, quantity, *args)

  @Before
  fun seedProfile() {
    // First launch mints a random FunnyName — pin the name BEFORE the activity starts
    // so assertions and store screenshots are deterministic.
    ProfileStore.save(appContext, Profile(PLAYER_NAME))
    // A fresh device shows the one-time "Viewing full screen" education overlay the
    // first time an app hides the system bars (the in-game host does) — pre-confirm it.
    shell("settings put secure immersive_mode_confirmations confirmed")
  }

  @After
  fun resetDeviceState() {
    shell("cmd uimode night no")
    shell("am broadcast -a com.android.systemui.demo -e command exit")
  }

  @Test fun lightMode() = captureAll(dark = false)

  @Test fun darkMode() = captureAll(dark = true)

  private fun captureAll(dark: Boolean) {
    val suffix = if (dark) "dark" else "light"
    // Set the theme BEFORE launching — no recreate races (the activity handles uiMode
    // via configChanges, but a pre-launch flip sidesteps the question entirely).
    shell("cmd uimode night " + if (dark) "yes" else "no")
    Thread.sleep(1_000)
    // The uimode flip knocks SystemUI out of demo mode, so the clean status bar
    // (fixed clock, full battery/signal, no notifications) is re-applied here, per
    // pass — not in the CI script.
    applyDemoStatusBar()
    ActivityScenario.launch(MainActivity::class.java).use { walkHomeFlow(suffix) }
    captureInGame(suffix)
  }

  private fun applyDemoStatusBar() {
    shell("settings put global sysui_demo_allowed 1")
    shell("am broadcast -a com.android.systemui.demo -e command enter")
    shell("am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false")
    shell("am broadcast -a com.android.systemui.demo -e command network -e wifi show -e level 4 -e fully true")
    shell("am broadcast -a com.android.systemui.demo -e command notifications -e visible false")
    shell("am broadcast -a com.android.systemui.demo -e command clock -e hhmm 0941")
    Thread.sleep(500)
  }

  private fun walkHomeFlow(suffix: String) {
    // ---- Home: catalog, live status, join card, profile chip ----
    waitForText("HexStacker")
    compose.onNodeWithText(str(R.string.app_name)).assertIsDisplayed()
    compose.onNodeWithText("HexStacker").assertIsDisplayed()
    compose.onNodeWithText("Tiny Track").assertExists()
    compose.onNodeWithText("Powder").assertExists()
    compose.onNodeWithText(str(R.string.status_live)).assertIsDisplayed()
    compose.onAllNodesWithText(str(R.string.status_coming_soon)).assertCountEquals(2)
    compose.onNodeWithText(str(R.string.join_title)).assertIsDisplayed()
    compose.onNodeWithText(str(R.string.scan_code)).assertIsDisplayed()
    compose.onNodeWithText(PLAYER_NAME).assertIsDisplayed()
    screenshot("01-home-$suffix")

    // ---- Game info sheet: manifest copy + join actions for the live game ----
    compose.onNodeWithText("HexStacker").performClick()
    // HexStacker is 1–8 players: the range plural, agreeing with the max (e.g. "1–8 players").
    waitForText(plural(R.plurals.game_players_range, 8, 1, 8))
    compose.onNodeWithText(str(R.string.play_step_scan)).assertIsDisplayed()
    // Both the sheet and the home join card behind it carry the two join buttons.
    compose.onAllNodesWithText(str(R.string.scan_code)).assertCountEquals(2)
    compose.onAllNodesWithText(str(R.string.enter_code_manually)).assertCountEquals(2)
    // Give the muted gameplay trailer time to download (first locale only — it's
    // cached after) and render real frames; cover art shows if it isn't ready.
    Thread.sleep(4_000)
    screenshot("02-game-info-$suffix")
    Espresso.pressBack()
    waitForTextGone(plural(R.plurals.game_players_range, 8, 1, 8))

    // ---- Profile sheet: blank-name gating + persistence round-trip ----
    compose.onNodeWithText(PLAYER_NAME).performClick()
    waitForText(str(R.string.name))
    val nameField = compose.onNode(hasSetTextAction())
    nameField.performTextClearance()
    compose.onNodeWithText(str(R.string.save)).assertIsNotEnabled()
    nameField.performTextInput(PLAYER_NAME)
    compose.onNodeWithText(str(R.string.save)).assertIsEnabled()
    screenshot("03-profile-$suffix")
    compose.onNodeWithText(str(R.string.save)).performClick()
    waitForTextGone(str(R.string.save))
    compose.onNodeWithText(PLAYER_NAME).assertIsDisplayed()

    // ---- Manual code entry: empty-code gating; never submitted (no relay dependency) ----
    compose.onNodeWithText(str(R.string.enter_code_manually)).performClick()
    waitForText(str(R.string.enter_room_code))
    val playButton = compose.onNode(hasText(str(R.string.join)) and hasAnyAncestor(isDialog()))
    playButton.assertIsNotEnabled()
    compose.onNode(hasSetTextAction()).performTextInput(ROOM_CODE)
    playButton.assertIsEnabled()
    screenshot("04-enter-code-$suffix")
    compose.onNodeWithText(str(R.string.cancel)).performClick()
    waitForTextGone(str(R.string.enter_room_code))

    // ---- About: legal hub reachable, version label present ----
    compose.onNodeWithContentDescription(str(R.string.about)).performClick()
    waitForText(str(R.string.privacy_policy))
    compose.onNodeWithText(str(R.string.imprint)).assertIsDisplayed()
    compose.onNodeWithText(str(R.string.open_source_licenses)).assertIsDisplayed()
    compose.onNodeWithText(str(R.string.version_label, BuildConfig.VERSION_NAME)).assertIsDisplayed()
    screenshot("05-about-$suffix")
  }

  /** Deep-link (the App Link path) into the controller's scenario harness and capture
   *  the in-game touchpad once the "Joining…" cover has faded. */
  private fun captureInGame(suffix: String) {
    val intent = Intent(Intent.ACTION_VIEW, IN_GAME_URL.toUri(), appContext, MainActivity::class.java)
    ActivityScenario.launch<MainActivity>(intent).use {
      // "Joining %1$s…" localized prefix, title-independent (the page <title>
      // replaces the manifest name while the cover is still up).
      val joiningPrefix = str(R.string.joining_game, MARKER).substringBefore(MARKER)
      // The cover composes with the game host; wait for it, then for the page paint.
      compose.waitUntil(timeoutMillis = 15_000) {
        compose.onAllNodes(hasText(joiningPrefix, substring = true)).fetchSemanticsNodes().isNotEmpty()
      }
      compose.waitUntil(timeoutMillis = 30_000) {
        compose.onAllNodes(hasText(joiningPrefix, substring = true)).fetchSemanticsNodes().isEmpty()
      }
      // The shell fades the cover on WebView's visual-state callback, so the cover
      // being gone means the page has actually been drawn (not merely loaded).
      Thread.sleep(1_000) // fonts + touchpad canvas settle
      screenshot("06-in-game-$suffix")
    }
  }

  private fun waitForText(text: String) {
    compose.waitUntil(timeoutMillis = 10_000) {
      compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }
  }

  private fun waitForTextGone(text: String) {
    compose.waitUntil(timeoutMillis = 10_000) {
      compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()
    }
  }

  /** Run a shell command with shell privileges and wait for it to finish. */
  private fun shell(cmd: String) {
    val pfd = instrumentation.uiAutomation.executeShellCommand(cmd)
    ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
  }

  /** Full-device capture (system bars included) via UiAutomation — sheets and dialogs
   *  live in their own windows, which a compose-root capture would miss. */
  private fun screenshot(name: String) {
    compose.waitForIdle()
    Thread.sleep(400) // ripples, IME slide-in, window transitions
    val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) { "takeScreenshot returned null" }
    save(bitmap, name)
  }

  private fun save(bitmap: Bitmap, name: String) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$SHOT_DIR")
      }
      val resolver = appContext.contentResolver
      val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)) {
        "MediaStore insert failed for $name"
      }
      resolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    } else {
      // Pre-Q fallback (dev devices only — CI runs API 35): app-private files.
      val dir = File(appContext.filesDir, SHOT_DIR).apply { mkdirs() }
      File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
  }

  private companion object {
    const val PLAYER_NAME = "Alex"
    const val ROOM_CODE = "A3KX9p"
    const val SHOT_DIR = "couchpad-screenshots"
    const val MARKER = "@@"

    // The controller's own gallery/test harness (ControllerTestHarness.js, shipped in
    // the production bundle): renders the playing screen with a stubbed connection.
    const val IN_GAME_URL =
      "https://hexstacker.com/$ROOM_CODE?scenario=playing&name=$PLAYER_NAME&color=0"
  }
}
