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
 * Plays the named primitives the page hands to `CouchPadHost.haptic()` and the timed
 * sequences it hands to `CouchPadHost.hapticComposition()` (CONTRACT.md §12).
 * Android 12+ plays each as the device's own composition primitive; an older phone, or
 * one that lacks a primitive, gets a plain pulse per step instead, so a game never checks
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
    play(listOf(HapticStep(primitive, scale.coerceIn(0.0, 1.0).toFloat(), 0)))
  }

  /**
   * The contract's stop (an empty `hapticComposition()`): the page's own
   * `navigator.vibrate(0)` cancels only the WebView's vibrations, never this Vibrator's —
   * and this cancels only ours, so a §11 pattern keeps playing.
   */
  fun stop() {
    vibrator.cancel()
  }

  /**
   * A sequence of primitives, each after its rest (`parseComposition` has validated and
   * capped it). Nothing valid plays nothing, like an unknown name to `play(name, scale)`.
   */
  fun play(steps: List<HapticStep>) {
    if (steps.isEmpty()) return
    if (Build.VERSION.SDK_INT >= 31 &&
      vibrator.arePrimitivesSupported(*IntArray(steps.size) { steps[it].primitive.id }).all { it }
    ) {
      val composition = VibrationEffect.startComposition()
      for (step in steps) composition.addPrimitive(step.primitive.id, step.scale, step.delayMillis.toInt())
      vibrate(composition.compose())
    } else {
      pulses(steps)
    }
  }

  /**
   * One pulse per step, as a waveform of rest/pulse pairs. A composition's scale 0 is the
   * faintest buzz the device can make, not silence, so the pulse keeps a floor too. Without
   * amplitude control strength can only be length. Where the pulse is shorter than the
   * primitive it stands in for, the shortfall is added to the next rest: the sequence keeps
   * the clock a composition device (and iOS) plays it on.
   */
  private fun pulses(steps: List<HapticStep>) {
    val amplitudeControl = Build.VERSION.SDK_INT >= 26 && vibrator.hasAmplitudeControl()
    val timings = LongArray(steps.size * 2)
    val amplitudes = IntArray(steps.size * 2)
    var carry = 0L
    steps.forEachIndexed { i, step ->
      val level = PULSE_FLOOR + (1 - PULSE_FLOOR) * step.scale
      val millis = step.primitive.pulseMillis
      val pulse = if (amplitudeControl) millis else (millis * level).roundToLong().coerceAtLeast(1)
      timings[2 * i] = step.delayMillis + carry
      timings[2 * i + 1] = pulse
      amplitudes[2 * i + 1] = (level * 255).roundToInt().coerceAtLeast(1)
      carry = (step.primitive.millis - pulse).coerceAtLeast(0)
    }
    when {
      Build.VERSION.SDK_INT < 26 -> {
        @Suppress("DEPRECATION")
        vibrator.vibrate(timings, -1, GAME_AUDIO)
      }
      amplitudeControl -> vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
      else -> vibrate(VibrationEffect.createWaveform(timings, -1))
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

  /**
   * `millis` is the length AOSP targets for the primitive (the haptics-constants page),
   * which a sequence's run-time cap counts; `pulseMillis` is the plain pulse that stands
   * in for it where the device has no composition.
   */
  class Primitive(val id: Int, val millis: Long, val pulseMillis: Long)

  /** One step of a `CouchPadHost.hapticComposition()` sequence (CONTRACT.md §12), validated. */
  class HapticStep(val primitive: Primitive, val scale: Float, val delayMillis: Long)

  companion object {
    private const val PULSE_FLOOR = 0.25f
    private const val MAX_STEPS = 32
    private const val MAX_RUN_MILLIS = 5000L
    private const val MAX_ENCODED_LENGTH = 2048

    // Before VibrationAttributes (API 33) usage came from audio attributes; GAME is
    // what the framework later files under media.
    private val GAME_AUDIO: AudioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).build()

    // Pulse lengths are sized against Android's own pre-composition fallbacks (click
    // 20 ms, heavy click 30 ms; config_*VibePattern): an on/off motor needs that long
    // to be felt, so the chirps get a short pulse of their character, not their length.
    // The ids are compile-time constants, inlined; only API 31+ ever passes them on.
    @SuppressLint("InlinedApi")
    private val PRIMITIVES = mapOf(
      "click" to Primitive(Composition.PRIMITIVE_CLICK, 12, 20),
      "tick" to Primitive(Composition.PRIMITIVE_TICK, 5, 10),
      "low_tick" to Primitive(Composition.PRIMITIVE_LOW_TICK, 12, 12),
      "thud" to Primitive(Composition.PRIMITIVE_THUD, 300, 30),
      "spin" to Primitive(Composition.PRIMITIVE_SPIN, 150, 35),
      "quick_rise" to Primitive(Composition.PRIMITIVE_QUICK_RISE, 150, 50),
      "slow_rise" to Primitive(Composition.PRIMITIVE_SLOW_RISE, 500, 80),
      "quick_fall" to Primitive(Composition.PRIMITIVE_QUICK_FALL, 100, 50),
    )

    /**
     * `primitive,scale,delay;…` as the shim posts a sequence (CONTRACT.md §12). Untrusted
     * like a §11 pattern: the valid prefix plays, and both the step count and the run time
     * (rests plus the primitives' own lengths) are capped, so nothing a page sends leaves
     * the phone shaking after the tap that caused it. A delay is range-checked before it
     * is rounded: `roundToLong()` saturates, and a saturated sum wraps negative past the
     * cap and into `addPrimitive`, which throws on a negative delay.
     */
    fun parseComposition(encoded: String?): List<HapticStep> {
      if (encoded.isNullOrEmpty() || encoded.length > MAX_ENCODED_LENGTH) return emptyList()
      val steps = ArrayList<HapticStep>()
      var run = 0L
      for (field in encoded.split(';')) {
        if (steps.size >= MAX_STEPS) break
        val parts = field.split(',')
        if (parts.size != 3) break
        val primitive = PRIMITIVES[parts[0]] ?: break
        val scale = parts[1].toDoubleOrNull()?.takeIf { it.isFinite() } ?: break
        val delay = parts[2].toDoubleOrNull()?.takeIf { it >= 0 && it <= MAX_RUN_MILLIS }?.roundToLong() ?: break
        run += delay + primitive.millis
        if (run > MAX_RUN_MILLIS) break
        steps += HapticStep(primitive, scale.coerceIn(0.0, 1.0).toFloat(), delay)
      }
      return steps
    }
  }
}
