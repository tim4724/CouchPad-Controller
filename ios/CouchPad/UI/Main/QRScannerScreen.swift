import SwiftUI
import AVFoundation
import UIKit

// MARK: - QRScanResult

enum QRScanResult: Equatable {
    case code(String)
    case cancelled
    case enterCode                 // manual entry chosen, scanning or denied
    case failure(message: String)
}

// MARK: - QRScannerScreen

struct QRScannerScreen: View {
    let games: [Game]                      // to validate payloads in place — see handleCode
    let onFinish: (QRScanResult) -> Void   // called exactly once; caller dismisses the cover

    init(games: [Game], onFinish: @escaping (QRScanResult) -> Void) {
        self.games = games
        self.onFinish = onFinish
    }

    @Environment(\.cpPalette) private var palette

    @State private var finished = false
    @State private var authorized = false
    @State private var denied = false
    @State private var cameraAvailable = AVCaptureDevice.default(for: .video) != nil
    // Why the last QR was refused, shown under the reticle while scanning continues.
    // Rejections are remembered so a bad code sitting in frame buzzes once, not
    // every frame.
    @State private var scanError: String? = nil
    @State private var rejected: Set<String> = []
    @State private var torchOn = false
    @State private var hasTorch = false

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            if !cameraAvailable {
                // Simulator / no camera hardware: degrade to a message + Cancel.
                // Manual entry lives on the home screen.
                Text("Camera unavailable")
                    .font(.cpBodyMedium)
                    .foregroundStyle(.white)
            } else if authorized {
                CameraPreview(
                    torchOn: torchOn,
                    onCode: handleCode,
                    onFailure: { message in
                        finish(.failure(message: message))
                    },
                    onTorchProbed: { hasTorch = $0 }
                )
                .ignoresSafeArea()

                RoundedRectangle(cornerRadius: 24)
                    .stroke(Color.white.opacity(0.9), lineWidth: 3)
                    .frame(width: 240, height: 240)
            } else if denied {
                // Not a dead end: point at Settings (the only place that can still
                // grant it) and keep the typed-code path in reach — mirrors Android's
                // PermissionDeniedContent.
                VStack(spacing: 20) {
                    Text("Allow camera access to scan the code on your TV — or type the room code instead.")
                        .font(.cpBodyMedium)
                        .foregroundStyle(.white)
                        .multilineTextAlignment(.center)
                    // Same verb-only wording as the nearby slot's denied button — the
                    // rationale line above already says what to allow.
                    Button("Open Settings") {
                        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
                        UIApplication.shared.open(url)
                    }
                    .buttonStyle(.borderedProminent)
                    Button("Enter code manually") { finish(.enterCode) }
                        .foregroundStyle(.white)
                }
                .padding(.horizontal, 32)
            }

            // Top and bottom scrims keep the white controls (and the status-bar
            // icons) legible over a bright camera image — same recipe as Android.
            VStack(spacing: 0) {
                LinearGradient(colors: [.black.opacity(0.5), .clear],
                               startPoint: .top, endPoint: .bottom)
                    .frame(height: 140)
                Spacer(minLength: 0)
                LinearGradient(colors: [.clear, .black.opacity(0.6)],
                               startPoint: .top, endPoint: .bottom)
                    .frame(height: 200)
            }
            .ignoresSafeArea()
            .allowsHitTesting(false)

