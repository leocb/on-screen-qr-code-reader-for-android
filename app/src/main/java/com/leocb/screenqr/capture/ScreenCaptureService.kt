package com.leocb.screenqr.capture

import android.app.Activity
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import com.leocb.screenqr.util.Notifications
import com.leocb.screenqr.util.AppLocales

/**
 * Hosts the MediaProjection session. A foreground service of type `mediaProjection` is mandatory
 * for capturing on Android 10+, and from Android 14 the projection object must be created while it
 * is running.
 *
 * The service (and so the session) stays alive until the app is closed, the notification's "Stop"
 * action is used, or nothing has been scanned for [IDLE_TIMEOUT_MS].
 */
class ScreenCaptureService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())

    private val idleCheck = object : Runnable {
        override fun run() {
            if (!ProjectionSession.isActive) {
                stopSelf()
                return
            }
            val idleFor = SystemClock.uptimeMillis() - ProjectionSession.lastUsedAt
            if (idleFor > IDLE_TIMEOUT_MS) {
                stopSession()
                return
            }
            mainHandler.postDelayed(this, 30_000)
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocales.wrap(newBase))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startSession(intent)
            ACTION_STOP -> stopSession()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(idleCheck)
        ProjectionSession.onStoppedExternally = null
        ProjectionSession.stop()
        super.onDestroy()
    }

    private fun startSession(intent: Intent) {
        startInForeground()

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)
        if (resultCode != Activity.RESULT_OK || data == null) {
            ProjectionBridge.deliver(null, "Screen capture permission was not granted")
            stopSelf()
            return
        }

        val error = ProjectionSession.start(this, resultCode, data)
        if (error != null) {
            ProjectionBridge.deliver(null, error)
            stopSelf()
            return
        }

        ProjectionSession.onStoppedExternally = { stopSelf() }
        mainHandler.removeCallbacks(idleCheck)
        mainHandler.postDelayed(idleCheck, 30_000)
    }

    private fun startInForeground() {
        runCatching {
            ServiceCompat.startForeground(
                this,
                Notifications.ID_ONGOING,
                Notifications.ongoingCapture(this),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        }
    }

    private fun stopSession() {
        mainHandler.removeCallbacks(idleCheck)
        ProjectionSession.onStoppedExternally = null
        ProjectionSession.stop()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        private const val ACTION_START = "com.leocb.screenqr.action.START_CAPTURE"
        const val ACTION_STOP = "com.leocb.screenqr.action.STOP_CAPTURE"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"

        private const val IDLE_TIMEOUT_MS = 10 * 60 * 1000L

        /** Starts the foreground service and the projection session with a consent token. */
        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, ScreenCaptureService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
            context.startForegroundService(intent)
        }

        /** Stops the session. Safe to call from the UI or the notification. */
        fun stop(context: Context) {
            context.stopService(Intent(context, ScreenCaptureService::class.java))
        }
    }
}

/** One-shot hand-off of a captured frame from the session back to the requesting activity. */
object ProjectionBridge {

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var listener: ((Bitmap?, String?) -> Unit)? = null

    fun expect(callback: (Bitmap?, String?) -> Unit) {
        listener = callback
    }

    fun abandon() {
        listener = null
    }

    internal fun deliver(bitmap: Bitmap?, error: String?) {
        val callback = listener
        listener = null
        if (callback == null) {
            bitmap?.recycle()
            return
        }
        mainHandler.post { callback(bitmap, error) }
    }
}
