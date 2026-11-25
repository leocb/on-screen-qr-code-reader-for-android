package com.leocb.screenqr

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.leocb.screenqr.capture.AccessibilityCapture
import com.leocb.screenqr.capture.ProjectionBridge
import com.leocb.screenqr.capture.ProjectionSession
import com.leocb.screenqr.capture.ScreenCaptureService
import com.leocb.screenqr.decode.QrDecoder
import com.leocb.screenqr.model.QrPayload
import com.leocb.screenqr.ui.QrResultCard
import com.leocb.screenqr.ui.theme.ScreenQrTheme
import com.leocb.screenqr.util.Clipboard
import com.leocb.screenqr.util.Haptics
import com.leocb.screenqr.util.Notifications
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.leocb.screenqr.util.AppLocales

/**
 * Invisible activity that reads a QR code off the screen.
 *
 * While the capture runs the window is fully transparent and non-interactive, so the app
 * underneath stays visible (and keeps the input focus). The decoded result is then shown in
 * a card on top of it.
 */
class ScanActivity : ComponentActivity() {

    private var countdownSeconds = 0
    private var consent: CompletableDeferred<ActivityResult>? = null
    private var scanJob: Job? = null

    private var state by mutableStateOf(ScanUiState())

    private val projectionPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            consent?.complete(result)
        }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocales.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        countdownSeconds = intent.getIntExtra(EXTRA_COUNTDOWN, 0).coerceIn(0, MAX_COUNTDOWN)
        setInteractive(false)

        setContent {
            ScreenQrTheme {
                ScanScreen(
                    state = state,
                    onClose = { finish() },
                    onRetry = { restart() },
                    onOpenAccessibilitySettings = { openAccessibilitySettings() },
                    onUseCamera = {
                        startActivity(Intent(this, CameraScanActivity::class.java))
                        finish()
                    },
                )
            }
        }

        restart()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        countdownSeconds = intent.getIntExtra(EXTRA_COUNTDOWN, 0).coerceIn(0, MAX_COUNTDOWN)
        restart()
    }

    override fun onDestroy() {
        scanJob?.cancel()
        ProjectionBridge.abandon()
        super.onDestroy()
    }

    private fun restart() {
        scanJob?.cancel()
        consent = null
        scanJob = lifecycleScope.launch { runScan() }
    }

    private suspend fun runScan() {
        state = ScanUiState()
        setInteractive(false)
        try {
            when (val outcome = captureScreen()) {
                is CaptureOutcome.Success -> decode(outcome)
                is CaptureOutcome.Failure -> fail(outcome)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            fail(CaptureOutcome.Failure(CaptureOutcome.Reason.ERROR, throwable.message))
        }
    }

    private fun prefersAccessibility(): Boolean = when (Prefs.settings.value.engine) {
        CaptureEngine.ACCESSIBILITY -> true
        CaptureEngine.PROJECTION -> false
        CaptureEngine.AUTO -> AccessibilityCapture.isEnabled(this)
    }

    private suspend fun captureScreen(): CaptureOutcome =
        if (prefersAccessibility()) accessibilityCapture() else projectionCapture()

    private suspend fun accessibilityCapture(): CaptureOutcome {
        if (!AccessibilityCapture.isEnabled(this)) {
            return CaptureOutcome.Failure(CaptureOutcome.Reason.NOT_ENABLED)
        }
        runCountdown()

        // The first frame can still show the collapsing Quick Settings shade (or an animation),
        // so keep grabbing frames until one actually decodes, for about a second.
        val deadline = SystemClock.uptimeMillis() + 1_300
        var lastFrame: Bitmap? = null
        var lastError: String? = null
        var attempt = 0

        while (SystemClock.uptimeMillis() < deadline) {
            delay(if (attempt == 0) 350 else 220)
            attempt++

            val shot = AccessibilityCapture.screenshot()
            val bitmap = shot.getOrNull()
            if (bitmap == null) {
                val failure = shot.exceptionOrNull()
                if (failure is AccessibilityCapture.ScreenshotFailure && failure.isSecureWindow) {
                    lastFrame?.recycle()
                    return CaptureOutcome.Failure(CaptureOutcome.Reason.SECURE, failure.message)
                }
                lastError = failure?.message
                continue
            }

            val raw = withContext(Dispatchers.Default) { QrDecoder.decodeWithZxing(bitmap) }
            if (raw != null) {
                lastFrame?.recycle()
                return CaptureOutcome.Success(bitmap, "accessibility", raw)
            }
            lastFrame?.recycle()
            lastFrame = bitmap
        }

        val frame = lastFrame
            ?: return CaptureOutcome.Failure(
                if (lastError != null) CaptureOutcome.Reason.ERROR else CaptureOutcome.Reason.TIMEOUT,
                lastError,
            )
        // Hand the last frame back; decode() runs the full ZXing + ML Kit pass on it, which also
        // produces the "no QR code found" error with the on-screen text fallback.
        return CaptureOutcome.Success(frame, "accessibility", null)
    }

    private suspend fun projectionCapture(): CaptureOutcome {
        if (!ProjectionSession.isActive) {
            val manager = getSystemService(android.media.projection.MediaProjectionManager::class.java)
                ?: return CaptureOutcome.Failure(CaptureOutcome.Reason.ERROR, "Screen capture is not available")

            val deferred = CompletableDeferred<ActivityResult>()
            consent = deferred
            // Android 14+ defaults the dialog to "share one app"; ask for the whole display instead.
            val captureIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                manager.createScreenCaptureIntent(
                    android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay(),
                )
            } else {
                manager.createScreenCaptureIntent()
            }
            projectionPermission.launch(captureIntent)
            val permission = withTimeoutOrNull(120_000) { deferred.await() }
                ?: return CaptureOutcome.Failure(CaptureOutcome.Reason.DENIED, "Timed out waiting for permission")

            val data = permission.data
            if (permission.resultCode != RESULT_OK || data == null) {
                return CaptureOutcome.Failure(CaptureOutcome.Reason.DENIED)
            }

            // Started while this activity is visible: Android 12+ refuses to launch foreground
            // services from the background.
            ScreenCaptureService.start(this, permission.resultCode, data)
            if (!awaitSession()) {
                return CaptureOutcome.Failure(
                    CaptureOutcome.Reason.ERROR,
                    "The screen capture session did not start",
                )
            }
        }

        runCountdown()

        // A tile press starts with the Quick Settings shade still collapsing, so the first frame
        // can be the panel instead of the app. Frames are cheap here, so decode and retry.
        val deadline = SystemClock.uptimeMillis() + 1_800
        var lastFrame: Bitmap? = null
        var lastError: String? = null
        var attempt = 0

        try {
            while (SystemClock.uptimeMillis() < deadline) {
                val (bitmap, error) = grabFrame()
                attempt++
                if (bitmap == null) {
                    lastError = error
                    if (attempt >= 2) break
                    continue
                }
                val raw = withContext(Dispatchers.Default) { QrDecoder.decodeWithZxing(bitmap) }
                if (raw != null) {
                    lastFrame?.recycle()
                    lastFrame = null
                    return CaptureOutcome.Success(bitmap, "projection", raw)
                }
                lastFrame?.recycle()
                lastFrame = bitmap
            }
        } finally {
            if (!Prefs.settings.value.keepSession) {
                ScreenCaptureService.stop(this)
            }
        }

        val frame = lastFrame
            ?: return CaptureOutcome.Failure(CaptureOutcome.Reason.ERROR, lastError)
        return CaptureOutcome.Success(frame, "projection", null)
    }

    private suspend fun grabFrame(): Pair<Bitmap?, String?> {
        val deferred = CompletableDeferred<Pair<Bitmap?, String?>>()
        ProjectionBridge.expect { bitmap, error ->
            if (!deferred.isCompleted) deferred.complete(bitmap to error)
        }
        ProjectionSession.capture()
        return withTimeoutOrNull(6_000) { deferred.await() } ?: run {
            ProjectionBridge.abandon()
            null to "The capture did not answer"
        }
    }

    private suspend fun awaitSession(timeoutMillis: Long = 5_000): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            if (ProjectionSession.isActive) return true
            delay(60)
        }
        return ProjectionSession.isActive
    }

    /** Shows the "switch to the other app" timer and clears the overlay right before capture. */
    private suspend fun runCountdown() {
        if (countdownSeconds <= 0) return
        for (remaining in countdownSeconds downTo 1) {
            state = state.copy(countdown = remaining)
            delay(1_000)
        }
        state = state.copy(countdown = 0)
        delay(250)
    }

    private suspend fun decode(success: CaptureOutcome.Success) {
        val bitmap = success.bitmap
        try {
            if (BuildConfig.DEBUG) {
                android.util.Log.d(
                    "ScreenQr",
                    "capture ${bitmap.width}x${bitmap.height} ${bitmap.config} via ${success.source}",
                )
                runCatching {
                    java.io.File(cacheDir, "last-capture.png").outputStream().use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                }
            }
            if (QrDecoder.looksBlank(bitmap)) {
                fail(CaptureOutcome.Failure(CaptureOutcome.Reason.SECURE, "The capture came back black"))
                return
            }
            val raw = success.raw
                ?: withContext(Dispatchers.Default) { QrDecoder.decodeWithZxing(bitmap) }
                ?: QrDecoder.decodeWithMlKit(bitmap)
            if (raw.isNullOrBlank()) {
                fail(CaptureOutcome.Failure(CaptureOutcome.Reason.NO_CODE))
            } else {
                succeed(raw)
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun succeed(raw: String) {
        Prefs.addToHistory(raw)
        val payload = QrPayload.parse(raw)
        if (Prefs.settings.value.autoCopy) Clipboard.copy(this, raw)
        state = state.copy(payload = payload, error = null, busy = false, countdown = 0)
        setInteractive(true)
        Haptics.success(this)
        if (!App.isInForeground) {
            Notifications.showResult(this, payload)
        }
    }

    private fun fail(failure: CaptureOutcome.Failure) {
        android.util.Log.d("ScreenQr", "capture failed: ${failure.reason} ${failure.detail}")
        val error = failure.toUi()
        state = state.copy(error = error, payload = null, busy = false, countdown = 0)
        setInteractive(true)
        if (!App.isInForeground) {
            Notifications.showError(this, getString(error.titleRes))
            finish()
        }
    }

    private fun setInteractive(interactive: Boolean) {
        if (interactive) {
            window.clearFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            )
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            )
        }
    }

    private fun openAccessibilitySettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    companion object {
        private const val EXTRA_COUNTDOWN = "countdown"
        private const val MAX_COUNTDOWN = 10

        /** @param countdownSeconds delay before the capture, so the user can switch apps. */
        fun intent(context: Context, countdownSeconds: Int = 0): Intent =
            Intent(context, ScanActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_COUNTDOWN, countdownSeconds)
    }
}

