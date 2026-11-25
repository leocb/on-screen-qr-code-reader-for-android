package com.leocb.screenqr.decode

import android.graphics.Bitmap
import android.graphics.Color
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.GenericMultipleBarcodeReader
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.math.max

/**
 * Turns a screenshot (or camera frame) into a QR payload.
 *
 * ZXing handles clean screenshots quickly; ML Kit is used as a second pass because it copes
 * better with perspective, glare and tiny codes.
 */
object QrDecoder {

    private const val MAX_DIMENSION = 2200

    private val hints: Map<DecodeHintType, Any> = mapOf(
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.CHARACTER_SET to "UTF-8",
        DecodeHintType.POSSIBLE_FORMATS to listOf(
            BarcodeFormat.QR_CODE,
            BarcodeFormat.DATA_MATRIX,
            BarcodeFormat.AZTEC,
            BarcodeFormat.PDF_417,
        ),
    )

    private val mlKitOptions: BarcodeScannerOptions =
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()

    suspend fun decode(bitmap: Bitmap): String? =
        decodeWithZxing(bitmap) ?: decodeWithMlKit(bitmap)

    fun decodeWithZxing(source: Bitmap): String? {
        val attempts = linkedSetOf<Bitmap>()
        attempts += source.scaledToFit(MAX_DIMENSION)
        attempts += source.scaledToFit(1000)
        try {
            for (bitmap in attempts) {
                readSingle(bitmap)?.let { return it }
                readMultiple(bitmap)?.let { return it }
            }
            // Light-on-dark codes (rare, but they show up in dark themed apps).
            attempts.lastOrNull()?.let { bitmap ->
                val inverted = invert(bitmap)
                try {
                    readSingle(inverted)?.let { return it }
                } finally {
                    if (inverted !== bitmap) inverted.recycle()
                }
            }
            return null
        } finally {
            attempts.forEach { if (it !== source) it.recycle() }
        }
    }

    suspend fun decodeWithMlKit(bitmap: Bitmap): String? {
        val scanner = BarcodeScanning.getClient(mlKitOptions)
        return try {
            suspendCancellableCoroutine { continuation ->
                scanner.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { barcodes ->
                        val value = barcodes.firstNotNullOfOrNull { barcode ->
                            barcode.rawValue?.trim()?.takeIf { it.isNotEmpty() }
                        }
                        if (continuation.isActive) continuation.resumeWith(Result.success(value))
                    }
                    .addOnFailureListener {
                        if (continuation.isActive) continuation.resumeWith(Result.success(null))
                    }
            }
        } finally {
            runCatching { scanner.close() }
        }
    }

    /** True when the capture is essentially a black rectangle, which is what FLAG_SECURE produces. */
    fun looksBlank(source: Bitmap): Boolean {
        val software = source.asSoftwareBitmap() ?: return true
        val width = 24
        val height = 24
        val small = Bitmap.createScaledBitmap(software, width, height, false)
        val pixels = IntArray(width * height)
        small.getPixels(pixels, 0, width, 0, 0, width, height)
        if (small !== software) small.recycle()
        val maxLuminance = pixels.maxOfOrNull { Color.red(it) * 299 + Color.green(it) * 587 + Color.blue(it) * 114 } ?: 0
        return maxLuminance / 1000 < 10
    }

    private fun readSingle(bitmap: Bitmap): String? = runCatching {
        val reader = MultiFormatReader().apply { setHints(hints) }
        val binary = BinaryBitmap(HybridBinarizer(luminanceSource(bitmap)))
        reader.decodeWithState(binary).text?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun readMultiple(bitmap: Bitmap): String? = runCatching {
        val reader = GenericMultipleBarcodeReader(MultiFormatReader().apply { setHints(hints) })
        reader.decodeMultiple(BinaryBitmap(HybridBinarizer(luminanceSource(bitmap))), hints)
            .firstNotNullOfOrNull { it.text?.trim()?.takeIf { text -> text.isNotEmpty() } }
    }.getOrNull()

    private fun luminanceSource(bitmap: Bitmap): RGBLuminanceSource {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
    }

    private fun Bitmap.scaledToFit(maxSize: Int): Bitmap {
        val longest = max(width, height)
        if (longest <= maxSize) return this
        val scale = maxSize.toFloat() / longest
        return Bitmap.createScaledBitmap(
            this,
            (width * scale).toInt().coerceAtLeast(1),
            (height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }

    private fun invert(bitmap: Bitmap): Bitmap {
        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, true) ?: return bitmap
        val pixels = IntArray(copy.width * copy.height)
        copy.getPixels(pixels, 0, copy.width, 0, 0, copy.width, copy.height)
        for (index in pixels.indices) {
            val pixel = pixels[index]
            pixels[index] = Color.rgb(255 - Color.red(pixel), 255 - Color.green(pixel), 255 - Color.blue(pixel))
        }
        copy.setPixels(pixels, 0, copy.width, 0, 0, copy.width, copy.height)
        return copy
    }
}

/** Hardware bitmaps cannot be read with getPixels(); make a software copy when needed. */
fun Bitmap.asSoftwareBitmap(): Bitmap? {
    if (config == Bitmap.Config.HARDWARE) {
        return runCatching { copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
    }
    return this
}
