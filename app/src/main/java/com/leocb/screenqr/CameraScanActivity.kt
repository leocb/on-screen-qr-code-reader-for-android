package com.leocb.screenqr

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.leocb.screenqr.camera.CameraSession
import com.leocb.screenqr.model.QrPayload
import com.leocb.screenqr.ui.QrResultCard
import com.leocb.screenqr.ui.theme.ScreenQrTheme
import com.leocb.screenqr.util.Clipboard
import com.leocb.screenqr.util.Haptics
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.leocb.screenqr.util.AppLocales
import android.content.Context

private data class CameraUiState(
    val permissionGranted: Boolean = false,
    val permissionAsked: Boolean = false,
    val cameraReady: Boolean = false,
    val cameraFailed: Boolean = false,
    val torchAvailable: Boolean = false,
    val result: String? = null,
)

class CameraScanActivity : ComponentActivity() {

    private var state by mutableStateOf(CameraUiState())
    private var torchOn by mutableStateOf(false)

    private var session: CameraSession? = null
    private var previewView: PreviewView? = null
    private var scanner: BarcodeScanner? = null
    private val busy = AtomicBoolean(false)

    private val permissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            state = state.copy(permissionAsked = true, permissionGranted = granted)
            if (granted) bindCamera()
        }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocales.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build(),
        )
        session = CameraSession(this, this) { proxy -> analyze(proxy) }

        setContent {
            ScreenQrTheme {
                CameraScreen(
                    state = state,
                    torchOn = torchOn,
                    onPreviewReady = { view ->
                        previewView = view
                        bindCamera()
                    },
                    onToggleTorch = {
                        val next = !torchOn
                        torchOn = next
                        session?.setTorch(next)
                    },
                    onDismissResult = { finish() },
                    onScanAgain = { resumeScanning() },
                    onRequestPermission = { permissionRequest.launch(Manifest.permission.CAMERA) },
                    onOpenAppSettings = { openAppSettings() },
                    onClose = { finish() },
                )
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            state = state.copy(permissionGranted = true, permissionAsked = true)
        } else {
            permissionRequest.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
        session?.release()
        runCatching { scanner?.close() }
        super.onDestroy()
    }

    private fun openAppSettings() {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
            )
        }
    }

    private fun bindCamera() {
        if (!state.permissionGranted) return
        val view = previewView ?: return
        val cameraSession = session ?: return
        if (state.cameraReady) return
        lifecycleScope.launch {
            runCatching { cameraSession.start(view) }
                .onSuccess {
                    state = state.copy(
                        cameraReady = true,
                        cameraFailed = false,
                        torchAvailable = cameraSession.hasFlash(),
                    )
                }
                .onFailure { error ->
                    android.util.Log.d("ScreenQr", "camera start failed: ${error.message}")
                    state = state.copy(cameraFailed = true)
                }
        }
    }

    @OptIn(ExperimentalGetImage::class)
    private fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        if (media == null || state.result != null || !busy.compareAndSet(false, true)) {
            proxy.close()
            return
        }
        val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        val task = scanner?.process(image)
        if (task == null) {
            busy.set(false)
            proxy.close()
            return
        }
        task.addOnSuccessListener { barcodes ->
            barcodes.firstNotNullOfOrNull { it.rawValue?.takeIf { value -> value.isNotBlank() } }
                ?.let { report(it) }
        }.addOnCompleteListener {
            busy.set(false)
            proxy.close()
        }
    }

    private fun report(raw: String) {
        if (state.result != null) return
        Prefs.addToHistory(raw)
        if (Prefs.settings.value.autoCopy) Clipboard.copy(this, raw)
        session?.pauseAnalysis()
        state = state.copy(result = raw)
        Haptics.success(this)
    }

    private fun resumeScanning() {
        state = state.copy(result = null)
        session?.resumeAnalysis()
    }
}

@Composable
private fun CameraScreen(
    state: CameraUiState,
    torchOn: Boolean,
    onPreviewReady: (PreviewView) -> Unit,
    onToggleTorch: () -> Unit,
    onDismissResult: () -> Unit,
    onScanAgain: () -> Unit,
    onRequestPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(enabled = state.result != null) { onScanAgain() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (state.permissionGranted && !state.cameraFailed) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    PreviewView(context).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        onPreviewReady(this)
                    }
                },
            )

            // Viewfinder
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .fillMaxWidth(0.72f)
                        .aspectRatio(1f)
                        .border(
                            width = 2.dp,
                            color = Color.White.copy(alpha = 0.85f),
                            shape = RoundedCornerShape(28.dp),
                        ),
                )
            }

            Text(
                text = stringResource(R.string.camera_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.9f),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 120.dp),
            )
        }

        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.camera_title),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (state.torchAvailable) {
                TextButton(onClick = onToggleTorch) {
                    Text(
                        text = stringResource(if (torchOn) R.string.action_torch_on else R.string.action_torch),
                        color = Color.White,
                    )
                }
            }
            TextButton(onClick = onClose) {
                Text(stringResource(R.string.action_close), color = Color.White)
            }
        }

        // Camera problems
        if (state.cameraFailed || (!state.permissionGranted && state.permissionAsked)) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Card(
                    modifier = Modifier.padding(24.dp),
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(
                        modifier = Modifier.padding(22.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = stringResource(
                                if (!state.permissionGranted) R.string.camera_permission_title
                                else R.string.camera_unavailable_title,
                            ),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            text = stringResource(
                                if (!state.permissionGranted) R.string.camera_permission_message
                                else R.string.camera_error_message,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = if (!state.permissionGranted) onRequestPermission else onOpenAppSettings,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(
                                    stringResource(
                                        if (!state.permissionGranted) R.string.action_allow_camera
                                        else R.string.action_app_settings,
                                    ),
                                )
                            }
                            OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.action_close))
                            }
                        }
                    }
                }
            }
        }

        // Result
        state.result?.let { raw ->
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Column(
                    Modifier
                        .navigationBarsPadding()
                        .padding(16.dp)
                        .heightIn(max = 620.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    QrResultCard(
                        payload = QrPayload.parse(raw),
                        onDismiss = onDismissResult,
                        onRetry = onScanAgain,
                    )
                }
            }
        }
    }
}
