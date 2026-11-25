package com.leocb.screenqr.capture

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager

/**
 * One long-lived MediaProjection session.
 *
 * Android 14+ wants a new consent dialog per MediaProjection *session*, not per screenshot, so
 * keeping the session (and its virtual display) alive means later scans are a plain frame grab.
 * The trade-off is the persistent capture notification and the system's screen-casting chip.
 *
 * The image reader keeps the newest frame buffered, so a capture is a copy rather than a wait for
 * the next screen update. Everything except [isActive] runs on one private handler thread.
 */
object ProjectionSession {

    private const val SETTLE_MS = 220L
    private const val FRAME_TIMEOUT_MS = 450L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var width = 0
    private var height = 0

    @Volatile
    private var active = false

    /** Uptime of the last capture request, for the idle shutdown in the service. */
    @Volatile
    var lastUsedAt: Long = 0L
        private set

    /** Called when the system (or the user, via the casting chip) ends the session. */
    @Volatile
    var onStoppedExternally: (() -> Unit)? = null

    val isActive: Boolean
        get() = active

    fun start(context: Context, resultCode: Int, data: Intent): String? = synchronized(lock) {
        if (active) return null

        val manager = context.getSystemService(MediaProjectionManager::class.java)
            ?: return "Screen capture is not available on this device"
        val mediaProjection = runCatching { manager.getMediaProjection(resultCode, data) }.getOrNull()
            ?: return "The system refused the screen capture session"

        val metrics = context.resources.displayMetrics
        val bounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.getSystemService(WindowManager::class.java)?.maximumWindowMetrics?.bounds
        } else {
            null
        }
        val captureWidth = bounds?.width() ?: metrics.widthPixels
        val captureHeight = bounds?.height() ?: metrics.heightPixels

        val captureThread = HandlerThread("screen-capture").apply { start() }
        val captureHandler = Handler(captureThread.looper)
        val imageReader = runCatching {
            ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 3)
        }.getOrNull() ?: run {
            captureThread.quitSafely()
            runCatching { mediaProjection.stop() }
            return "Could not allocate the screen capture buffer"
        }

        // Android 14+ requires the callback to be registered before creating the display.
        mediaProjection.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    val wasActive = active
                    stop()
                    if (wasActive) mainHandler.post { onStoppedExternally?.invoke() }
                }
            },
            captureHandler,
        )

        val display = runCatching {
            mediaProjection.createVirtualDisplay(
                "screen-qr-capture",
                captureWidth,
                captureHeight,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface,
                null,
                captureHandler,
            )
        }.getOrNull()

        if (display == null) {
            runCatching { imageReader.close() }
            runCatching { mediaProjection.stop() }
            captureThread.quitSafely()
            return "Could not start mirroring the screen"
        }

        thread = captureThread
        handler = captureHandler
        projection = mediaProjection
        virtualDisplay = display
        reader = imageReader
        width = captureWidth
        height = captureHeight
        active = true
        lastUsedAt = SystemClock.uptimeMillis()

        imageReader.setOnImageAvailableListener({ source -> onImageAvailable(source) }, captureHandler)
        return null
    }

    /** Grabs the newest frame, allowing [SETTLE_MS] for the screen to finish animating. */
    fun capture() {
        val captureHandler = handler
        if (!active || captureHandler == null) {
            ProjectionBridge.deliver(null, "The screen capture session is not running")
            return
        }
        lastUsedAt = SystemClock.uptimeMillis()

        val posted = captureHandler.post {
            pending = { bitmap, error -> ProjectionBridge.deliver(bitmap, error) }
            pendingAt = SystemClock.uptimeMillis() + SETTLE_MS
            captureHandler.postDelayed(timeoutRunnable, SETTLE_MS + FRAME_TIMEOUT_MS)
        }
        if (!posted) ProjectionBridge.deliver(null, "The screen capture session stopped")
    }

    fun stop() = synchronized(lock) {
        val captureHandler = handler
        captureHandler?.removeCallbacks(timeoutRunnable)
        pending = null
        handler = null
        active = false
        lastUsedAt = 0L

        val readerToClose = reader
        val displayToRelease = virtualDisplay
        val projectionToStop = projection
        val threadToQuit = thread
        reader = null
        virtualDisplay = null
        projection = null
        thread = null

        val cleanup = Runnable {
            latest?.let { runCatching { it.close() } }
            latest = null
            runCatching { displayToRelease?.release() }
            runCatching { readerToClose?.close() }
            runCatching { projectionToStop?.stop() }
            threadToQuit?.quitSafely()
        }
        if (captureHandler != null) captureHandler.post(cleanup) else cleanup.run()
    }

    // ---- frame plumbing, always on the capture handler thread ----

    private var latest: Image? = null
    private var pending: ((Bitmap?, String?) -> Unit)? = null
    private var pendingAt = 0L

    private fun onImageAvailable(source: ImageReader) {
        val image = runCatching { source.acquireLatestImage() }.getOrNull() ?: return
        latest?.let { runCatching { it.close() } }
        latest = image

        val callback = pending ?: return
        if (SystemClock.uptimeMillis() < pendingAt) return

        pending = null
        latest = null
        val bitmap = runCatching { image.toBitmap() }.getOrNull()
        runCatching { image.close() }
        mainHandler.post {
            callback(bitmap, if (bitmap == null) "Could not read the captured frame" else null)
        }
    }

    private val timeoutRunnable = object : Runnable {
        override fun run() {
            val callback = pending ?: return
            pending = null
            val image = latest
            latest = null
            val bitmap = image?.let { frame ->
                runCatching { frame.toBitmap() }.getOrNull().also { runCatching { frame.close() } }
            }
            mainHandler.post {
                callback(bitmap, if (bitmap == null) "The screen did not produce a frame" else null)
            }
        }
    }

    private fun Image.toBitmap(): Bitmap? {
        val plane = planes.firstOrNull() ?: return null
        val pixelStride = plane.pixelStride
        if (pixelStride <= 0 || width <= 0 || height <= 0) return null
        val rowPadding = plane.rowStride - pixelStride * width
        if (rowPadding < 0) return null

        var bitmap = Bitmap.createBitmap(
            width + rowPadding / pixelStride,
            height,
            Bitmap.Config.ARGB_8888,
        )
        bitmap.copyPixelsFromBuffer(plane.buffer)
        if (bitmap.width != width) {
            val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)
            bitmap.recycle()
            bitmap = cropped
        }
        return bitmap
    }
}
