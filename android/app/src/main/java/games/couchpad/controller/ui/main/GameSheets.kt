package games.couchpad.controller.ui.main

import android.graphics.SurfaceTexture
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.view.OrientationEventListener
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import android.widget.VideoView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.util.lerp
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
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
  // On screen — where the fullscreen clip grows from and shrinks back to.
  var bounds by remember { mutableStateOf(Rect.Zero) }
  var progress by remember { mutableStateOf<Float?>(null) }
  // Starts no sooner than 0.6s after the sheet opens, cached or not, so the clip
  // never starts under the sheet's slide-in. Any longer reads as loading.
  val file by produceState<File?>(initialValue = null, url) {
    val fetched = async(Dispatchers.IO) { TrailerCache.fetch(context, url) { progress = it } }
    delay(600)
    value = fetched.await()
  }
  Box(
    Modifier
      .fillMaxWidth()
      .aspectRatio(16f / 9f)
      .onGloballyPositioned { bounds = Rect(it.positionOnScreen(), it.size.toSize()) }
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
    TrailerFullscreen(trailer, bounds) { position ->
      fullscreen = false
      // Picks up where the fullscreen clip was left. VideoView may be mid-reopen
      // (surface recreated), with no player to seek yet.
      runCatching { player?.seekToNearestKeyframe(position) }
      videoView?.start()
    }
  }
}

