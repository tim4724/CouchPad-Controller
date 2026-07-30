package games.couchpad.controller.data

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * GETs [url] and, on a 200, returns [read] applied to the body stream. Blocking —
 * call on IO. Null on any failure (in [read] too). The data layer's one copy of the
 * HttpURLConnection plumbing — timeouts, status check, disconnect. (RoomDirectory
 * still hand-rolls its own: it must tell 404 apart from other failures.)
 */
internal fun <T> httpGet(url: String, readTimeoutMs: Int = 10_000, read: (InputStream) -> T?): T? = runCatching {
  val conn = (URL(url).openConnection() as HttpURLConnection).apply {
    connectTimeout = 10_000
    readTimeout = readTimeoutMs
  }
  try {
    if (conn.responseCode != HttpURLConnection.HTTP_OK) null
    else conn.inputStream.use(read)
  } finally {
    conn.disconnect()
  }
}.getOrNull()
