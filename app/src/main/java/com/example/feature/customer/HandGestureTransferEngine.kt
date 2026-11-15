package com.example.feature.customer

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class HandGestureState {
    NO_HAND,
    CLOSED_FIST,
    OPEN_PALM
}

data class HandGestureFrameMetric(
    val state: HandGestureState,
    val skinCoverageRatio: Float,
    val compactness: Float,
    val fingerSpreadPeaks: Int
)

/**
 * Deterministic, fast on-device front-camera hand posture classifier + CameraX ImageAnalysis.Analyzer.
 *
 * Detects:
 *  - `CLOSED_FIST` (হাত মুঠো): compact hand silhouette in front of camera (`compactness >= 0.50`, fewer extended finger peaks)
 *  - `OPEN_PALM` (হাত খোলা): spread fingers / open palm (`compactness < 0.48` or `>= 3` top-row finger peaks)
 *  - `NO_HAND`: insufficient skin/hand foreground pixels in central ROI.
 */
object HandGestureClassifier {

    fun classifyBitmap(bitmap: Bitmap): HandGestureFrameMetric {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 16 || h < 16) {
            return HandGestureFrameMetric(HandGestureState.NO_HAND, 0f, 0f, 0)
        }

        val stepX = max(1, w / 48)
        val stepY = max(1, h / 48)
        val gridW = w / stepX
        val gridH = h / stepY
        if (gridW < 8 || gridH < 8) {
            return HandGestureFrameMetric(HandGestureState.NO_HAND, 0f, 0f, 0)
        }

        val mask = BooleanArray(gridW * gridH)
        var skinCount = 0
        var minGx = gridW
        var maxGx = -1
        var minGy = gridH
        var maxGy = -1

        for (gy in 0 until gridH) {
            val py = min(h - 1, gy * stepY)
            for (gx in 0 until gridW) {
                val px = min(w - 1, gx * stepX)
                val pixel = bitmap.getPixel(px, py)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                if (isHandSkinToneRgb(r, g, b)) {
                    mask[gy * gridW + gx] = true
                    skinCount++
                    if (gx < minGx) minGx = gx
                    if (gx > maxGx) maxGx = gx
                    if (gy < minGy) minGy = gy
                    if (gy > maxGy) maxGy = gy
                }
            }
        }

        val totalCells = (gridW * gridH).coerceAtLeast(1)
        val coverage = skinCount.toFloat() / totalCells.toFloat()

        if (coverage < 0.055f || coverage > 0.78f || maxGx <= minGx || maxGy <= minGy) {
            return HandGestureFrameMetric(HandGestureState.NO_HAND, coverage, 0f, 0)
        }

        val boxW = (maxGx - minGx + 1).coerceAtLeast(1)
        val boxH = (maxGy - minGy + 1).coerceAtLeast(1)
        val boxArea = (boxW * boxH).coerceAtLeast(1)
        val compactness = skinCount.toFloat() / boxArea.toFloat()

        // Measure finger-spread transitions across the upper 38% of the hand bounding box
        val upperEndGy = min(maxGy, minGy + max(2, (boxH * 38) / 100))
        var maxRowPeaks = 0
        for (gy in minGy..upperEndGy) {
            var rowPeaks = 0
            var inRun = false
            var runLen = 0
            for (gx in minGx..maxGx) {
                val active = mask[gy * gridW + gx]
                if (active) {
                    if (!inRun) {
                        inRun = true
                        runLen = 1
                    } else {
                        runLen++
                    }
                } else if (inRun) {
                    if (runLen >= 1) rowPeaks++
                    inRun = false
                    runLen = 0
                }
            }
            if (inRun && runLen >= 1) rowPeaks++
            if (rowPeaks > maxRowPeaks) maxRowPeaks = rowPeaks
        }

        val aspectRatio = boxH.toFloat() / boxW.toFloat()
        val detectedState = when {
            maxRowPeaks >= 3 || (compactness < 0.49f && coverage >= 0.085f) || (aspectRatio > 1.42f && compactness < 0.56f) ->
                HandGestureState.OPEN_PALM
            compactness >= 0.50f && maxRowPeaks <= 2 ->
                HandGestureState.CLOSED_FIST
            else ->
                HandGestureState.CLOSED_FIST
        }

