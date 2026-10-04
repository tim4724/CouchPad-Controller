import CryptoKit
import Foundation

/// On-demand URL-keyed download caches in Caches/, so the OS reclaims the bytes
/// under storage pressure. Entries are NEVER revalidated — a changed remote
/// file must ship under a new URL, which simply fetches a new entry here. For
/// art that means a new file name, not a ?v= bump, which breaks the bundled-art
/// lookup (README §Where things live); trailers aren't bundled, so ?v= is enough.
///
/// - `TrailerCache`: gameplay mp4s — not bundled, they'd grow the install per game.
///   The info sheet shows cover art while the file lands, then plays from disk.
/// - `ArtworkCache`: cover art the current manifest names but this build didn't
///   ship (a game added or re-artworked after install; see ArtCache).
enum TrailerCache {
    /// Local file for `url`, downloading it first if absent. Nil on any failure.
    /// `onProgress` gets the downloaded fraction (0...1) as bytes land, off the main
    /// actor — never on a cache hit, nor when the server sends no length.
    static func fetch(_ url: URL, onProgress: @escaping @Sendable (Double) -> Void) async -> URL? {
        await fetchCached(url, dirName: "trailers", ext: "mp4", delegate: DownloadProgress(onProgress))
    }
}

enum ArtworkCache {
    /// Local file for `url`, downloading it first if absent. Nil on any failure.
    static func fetch(_ url: URL) async -> URL? {
        await fetchCached(url, dirName: "artwork", ext: "img")
    }
}

private func fetchCached(_ url: URL, dirName: String, ext: String,
                         delegate: URLSessionTaskDelegate? = nil) async -> URL? {
    let fm = FileManager.default
    let dir = fm.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        .appendingPathComponent(dirName, isDirectory: true)
    let digest = SHA256.hash(data: Data(url.absoluteString.utf8))
    let key = digest.map { String(format: "%02x", $0) }.joined().prefix(16)
    let dest = dir.appendingPathComponent("\(key).\(ext)")
    if fm.fileExists(atPath: dest.path) { return dest }
    guard let (tmp, response) = try? await URLSession.shared.download(from: url, delegate: delegate),
          (response as? HTTPURLResponse)?.statusCode == 200
    else { return nil }
    try? fm.createDirectory(at: dir, withIntermediateDirectories: true)
    do {
        try fm.moveItem(at: tmp, to: dest)
    } catch {
        // A concurrent fetch may have landed the file first — that copy is fine.
        return fm.fileExists(atPath: dest.path) ? dest : nil
    }
    return dest
}

/// Reports a download's progress by observing the task's own `Progress`.
private final class DownloadProgress: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    private let onProgress: @Sendable (Double) -> Void
    private var observation: NSKeyValueObservation?

    init(_ onProgress: @escaping @Sendable (Double) -> Void) {
        self.onProgress = onProgress
    }

    func urlSession(_ session: URLSession, didCreateTask task: URLSessionTask) {
        observation = task.progress.observe(\.fractionCompleted) { [onProgress] progress, _ in
            if progress.totalUnitCount > 0 { onProgress(progress.fractionCompleted) }
        }
    }
}
