package games.couchpad.controller.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records the launcher's hot startup path for the baseline profile: cold start to
 * the home catalog, then a scroll through the posters. That covers what every
 * session executes — Activity/Compose init, manifest parse, catalog composition,
 * art decode, and first scroll — without touching flows that need a live room
 * (join/game host) or hardware (scanner).
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

  @get:Rule
  val rule = BaselineProfileRule()

  /**
   * Startup profile (feeds dexLayoutOptimization): launch-to-first-frame ONLY, no
   * scroll — dex layout should cluster exactly the classes cold start touches, and
   * a broader journey would dilute that. Merged with [generate]'s output; the
   * scroll rules still land in the baseline profile for AOT.
   */
  @Test
  fun startup() {
    rule.collect(
      packageName = "games.couchpad.controller",
      includeInStartupProfile = true,
    ) {
      pressHome()
      startActivityAndWait()
    }
  }

  @Test
  fun generate() {
    rule.collect(packageName = "games.couchpad.controller") {
      pressHome()
      startActivityAndWait()

      // Scroll the catalog down and back — the art has decoded by now (the wait
      // above returns on first frame; the swipe itself exercises decode + draw).
      val w = device.displayWidth
      val h = device.displayHeight
      device.swipe(w / 2, h * 3 / 4, w / 2, h / 4, 20)
      device.waitForIdle()
      device.swipe(w / 2, h / 4, w / 2, h * 3 / 4, 20)
      device.waitForIdle()
    }
  }
}
