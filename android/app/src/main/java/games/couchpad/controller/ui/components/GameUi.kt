package games.couchpad.controller.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import games.couchpad.controller.R
import games.couchpad.controller.data.ArtworkCache
import games.couchpad.controller.data.Game
import games.couchpad.controller.data.PLATFORM_ANDROID_TV
import games.couchpad.controller.data.PLATFORM_TVOS
import games.couchpad.controller.data.PLATFORM_WEB
import games.couchpad.controller.data.remoteArtUrl
import games.couchpad.controller.theme.ActionCoral
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// The tappable player identity — home header and in-game bar. [accented]: name,
// icon, and outline take `primary`, which the game host has already remapped to
// the game's cp-accent-color.
@Composable
fun PlayerChip(name: String, onClick: () -> Unit, accented: Boolean = false) {
  AssistChip(
    onClick = onClick,
    modifier = Modifier.height(40.dp),
    label = {
      Text(
        name.ifBlank { stringResource(R.string.set_name) },
        style = MaterialTheme.typography.bodyLarge,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    },
    leadingIcon = {
      Icon(Icons.Filled.Person, contentDescription = null, Modifier.size(20.dp))
    },
    colors =
      if (accented) {
        AssistChipDefaults.assistChipColors(
          labelColor = MaterialTheme.colorScheme.primary,
          leadingIconContentColor = MaterialTheme.colorScheme.primary,
        )
      } else {
        AssistChipDefaults.assistChipColors()
      },
    border =
      if (accented) {
        AssistChipDefaults.assistChipBorder(enabled = true, borderColor = MaterialTheme.colorScheme.primary)
      } else {
        AssistChipDefaults.assistChipBorder(enabled = true)
      },
  )
}

/**
 * Decoded-bitmap ceiling, matching iOS's ArtCache: a poster card renders at most
 * ~410dp wide (~1230px at 3x), so 720p covers every device. The bundled covers are
 * encoded to exactly this (tools/encode_artwork.sh); the ceiling is what stops a
 * larger one — served art, or a future master — from decoding at full size.
 */
private const val MAX_ART_WIDTH = 1280
private const val MAX_ART_HEIGHT = 720

/**
 * Process-wide memoized decode of artwork, keyed by manifest art path. Decoding runs
 * off the main thread, so opening a sheet never decodes mid-animation, and a path
 * that FAILS is remembered too — otherwise a game whose art this build didn't ship
 * (and whose download failed) re-probes assets and disk on every recomposition.
 * Mirrors iOS's ArtCache (UI/Components/SharedComponents.swift).
 */
internal object ArtCache {
  private val images = ConcurrentHashMap<String, ImageBitmap>()
  private val failures = ConcurrentHashMap.newKeySet<String>()

  /** Non-null only after a successful decode has been memoized. */
  fun cached(art: String): ImageBitmap? = images[art]

  suspend fun load(context: Context, art: String): ImageBitmap? {
    images[art]?.let { return it }
    if (art in failures) return null
    val decoded = withContext(Dispatchers.IO) { decode(context, art) }
    if (decoded == null) failures.add(art) else images[art] = decoded
    return decoded
  }

  /**
   * The bundled asset when one ships under artwork/<file> (matched by file name —
   * the bundled and served manifests root the same art differently), else the
   * ArtworkCache copy downloaded from the manifest URL. Both lookups key on the
   * URL/name, never on content, so a changed image must ship under a new file name
   * or ?v= — see ArtworkCache.
   */
  private fun decode(context: Context, art: String): ImageBitmap? {
    val assetPath = "artwork/" + art.substringAfterLast('/')
    decodeScaled { runCatching { context.assets.open(assetPath) }.getOrNull() }
      ?.let { return it.asImageBitmap() }
    val url = remoteArtUrl(art) ?: return null
    val file = ArtworkCache.fetch(context, url) ?: return null
    return decodeScaled { runCatching { file.inputStream() }.getOrNull() }?.asImageBitmap()
  }
}

/**
 * Decodes [open]'s bytes scaled down inside the decoder, so an oversized source
 * never materializes at full size: a power-of-two prepass (all `inSampleSize`
 * offers) gets within 2x of the ceiling, then inDensity/inTargetDensity lands the
 * exact aspect-preserving fit. Needs TWO streams — the bounds pass consumes one.
 */
private fun decodeScaled(open: () -> InputStream?): Bitmap? {
  val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
  // The bounds pass returns null BY DESIGN — the dimensions land on `bounds`, so
  // the stream, not the decode result, is what says whether there was anything read.
  (open() ?: return null).use { BitmapFactory.decodeStream(it, null, bounds) }
  val width = bounds.outWidth
  val height = bounds.outHeight
  if (width <= 0 || height <= 0) return null
  val opts = BitmapFactory.Options().apply { inSampleSize = 1 }
  while (width / (opts.inSampleSize * 2) >= MAX_ART_WIDTH &&
    height / (opts.inSampleSize * 2) >= MAX_ART_HEIGHT
  ) {
    opts.inSampleSize *= 2
  }
  val sampledWidth = width / opts.inSampleSize
  val sampledHeight = height / opts.inSampleSize
  if (sampledWidth > MAX_ART_WIDTH || sampledHeight > MAX_ART_HEIGHT) {
    opts.inScaled = true
    // Whichever axis binds first supplies the one ratio, so the aspect holds.
    if (sampledWidth * MAX_ART_HEIGHT >= sampledHeight * MAX_ART_WIDTH) {
      opts.inDensity = sampledWidth
      opts.inTargetDensity = MAX_ART_WIDTH
    } else {
      opts.inDensity = sampledHeight
      opts.inTargetDensity = MAX_ART_HEIGHT
    }
  }
  return open()?.use { BitmapFactory.decodeStream(it, null, opts) }
}

/**
 * Cover art or, when a game has none yet, its accent as a soft gradient. The
 * accent is the one place a game's own branding shows outside the WebView.
 */
@Composable
fun GameArt(game: Game, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val img by produceState(initialValue = game.art?.let(ArtCache::cached), game.art) {
    value = game.art?.let { ArtCache.load(context, it) }
  }
  Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
    val bitmap = img
    if (bitmap != null) {
      Image(bitmap, contentDescription = game.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    } else {
      Box(
        Modifier.fillMaxSize().background(
          Brush.linearGradient(listOf(game.accentColor.copy(alpha = 0.50f), game.accentColor.copy(alpha = 0.14f))),
        ),
      )
    }
  }
}

/**
 * The display's name for a `cpp` platform code, rendered by the launcher and never by
 * the wire, so the wording stays localized and consistent. Null when the URL declared
 * nothing usable.
 */
@Composable
fun deviceName(platform: String?): String? = when (platform) {
  PLATFORM_TVOS -> stringResource(R.string.device_apple_tv)
  PLATFORM_ANDROID_TV -> stringResource(R.string.device_android_tv)
  PLATFORM_WEB -> stringResource(R.string.device_web)
  else -> null
}

/**
 * A game's square brand mark — the manifest `icon`, not the 16:9 cover (a poster crop is
 * unreadable this small). Falls back to the TV glyph for a game with no icon, or an
 * advert that resolved to an unknown launcher subdomain. The mark fills the tile and is
 * clipped to it: icons ship in their own app-icon frame (rounded square, own backdrop),
 * so a plate underneath would only show as a ring around a frame.
 */
@Composable
fun GameIcon(game: Game, tint: Color, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val img by produceState(initialValue = game.icon?.let(ArtCache::cached), game.icon) {
    value = game.icon?.let { ArtCache.load(context, it) }
  }
  val bitmap = img
  if (bitmap != null) {
    Image(
      bitmap,
      contentDescription = null,
      modifier.size(48.dp).clip(RoundedCornerShape(12.dp)),
      contentScale = ContentScale.Fit,
    )
  } else {
    Box(modifier.size(48.dp), contentAlignment = Alignment.Center) {
      Icon(painterResource(R.drawable.ic_tv), contentDescription = null, Modifier.size(34.dp), tint = tint)
    }
  }
}

/**
 * The "Open <host> on your TV…" line: the localized template positions the host,
 * and the host gets a semibold accent span wherever the language puts it.
 */
fun annotatedHostLine(template: String, host: String, hostColor: Color): AnnotatedString =
  buildAnnotatedString {
    // A translation missing the placeholder degrades to template-then-host rather
    // than crashing — the same shape iOS falls back to.
    val at = template.indexOf("%1\$s")
    append(if (at >= 0) template.substring(0, at) else template)
    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = hostColor)) { append(host) }
    if (at >= 0) append(template.substring(at + 4))
  }