            VStack(spacing: 0) {
                HStack {
                    puckButton("xmark", label: String(localized: "Close scanner")) {
                        finish(.cancelled)
                    }
                    Spacer()
                    if hasTorch {
                        puckButton(
                            torchOn ? "bolt.fill" : "bolt.slash.fill",
                            label: torchOn ? String(localized: "Turn flashlight off")
                                           : String(localized: "Turn flashlight on")
                        ) {
                            torchOn.toggle()
                        }
                    }
                }
                Spacer(minLength: 0)
                if authorized {
                    VStack(spacing: 12) {
                        Text("Scan the room QR code")
                            .font(.cpBodyLarge)
                            .foregroundStyle(.white)
                            .multilineTextAlignment(.center)
                        if let scanError {
                            Text(scanError)
                                .font(.cpBodyMedium)
                                .multilineTextAlignment(.center)
                                // The palette's error tone flips light/dark, so pick
                                // the label against it rather than assuming white.
                                .foregroundStyle(contentColorOn(palette.error))
                                .padding(.horizontal, 16)
                                .padding(.vertical, 10)
                                .background(Capsule().fill(palette.error))
                        }
                        // The scan can always fail (a scratched code, a glossy TV);
                        // without this the only way out is backing all the way home.
                        Button { finish(.enterCode) } label: {
                            Text("Enter code manually")
                                .font(.cpTitleMedium)
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 14)
                        }
                        .buttonStyle(.plain)
                        .background(
                            palette.secondaryContainer,
                            in: RoundedRectangle(cornerRadius: 14, style: .continuous)
                        )
                        .foregroundStyle(palette.onSecondaryContainer)
                    }
                    .padding(.horizontal, 16)
                    .padding(.bottom, 8)
                    .animation(.easeInOut(duration: 0.2), value: scanError)
                }
            }
            .padding(8)
        }
        .statusBarHidden(false)
        // A refusal explains itself for a moment, then gets out of the way — the
        // hint underneath is what the player needs while they keep scanning.
        .task(id: scanError) {
            guard scanError != nil else { return }
            try? await Task.sleep(for: .seconds(3))
            scanError = nil
        }
        .task {
            guard cameraAvailable else { return }
            switch AVCaptureDevice.authorizationStatus(for: .video) {
            case .authorized:
                authorized = true
            case .notDetermined:
                if await AVCaptureDevice.requestAccess(for: .video) {
                    authorized = true
                } else {
                    denied = true
                }
            default:
                denied = true
            }
        }
        // A grant made in Settings backgrounds the app — pick it up on return so the
        // camera comes up without reopening the scanner.
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.willEnterForegroundNotification)) { _ in
            if denied, AVCaptureDevice.authorizationStatus(for: .video) == .authorized {
                denied = false
                authorized = true
            }
        }
    }

    /// The floating controls sit on live video — give them a constant dark puck so
    /// they read on any background.
    private func puckButton(_ systemName: String, label: String,
                            action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: systemName)
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(.white)
                .frame(width: 44, height: 44)
                .background(Color.black.opacity(0.35), in: Circle())
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }

    /// A frame may hold several QRs (the room code next to a poster QR on the wall),
    /// and only the launcher knows which ones mean anything — so validate HERE and
    /// keep the session running past codes that don't resolve, instead of letting the
    /// first decode end the scan. Same behavior as Android's ScanScreen: a stray QR
    /// buzzes and explains itself in place; the scan goes on.
    private func handleCode(_ value: String) {
        guard !finished else { return }
        if CP.scannedLegalUrl(value) != nil {
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
            finish(.code(value))
            return
        }
        switch JoinResolver.resolve(value, games: games) {
        case .success:
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
            finish(.code(value))
        case .failure(let message):
            // Once per distinct payload — the same code decodes on every frame, and
            // two bad codes in one frame must not take turns buzzing.
            guard rejected.insert(value).inserted else { return }
            UINotificationFeedbackGenerator().notificationOccurred(.error)
            scanError = message
        }
    }

    private func finish(_ result: QRScanResult) {
        guard !finished else { return }
        finished = true
        onFinish(result)
    }
}

// MARK: - CameraPreview (private)

private struct CameraPreview: UIViewRepresentable {
    let torchOn: Bool
    let onCode: (String) -> Void
    let onFailure: (String) -> Void
    let onTorchProbed: (Bool) -> Void

    final class PreviewView: UIView {
        override static var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }

        /// The layer-to-frame mapping only exists once the view has bounds, so the
        /// scan region is (re-)derived here as well as when the session starts.
        var onLayout: (() -> Void)?

