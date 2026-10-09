package games.couchpad.controller.ui.game

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibrationEffect.Composition
import android.os.Vibrator
import android.os.VibratorManager
import androidx.annotation.RequiresApi
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Plays the named primitives the page hands to `CouchPadHost.haptic()` (CONTRACT.md §13).
 * Android 12+ plays each as the device's own composition primitive; an older phone, or
 * one that lacks that primitive, gets a plain pulse instead, so a game never checks
 * support. Android 11 has the composition API too, but its support check answers yes
 * for every primitive and three of the eight don't exist yet, so it takes the pulse.
 *
 * Tagged as a game's (media usage), never left untagged: Android files an untagged
 * short effect under touch feedback — the WebView's own navigator.vibrate included —
 * and the system touch-feedback switch must not silence a game that has its own
 * haptics setting.
 */
internal class GameHaptics(context: Context) {
  private val vibrator: Vibrator =
    if (Build.VERSION.SDK_INT >= 31) {
      context.getSystemService(VibratorManager::class.java).defaultVibrator
    } else {
      @Suppress("DEPRECATION")
      context.getSystemService(Vibrator::class.java)
    }

  /** Runs on the JS bridge thread — Vibrator is thread-safe. Arguments are untrusted. */
  fun play(name: String?, scale: Double) {
    val primitive = PRIMITIVES[name] ?: return
    if (!scale.isFinite()) return
    val strength = scale.coerceIn(0.0, 1.0).toFloat()
    if (Build.VERSION.SDK_INT >= 31 && vibrator.arePrimitivesSupported(primitive.id)[0]) {
      vibrate(VibrationEffect.startComposition().addPrimitive(primitive.id, strength).compose())
    } else {
      pulse(primitive.pulseMillis, strength)
    }
  }

  /**
   * A composition's scale 0 is the faintest buzz the device can make, not silence, so
   * the pulse keeps a floor too. Without amplitude control strength can only be length.
   */
  private fun pulse(millis: Long, strength: Float) {
    val level = PULSE_FLOOR + (1 - PULSE_FLOOR) * strength
    val shortened = (millis * level).roundToLong().coerceAtLeast(1)
    when {
      Build.VERSION.SDK_INT < 26 -> {
        @Suppress("DEPRECATION")
        vibrator.vibrate(shortened, GAME_AUDIO)
      }
      vibrator.hasAmplitudeControl() ->
        vibrate(VibrationEffect.createOneShot(millis, (level * 255).roundToInt()))
      else -> vibrate(VibrationEffect.createOneShot(shortened, VibrationEffect.DEFAULT_AMPLITUDE))
    }
  }

  @RequiresApi(26)
  private fun vibrate(effect: VibrationEffect) {
    if (Build.VERSION.SDK_INT >= 33) {
      vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_MEDIA))
    } else {
      @Suppress("DEPRECATION")
      vibrator.vibrate(effect, GAME_AUDIO)
    }
  }

  private class Primitive(val id: Int, val pulseMillis: Long)

  private companion object {
    const val PULSE_FLOOR = 0.25f

    // Before VibrationAttributes (API 33) usage came from audio attributes; GAME is
    // what the framework later files under media.
    val GAME_AUDIO: AudioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).build()

    // Pulse lengths are sized against Android's own pre-composition fallbacks (click
    // 20 ms, heavy click 30 ms; config_*VibePattern): an on/off motor needs that long
    // to be felt, so the chirps get a short pulse of their character, not their length.
    // The ids are compile-time constants, inlined; only API 31+ ever passes them on.
    @SuppressLint("InlinedApi")
    val PRIMITIVES = mapOf(
      "click" to Primitive(Composition.PRIMITIVE_CLICK, 20),
      "tick" to Primitive(Composition.PRIMITIVE_TICK, 10),
      "low_tick" to Primitive(Composition.PRIMITIVE_LOW_TICK, 12),
      "thud" to Primitive(Composition.PRIMITIVE_THUD, 30),
      "spin" to Primitive(Composition.PRIMITIVE_SPIN, 35),
      "quick_rise" to Primitive(Composition.PRIMITIVE_QUICK_RISE, 50),
      "slow_rise" to Primitive(Composition.PRIMITIVE_SLOW_RISE, 80),
      "quick_fall" to Primitive(Composition.PRIMITIVE_QUICK_FALL, 50),
    )
  }
}
