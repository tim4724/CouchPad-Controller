package games.couchpad.controller.ui.main

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.util.Size
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import androidx.camera.core.CameraInfo
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.core.content.ContextCompat
import kotlin.math.max

/**
 * Full-bleed, center-cropped camera preview — the only thing [ScanScreen] ever wanted
 * from `androidx.camera:camera-view`'s `PreviewView`, without the AppCompat →
 * Fragment → ViewPager chain (plus camera-video) that library drags into the APK for
 * the features we don't use.
 *
 * A [SurfaceView] receives the camera transform on its Surface, so the display
 * pipeline performs the rotation itself; this class only sizes the SurfaceView to the
 * camera buffer and then scales and centers it so the stream's crop rect fills these
 * bounds without distortion. That is PreviewView's own transform, narrowed to
 * FILL_CENTER and the back camera — no FIT_* scale types, no front-camera mirroring.
 *
 * Every callback below (surface request, transformation info, holder, layout) arrives
 * on the main thread, so the state it shares needs no synchronization.
 */
internal class CameraPreviewView(context: Context) : FrameLayout(context), Preview.SurfaceProvider {

  private val surfaceView = SurfaceView(context)
  private val mainExecutor = ContextCompat.getMainExecutor(context)

  private var request: SurfaceRequest? = null
  private var bufferSize: Size? = null
  private var cropRect: RectF? = null
  private var bufferRotationDegrees = 0
  private var surfaceSize: Size? = null
  private var surfaceProvided = false
  /** A Surface already handed to the camera can't be taken back — the request has to be re-issued. */
  private var requestToInvalidate: SurfaceRequest? = null
  private var cameraInfo: CameraInfo? = null

  // MainActivity handles rotation without recreating itself, which leaves the use
  // case's target rotation stale. Deriving the stream rotation from the live display
  // instead keeps the preview upright — the same override PreviewView applies.
  private val displayListener = object : DisplayManager.DisplayListener {
    override fun onDisplayAdded(displayId: Int) = Unit
    override fun onDisplayRemoved(displayId: Int) = Unit
    override fun onDisplayChanged(displayId: Int) {
      if (displayId == display?.displayId) applyTransform()
    }
  }

  init {
    addView(surfaceView)
    surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
      override fun surfaceCreated(holder: SurfaceHolder) {
        // Whatever was pending on the Surface that just died gets a fresh request.
        requestToInvalidate?.invalidate()
        requestToInvalidate = null
      }

      override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceSize = Size(width, height)
        provideSurfaceIfReady()
      }

      override fun surfaceDestroyed(holder: SurfaceHolder) {
        // An unanswered request has to be declined or the camera waits forever.
        if (surfaceProvided) requestToInvalidate = request else request?.willNotProvideSurface()
        request = null
        surfaceSize = null
        surfaceProvided = false
      }
    })
  }

  override fun onSurfaceRequested(request: SurfaceRequest) {
    this.request.let { if (it != null && !surfaceProvided) it.willNotProvideSurface() }
    this.request = request
    surfaceProvided = false
    val size = request.resolution
    bufferSize = size
    request.setTransformationInfoListener(mainExecutor) { info ->
      cropRect = RectF(info.cropRect)
      bufferRotationDegrees = info.rotationDegrees
      applyTransform()
    }
    // Laying the SurfaceView out at the buffer's own size makes the transform below a
    // plain rect-to-rect map from buffer pixels to this view's coordinates.
    surfaceView.layoutParams = LayoutParams(size.width, size.height)
    if (!provideSurfaceIfReady()) surfaceView.holder.setFixedSize(size.width, size.height)
  }

  /** Called once the camera is bound; unlocks the display-derived stream rotation. */
  fun setCameraInfo(info: CameraInfo) {
    cameraInfo = info
    applyTransform()
  }

  private fun provideSurfaceIfReady(): Boolean {
    val pending = request ?: return false
    if (surfaceProvided || surfaceSize != bufferSize) return false
    pending.provideSurface(surfaceView.holder.surface, mainExecutor) {}
    surfaceProvided = true
    return true
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    displayManager()?.registerDisplayListener(displayListener, null)
  }

  override fun onDetachedFromWindow() {
    super.onDetachedFromWindow()
    displayManager()?.unregisterDisplayListener(displayListener)
  }

  override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
    super.onLayout(changed, left, top, right, bottom)
    applyTransform()
  }

  private fun displayManager() = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager

  private fun applyTransform() {
    val buffer = bufferSize ?: return
    val crop = cropRect ?: return
    if (width == 0 || height == 0) return

    // Before the camera is bound there is no display-relative rotation to ask for, so
    // fall back to what the stream itself reported.
    val info = cameraInfo
    val displayRotation = display?.rotation
    val rotationDegrees =
      if (info != null && displayRotation != null) info.getSensorRotationDegrees(displayRotation)
      else bufferRotationDegrees

    val sideways = rotationDegrees % 180 != 0
    val contentWidth = if (sideways) crop.height() else crop.width()
    val contentHeight = if (sideways) crop.width() else crop.height()
    if (contentWidth <= 0f || contentHeight <= 0f) return

    // FILL_CENTER: the smallest uniform scale that still covers both axes, centered.
    val scale = max(width / contentWidth, height / contentHeight)
    val filled = RectF(0f, 0f, contentWidth * scale, contentHeight * scale)
    filled.offset((width - filled.width()) / 2f, (height - filled.height()) / 2f)

    // Map the crop rect onto that filled rect with the stream rotation folded in, then
    // push the whole buffer through it: where the buffer lands is where the SurfaceView
    // has to sit for the crop rect alone to be visible.
    val bufferToView = Matrix().apply {
      setRectToRect(crop, NORMALIZED, Matrix.ScaleToFit.FILL)
      postRotate(rotationDegrees.toFloat())
      postConcat(Matrix().apply { setRectToRect(NORMALIZED, filled, Matrix.ScaleToFit.FILL) })
    }
    val placed = RectF(0f, 0f, buffer.width.toFloat(), buffer.height.toFloat())
    bufferToView.mapRect(placed)

    surfaceView.pivotX = 0f
    surfaceView.pivotY = 0f
    surfaceView.scaleX = placed.width() / buffer.width
    surfaceView.scaleY = placed.height() / buffer.height
    surfaceView.translationX = placed.left - surfaceView.left
    surfaceView.translationY = placed.top - surfaceView.top
  }

  private companion object {
    /** Rotation is only well behaved around the origin, so rotate in normalized space. */
    val NORMALIZED = RectF(-1f, -1f, 1f, 1f)
  }
}
