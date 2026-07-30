package games.couchpad.controller.ui.main

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.MeteringRectangle
import android.net.Uri
import android.provider.Settings
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size as GeometrySize
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import games.couchpad.controller.R
import games.couchpad.controller.data.Game
import games.couchpad.controller.data.JoinOutcome
import games.couchpad.controller.data.JoinResolver
import games.couchpad.controller.ui.components.findActivity
import games.couchpad.controller.ui.components.stableScreenInsets
import games.couchpad.controller.ui.components.themeLightBarIcons
import games.couchpad.controller.ui.legal.LegalLinks
import java.util.concurrent.Executors
import kotlinx.coroutines.delay
import zxingcpp.BarcodeReader

/**
 * In-app QR scanner: full-bleed CameraX preview decoded on-device by zxing-cpp —
 * no Play Services dependency, no telemetry, camera frames never leave the
 * process. In-app (vs a scanner activity) so it opens instantly and keeps the
 * manual-entry fallback one tap away on the same screen.
 *
 * A decoded QR resolves through [JoinResolver] right here: a bad code shows an
 * inline banner and scanning continues — the player never gets bounced out to
 * retry. Only a successful resolve leaves the screen (via [onJoin]), except a
 * legal-page QR (the privacy/imprint URL printed on packaging or shown by a
 * game), which opens the in-app doc viewer instead — same routing the App Link
 * path applies, since neither is a room.
 */
@Composable
fun ScanScreen(
  games: List<Game>,
  onJoin: (JoinOutcome.Success) -> Unit,
  onOpenLegalDoc: (url: String) -> Unit,
  onEnterCode: () -> Unit,
  onClose: () -> Unit,
) {
  val context = LocalContext.current
  // Config-aware resources for string lookups in callbacks (context.getString on
  // LocalContext.current is flagged by lint as not tracking config changes).
  val resources = LocalResources.current
  val haptics = LocalHapticFeedback.current
  val view = LocalView.current

  BackHandler(onBack = onClose)

  // The scanner is always a dark surface — force light bar icons while it's up and
  // restore the theme's own contrast on exit (same recipe as GameHostScreen). Keyed
  // on uiMode: a theme flip re-runs MainActivity.applyEdgeToEdge, which stomps this,
  // so re-assert after it.
  val uiMode = LocalConfiguration.current.uiMode
  DisposableEffect(uiMode) {
    val controller = context.findActivity()?.window?.let { WindowCompat.getInsetsController(it, view) }
    controller?.isAppearanceLightStatusBars = false
    controller?.isAppearanceLightNavigationBars = false
    onDispose {
      val light = themeLightBarIcons(context)
      controller?.isAppearanceLightStatusBars = light
      controller?.isAppearanceLightNavigationBars = light
    }
  }

  var granted by remember { mutableStateOf(hasCameraPermission(context)) }
  var requested by remember { mutableStateOf(false) }
  val permissionLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission(),
  ) { ok ->
    requested = true
    granted = ok
  }
  LaunchedEffect(Unit) { if (!granted) permissionLauncher.launch(Manifest.permission.CAMERA) }
  // Coming back from Settings after a grant there — no callback fires, so re-probe.
  LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = hasCameraPermission(context) }

  // One join per screen visit: the analyzer keeps delivering frames after a hit,
  // and a second onJoin would double-navigate.
  var joined by remember { mutableStateOf(false) }
  // The banner for a QR that decoded but isn't a room. Rejections are remembered
  // so a bad code sitting in frame buzzes once, not every frame.
  var scanError by remember { mutableStateOf<String?>(null) }
  val rejected = remember { mutableSetOf<String>() }
  LaunchedEffect(scanError) {
    if (scanError != null) {
      delay(3000)
      scanError = null
    }
  }

  // A frame can hold several QRs (the room code on the TV plus whatever poster
  // is on the wall) — any one resolving is a join; only if none do does the
  // first new offender surface as the error.
  fun onQr(raws: List<String>) {
    if (joined) return
    var newFailure: String? = null
    for (raw in raws) {
      // The launcher's own privacy/imprint URL isn't a room — route it to the doc
      // viewer (host-validated: the payload is untrusted) instead of failing it.
      LegalLinks.scannedLegalUrl(raw)?.let {
        joined = true
        onOpenLegalDoc(it)
        return
      }
      when (val r = JoinResolver.resolve(raw, games)) {
        is JoinOutcome.Success -> {
          joined = true
          onJoin(r)
          return
        }
        is JoinOutcome.Failure ->
          if (newFailure == null && rejected.add(raw)) newFailure = resources.getString(r.messageRes)
      }
    }
    scanError = newFailure ?: return
    haptics.performHapticFeedback(HapticFeedbackType.Reject)
  }

  var torchOn by remember { mutableStateOf(false) }
  var hasTorch by remember { mutableStateOf(false) }

  Box(Modifier.fillMaxSize().background(Color.Black)) {
    if (granted) {
      CameraPreview(
        onQr = ::onQr,
        onTorchProbed = { hasTorch = it },
        torchOn = torchOn,
      )
      ViewfinderOverlay(Modifier.fillMaxSize())
    } else if (requested) {
      PermissionDeniedContent(
        onRetry = {
          val activity = context.findActivity()
          // A hard "don't ask again" denial makes the system dialog a silent no-op;
          // Settings is the only path left.
          if (activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)
          ) {
            context.startActivity(
              Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null),
              ),
            )
          } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
          }
        },
        onEnterCode = onEnterCode,
      )
    }
    // else: system permission dialog is up over a black screen — nothing to draw.

    // Top and bottom scrims keep the white controls (and status-bar icons) legible
    // over a bright camera image.
    Box(
      Modifier
        .fillMaxWidth()
        .height(140.dp)
        .background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.5f), 1f to Color.Transparent)),
    )
    Box(
      Modifier
        .fillMaxWidth()
        .height(200.dp)
        .align(Alignment.BottomCenter)
        .background(Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.6f))),
    )

    Column(Modifier.fillMaxSize().windowInsetsPadding(stableScreenInsets)) {
      Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        ScannerIconButton(onClick = onClose) {
          Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close_scanner), tint = Color.White)
        }
        Spacer(Modifier.weight(1f))
        if (hasTorch) {
          ScannerIconButton(onClick = { torchOn = !torchOn }) {
            Icon(
              painterResource(if (torchOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off),
              contentDescription = stringResource(if (torchOn) R.string.flashlight_off else R.string.flashlight_on),
              tint = Color.White,
            )
          }
        }
      }
      Spacer(Modifier.weight(1f))
      Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        if (granted) {
          Text(
            stringResource(R.string.scan_hint),
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White,
            textAlign = TextAlign.Center,
          )
        }
        AnimatedVisibility(visible = scanError != null, enter = fadeIn(), exit = fadeOut()) {
          // Retain the last message so the fade-out still has content to render.
          var lastError by remember { mutableStateOf("") }
          scanError?.let { lastError = it }
          Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
          ) {
            Text(
              lastError,
              style = MaterialTheme.typography.bodyMedium,
              modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
              textAlign = TextAlign.Center,
            )
          }
        }
        if (granted) {
          FilledTonalButton(onClick = onEnterCode, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text(stringResource(R.string.enter_code_manually), style = MaterialTheme.typography.titleMedium)
          }
        }
      }
    }
  }
}

