package games.couchpad.controller.ui.main

import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.view.WindowManager
import android.widget.VideoView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import games.couchpad.controller.R
import games.couchpad.controller.data.Game
import games.couchpad.controller.data.TrailerCache
import games.couchpad.controller.ui.components.AppSheet
import games.couchpad.controller.ui.components.GameArt
import games.couchpad.controller.ui.components.JoinButtons
import games.couchpad.controller.ui.components.PlaySteps
import games.couchpad.controller.ui.components.findActivity
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay

/**
 * Pure game info — name, media, players. A live game shows its gameplay loop,
 * muted until the user unmutes it; a not-yet-live game (no video) shows its
 * cover art instead. Joining lives on the home's Join card.
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
      if (game.video != null) {
        GameplayLoop(game, game.video)
      } else {
        GameArt(game, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(MaterialTheme.shapes.large))
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

// A gameplay loop, fetched to cache on demand (TrailerCache) and played from
// disk. Cover art fills the slot immediately and stays on top until the video
// renders its first frame — a VideoView is SurfaceView-backed, so it shows
// through as black until then. VideoView over ExoPlayer: a local 30s loop
// doesn't justify the Media3 dependency.
//
// Every open starts muted; a clip with an audio track gets a mute toggle. Unmuted
// sound still takes no audio focus, so it layers over the user's music the way
// game audio does — iOS mixes the same way (GameAudioSession). A tap plays the
// clip fullscreen (TrailerFullscreen), pausing this one meanwhile.
@Composable
private fun GameplayLoop(game: Game, url: String) {
  val context = LocalContext.current
  var videoRendering by remember { mutableStateOf(false) }
  var videoView by remember { mutableStateOf<VideoView?>(null) }
  var player by remember { mutableStateOf<MediaPlayer?>(null) }
  var hasAudio by remember { mutableStateOf(false) }
  var muted by remember { mutableStateOf(true) }
  var fullscreen by remember { mutableStateOf(false) }
  var progress by remember { mutableStateOf<Float?>(null) }
  // Starts no sooner than 2s after the sheet opens, cached or not: the art gets a
  // moment of its own, and the clip never starts under the sheet's slide-in.
  val file by produceState<File?>(initialValue = null, url) {
    val fetched = async(Dispatchers.IO) { TrailerCache.fetch(context, url) { progress = it } }
    delay(2_000)
    value = fetched.await()
  }
  Box(
    Modifier
      .fillMaxWidth()
      .aspectRatio(16f / 9f)
      .clip(MaterialTheme.shapes.large)
      .clickable(enabled = videoRendering, onClickLabel = stringResource(R.string.trailer_fullscreen)) {
        fullscreen = true
        videoView?.pause()
      },
  ) {
    file?.let { trailer ->
      AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
          VideoView(ctx).also { videoView = it }.apply {
            // Never take audio focus — stock VideoView otherwise pauses whatever the
            // user is listening to. (No-op below API 26.)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
              setAudioFocusRequest(AudioManager.AUDIOFOCUS_NONE)
            }
            setVideoPath(trailer.absolutePath)
            setOnInfoListener { _, what, _ ->
              if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) videoRendering = true
              true
            }
            // Runs again with a new MediaPlayer when VideoView reopens the file after
            // its surface was recreated (app backgrounded), so it reapplies `muted`.
            setOnPreparedListener { mp ->
              player = mp
              hasAudio = mp.trackInfo.any { it.trackType == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO }
              mp.isLooping = true
              mp.applyMuted(muted)
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
    // A download in flight — never shown for a cached clip. Stays full from the
    // last byte until the first frame replaces the art. Inset as a pill on its own
    // track, clear of the rounded corners.
    progress?.let {
      if (!videoRendering) {
        Box(
          Modifier
            .align(Alignment.BottomCenter)
            .padding(start = 14.dp, end = 14.dp, bottom = 6.dp)
            .fillMaxWidth()
            .height(4.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.35f)),
        ) {
          Box(Modifier.fillMaxWidth(it).fillMaxHeight().clip(CircleShape).background(game.accentColor))
        }
      }
    }
    // The puck matches the scanner's flashlight toggle — the icon shows the state,
    // the label names the action.
    if (videoRendering && hasAudio) {
      IconButton(
        onClick = {
          muted = !muted
          // VideoView releases its player when the surface goes and the reopen
          // briefly lags it; the prepared listener applies `muted` on reopen anyway.
          runCatching { player?.applyMuted(muted) }
        },
        modifier = Modifier
          .align(Alignment.TopEnd)
          .padding(8.dp)
          .background(Color.Black.copy(alpha = 0.35f), CircleShape),
      ) {
        Icon(
          painterResource(if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume_up),
          contentDescription = stringResource(if (muted) R.string.trailer_unmute else R.string.trailer_mute),
          tint = Color.White,
        )
      }
    }
  }
  val trailer = file
  if (fullscreen && trailer != null) {
    TrailerFullscreen(trailer) {
      fullscreen = false
      videoView?.start()
    }
  }
}

// The clip fullscreen in landscape, with sound, played once from the start —
// closing itself at the end. Unlike the inline loop it takes transient audio
// focus: this is the user asking to watch, so their music pauses and resumes on
// close. Rotating needs no recreation — the activity handles orientation itself
// (see the manifest).
@Composable
private fun TrailerFullscreen(file: File, onClose: () -> Unit) {
  Dialog(
    onDismissRequest = onClose,
    properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
  ) {
    val context = LocalContext.current
    val view = LocalView.current
    DisposableEffect(Unit) {
      val activity = context.findActivity()
      activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
      (view.parent as? DialogWindowProvider)?.window?.let { window ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
          window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
          }
        }
        WindowCompat.getInsetsController(window, view).run {
          systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
          hide(WindowInsetsCompat.Type.systemBars())
        }
      }
      onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
    }
    // A 16:9 box as large as the screen allows, like the inline slot — left to size
    // itself, VideoView stops at the clip's native resolution. The X sits on the
    // video's corner rather than the screen's, where it would straddle the
    // pillarbox edge on a wider screen.
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
      Box(Modifier.aspectRatio(16f / 9f)) {
        AndroidView(
          modifier = Modifier.fillMaxSize(),
          factory = { ctx ->
            VideoView(ctx).apply {
              if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setAudioFocusRequest(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
              }
              setVideoPath(file.absolutePath)
              setOnPreparedListener { start() }
              setOnCompletionListener { onClose() }
            }
          },
          onRelease = { it.stopPlayback() },
        )
        IconButton(
          onClick = onClose,
          modifier = Modifier
            .align(Alignment.TopStart)
            .padding(12.dp)
            .background(Color.Black.copy(alpha = 0.35f), CircleShape),
        ) {
          Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.trailer_close), tint = Color.White)
        }
      }
    }
  }
}

private fun MediaPlayer.applyMuted(muted: Boolean) {
  val volume = if (muted) 0f else 1f
  setVolume(volume, volume)
}
