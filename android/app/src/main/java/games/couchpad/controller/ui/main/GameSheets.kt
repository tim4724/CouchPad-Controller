package games.couchpad.controller.ui.main

import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.widget.VideoView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import games.couchpad.controller.R
import games.couchpad.controller.data.Game
import games.couchpad.controller.data.TrailerCache
import games.couchpad.controller.ui.components.AppSheet
import games.couchpad.controller.ui.components.GameArt
import games.couchpad.controller.ui.components.JoinButtons
import games.couchpad.controller.ui.components.PlaySteps
import games.couchpad.controller.ui.components.PosterStatusChip
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Pure game info — name, media, players. A live game shows its muted gameplay
 * loop; a not-yet-live game (no video) shows its cover art instead. Joining
 * lives on the home's Join card.
 */
@Composable
fun GameInfoSheet(
  game: Game,
  onDismiss: () -> Unit,
  onScan: () -> Unit,
  onEnterCode: () -> Unit,
) {
  AppSheet(onDismiss = onDismiss) {
    Column(
      Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 28.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          game.name,
          Modifier.weight(1f),
          style = MaterialTheme.typography.headlineSmall,
          fontWeight = FontWeight.Bold,
        )
        game.playersRange?.let { PlayersChip(it) }
      }
      Box {
        if (game.video != null) {
          GameplayLoop(game, game.video)
        } else {
          GameArt(game, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(MaterialTheme.shapes.large))
        }
        if (!game.isLive) {
          PosterStatusChip(game, Modifier.align(Alignment.BottomEnd).padding(14.dp))
        }
      }
      if (game.tvApps.isNotEmpty() || game.displayHost != null) PlatformTiles(game)
      if (game.isLive) {
        PlaySteps(game)
        JoinButtons(onScan = onScan, onEnterCode = onEnterCode)
      }
    }
  }
}

// Platforms with native TV apps (manifest tvApps): id -> the nearby-card device
// label, reused. Unknown manifest ids simply have no tile. Deliberately no brand
// logos: Apple licenses only its word marks to third parties, and Google's
// Android TV guidance excludes the robot — the neutral TV glyph + name is the
// compliant version of the same message on both platforms.
private val TV_PLATFORMS = listOf(
  "appletv" to R.string.device_apple_tv,
  "androidtv" to R.string.device_android_tv,
)

/**
 * Where the game runs, as a row of equal device tiles: one per declared TV app
 * (dimmed, with the shared "Coming soon" copy, when not yet live) plus a tile
 * for the browser path. This row is the sheet's only mention of platforms and
 * host, so the play steps stay path-free (PlaySteps).
 */
@Composable
private fun PlatformTiles(game: Game) {
  Row(
    Modifier.fillMaxWidth().height(IntrinsicSize.Min),
    horizontalArrangement = Arrangement.spacedBy(9.dp),
  ) {
    TV_PLATFORMS.forEach { (id, nameRes) ->
      val status = game.tvApps[id] ?: return@forEach
      Tile(R.drawable.ic_tv, stringResource(nameRes), soon = status != "live", Modifier.weight(1f))
    }
    game.displayHost?.let {
      // The zero-width space before each dot is an invisible break hint: a
      // narrow tile wraps to "hexstacker" / ".com" instead of ellipsizing.
      Tile(R.drawable.ic_globe, it.replace(".", "\u200B."), soon = false, Modifier.weight(1f))
    }
  }
}

// A not-yet-live tile dims its icon and label to 45% — same state the poster's
// "Coming soon" chip marks, signalled here by dimming instead of a color swap.
@Composable
private fun Tile(iconRes: Int, label: String, soon: Boolean, modifier: Modifier) {
  val base = MaterialTheme.colorScheme.onSurface
  val content = if (soon) base.copy(alpha = 0.45f) else base
  Column(
    modifier
      .fillMaxHeight()
      .clip(MaterialTheme.shapes.large)
      // Highest, not High — the sheet surface itself is surfaceContainerHigh
      // (AppSheet), so the tile needs the next step to be visible on it.
      .background(MaterialTheme.colorScheme.surfaceContainerHighest)
      .padding(vertical = 12.dp, horizontal = 6.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    // Top-aligned so icons and names line up across tiles even when one tile
    // carries the extra "Coming soon" line.
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Icon(painterResource(iconRes), contentDescription = null, Modifier.size(20.dp), tint = content)
    Text(
      label,
      style = MaterialTheme.typography.labelMedium,
      color = content,
      textAlign = TextAlign.Center,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
    )
    if (soon) {
      Text(
        stringResource(R.string.status_coming_soon),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
      )
    }
  }
}

@Composable
private fun PlayersChip(range: String) {
  Row(
    Modifier
      .clip(CircleShape)
      .background(MaterialTheme.colorScheme.surfaceContainerHighest)
      .padding(horizontal = 10.dp, vertical = 5.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(5.dp),
  ) {
    Icon(
      painterResource(R.drawable.ic_people),
      contentDescription = null,
      Modifier.size(16.dp),
      tint = MaterialTheme.colorScheme.onSurface,
    )
    Text(
      range,
      style = MaterialTheme.typography.labelLarge,
      color = MaterialTheme.colorScheme.onSurface,
    )
  }
}

// A muted gameplay loop, fetched to cache on demand (TrailerCache) and played
// from disk. Cover art fills the slot immediately and stays on top until the
// video renders its first frame — a VideoView is SurfaceView-backed, so it
// shows through as black until then. VideoView over ExoPlayer: a local 30s
// loop doesn't justify the Media3 dependency.
@Composable
private fun GameplayLoop(game: Game, url: String) {
  val context = LocalContext.current
  var videoRendering by remember { mutableStateOf(false) }
  val file by produceState<File?>(initialValue = null, url) {
    value = withContext(Dispatchers.IO) { TrailerCache.fetch(context, url) }
  }
  Box(
    Modifier
      .fillMaxWidth()
      .aspectRatio(16f / 9f)
      .clip(MaterialTheme.shapes.large),
  ) {
    file?.let { trailer ->
      AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
          VideoView(ctx).apply {
            // The clip is muted, so never take audio focus — stock VideoView otherwise
            // pauses whatever the user is listening to. (No-op below API 26.)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
              setAudioFocusRequest(AudioManager.AUDIOFOCUS_NONE)
            }
            setVideoPath(trailer.absolutePath)
            setOnInfoListener { _, what, _ ->
              if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) videoRendering = true
              true
            }
            setOnPreparedListener { mp ->
              mp.isLooping = true
              mp.setVolume(0f, 0f)
              mp.start()
            }
          }
        },
        onRelease = { it.stopPlayback() },
      )
    }
    AnimatedVisibility(visible = !videoRendering, exit = fadeOut()) {
      GameArt(game, Modifier.fillMaxSize())
    }
  }
}