data class ScanUiState(
    val busy: Boolean = true,
    val countdown: Int = 0,
    val payload: QrPayload? = null,
    val error: ScanError? = null,
)

data class ScanError(
    @param:StringRes val titleRes: Int,
    @param:StringRes val messageRes: Int,
    val kind: Kind = Kind.GENERIC,
    val fallbackText: String? = null,
) {
    enum class Kind { GENERIC, ACCESSIBILITY, SECURE, NO_CODE }
}

private sealed interface CaptureOutcome {
    data class Success(val bitmap: Bitmap, val source: String, val raw: String? = null) : CaptureOutcome
    data class Failure(val reason: Reason, val detail: String? = null) : CaptureOutcome
    enum class Reason { NOT_ENABLED, SECURE, DENIED, TIMEOUT, NO_CODE, ERROR }
}

private fun CaptureOutcome.Failure.toUi(): ScanError = when (reason) {
    CaptureOutcome.Reason.NOT_ENABLED -> ScanError(
        titleRes = R.string.error_accessibility_off_title,
        messageRes = R.string.error_accessibility_off_message,
        kind = ScanError.Kind.ACCESSIBILITY,
    )

    CaptureOutcome.Reason.SECURE -> ScanError(
        titleRes = R.string.error_secure_title,
        messageRes = R.string.error_secure_message,
        kind = ScanError.Kind.SECURE,
    )

    CaptureOutcome.Reason.DENIED -> ScanError(
        titleRes = R.string.error_denied_title,
        messageRes = R.string.error_denied_message,
    )

    CaptureOutcome.Reason.TIMEOUT -> ScanError(
        titleRes = R.string.error_timeout_title,
        messageRes = R.string.error_timeout_message,
    )

    CaptureOutcome.Reason.NO_CODE -> ScanError(
        titleRes = R.string.error_no_code_title,
        messageRes = R.string.error_no_code_message,
        kind = ScanError.Kind.NO_CODE,
        fallbackText = AccessibilityCapture.screenText(),
    )

    CaptureOutcome.Reason.ERROR -> ScanError(
        titleRes = R.string.error_capture_failed_title,
        messageRes = R.string.error_capture_failed_message,
    )
}

