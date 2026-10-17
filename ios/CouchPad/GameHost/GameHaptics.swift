import CoreHaptics

/// CONTRACT.md §12's named primitives — Android's composition primitives, under their
/// constant names.
enum HapticPrimitive: String {
    case click, tick, thud, spin
    case lowTick = "low_tick", quickRise = "quick_rise", slowRise = "slow_rise", quickFall = "quick_fall"

    /// The length AOSP targets for each (source.android.com/docs/core/interaction/haptics/haptics-constants-primitives):
    /// how long the rebuilt pattern lasts, and what a composition's clock advances by.
    var duration: TimeInterval {
        switch self {
        case .click, .lowTick: 0.012
        case .tick: 0.005
        case .thud: 0.3
        case .spin, .quickRise: 0.15
        case .slowRise: 0.5
        case .quickFall: 0.1
        }
    }
}

/// One step of a `CouchPadHost.hapticComposition()` sequence (CONTRACT.md §12), validated.
struct HapticStep {
    let primitive: HapticPrimitive
    let scale: Double
    /// Seconds of rest after the previous step ends.
    let delay: TimeInterval
}

/// Plays the patterns the page hands to `navigator.vibrate()` (CONTRACT.md §11) and
/// the primitives it hands to `CouchPadHost.haptic()` / `hapticComposition()` (§12).
/// WebKit implements no Vibration API, so the bridge shim polyfills it and forwards the
/// pattern here; Core Haptics is the only iOS API that takes an arbitrary duration or
/// shape rather than a fixed system feel.
///
/// No feedback-generator fallback: the app is iPhone-only from iOS 17, and every
/// such device has a haptic engine. Where the check does fail — the simulator — a
/// generator would be just as silent.
@MainActor final class GameHaptics {
    private let supported = CHHapticEngine.capabilitiesForHardware().supportsHaptics
    private var engine: CHHapticEngine?
    /// Mirrors the engine's run state, which it leaves on its own (audio-session
    /// interruption, backgrounding, a server reset) — the next pattern restarts it.
    private var running = false
    private var player: CHHapticPatternPlayer?

    /// `pattern` is milliseconds, alternating vibrate/pause, already shape-checked and
    /// capped by `parseVibrationPattern`. Empty = the API's cancel call.
    func play(_ pattern: [Int]) {
        stop()
        guard supported, !pattern.isEmpty else { return }

        var events: [CHHapticEvent] = []
        var elapsed: TimeInterval = 0
        for (index, milliseconds) in pattern.enumerated() {
            let duration = TimeInterval(milliseconds) / 1000
            if index.isMultiple(of: 2), duration > 0 {
                events += pulse(at: elapsed, duration: duration)
            }
            elapsed += duration
        }
        start(events)
    }

    /// `scale` is already clamped to 0–1 by `parseHaptic`.
    func play(_ primitive: HapticPrimitive, scale: Double) {
        play([HapticStep(primitive: primitive, scale: scale, delay: 0)])
    }

    /// A sequence of primitives, each after its rest, already validated and capped by
    /// `parseHapticComposition`. Nothing valid plays nothing and leaves the current
    /// haptic alone, like an unknown name to `haptic()`; the contract's stop is `stop()`.
    func play(_ steps: [HapticStep]) {
        guard supported, !steps.isEmpty else { return }
        stop()
        var events: [CHHapticEvent] = []
        var curves: [CHHapticParameterCurve] = []
        var at: TimeInterval = 0
        for step in steps {
            at += step.delay
            let built = Self.build(step.primitive, scale: step.scale, at: at)
            events += built.events
            curves += built.curves
            at += step.primitive.duration
        }
        start(events, curves: curves)
    }

    /// Core Haptics has no primitives, so each is rebuilt from the hardware targets AOSP
    /// sets for it (source.android.com/docs/core/interaction/haptics/haptics-constants-primitives):
    /// duration and sweep shape carry over, peak acceleration relative to click becomes
    /// intensity, and frequency relative to the actuator's resonance becomes sharpness.
    /// Apple publishes no mapping of either to acceleration or Hz, so the levels are a
    /// judgement call.
    private static func build(_ primitive: HapticPrimitive, scale: Double, at: TimeInterval)
        -> (events: [CHHapticEvent], curves: [CHHapticParameterCurve]) {
        let level = floor + (1 - floor) * Float(scale)
        switch primitive {
        case .click:     // 12 ms, 2 G, at resonance
            return ([tap(level, sharpness: 0.5, at: at)], [])
        case .tick:      // 5 ms, 1 G, 2× resonance
            return ([tap(0.7 * level, sharpness: 1, at: at)], [])
        case .lowTick:   // 12 ms, 0.25 G, ⅔ resonance
            return ([tap(0.45 * level, sharpness: 0.2, at: at)], [])
        case .thud:      // 300 ms, 0.25 G, falling ½ → ⅓ resonance, percussive onset
            let tail = sweep(0.3, peak: 0.6 * level, at: at,
                             intensity: [(0, 1), (0.3, 0)], sharpness: [(0, 0.2), (0.3, 0)])
            return ([tap(0.8 * level, sharpness: 0.1, at: at)] + tail.events, tail.curves)
        case .spin:      // 150 ms, 0.5 G; strength and pitch sweep opposite ways, then back
            return sweep(0.15, peak: 0.7 * level, at: at,
                         intensity: [(0, 0.2), (0.075, 1), (0.15, 0.2)],
                         sharpness: [(0, 0.4), (0.075, 0.1), (0.15, 0.3)])
        case .quickRise, .slowRise:  // 150 / 500 ms, 0.5 G, rising ½ → ⅔ resonance
            let duration = primitive == .quickRise ? 0.15 : 0.5
            return sweep(duration, peak: 0.7 * level, at: at,
                         intensity: [(0, 0), (duration, 1)], sharpness: [(0, 0.2), (duration, 0.4)])
        case .quickFall: // 100 ms, 1 G, soft onset, falling 2× → 1× resonance
            return sweep(0.1, peak: 0.9 * level, at: at,
                         intensity: [(0, 0), (0.03, 1), (0.1, 0)], sharpness: [(0, 1), (0.1, 0.5)])
        }
    }

