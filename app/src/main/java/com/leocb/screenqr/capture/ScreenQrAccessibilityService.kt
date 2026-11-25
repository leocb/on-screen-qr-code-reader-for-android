package com.leocb.screenqr.capture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.provider.Settings
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import com.leocb.screenqr.util.AppLocales

/**
 * Optional, user-enabled fallback. Screenshots taken through an accessibility service are not
 * affected by the "one-time screen recording" dialog, and they leave the app underneath
 * untouched (no window on top of it).
 *
 * This service does nothing except take that screenshot when asked.
 */
class ScreenQrAccessibilityService : AccessibilityService() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocales.wrap(newBase))
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        AccessibilityCapture.attach(this)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        AccessibilityCapture.detach(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        AccessibilityCapture.detach(this)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit
}

object AccessibilityCapture {

    private const val SECURE_WINDOW_ERROR = 5 // AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW

    @Volatile
    private var service: ScreenQrAccessibilityService? = null

    val isReady: Boolean
        get() = service != null

    class ScreenshotFailure(val code: Int, message: String) : Exception(message) {
        val isSecureWindow: Boolean
            get() = code == SECURE_WINDOW_ERROR
    }

    internal fun attach(service: ScreenQrAccessibilityService) {
        this.service = service
    }

    internal fun detach(service: ScreenQrAccessibilityService) {
        if (this.service === service) this.service = null
    }

    /** True when the user has switched the service on in system settings. */
    fun isEnabled(context: Context): Boolean {
        if (service != null) return true
        val component = ComponentName(context, ScreenQrAccessibilityService::class.java)
        val enabledList = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        if (enabledList.split(':').any {
                it.equals(component.flattenToString(), true) ||
                    it.equals(component.flattenToShortString(), true)
            }
        ) {
            return true
        }
        val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
        if (!manager.isEnabled) return false
        return runCatching {
            manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any { info ->
                val serviceInfo = info.resolveInfo?.serviceInfo
                serviceInfo != null && ComponentName(serviceInfo.packageName, serviceInfo.name) == component
            }
        }.getOrDefault(false)
    }

    /** Takes one screenshot of the primary display. */
    suspend fun screenshot(): Result<Bitmap> {
        val service = awaitService() ?: return Result.failure(
            ScreenshotFailure(-1, "The accessibility service is not running"),
        )
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return Result.failure(ScreenshotFailure(-2, "Accessibility screenshots need Android 11 or newer"))
        }
        return withTimeoutOrNull(8_000) { runCatching { takeScreenshot(service) } }
            ?: Result.failure(ScreenshotFailure(-3, "The screenshot timed out"))
    }

    /**
     * Last-resort fallback: pulls visible text out of the app behind us. Useful when a QR code
     * is paired with a copyable "copia e cola" style string.
     */
    fun screenText(): String? {
        val service = service ?: return null
        return runCatching {
            val builder = StringBuilder()
            var budget = 700
            for (window in service.windows) {
                if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                val root = window.root ?: continue
                if (root.packageName == service.packageName) continue
                budget = collectText(root, builder, 0, budget)
                if (budget <= 0 || builder.length > 2500) break
            }
            builder.toString()
                .lineSequence()
                .map { it.trim() }
                .filter { line -> line.length >= 24 && line.count(Char::isLetterOrDigit) >= line.length / 2 }
                .maxByOrNull { it.length }
        }.getOrNull()
    }

    private suspend fun awaitService(): ScreenQrAccessibilityService? {
        repeat(20) {
            service?.let { return it }
            delay(80)
        }
        return service
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun takeScreenshot(service: ScreenQrAccessibilityService): Bitmap =
        suspendCancellableCoroutine { continuation ->
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                ContextCompat.getMainExecutor(service),
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        val bitmap = runCatching {
                            val buffer = result.hardwareBuffer
                            try {
                                Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)?.let { hardware ->
                                    val software = hardware.copy(Bitmap.Config.ARGB_8888, false)
                                    hardware.recycle()
                                    software
                                }
                            } finally {
                                buffer.close()
                            }
                        }.getOrNull()

                        if (continuation.isActive) {
                            if (bitmap != null) {
                                continuation.resumeWith(Result.success(bitmap))
                            } else {
                                continuation.resumeWith(
                                    Result.failure(ScreenshotFailure(-4, "The screenshot could not be read")),
                                )
                            }
                        } else {
                            bitmap?.recycle()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        if (continuation.isActive) {
                            continuation.resumeWith(
                                Result.failure(
                                    ScreenshotFailure(
                                        errorCode,
                                        if (errorCode == SECURE_WINDOW_ERROR) {
                                            "This screen is protected against screenshots"
                                        } else {
                                            "The screenshot failed (code $errorCode)"
                                        },
                                    ),
                                ),
                            )
                        }
                    }
                },
            )
        }

    private fun collectText(
        node: AccessibilityNodeInfo,
        out: StringBuilder,
        depth: Int,
        budget: Int,
    ): Int {
        if (budget <= 0 || depth > 16) return budget
        val text = node.text?.toString()?.trim().orEmpty()
        val description = node.contentDescription?.toString()?.trim().orEmpty()
        for (value in listOf(text, description)) {
            if (value.isNotEmpty() && value.length < 400 && !out.contains(value)) {
                out.append(value).append('\n')
                if (out.length > 2500) return 0
            }
        }
        var remaining = budget - 1
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            remaining = collectText(child, out, depth + 1, remaining)
            if (remaining <= 0) break
        }
        return remaining
    }
}
