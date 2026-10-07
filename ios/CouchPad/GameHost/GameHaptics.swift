import CoreHaptics

/// Plays the patterns the page hands to `navigator.vibrate()` (CONTRACT.md §12).
/// WebKit implements no Vibration API, so the bridge shim polyfills it and forwards
/// the pattern here; Core Haptics is the only iOS API that takes an arbitrary
/// duration rather than a fixed system feel.
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
        guard !events.isEmpty, let engine = startedEngine(),
              let haptic = try? CHHapticPattern(events: events, parameters: []),
              let player = try? engine.makePlayer(with: haptic),
              (try? player.start(atTime: CHHapticTimeImmediate)) != nil
        else { return }
        self.player = player
    }

    /// Cuts a pattern short — the API's `vibrate(0)`, and the game host going away.
    private func stop() {
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
    /// varies. A transient plus a continuous event, as a continuous event alone barely
    /// registers at the 10–25ms a game spends on a tap, and a transient alone has no length.
    private func pulse(at start: TimeInterval, duration: TimeInterval) -> [CHHapticEvent] {
        let parameters = [
            CHHapticEventParameter(parameterID: .hapticIntensity, value: 1),
            CHHapticEventParameter(parameterID: .hapticSharpness, value: 0.5),
        ]
        return [
            CHHapticEvent(eventType: .hapticTransient, parameters: parameters,
                          relativeTime: start),
            CHHapticEvent(eventType: .hapticContinuous, parameters: parameters,
                          relativeTime: start, duration: duration),
        ]
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