// The camera side, isolated so controller/analyzer lifecycle stays in one place.
@Composable
private fun CameraPreview(
  onQr: (List<String>) -> Unit,
  onTorchProbed: (Boolean) -> Unit,
  torchOn: Boolean,
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val controller = remember {
    LifecycleCameraController(context).apply {
      // Analysis defaults to ~640x480 — too coarse for a QR scanned from across
      // the couch (a ~25 cm code at 3 m is only ~3 px/module even at 720p). 1080p
      // gives ~4.5 px/module of headroom; going beyond it isn't guaranteed to
      // combine with a preview stream on LIMITED-hardware devices.
      imageAnalysisResolutionSelector = ResolutionSelector.Builder()
        .setResolutionStrategy(
          ResolutionStrategy(Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
        )
        .build()
    }
  }

  DisposableEffect(Unit) {
    // read() is synchronous CPU work — keep it off the main thread. The
    // controller's KEEP_ONLY_LATEST backpressure drops frames while a decode is
    // in flight, so a slow frame lowers the scan rate instead of queueing work.
    val decodeExecutor = Executors.newSingleThreadExecutor()
    val mainExecutor = ContextCompat.getMainExecutor(context)
    val reader = BarcodeReader(
      BarcodeReader.Options(
        formats = setOf(BarcodeReader.Format.QR_CODE),
        // The extra passes mostly cost time on frames the plain pass can't decode
        // anyway: tryHarder finds small codes scanned from across the couch,
        // tryInvert light-on-dark codes from dark-themed games, tryDownscale
        // oversized close-ups.
        tryHarder = true,
        tryInvert = true,
        tryDownscale = true,
        // Raw payload exactly as encoded — what JoinResolver expects.
        textMode = BarcodeReader.TextMode.PLAIN,
      ),
    )
    controller.setImageAnalysisAnalyzer(decodeExecutor) { image ->
      // read() decodes straight out of the frame's Y plane via JNI (no copy, no
      // bitmap); use{} closes the proxy right after so CameraX can reuse the
      // buffer. Idle frames allocate nothing beyond the reader's result list,
      // and the main thread is only touched when a code was actually decoded.
      val results = image.use { reader.read(it) }
      if (results.isNotEmpty()) {
        val texts = results.mapNotNull { it.text }
        if (texts.isNotEmpty()) mainExecutor.execute { onQr(texts) }
      }
    }
    controller.bindToLifecycle(lifecycleOwner)
    // cameraInfo is null until the async bind completes — probe when it has.
    controller.initializationFuture.addListener(
      {
        onTorchProbed(controller.cameraInfo?.hasFlashUnit() == true)
        applyCenterMeteringRegions(controller)
      },
      mainExecutor,
    )
    onDispose {
      controller.unbind()
      decodeExecutor.shutdown()
    }
  }
  LaunchedEffect(torchOn) { controller.enableTorch(torchOn) }

  AndroidView(
    factory = { ctx ->
      PreviewView(ctx).apply {
        scaleType = PreviewView.ScaleType.FILL_CENTER
        this.controller = controller
      }
    },
    modifier = Modifier.fillMaxSize(),
  )
}

// Continuous AF meters the whole frame by default, so bright or high-contrast
// clutter at the edges (a lamp, a face on the couch) can pull focus away from
// the code sitting in the viewfinder. Pin the AF/AE regions to a centered
// square roughly matching the viewfinder while leaving the mode continuous:
// the camera still refocuses as the phone moves, just weighted to the center.
// The square is defined on the sensor's active array, which keeps it centered
// through the FILL_CENTER crop and any display rotation. Best-effort: devices
// that support no metering regions (LEGACY hardware) simply skip it.
// androidx.annotation.OptIn (not kotlin.OptIn) so lint's UnsafeOptInUsageError also
// recognizes the opt-in for this androidx experimental interop marker.
@OptIn(markerClass = [ExperimentalCamera2Interop::class])
private fun applyCenterMeteringRegions(controller: CameraController) {
  val info = controller.cameraInfo ?: return
  val control = controller.cameraControl ?: return
  val camera2Info = Camera2CameraInfo.from(info)
  val active = camera2Info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
  val side = minOf(active.width(), active.height()) / 2
  val region = MeteringRectangle(
    active.centerX() - side / 2,
    active.centerY() - side / 2,
    side,
    side,
    MeteringRectangle.METERING_WEIGHT_MAX,
  )
  val options = CaptureRequestOptions.Builder()
  if ((camera2Info.getCameraCharacteristic(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0) > 0) {
    options.setCaptureRequestOption(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
  }
  if ((camera2Info.getCameraCharacteristic(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0) > 0) {
    options.setCaptureRequestOption(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))
  }
  Camera2CameraControl.from(control).setCaptureRequestOptions(options.build())
}

// Dim everything except a centered rounded window, with a white outline. The
// clear-out needs its own layer (Offscreen) or BlendMode.Clear punches through
// to the app background instead of this Canvas's scrim.
@Composable
private fun ViewfinderOverlay(modifier: Modifier = Modifier) {
  Canvas(modifier.graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)) {
    val side = size.minDimension * 0.66f
    val topLeft = Offset((size.width - side) / 2f, (size.height - side) / 2f - size.height * 0.06f)
    val corner = CornerRadius(28.dp.toPx())
    drawRect(Color.Black.copy(alpha = 0.45f))
    drawRoundRect(
      color = Color.Transparent,
      topLeft = topLeft,
      size = GeometrySize(side, side),
      cornerRadius = corner,
      blendMode = BlendMode.Clear,
    )
    drawPath(
      Path().apply { addRoundRect(RoundRect(Rect(topLeft, GeometrySize(side, side)), corner)) },
      color = Color.White.copy(alpha = 0.9f),
      style = Stroke(width = 2.dp.toPx()),
    )
  }
}

// Camera denied (maybe permanently). The screen still earns its keep: explain,
// offer the system path back, and keep manual entry front and center.
@Composable
private fun PermissionDeniedContent(onRetry: () -> Unit, onEnterCode: () -> Unit) {
  Column(
    Modifier.fillMaxSize().windowInsetsPadding(stableScreenInsets).padding(horizontal = 32.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Icon(
      painterResource(R.drawable.ic_qr_scan),
      contentDescription = null,
      tint = Color.White.copy(alpha = 0.85f),
      modifier = Modifier.size(48.dp),
    )
    Spacer(Modifier.height(16.dp))
    Text(
      stringResource(R.string.camera_access_needed),
      style = MaterialTheme.typography.titleLarge,
      color = Color.White,
    )
    Spacer(Modifier.height(8.dp))
    Text(
      stringResource(R.string.camera_access_rationale),
      style = MaterialTheme.typography.bodyLarge,
      color = Color.White.copy(alpha = 0.75f),
      textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(24.dp))
    Button(onClick = onRetry, modifier = Modifier.fillMaxWidth().height(52.dp)) {
      Text(stringResource(R.string.allow_camera_access), style = MaterialTheme.typography.titleMedium)
    }
    Spacer(Modifier.height(10.dp))
    FilledTonalButton(onClick = onEnterCode, modifier = Modifier.fillMaxWidth().height(52.dp)) {
      Text(stringResource(R.string.enter_code_manually), style = MaterialTheme.typography.titleMedium)
    }
  }
}

// The floating controls sit on live video — give them a constant dark puck so
// they read on any background.
@Composable
private fun ScannerIconButton(onClick: () -> Unit, content: @Composable () -> Unit) {
  IconButton(
    onClick = onClick,
    modifier = Modifier.background(Color.Black.copy(alpha = 0.35f), CircleShape),
  ) {
    content()
  }
}

private fun hasCameraPermission(context: Context): Boolean =
  ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
