package com.leocb.screenqr.camera

import android.content.Context
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors

/** Thin CameraX wrapper: preview + single-image analysis, bound to the activity lifecycle. */
class CameraSession(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val analyzer: (ImageProxy) -> Unit,
) {

    private val executor = Executors.newSingleThreadExecutor()
    private val analysis = ImageAnalysis.Builder()
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .build()

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var started = false

    suspend fun start(previewView: PreviewView) {
        val provider = awaitProvider()
        this.provider = provider
        val preview = Preview.Builder()
            .build()
            .apply { surfaceProvider = previewView.surfaceProvider }

        analysis.setAnalyzer(executor, analyzer)
        provider.unbindAll()
        camera = provider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            analysis,
        )
        started = true
    }

    fun hasFlash(): Boolean = camera?.cameraInfo?.hasFlashUnit() == true

    fun setTorch(enabled: Boolean) {
        camera?.cameraControl?.enableTorch(enabled)
    }

    fun pauseAnalysis() {
        runCatching { analysis.clearAnalyzer() }
    }

    fun resumeAnalysis() {
        if (started) analysis.setAnalyzer(executor, analyzer)
    }

    fun release() {
        runCatching { provider?.unbindAll() }
        runCatching { analysis.clearAnalyzer() }
        executor.shutdown()
    }

    private suspend fun awaitProvider(): ProcessCameraProvider =
        suspendCancellableCoroutine { continuation ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener(
                {
                    runCatching { future.get() }
                        .onSuccess { provider ->
                            if (continuation.isActive) continuation.resumeWith(Result.success(provider))
                        }
                        .onFailure { error ->
                            if (continuation.isActive) continuation.resumeWith(Result.failure(error))
                        }
                },
                ContextCompat.getMainExecutor(context),
            )
        }
}