        return HandGestureFrameMetric(
            state = detectedState,
            skinCoverageRatio = coverage,
            compactness = compactness,
            fingerSpreadPeaks = maxRowPeaks
        )
    }

    /**
     * Combines YCbCr + normalized RGB human skin chromaticity bounds across South Asian & global lighting conditions.
     */
    internal fun isHandSkinToneRgb(r: Int, g: Int, b: Int): Boolean {
        val y = (0.299f * r) + (0.587f * g) + (0.114f * b)
        if (y < 36f || y > 246f) return false
        val cb = 128f - (0.168736f * r) - (0.331264f * g) + (0.5f * b)
        val cr = 128f + (0.5f * r) - (0.418688f * g) - (0.081312f * b)
        val ycbcrMatch = cb in 75f..132f && cr in 131f..178f
        val rgbDominance = r > 55 && g > 35 && b > 18 && r > b && abs(r - g) >= 6
        return ycbcrMatch && rgbDominance
    }
}

class HandGestureCameraAnalyzer(
    private val onStableGestureChanged: (HandGestureState, HandGestureFrameMetric) -> Unit
) : ImageAnalysis.Analyzer {

    private val busy = AtomicBoolean(false)
    private var lastAnalyzedEpochMs = 0L
    private var candidateState = HandGestureState.NO_HAND
    private var consecutiveFrames = 0
    private var committedState = HandGestureState.NO_HAND

    override fun analyze(image: ImageProxy) {
        val now = System.currentTimeMillis()
        if (now - lastAnalyzedEpochMs < 130L || !busy.compareAndSet(false, true)) {
            image.close()
            return
        }
        lastAnalyzedEpochMs = now
        try {
            val bitmap = imageProxyToSmallRgbBitmap(image)
            if (bitmap != null) {
                try {
                    val metric = HandGestureClassifier.classifyBitmap(bitmap)
                    if (metric.state == candidateState) {
                        consecutiveFrames++
                    } else {
                        candidateState = metric.state
                        consecutiveFrames = 1
                    }
                    val requiredFrames = if (metric.state == HandGestureState.NO_HAND) 3 else 2
                    if (consecutiveFrames >= requiredFrames && committedState != candidateState) {
                        committedState = candidateState
                        onStableGestureChanged(committedState, metric)
                    }
                } finally {
                    bitmap.recycle()
                }
            }
        } catch (_: Exception) {
        } catch (_: OutOfMemoryError) {
        } finally {
            busy.set(false)
            image.close()
        }
    }

    private fun imageProxyToSmallRgbBitmap(image: ImageProxy): Bitmap? {
        return try {
            val rawBitmap = image.toBitmap()
            val rotation = image.imageInfo.rotationDegrees
            val scaled = Bitmap.createScaledBitmap(rawBitmap, 96, 96, false)
            if (scaled !== rawBitmap) rawBitmap.recycle()
            if (rotation != 0) {
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                val rotated = Bitmap.createBitmap(scaled, 0, 0, scaled.width, scaled.height, matrix, false)
                if (rotated !== scaled) scaled.recycle()
                rotated
            } else {
                scaled
            }
        } catch (_: Exception) {
            yuvPlanesToSmallBitmapFallback(image)
        }
    }

    private fun yuvPlanesToSmallBitmapFallback(image: ImageProxy): Bitmap? {
        val planes = image.planes
        if (planes.size < 3) return null
        val width = image.width
        val height = image.height
        val outW = 64
        val outH = 64
        val yBuf: ByteBuffer = planes[0].buffer
        val uBuf: ByteBuffer = planes[1].buffer
        val vBuf: ByteBuffer = planes[2].buffer
        val yRowStride = planes[0].rowStride
        val uvRowStride = planes[1].rowStride
        val uvPixelStride = planes[1].pixelStride

        val bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        for (oy in 0 until outH) {
            val sy = (oy * height) / outH
            for (ox in 0 until outW) {
                val sx = (ox * width) / outW
                val yIdx = sy * yRowStride + sx
                val uvIdx = (sy / 2) * uvRowStride + (sx / 2) * uvPixelStride
                val yVal = (yBuf.get(yIdx).toInt() and 0xFF)
                val uVal = (uBuf.get(uvIdx).toInt() and 0xFF) - 128
                val vVal = (vBuf.get(uvIdx).toInt() and 0xFF) - 128
                val r = (yVal + (1.370705f * vVal)).toInt().coerceIn(0, 255)
                val g = (yVal - (0.337633f * uVal) - (0.698001f * vVal)).toInt().coerceIn(0, 255)
                val b = (yVal + (1.732446f * uVal)).toInt().coerceIn(0, 255)
                bmp.setPixel(ox, oy, (0xFF shl 24) or (r shl 16) or (g shl 8) or b)
            }
        }
        return bmp
    }
}
