import CryptoKit
import Foundation

/// On-demand URL-keyed download caches in Caches/, so the OS reclaims the bytes
/// under storage pressure. Entries are NEVER revalidated — a changed remote file
/// must ship under a new URL (file rename or ?v= bump in the manifest), which
/// simply fetches a new entry here.
///
/// - `TrailerCache`: gameplay mp4s — not bundled, they'd grow the install per game.
///   The info sheet shows cover art while the file lands, then plays from disk.
/// - `ArtworkCache`: cover art the current manifest names but this build didn't
///   ship (a game added or re-artworked after install; see ArtCache).
enum TrailerCache {
    /// Local file for `url`, downloading it first if absent. Nil on any failure.
    static func fetch(_ url: URL) async -> URL? {
        await fetchCached(url, dirName: "trailers", ext: "mp4")
    }
}

enum ArtworkCache {
    /// Local file for `url`, downloading it first if absent. Nil on any failure.
    static func fetch(_ url: URL) async -> URL? {
        await fetchCached(url, dirName: "artwork", ext: "img")
    }
}

private func fetchCached(_ url: URL, dirName: String, ext: String) async -> URL? {
    let fm = FileManager.default
    let dir = fm.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        .appendingPathComponent(dirName, isDirectory: true)
    let digest = SHA256.hash(data: Data(url.absoluteString.utf8))
    let key = digest.map { String(format: "%02x", $0) }.joined().prefix(16)
    let dest = dir.appendingPathComponent("\(key).\(ext)")
    if fm.fileExists(atPath: dest.path) { return dest }
    guard let (tmp, response) = try? await URLSession.shared.download(from: url),
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