@Composable
private fun ScanScreen(
    state: ScanUiState,
    onClose: () -> Unit,
    onRetry: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onUseCamera: () -> Unit,
) {
    val hasCard = state.payload != null || state.error != null
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (hasCard) Color.Black.copy(alpha = 0.55f) else Color.Transparent),
        contentAlignment = Alignment.BottomCenter,
    ) {
        if (!hasCard && state.countdown > 0) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.TopCenter) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(50),
                    shadowElevation = 6.dp,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.scan_countdown_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = state.countdown.toString(),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }

        if (hasCard) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(16.dp)
                    .heightIn(max = 640.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                state.payload?.let { payload ->
                    QrResultCard(
                        payload = payload,
                        onDismiss = onClose,
                        onRetry = onRetry,
                        retryLabelRes = R.string.action_read_again,
                    )
                }
                state.error?.let { error ->
                    ScanErrorCard(
                        error = error,
                        onClose = onClose,
                        onRetry = onRetry,
                        onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                        onUseCamera = onUseCamera,
                    )
                }
            }
        }
    }
}

@Composable
private fun ScanErrorCard(
    error: ScanError,
    onClose: () -> Unit,
    onRetry: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onUseCamera: () -> Unit,
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(error.titleRes),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onClose) { Text(stringResource(R.string.action_close)) }
            }
            Text(
                text = stringResource(error.messageRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            error.fallbackText?.let { text ->
                Text(
                    text = stringResource(R.string.error_text_found),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SelectionContainer {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 120.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(text = text, style = MaterialTheme.typography.bodySmall)
                    }
                }
                TextButton(onClick = {
                    Clipboard.copy(context, text)
                }) {
                    Text(stringResource(R.string.action_copy_text))
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                when (error.kind) {
                    ScanError.Kind.ACCESSIBILITY -> Button(
                        onClick = onOpenAccessibilitySettings,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.action_settings), maxLines = 1)
                    }

                    ScanError.Kind.SECURE, ScanError.Kind.NO_CODE -> Button(
                        onClick = { onUseCamera() },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.action_camera), maxLines = 1)
                    }

                    ScanError.Kind.GENERIC -> Button(
                        onClick = onRetry,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.action_try_again), maxLines = 1)
                    }
                }
                if (error.kind != ScanError.Kind.GENERIC) {
                    OutlinedButton(onClick = onRetry, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.action_try_again), maxLines = 1)
                    }
                }
            }
        }
    }
}