        override func layoutSubviews() {
            super.layoutSubviews()
            onLayout?()
        }
    }

    final class Coordinator: NSObject, AVCaptureMetadataOutputObjectsDelegate {
        var onCode: (String) -> Void
        var onFailure: (String) -> Void
        var onTorchProbed: (Bool) -> Void

        let session = AVCaptureSession()
        private let sessionQueue = DispatchQueue(label: "games.couchpad.controller.qr-session")
        private var configured = false
        // Session-queue state: the capture device, and the torch state last applied
        // to it (so a SwiftUI update that changed something else doesn't re-lock it).
        private var device: AVCaptureDevice?
        private var torchOn = false
        // Main-thread state: the running output, published from the session queue only
        // once it is live, and the view whose bounds define the scan region.
        private var metadataOutput: AVCaptureMetadataOutput?
        private weak var previewView: PreviewView?

        init(onCode: @escaping (String) -> Void, onFailure: @escaping (String) -> Void,
             onTorchProbed: @escaping (Bool) -> Void) {
            self.onCode = onCode
            self.onFailure = onFailure
            self.onTorchProbed = onTorchProbed
        }

        private struct SetupError: LocalizedError {
            let message: String
            var errorDescription: String? { message }
        }

        func start() {
            guard !configured else { return }
            configured = true
            sessionQueue.async { [weak self] in
                guard let self else { return }
                do {
                    guard let device = AVCaptureDevice.default(for: .video) else {
                        throw SetupError(message: String(localized: "No camera available"))
                    }
                    let input = try AVCaptureDeviceInput(device: device)
                    self.device = device
                    Self.applyCenterMetering(device)
                    let torchAvailable = device.hasTorch
                    DispatchQueue.main.async { self.onTorchProbed(torchAvailable) }
                    self.session.beginConfiguration()
                    guard self.session.canAddInput(input) else {
                        self.session.commitConfiguration()
                        throw SetupError(message: String(localized: "Unable to use the camera"))
                    }
                    self.session.addInput(input)
                    let output = AVCaptureMetadataOutput()
                    guard self.session.canAddOutput(output) else {
                        self.session.commitConfiguration()
                        throw SetupError(message: String(localized: "Unable to scan with this camera"))
                    }
                    self.session.addOutput(output)
                    output.setMetadataObjectsDelegate(self, queue: .main)
                    guard output.availableMetadataObjectTypes.contains(.qr) else {
                        self.session.commitConfiguration()
                        throw SetupError(message: String(localized: "QR scanning is not supported on this device"))
                    }
                    output.metadataObjectTypes = [.qr]
                    self.session.commitConfiguration()
                    self.session.startRunning()
                    // Only now can the preview layer have a connection to convert
                    // through, so the scan region is derived here (and on every layout).
                    DispatchQueue.main.async {
                        self.metadataOutput = output
                        self.applyScanRegionWhenReady()
                    }
                } catch {
                    let message = error.localizedDescription
                    DispatchQueue.main.async { self.onFailure(message) }
                }
            }
        }

        // Mirror the Android scanner's center-weighted metering: bias continuous
        // AF/AE toward the viewfinder center so clutter at the frame edges can't
        // pull focus or exposure off the code. AVFoundation only takes a point of
        // interest (no rect); the point must be set BEFORE the mode — changing the
        // mode is what makes the new point take effect. Best-effort: a lock
        // failure just leaves the system defaults in place.
        private static func applyCenterMetering(_ device: AVCaptureDevice) {
            guard (try? device.lockForConfiguration()) != nil else { return }
            defer { device.unlockForConfiguration() }
            let center = CGPoint(x: 0.5, y: 0.5)
            if device.isFocusPointOfInterestSupported {
                device.focusPointOfInterest = center
            }
            if device.isFocusModeSupported(.continuousAutoFocus) {
                device.focusMode = .continuousAutoFocus
            }
            if device.isExposurePointOfInterestSupported {
                device.exposurePointOfInterest = center
            }
            if device.isExposureModeSupported(.continuousAutoExposure) {
                device.exposureMode = .continuousAutoExposure
            }
        }

        /// Confine decoding to the frame the player can actually see. `.resizeAspectFill`
        /// crops the camera frame to the screen, so without this a code sitting just
        /// outside the preview — the TV next to the one they're aiming at, a poster on
        /// the wall — decodes and joins a room they never pointed at. Android gets the
        /// same guarantee from its CameraX ViewPort (ScanScreen.kt).
        ///
        /// Main thread only. Deliberately conservative: until the session is running
        /// there is no layer-to-frame mapping, and a conversion that lands outside the
        /// unit square or degenerate is discarded rather than applied — a bad region
        /// scans nothing at all. Reports whether a region was actually installed.
        @discardableResult
        func applyScanRegion() -> Bool {
            guard let output = metadataOutput, let view = previewView,
                  view.previewLayer.connection != nil,
                  view.bounds.width > 0, view.bounds.height > 0 else { return false }
            let unit = CGRect(x: 0, y: 0, width: 1, height: 1)
            let region = view.previewLayer
                .metadataOutputRectConverted(fromLayerRect: view.previewLayer.bounds)
                .intersection(unit)
            guard !region.isNull, region.width > 0.05, region.height > 0.05 else { return false }
            output.rectOfInterest = region
            return true
        }

        /// Keeps trying until the region lands, because the fallback for one that never
        /// does is the whole camera frame — the exact thing it exists to prevent, and
        /// invisible when it happens. Layout is not a dependable second chance: the view
        /// is normally laid out well before the session comes up, so the attempt right
        /// after `startRunning` can be the last one anything triggers.
        func applyScanRegionWhenReady(attemptsLeft: Int = 10) {
            if applyScanRegion() || attemptsLeft == 0 { return }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) { [weak self] in
                self?.applyScanRegionWhenReady(attemptsLeft: attemptsLeft - 1)
            }
        }

        func attach(_ view: PreviewView) {
            previewView = view
        }

        /// The scanner's own flashlight — the room code is often on a card or a
        /// sleeve, not just a lit TV. Stopping the session releases the torch, so
        /// there's nothing to undo on teardown.
        func setTorch(_ on: Bool) {
            sessionQueue.async { [weak self] in
                guard let self, self.torchOn != on,
                      let device = self.device, device.hasTorch,
                      (try? device.lockForConfiguration()) != nil else { return }
                defer { device.unlockForConfiguration() }
                self.torchOn = on
                device.torchMode = on ? .on : .off
            }
        }

        func stop() {
            sessionQueue.async { [weak self] in
                guard let self else { return }
                if self.session.isRunning {
                    self.session.stopRunning()
                }
            }
        }

        // Every decoded QR in the frame goes up — the screen decides which ones end
        // the scan (see handleCode), so the session keeps running until dismantle.
        func metadataOutput(_ output: AVCaptureMetadataOutput,
                            didOutput metadataObjects: [AVMetadataObject],
                            from connection: AVCaptureConnection) {
            for object in metadataObjects {
                guard let object = object as? AVMetadataMachineReadableCodeObject,
                      object.type == .qr,
                      let value = object.stringValue else { continue }
                onCode(value)
            }
        }
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(onCode: onCode, onFailure: onFailure, onTorchProbed: onTorchProbed)
    }

    func makeUIView(context: Context) -> PreviewView {
        let view = PreviewView()
        view.backgroundColor = .black
        view.previewLayer.session = context.coordinator.session
        view.previewLayer.videoGravity = .resizeAspectFill
        view.onLayout = { [weak coordinator = context.coordinator] in coordinator?.applyScanRegion() }
        context.coordinator.attach(view)
        context.coordinator.start()
        return view
    }

    func updateUIView(_ uiView: PreviewView, context: Context) {
        context.coordinator.onCode = onCode
        context.coordinator.onFailure = onFailure
        context.coordinator.onTorchProbed = onTorchProbed
        context.coordinator.setTorch(torchOn)
    }

    static func dismantleUIView(_ uiView: PreviewView, coordinator: Coordinator) {
        coordinator.stop()
    }
}