// The clip fullscreen in landscape, with sound, played once from the start —
// closing itself at the end. Unlike the inline loop it takes transient audio
// focus: this is the user asking to watch, so their music pauses and resumes on
// close. `onClose` gets where the clip got to — 0 once it played to the end.
//
// Landscape is drawn, not rotated into, as on iOS: the activity stays portrait and
// the clip grows out of the inline loop's `source` (screen coordinates) while
// turning 90° toward the side the phone is held, and shrinks back on close.
// Rotating the activity re-laid out the whole app twice and janked. A TextureView,
// not VideoView: a SurfaceView can't be rotated. The animation is all graphicsLayer,
// so no frame of it re-measures anything.
@Composable
private fun TrailerFullscreen(file: File, source: Rect, onClose: (positionMs: Int) -> Unit) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val progress = remember { Animatable(0f) }
  val angle = remember { Animatable(90f) }
  var player by remember { mutableStateOf<MediaPlayer?>(null) }
  var rendering by remember { mutableStateOf(false) }
  val videoAlpha by animateFloatAsState(if (rendering) 1f else 0f, tween(300), label = "video")
  var closing by remember { mutableStateOf(false) }
  // Hidden only while the backdrop is fully black: hiding or showing the bars
  // re-lays out the windows beneath, which would show as a jump mid-transition.
  var bars by remember { mutableStateOf<WindowInsetsControllerCompat?>(null) }
  val morph = spring<Float>(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow)
  val close = { positionMs: Int ->
    if (!closing) {
      closing = true
      player?.pause()
      bars?.show(WindowInsetsCompat.Type.systemBars())
      scope.launch {
        progress.animateTo(0f, morph)
        onClose(positionMs)
      }
    }
  }
  LaunchedEffect(Unit) {
    progress.animateTo(1f, morph)
    if (!closing) bars?.hide(WindowInsetsCompat.Type.systemBars())
  }
  // Turns toward the side the phone is held; the first reading only settles it.
  DisposableEffect(Unit) {
    var first = true
    val listener = object : OrientationEventListener(context) {
      override fun onOrientationChanged(degrees: Int) {
        if (degrees == ORIENTATION_UNKNOWN) return
        val target = when (degrees) {
          in 45..135 -> -90f
          in 225..315 -> 90f
          else -> null
        }
        val animate = !first
        first = false
        if (target == null || target == angle.targetValue) return
        scope.launch { if (animate) angle.animateTo(target, tween(300)) else angle.snapTo(target) }
      }
    }
    listener.enable()
    onDispose { listener.disable() }
  }
  DisposableEffect(Unit) {
    val audio = context.getSystemService(AudioManager::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).build()
      audio.requestAudioFocus(request)
      onDispose { audio.abandonAudioFocusRequest(request) }
    } else {
      @Suppress("DEPRECATION")
      audio.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
      @Suppress("DEPRECATION")
      onDispose { audio.abandonAudioFocus(null) }
    }
  }
  Dialog(
    onDismissRequest = { close(player?.currentPosition ?: 0) },
    properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
  ) {
    val view = LocalView.current
    LaunchedEffect(Unit) {
      (view.parent as? DialogWindowProvider)?.window?.let { window ->
        // The transition is drawn below; the window itself neither animates nor dims.
        window.setWindowAnimations(0)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
          window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
          }
        }
        bars = WindowCompat.getInsetsController(window, view).apply {
          systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
      }
    }
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = progress.value }.background(Color.Black))
    BoxWithConstraints(
      Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionOnScreen() },
      contentAlignment = Alignment.Center,
    ) {
      // The 16:9 video as large as the turned screen allows, like the inline slot.
      // Source and target share the aspect, so the grow is a uniform scale.
      val screenW = constraints.maxWidth.toFloat()
      val screenH = constraints.maxHeight.toFloat()
      val fullW = minOf(screenH, screenW * 16f / 9f)
      val fullH = fullW * 9f / 16f
      val density = LocalDensity.current
      val corner = with(density) { 16.dp.toPx() }
      Box(
        Modifier
          .requiredSize(with(density) { fullW.toDp() }, with(density) { fullH.toDp() })
          .graphicsLayer {
            val p = progress.value
            val scale = lerp(source.width / fullW, 1f, p)
            scaleX = scale
            scaleY = scale
            translationX = lerp(source.center.x - origin.x - screenW / 2, 0f, p)
            translationY = lerp(source.center.y - origin.y - screenH / 2, 0f, p)
            rotationZ = angle.value * p
            shape = RoundedCornerShape(corner * (1 - p) / scale)
            clip = true
          },
      ) {
        AndroidView(
          modifier = Modifier.fillMaxSize().graphicsLayer { alpha = videoAlpha },
          factory = { ctx ->
            TextureView(ctx).apply {
              surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                private var surface: Surface? = null

                override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                  val target = Surface(texture).also { surface = it }
                  player = MediaPlayer().apply {
                    setSurface(target)
                    setDataSource(file.absolutePath)
                    setOnInfoListener { _, what, _ ->
                      if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) rendering = true
                      false
                    }
                    setOnPreparedListener { it.start() }
                    setOnCompletionListener { close(0) }
                    prepareAsync()
                  }
                }

                override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                  player?.release()
                  player = null
                  surface?.release()
                  return true
                }

                override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {}

                override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
              }
            }
          },
        )
        // The X sits on the video's corner rather than the screen's, where it would
        // straddle the pillarbox edge on a wider screen.
        IconButton(
          onClick = { close(player?.currentPosition ?: 0) },
          modifier = Modifier
            .align(Alignment.TopStart)
            .padding(12.dp)
            .graphicsLayer { alpha = progress.value }
            .background(Color.Black.copy(alpha = 0.35f), CircleShape),
        ) {
          Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.trailer_close), tint = Color.White)
        }
      }
    }
  }
}

// The nearest keyframe rather than the default previous one: the trailers' keyframes
// are ~4s apart, and an exact seek first decodes up to that much — a loop hides the
// difference. The mode needs API 26; below it, the previous keyframe.
private fun MediaPlayer.seekToNearestKeyframe(positionMs: Int) {
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
    seekTo(positionMs.toLong(), MediaPlayer.SEEK_CLOSEST_SYNC)
  } else {
    seekTo(positionMs)
  }
}

private fun MediaPlayer.applyMuted(muted: Boolean) {
  val volume = if (muted) 0f else 1f
  setVolume(volume, volume)
}
