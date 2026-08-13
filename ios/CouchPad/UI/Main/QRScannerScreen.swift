import SwiftUI
import AVFoundation
import UIKit

// MARK: - QRScanResult

enum QRScanResult: Equatable {
    case code(String)
    case cancelled
    case enterCode                 // manual entry chosen from the denied screen
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

    @State private var finished = false
    @State private var authorized = false
    @State private var denied = false
    @State private var cameraAvailable = AVCaptureDevice.default(for: .video) != nil
    // Why the last QR was refused, shown under the reticle while scanning continues.
    @State private var scanError: String? = nil
    @State private var lastRejected: String? = nil

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
                    onCode: handleCode,
                    onFailure: { message in
                        finish(.failure(message: message))
                    }
                )
                .ignoresSafeArea()

                VStack(spacing: 24) {
                    RoundedRectangle(cornerRadius: 24)
                        .stroke(Color.white.opacity(0.9), lineWidth: 3)
                        .frame(width: 240, height: 240)
                    Text(scanError ?? String(localized: "Scan the room QR code"))
                        .font(.cpBodyMedium)
                        .foregroundStyle(.white)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 32)
                }
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

            VStack {
                HStack {
                    Button {
                        finish(.cancelled)
                    } label: {
                        Image(systemName: "xmark")
                            .font(.system(size: 20, weight: .semibold))
                            .foregroundStyle(.white)
                            .frame(width: 44, height: 44)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    Spacer()
                }
                Spacer()
            }
            .padding(8)
        }
        .statusBarHidden(false)
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
            // Once per distinct payload — the same code decodes on every frame.
            guard value != lastRejected else { return }
            lastRejected = value
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
    let onCode: (String) -> Void
    let onFailure: (String) -> Void

    final class PreviewView: UIView {
        override static var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
    }

    final class Coordinator: NSObject, AVCaptureMetadataOutputObjectsDelegate {
        var onCode: (String) -> Void
        var onFailure: (String) -> Void

        let session = AVCaptureSession()
        private let sessionQueue = DispatchQueue(label: "games.couchpad.controller.qr-session")
        private var configured = false

        init(onCode: @escaping (String) -> Void, onFailure: @escaping (String) -> Void) {
            self.onCode = onCode
            self.onFailure = onFailure
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
                    Self.applyCenterMetering(device)
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
        Coordinator(onCode: onCode, onFailure: onFailure)
    }

    func makeUIView(context: Context) -> PreviewView {
        let view = PreviewView()
        view.backgroundColor = .black
        view.previewLayer.session = context.coordinator.session
        view.previewLayer.videoGravity = .resizeAspectFill
        context.coordinator.start()
        return view
    }

    func updateUIView(_ uiView: PreviewView, context: Context) {
        context.coordinator.onCode = onCode
        context.coordinator.onFailure = onFailure
    }

    static func dismantleUIView(_ uiView: PreviewView, coordinator: Coordinator) {
        coordinator.stop()
    }
}