/** Numbered instruction row. */
@Composable
private fun StepRow(n: Int, text: AnnotatedString) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Box(
      // Solid primary, not primaryContainer — the pale container tint is nearly
      // invisible against the sheet's surface in light mode.
      Modifier.size(28.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
      contentAlignment = Alignment.Center,
    ) {
      Text(
        "$n",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onPrimary,
      )
    }
    Text(text, style = MaterialTheme.typography.bodyLarge)
  }
}

/**
 * The two-step "start on your TV, then scan" how-to for a live game's info sheet —
 * the app is the controller, so a first-timer who taps the card learns they need
 * the game running on a big screen first.
 */
@Composable
fun PlaySteps(game: Game) {
  Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
    // Deliberately path-free ("start it", not "open the app / the site") — where
    // the game runs is the device-tile row's job (PlatformTiles).
    StepRow(1, AnnotatedString(stringResource(R.string.play_step_start, game.name)))
    StepRow(2, AnnotatedString(stringResource(R.string.play_step_scan)))
  }
}

/** The two join actions — shared by the home Join card and a live game's info sheet. */
@Composable
fun JoinButtons(onScan: () -> Unit, onEnterCode: () -> Unit) {
  // Coral is reserved for this one CTA (site --action rule): large/bold only,
  // everything else stays on the neutral chrome.
  Button(
    onClick = onScan,
    modifier = Modifier.fillMaxWidth().height(56.dp),
    colors = ButtonDefaults.buttonColors(containerColor = ActionCoral, contentColor = Color.White),
  ) {
    Icon(painterResource(R.drawable.ic_qr_scan), contentDescription = null, Modifier.size(22.dp))
    Spacer(Modifier.width(10.dp))
    Text(stringResource(R.string.scan_code), style = MaterialTheme.typography.titleMedium)
  }
  FilledTonalButton(onClick = onEnterCode, modifier = Modifier.fillMaxWidth().height(56.dp)) {
    Text(stringResource(R.string.enter_code_manually), style = MaterialTheme.typography.titleMedium)
  }
}

// Solid accent = live, accent-tinted dark = coming soon. The chip can land on
// bright art (the scrim thins toward its top), so the soon-variant needs its own
// dark base rather than a bare translucent tint. Shared by the home poster cards
// and the info sheet's art overlay.
@Composable
fun PosterStatusChip(game: Game, modifier: Modifier = Modifier) {
  val bg =
    if (game.isLive) game.accentColor
    else game.accentColor.copy(alpha = 0.32f).compositeOver(Color.Black.copy(alpha = 0.55f))
  val fg = if (game.isLive) Color.Black.copy(alpha = 0.85f) else Color.White
  Text(
    stringResource(if (game.isLive) R.string.status_live else R.string.status_coming_soon),
    style = MaterialTheme.typography.labelMedium,
    color = fg,
    modifier = modifier
      .clip(CircleShape)
      .background(bg)
      .padding(horizontal = 10.dp, vertical = 4.dp),
  )
}
