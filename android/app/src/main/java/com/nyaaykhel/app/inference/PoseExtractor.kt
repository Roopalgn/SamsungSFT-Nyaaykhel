package com.nyaaykhel.app.inference

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Wraps the YOLOv8n-pose TFLite model for multi-person keypoint extraction.
 *
 * Input:  Bitmap (any size) → internally scaled to 320×320, normalised [0,1]
 * Output: [PersonKeypoints] — up to [maxPersons] persons, sorted left-to-right.
 *         Bounding boxes stay in source-image pixel space for NMS/sorting.
 *         Keypoints are normalised to [0,1], matching the classifier training data.
 *         Keypoints with visibility < [kpConfThreshold] are zeroed out.
 *
 * GPU delegate was removed due to a GpuDelegate.Options / GpuDelegateFactory.Options
 * classpath conflict in tensorflow-lite-gpu 2.14.0. CPU inference with configurable
 * thread count is sufficient for demo video-file mode at 5-10 fps effective.
 * GPU support is a Phase E addition once TFLite dependency stabilises.
 *
 * On-device speed mitigations (from risk register):
 *  - [inferenceThreads]: configurable (default 2, reduce to 1 for single-thread mode)
 *  - Caller should keep video sampling aligned with the classifier training fps
 */
class PoseExtractor(
    context: Context,
    private val maxPersons: Int = 2,
    private val personConfThreshold: Float = 0.5f,
    private val kpConfThreshold: Float = 0.3f,
    private val inferenceThreads: Int = 2,
) : AutoCloseable {

    private val tag = "PoseExtractor"
    private val modelInputSize = 320

    private val interpreter: Interpreter

    // Output shape determined at runtime (handle both [1,56,8400] and [1,8400,56])
    private var outputTransposed = false   // true → output is [1, 56, N], need transpose
    private var numAnchors = 0
    private val outputChannels = 4 + 1 + 17 * 3  // 56: box(4) + conf(1) + 17kps×3

    companion object {
        const val MODEL_ASSET = "yolov8n_pose.tflite"
        private const val NMS_IOU_THRESHOLD = 0.45f
    }

    init {
        val options = Interpreter.Options().apply {
            numThreads = inferenceThreads
        }
        interpreter = Interpreter(loadModelFromAssets(context, MODEL_ASSET), options)
        inspectModel()
        Log.i(tag, "PoseExtractor ready (CPU, threads=$inferenceThreads)")
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Run pose inference on [bitmap].
     * Returns a list of at most [maxPersons] [PersonKeypoints], sorted by x-position.
     * Empty list = no persons detected above threshold.
     */
    fun extract(bitmap: Bitmap): List<PersonKeypoints> {
        val inputBuffer = preprocessBitmap(bitmap)
        val rawOutput = allocateOutputBuffer()

        interpreter.run(inputBuffer, rawOutput)

        val detections = parseOutput(rawOutput, bitmap.width, bitmap.height)
        val afterNms = nms(detections, NMS_IOU_THRESHOLD)
        val sorted = afterNms
            .sortedBy { it.boundingBox.centerX() }
            .take(maxPersons)

        return sorted.map { applyKpConfThreshold(it) }
    }

    override fun close() {
        interpreter.close()
    }

    // ── Model inspection ─────────────────────────────────────────────────────

    private fun inspectModel() {
        val inputTensor  = interpreter.getInputTensor(0)
        val outputTensor = interpreter.getOutputTensor(0)
        Log.i(tag, "Input  shape: ${inputTensor.shape().toList()}")
        Log.i(tag, "Output shape: ${outputTensor.shape().toList()}")

        val outShape = outputTensor.shape()
        // Expected: [1, 56, N] (transposed) or [1, N, 56]
        when {
            outShape.size == 3 && outShape[1] == outputChannels -> {
                outputTransposed = true
                numAnchors = outShape[2]
                Log.i(tag, "Output format: [1, $outputChannels, $numAnchors] — will transpose")
            }
            outShape.size == 3 && outShape[2] == outputChannels -> {
                outputTransposed = false
                numAnchors = outShape[1]
                Log.i(tag, "Output format: [1, $numAnchors, $outputChannels]")
            }
            else -> {
                Log.w(tag, "Unexpected output shape ${outShape.toList()} — guessing transposed")
                outputTransposed = true
                numAnchors = if (outShape.size >= 3) outShape[2] else 8400
            }
        }
    }

    // ── Preprocessing ────────────────────────────────────────────────────────

    private fun preprocessBitmap(bitmap: Bitmap): ByteBuffer {
        val scaled = if (bitmap.width == modelInputSize && bitmap.height == modelInputSize) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, modelInputSize, modelInputSize, true)
        }

        // [1, 320, 320, 3] float32 NHWC, values [0, 1]
        val buffer = ByteBuffer.allocateDirect(1 * modelInputSize * modelInputSize * 3 * 4)
        buffer.order(ByteOrder.nativeOrder())

        val pixels = IntArray(modelInputSize * modelInputSize)
        scaled.getPixels(pixels, 0, modelInputSize, 0, 0, modelInputSize, modelInputSize)
        for (pixel in pixels) {
            buffer.putFloat(((pixel shr 16) and 0xFF) / 255.0f)  // R
            buffer.putFloat(((pixel shr 8) and 0xFF) / 255.0f)   // G
            buffer.putFloat((pixel and 0xFF) / 255.0f)            // B
        }
        buffer.rewind()
        return buffer
    }

    private fun allocateOutputBuffer(): Array<Array<FloatArray>> {
        return if (outputTransposed) {
            Array(1) { Array(outputChannels) { FloatArray(numAnchors) } }
        } else {
            Array(1) { Array(numAnchors) { FloatArray(outputChannels) } }
        }
    }

    // ── Output parsing ───────────────────────────────────────────────────────

    private fun parseOutput(
        rawOutput: Array<Array<FloatArray>>,
        origW: Int,
        origH: Int,
    ): List<PersonKeypoints> {
        val detections = mutableListOf<PersonKeypoints>()
        val scaleX = origW.toFloat() / modelInputSize
        val scaleY = origH.toFloat() / modelInputSize

        for (a in 0 until numAnchors) {
            val row = FloatArray(outputChannels) { col ->
                if (outputTransposed) rawOutput[0][col][a] else rawOutput[0][a][col]
            }
            val conf = row[4]
            if (conf < personConfThreshold) continue

            val cx = row[0] * scaleX
            val cy = row[1] * scaleY
            val w  = row[2] * scaleX
            val h  = row[3] * scaleY
            val box = android.graphics.RectF(cx - w/2, cy - h/2, cx + w/2, cy + h/2)

            val keypoints = Array(17) { k ->
                val base = 5 + k * 3
                val xPixel = row[base] * scaleX
                val yPixel = row[base+1] * scaleY
                Keypoint(
                    x    = (xPixel / origW.toFloat()).coerceIn(0f, 1f),
                    y    = (yPixel / origH.toFloat()).coerceIn(0f, 1f),
                    conf = row[base+2],
                )
            }

            detections.add(PersonKeypoints(confidence = conf, boundingBox = box, keypoints = keypoints))
        }
        return detections
    }

    // ── NMS ─────────────────────────────────────────────────────────────────

    private fun nms(detections: List<PersonKeypoints>, iouThreshold: Float): List<PersonKeypoints> {
        val sorted = detections.sortedByDescending { it.confidence }.toMutableList()
        val suppressed = BooleanArray(sorted.size)
        val kept = mutableListOf<PersonKeypoints>()
        for (i in sorted.indices) {
            if (suppressed[i]) continue
            kept.add(sorted[i])
            for (j in i + 1 until sorted.size) {
                if (!suppressed[j] && iou(sorted[i].boundingBox, sorted[j].boundingBox) > iouThreshold) {
                    suppressed[j] = true
                }
            }
        }
        return kept
    }

    private fun iou(a: android.graphics.RectF, b: android.graphics.RectF): Float {
        val interLeft   = maxOf(a.left, b.left)
        val interTop    = maxOf(a.top, b.top)
        val interRight  = minOf(a.right, b.right)
        val interBottom = minOf(a.bottom, b.bottom)
        val interArea   = maxOf(0f, interRight - interLeft) * maxOf(0f, interBottom - interTop)
        if (interArea == 0f) return 0f
        val unionArea = (a.width() * a.height()) + (b.width() * b.height()) - interArea
        return interArea / unionArea
    }

    private fun applyKpConfThreshold(person: PersonKeypoints): PersonKeypoints {
        val filtered = person.keypoints.map { kp ->
            if (kp.conf < kpConfThreshold) Keypoint(0f, 0f, 0f) else kp
        }.toTypedArray()
        return person.copy(keypoints = filtered)
    }

    private fun loadModelFromAssets(context: Context, filename: String): MappedByteBuffer {
        val fd = context.assets.openFd(filename)
        val fis = FileInputStream(fd.fileDescriptor)
        return fis.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
    }
}

// ── Data classes ─────────────────────────────────────────────────────────────

data class Keypoint(val x: Float, val y: Float, val conf: Float)

data class PersonKeypoints(
    val confidence: Float,
    val boundingBox: android.graphics.RectF,
    val keypoints: Array<Keypoint>,  // 17 normalised keypoints per person
) {
    /** Flatten to Float array for classifier input: [x0,y0,c0, x1,y1,c1, ...] × 17 */
    fun toFlatArray(): FloatArray = FloatArray(17 * 3) { i ->
        val kpIdx = i / 3
        when (i % 3) {
            0    -> keypoints[kpIdx].x
            1    -> keypoints[kpIdx].y
            else -> keypoints[kpIdx].conf
        }
    }

    override fun equals(other: Any?) = other is PersonKeypoints && confidence == other.confidence
    override fun hashCode() = confidence.hashCode()
}
