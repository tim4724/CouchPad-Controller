import SwiftUI
import AVFoundation
import UIKit

// MARK: - QRScanResult

enum QRScanResult: Equatable {
    case code(String)
    case cancelled
    case failure(message: String)
}

// MARK: - QRScannerScreen

struct QRScannerScreen: View {
    let onFinish: (QRScanResult) -> Void   // called exactly once; caller dismisses the cover

    init(onFinish: @escaping (QRScanResult) -> Void) {
        self.onFinish = onFinish
    }

    @State private var finished = false
    @State private var authorized = false
    @State private var cameraAvailable = AVCaptureDevice.default(for: .video) != nil

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
                    onCode: { value in
                        UIImpactFeedbackGenerator(style: .light).impactOccurred()
                        finish(.code(value))
                    },
                    onFailure: { message in
                        finish(.failure(message: message))
                    }
                )
                .ignoresSafeArea()

                VStack(spacing: 24) {
                    RoundedRectangle(cornerRadius: 24)
                        .stroke(Color.white.opacity(0.9), lineWidth: 3)
                        .frame(width: 240, height: 240)
                    Text("Scan the room QR code")
                        .font(.cpBodyMedium)
                        .foregroundStyle(.white)
                }
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
                let granted = await AVCaptureDevice.requestAccess(for: .video)
                if granted {
                    authorized = true
                } else {
                    finish(.failure(message: String(localized: "Camera permission denied")))
                }
            default:
                finish(.failure(message: String(localized: "Camera permission denied")))
            }
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
        private var didEmit = false

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

        // First metadata callback wins.
        func metadataOutput(_ output: AVCaptureMetadataOutput,
                            didOutput metadataObjects: [AVMetadataObject],
                            from connection: AVCaptureConnection) {
            guard !didEmit else { return }
            guard let object = metadataObjects.first as? AVMetadataMachineReadableCodeObject,
                  object.type == .qr,
                  let value = object.stringValue else { return }
            didEmit = true
            stop()
            onCode(value)
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
