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
 * Binary classifier for short candidate contact windows.
 *
 * This is the Android landing point for the active-learning pipeline in:
 *   scripts/extract_touch_candidates.py
 *   scripts/train_touch_candidate_classifier.py
 *
 * Input shape:  [1, 7]
 * Feature order:
 *   max_iou, mean_iou, min_kp_dist, min_hand_torso_dist,
 *   max_compression, max_velocity_drop, duration_sec
 *
 * Output shape: [1, 1], sigmoid probability that the candidate is a visible
 * raider-defender touch/contact.
 */
class TouchFeatureClassifier(
    context: Context,
    private val confidenceThreshold: Float = 0.60f,
) : AutoCloseable {

    private val tag = "TouchFeatureClassifier"
    private val interpreter: Interpreter

    companion object {
        const val MODEL_ASSET = "touch_candidate_classifier.tflite"
        const val FEATURE_DIM = 7
    }

    init {
        val options = Interpreter.Options().apply { numThreads = 2 }
        interpreter = Interpreter(loadModelFromAssets(context, MODEL_ASSET), options)
        validateModelIO()
        Log.i(tag, "TouchFeatureClassifier ready (threshold=$confidenceThreshold)")
    }

    fun classify(features: TouchCandidateFeatures): TouchClassification {
        val inputBuffer = ByteBuffer.allocateDirect(FEATURE_DIM * 4).order(ByteOrder.nativeOrder())
        features.toFloatArray().forEach { inputBuffer.putFloat(it) }
        inputBuffer.rewind()

        val output = Array(1) { FloatArray(1) }
        interpreter.run(inputBuffer, output)

        val probability = output[0][0].coerceIn(0f, 1f)
        return TouchClassification(
            isTouch = probability >= confidenceThreshold,
            probability = probability,
            features = features,
        )
    }

    override fun close() {
        interpreter.close()
    }

    private fun validateModelIO() {
        val inShape = interpreter.getInputTensor(0).shape()
        val outShape = interpreter.getOutputTensor(0).shape()
        Log.i(tag, "Touch model input: ${inShape.toList()}")
        Log.i(tag, "Touch model output: ${outShape.toList()}")

        val inputFeatureDim = inShape.lastOrNull()
        if (inputFeatureDim != FEATURE_DIM) {
            Log.w(tag, "Model expects $inputFeatureDim features, Android provides $FEATURE_DIM.")
        }
    }

    private fun loadModelFromAssets(context: Context, filename: String): MappedByteBuffer {
        val fd = context.assets.openFd(filename)
        val fis = FileInputStream(fd.fileDescriptor)
        return fis.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
    }
}

data class TouchCandidateFeatures(
    val maxIou: Float,
    val meanIou: Float,
    val minKeypointDistance: Float,
    val minHandTorsoDistance: Float,
    val maxCompression: Float,
    val maxVelocityDrop: Float,
    val durationSeconds: Float,
) {
    fun toFloatArray(): FloatArray = floatArrayOf(
        maxIou,
        meanIou,
        minKeypointDistance,
        minHandTorsoDistance,
        maxCompression,
        maxVelocityDrop,
        durationSeconds,
    )
}

data class TouchClassification(
    val isTouch: Boolean,
    val probability: Float,
    val features: TouchCandidateFeatures,
)
