package com.nyaaykhel.app.inference

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * GRU/TCN temporal classifier for candidate kabaddi events.
 *
 * Maintains a sliding window of [windowSize] frames of keypoint sequences.
 * When the buffer is full, runs the TFLite classifier and returns the detected event.
 *
 * Sliding window config must match training (notebook 03 / model_config.json):
 *  - windowSize  = 30 frames
 *  - featureDim  = 102  (MAX_PERSONS=2 × 17_keypoints × 3 [x,y,conf])
 *  - maxPersons  = 2
 *
 * Config is hardcoded deliberately for the prototype (simpler, no file-read failure path
 * on demo day). Values are validated against the loaded model's actual I/O tensor shapes
 * at init time and a warning is logged to logcat if there is a mismatch — so swapping in
 * a retrained model with different dimensions will be caught immediately. Runtime loading
 * from model_config.json is a Phase E improvement.
 *
 * Output: [EventClassification] with predicted class and softmax confidence,
 *         or null if confidence < [confidenceThreshold] or buffer not yet full.
 */
class EventClassifier(
    context: Context,
    private val windowSize: Int = 30,
    private val featureDim: Int = 102,       // 2 × 17 × 3
    private val maxPersons: Int = 2,
    private val numKeypoints: Int = 17,
    private val confidenceThreshold: Float = 0.65f,
    private val windowStride: Int = 10,      // emit a new classification every N frames
) : AutoCloseable {

    private val tag = "EventClassifier"

    /** Integer → class name. Must match label_map.json from training. */
    val classNames = listOf("raid_start", "touch", "escape_return", "neutral")

    private val interpreter: Interpreter

    // Sliding window buffer: list of flat feature vectors, each (featureDim,)
    private val frameBuffer = ArrayDeque<FloatArray>(windowSize)
    private var framesSinceLastClassification = 0

    companion object {
        const val MODEL_ASSET = "nyaaykhel_classifier.tflite"
    }

    init {
        val options = Interpreter.Options().apply { numThreads = 2 }
        interpreter = Interpreter(loadModelFromAssets(context, MODEL_ASSET), options)
        validateModelIO()
        Log.i(tag, "EventClassifier ready (window=$windowSize, stride=$windowStride, threshold=$confidenceThreshold)")
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Add [persons] (a frame's detected pose data) to the sliding window.
     * Returns [EventClassification] if a new classification was computed this frame,
     * or null if the window isn't full yet or the stride hasn't elapsed.
     */
    fun addFrame(persons: List<PersonKeypoints>): EventClassification? {
        val frameVec = buildFrameVector(persons)
        frameBuffer.addLast(frameVec)
        if (frameBuffer.size > windowSize) frameBuffer.removeFirst()
        framesSinceLastClassification++

        if (frameBuffer.size < windowSize) return null
        if (framesSinceLastClassification < windowStride) return null

        framesSinceLastClassification = 0
        return classify()
    }

    /** Reset the sliding window (call when starting a new match or after a long pause). */
    fun reset() {
        frameBuffer.clear()
        framesSinceLastClassification = 0
    }

    override fun close() {
        interpreter.close()
    }

    // ── Internal ────────────────────────────────────────────────────────────

    /**
     * Build the flat feature vector for one frame.
     * Normalisation: x and y are already [0,1] from PoseExtractor (normalised to frame dims).
     * Padding: if fewer than maxPersons detected, pad with zeros.
     * Person order: left-to-right (already done in PoseExtractor.extract()).
     */
    private fun buildFrameVector(persons: List<PersonKeypoints>): FloatArray {
        val vec = FloatArray(featureDim)  // zero-initialised
        val n = minOf(persons.size, maxPersons)
        for (p in 0 until n) {
            val flat = persons[p].toFlatArray()  // (51,) = 17 kps × 3
            val offset = p * numKeypoints * 3
            flat.copyInto(vec, offset)
        }
        return vec
    }

    /**
     * Run the TFLite classifier on the current window buffer.
     * Input shape:  [1, windowSize, featureDim]
     * Output shape: [1, numClasses]
     */
    private fun classify(): EventClassification? {
        // Build input buffer from frame buffer
        val inputBuffer = ByteBuffer.allocateDirect(1 * windowSize * featureDim * 4)
        inputBuffer.order(ByteOrder.nativeOrder())
        frameBuffer.forEach { frame -> frame.forEach { v -> inputBuffer.putFloat(v) } }
        inputBuffer.rewind()

        // Output: [1, 4] logits
        val outputBuffer = Array(1) { FloatArray(classNames.size) }
        interpreter.run(inputBuffer, outputBuffer)

        // Softmax
        val logits = outputBuffer[0]
        val probs = softmax(logits)
        val bestIdx = probs.indices.maxByOrNull { probs[it] } ?: return null
        val bestProb = probs[bestIdx]

        if (bestProb < confidenceThreshold) return null

        return EventClassification(
            eventType  = classNames[bestIdx],
            confidence = bestProb,
            allProbs   = probs.clone(),
        )
    }

    private fun softmax(logits: FloatArray): FloatArray {
        val maxVal = logits.max()
        val exps = logits.map { Math.exp((it - maxVal).toDouble()).toFloat() }
        val sum = exps.sum()
        return exps.map { it / sum }.toFloatArray()
    }

    private fun validateModelIO() {
        val inShape  = interpreter.getInputTensor(0).shape()
        val outShape = interpreter.getOutputTensor(0).shape()
        Log.i(tag, "Classifier input:  ${inShape.toList()}")
        Log.i(tag, "Classifier output: ${outShape.toList()}")

        if (inShape.size == 3) {
            val modelWindow  = inShape[1]
            val modelFeature = inShape[2]
            if (modelWindow != windowSize || modelFeature != featureDim) {
                Log.w(tag, "Model I/O mismatch! Model expects [?, $modelWindow, $modelFeature] " +
                        "but EventClassifier is configured for [$windowSize, $featureDim]. " +
                        "Update windowSize/featureDim to match model_config.json.")
            }
        }
    }

    private fun loadModelFromAssets(context: Context, filename: String): MappedByteBuffer {
        val fd = context.assets.openFd(filename)
        val fis = FileInputStream(fd.fileDescriptor)
        return fis.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
    }
}

/** Result of a single classification pass. */
data class EventClassification(
    val eventType: String,     // "raid_start" | "touch" | "escape_return" | "neutral"
    val confidence: Float,     // softmax probability of winning class
    val allProbs: FloatArray,  // full softmax distribution [raid_start, touch, escape_return, neutral]
)