    private func start(_ events: [CHHapticEvent], curves: [CHHapticParameterCurve] = []) {
        guard !events.isEmpty, let engine = startedEngine(),
              let haptic = try? CHHapticPattern(events: events, parameterCurves: curves),
              let player = try? engine.makePlayer(with: haptic),
              (try? player.start(atTime: CHHapticTimeImmediate)) != nil
        else { return }
        self.player = player
    }

    /// Android's scale 0 is the faintest buzz the device can make, not silence — the same
    /// floor its pulse fallback keeps (`GameHaptics.kt` PULSE_FLOOR).
    private static let floor: Float = 0.25

    private static func tap(_ intensity: Float, sharpness: Float, at: TimeInterval) -> CHHapticEvent {
        CHHapticEvent(eventType: .hapticTransient, parameters: [
            CHHapticEventParameter(parameterID: .hapticIntensity, value: intensity),
            CHHapticEventParameter(parameterID: .hapticSharpness, value: sharpness),
        ], relativeTime: at)
    }

    /// A continuous event at `peak` from `at`, whose intensity (a 0–1 multiplier of
    /// `peak`) and sharpness follow (seconds, value) points, interpolated linearly.
    /// Sharpness control is an offset added to the event's own, which is therefore 0.
    private static func sweep(_ duration: TimeInterval, peak: Float, at: TimeInterval,
                              intensity: [(TimeInterval, Float)], sharpness: [(TimeInterval, Float)])
        -> (events: [CHHapticEvent], curves: [CHHapticParameterCurve]) {
        let event = CHHapticEvent(eventType: .hapticContinuous, parameters: [
            CHHapticEventParameter(parameterID: .hapticIntensity, value: peak),
            CHHapticEventParameter(parameterID: .hapticSharpness, value: 0),
        ], relativeTime: at, duration: duration)
        // A curve's last value holds after it ends, which would mute or detune every
        // later step of a composition — so each curve returns to its parameter's rest
        // value inside the event's own last two milliseconds, where nothing is left to
        // hear. It also ends a millisecond before the event does, so the next step's curve
        // for the same parameter — which may start the instant this event ends — never
        // touches it; Apple documents no rule for same-ID curves meeting, so none is relied on.
        func curve(_ id: CHHapticDynamicParameter.ID, _ points: [(TimeInterval, Float)], rest: Float) -> CHHapticParameterCurve {
            let shaped = points.map { (min($0.0, duration - 0.002), $0.1) } + [(duration - 0.001, rest)]
            return CHHapticParameterCurve(parameterID: id, controlPoints: shaped.map {
                CHHapticParameterCurve.ControlPoint(relativeTime: $0.0, value: $0.1)
            }, relativeTime: at)
        }
        return ([event], [curve(.hapticIntensityControl, intensity, rest: 1), curve(.hapticSharpnessControl, sharpness, rest: 0)])
    }

    /// Cuts a pattern short — the API's `vibrate(0)`, an empty `hapticComposition()`, the
    /// next haptic, and the game host going away.
    func stop() {
        try? player?.stop(atTime: CHHapticTimeImmediate)
        player = nil
    }

    func shutdown() {
        stop()
        engine?.stop(completionHandler: nil)
        engine = nil
        running = false
    }

    /// Full intensity, as Android drives the motor at full amplitude and only length
    /// varies. A continuous event alone barely registers at the 10–25ms a game spends on
    /// a tap, so a short pulse leads with a transient; a long one doesn't need it, and a
    /// held buzz the page re-issues before it ends then continues without a click.
    private func pulse(at start: TimeInterval, duration: TimeInterval) -> [CHHapticEvent] {
        let parameters = [
            CHHapticEventParameter(parameterID: .hapticIntensity, value: 1),
            CHHapticEventParameter(parameterID: .hapticSharpness, value: 0.5),
        ]
        let body = CHHapticEvent(eventType: .hapticContinuous, parameters: parameters,
                                 relativeTime: start, duration: duration)
        guard duration < 0.04 else { return [body] }
        return [CHHapticEvent(eventType: .hapticTransient, parameters: parameters,
                              relativeTime: start), body]
    }

    private func startedEngine() -> CHHapticEngine? {
        if engine == nil {
            guard let created = try? CHHapticEngine() else { return nil }
            // Kept alive for the whole game host instead of auto-shutting down: a
            // controller buzzes on nearly every tap, and a per-tap engine start would
            // put an audio-server round trip in front of the feedback.
            created.isAutoShutdownEnabled = false
            // Plays no audio events, which lets the engine skip audio setup and start
            // with less latency.
            created.playsHapticsOnly = true
            created.stoppedHandler = { [weak self] _ in
                Task { @MainActor in self?.running = false }
            }
            created.resetHandler = { [weak self] in
                Task { @MainActor in self?.running = false }
            }
            engine = created
        }
        guard let engine else { return nil }
        // Throws while the app isn't active — nothing to report, the tap is simply lost.
        if !running, (try? engine.start()) == nil { return nil }
        running = true
        return engine
    }
}
