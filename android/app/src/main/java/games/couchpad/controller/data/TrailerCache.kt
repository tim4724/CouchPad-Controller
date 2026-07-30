package games.couchpad.controller.data

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * On-demand URL-keyed download caches in cacheDir, so the OS reclaims the bytes
 * under storage pressure. Entries are NEVER revalidated — a changed remote file
 * must ship under a new URL (file rename or ?v= bump in the manifest), which
 * simply fetches a new entry here.
 *
 * - [TrailerCache]: gameplay mp4s — not bundled, they'd grow the install per game.
 *   The info sheet shows cover art while the file lands, then plays from disk.
 * - [ArtworkCache]: cover art the current manifest names but this build didn't
 *   ship (a game added or re-artworked after install; see GameArt).
 */
object TrailerCache {
  /** Cached file for [url], downloading it first if absent. Blocking — call on IO. Null on any failure. */
  fun fetch(context: Context, url: String): File? = fetchCached(context, url, "trailers", "mp4")
}

object ArtworkCache {
  /** Cached file for [url], downloading it first if absent. Blocking — call on IO. Null on any failure. */
  fun fetch(context: Context, url: String): File? = fetchCached(context, url, "artwork", "img")
}

private fun fetchCached(context: Context, url: String, dirName: String, ext: String): File? {
  val dir = File(context.cacheDir, dirName)
  dir.mkdirs()
  val key = MessageDigest.getInstance("SHA-256")
    .digest(url.toByteArray())
    .joinToString("") { "%02x".format(it) }
    .take(16)
  val file = File(dir, "$key.$ext")
  if (file.length() > 0) return file
  // Download to a unique temp sibling and rename, so a torn or concurrent
  // download can never be served.
  val tmp = runCatching { File.createTempFile(key, ".part", dir) }.getOrNull() ?: return null
  try {
    httpGet(url, readTimeoutMs = 30_000) { input ->
      tmp.outputStream().use { input.copyTo(it) }
    } ?: return null
    return if (tmp.renameTo(file) || file.length() > 0) file else null
  } finally {
    tmp.delete()
  }
}
