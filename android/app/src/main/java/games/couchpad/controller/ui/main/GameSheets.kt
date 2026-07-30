package games.couchpad.controller.ui.main

import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.widget.VideoView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.res.pluralStringResource
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
      Text(game.name, style = MaterialTheme.typography.headlineSmall)
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
      game.playersLabel()?.let {
        Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
      }
      if (game.isLive) {
        PlaySteps(game)
        JoinButtons(onScan = onScan, onEnterCode = onEnterCode)
      }
    }
  }
}

/**
 * The player-count line ("1–8 players"), rendered from the manifest's count range
 * through the shared plurals so the noun agrees with the count (Russian: "1–4
 * игрока" vs "1–8 игроков"; the range agrees with the max). A single-count game
 * (min == max) uses the plain-count plural. Null when the manifest gives no counts.
 */
@Composable
fun Game.playersLabel(): String? {
  val min = minPlayers ?: return null
  val max = maxPlayers ?: return null
  return if (min == max) {
    pluralStringResource(R.plurals.game_players_count, min, min)
  } else {
    pluralStringResource(R.plurals.game_players_range, max, min, max)
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
