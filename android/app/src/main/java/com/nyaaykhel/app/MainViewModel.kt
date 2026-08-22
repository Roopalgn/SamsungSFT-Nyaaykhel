package com.nyaaykhel.app

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.google.gson.GsonBuilder
import com.nyaaykhel.app.data.*
import com.nyaaykhel.app.inference.EventClassification
import com.nyaaykhel.app.inference.EventClassifier
import com.nyaaykhel.app.inference.PoseExtractor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Main business logic for the NyaayKhel prototype.
 *
 * Pipeline (video file mode):
 *   Uri → MediaMetadataRetriever → Bitmap frames → PoseExtractor → PersonKeypoints
 *   → EventClassifier (sliding window) → EventClassification
 *   → EventLog (hash chain) → Room DB → UI state
 *
 * Export:
 *   Room DB events → MatchExport → KeystoreSigner → JSON file
 */
class MainViewModel(private val appContext: Context) : ViewModel() {

    private val tag = "MainViewModel"
    private val db  = MatchDatabase.getInstance(appContext)

    // ── UI State ─────────────────────────────────────────────────────────────

    sealed class AnalysisState {
        object Idle : AnalysisState()
        data class Processing(val framesDone: Int, val framesTotal: Int) : AnalysisState()
        data class Done(val matchId: String, val eventCount: Int) : AnalysisState()
        data class Error(val message: String) : AnalysisState()
    }

    private val _state = MutableStateFlow<AnalysisState>(AnalysisState.Idle)
    val state: StateFlow<AnalysisState> = _state.asStateFlow()

    private val _liveEvents = MutableStateFlow<List<EventRecord>>(emptyList())
    val liveEvents: StateFlow<List<EventRecord>> = _liveEvents.asStateFlow()

    private val _exportPath = MutableStateFlow<String?>(null)
    val exportPath: StateFlow<String?> = _exportPath.asStateFlow()

    // ── Inference config (throttle mitigations) ──────────────────────────────

    /** Process every Nth frame. 3 → ~10fps at 30fps source. 6 → ~5fps. */
    var processEveryNFrames: Int = 3

    /** Frames sampled per second from video (used for MediaMetadataRetriever seek). */
    private val sampleFps = 10f

    /** Confidence threshold passed to EventClassifier. */
    var confidenceThreshold: Float = 0.65f

    // ── Processing job ───────────────────────────────────────────────────────

    private var currentJob: Job? = null
    private var currentMatchId: String? = null

    // ── Video file analysis ──────────────────────────────────────────────────

    /**
     * Analyse a video file at [videoUri].
     * Creates a new match, runs the full inference pipeline, writes events to Room.
     */
    fun analyseVideo(videoUri: Uri) {
        currentJob?.cancel()
        currentJob = viewModelScope.launch(Dispatchers.IO) {
            val matchId = UUID.randomUUID().toString()
            currentMatchId = matchId

            val match = Match(
                matchId = matchId,
                createdAt = nowIso(),
                venueNote = "YouTube demo footage",
            )
            db.matchDao().insertMatch(match)

            _state.value = AnalysisState.Processing(0, 0)
            _liveEvents.value = emptyList()

            // Initialise inference
            val poseExtractor = PoseExtractor(
                appContext,
                inferenceThreads = 2,
            )
            val classifier = EventClassifier(
                appContext,
                confidenceThreshold = confidenceThreshold,
            )

            try {
                runPipeline(videoUri, matchId, poseExtractor, classifier)
                val count = db.eventDao().countEventsForMatch(matchId)
                _state.value = AnalysisState.Done(matchId, count)
                Log.i(tag, "Analysis complete: $count events for match $matchId")
            } catch (e: CancellationException) {
                Log.i(tag, "Analysis cancelled")
                _state.value = AnalysisState.Idle
            } catch (e: Exception) {
                Log.e(tag, "Analysis error", e)
                _state.value = AnalysisState.Error(e.message ?: "Unknown error")
            } finally {
                poseExtractor.close()
                classifier.close()
            }
        }
    }

    fun cancelAnalysis() {
        currentJob?.cancel()
        _state.value = AnalysisState.Idle
    }

    // ── Core pipeline ────────────────────────────────────────────────────────

    private suspend fun runPipeline(
        videoUri: Uri,
        matchId: String,
        poseExtractor: PoseExtractor,
        classifier: EventClassifier,
    ) {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(appContext, videoUri)

        val durationMs = retriever.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_DURATION
        )?.toLongOrNull() ?: throw IllegalStateException("Cannot read video duration")

        // Sample at sampleFps, processing every Nth sample via processEveryNFrames
        val intervalMs = (1000f / sampleFps).toLong()
        val totalSamples = (durationMs / intervalMs).toInt()

        Log.i(tag, "Video duration: ${durationMs}ms | samples: $totalSamples | interval: ${intervalMs}ms")

        var prevHash = EventLog.GENESIS_PREV_HASH
        var frameIdx = 0
        val accumulatedEvents = mutableListOf<EventRecord>()

        var sampleIdx = 0
        var timeUs = 0L
        while (timeUs < durationMs * 1000) {
            ensureActive()

            sampleIdx++
            // Throttle: only run inference every Nth sample
            if (sampleIdx % processEveryNFrames != 0) {
                timeUs += intervalMs * 1000
                frameIdx++
                continue
            }

            val bitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
            if (bitmap != null) {
                val persons = poseExtractor.extract(bitmap)
                bitmap.recycle()

                val classification: EventClassification? = classifier.addFrame(persons)

                if (classification != null && classification.eventType != "neutral") {
                    val eventId = "evt-${UUID.randomUUID()}"
                    val timestamp = nowIso()
                    val hash = EventLog.computeHash(
                        eventId    = eventId,
                        matchId    = matchId,
                        timestamp  = timestamp,
                        eventType  = classification.eventType,
                        confidence = classification.confidence,
                        prevHash   = prevHash,
                    )
                    val event = EventRecord(
                        eventId    = eventId,
                        matchId    = matchId,
                        timestamp  = timestamp,
                        eventType  = classification.eventType,
                        confidence = classification.confidence,
                        prevHash   = prevHash,
                        hash       = hash,
                        frameIndex = frameIdx,
                    )
                    db.eventDao().insertEvent(event)
                    prevHash = hash
                    accumulatedEvents.add(event)
                    _liveEvents.value = accumulatedEvents.toList()

                    Log.d(tag, "Event: ${event.eventType} conf=${"%.2f".format(event.confidence)} " +
                            "hash=${event.hash.take(8)}...")
                }
            }

            _state.value = AnalysisState.Processing(sampleIdx, totalSamples)
            timeUs += intervalMs * 1000
            frameIdx++
        }

        retriever.release()
    }

    // ── Export ───────────────────────────────────────────────────────────────

    /**
     * Export the match record for [matchId] as a signed JSON file.
     * Writes to app's external files directory (accessible via Files app on Android 10+).
     */
    fun exportMatchRecord(matchId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val match  = db.matchDao().getMatch(matchId)
                    ?: throw IllegalStateException("Match $matchId not found")
                val events = db.eventDao().getEventsForMatch(matchId)

                if (events.isEmpty()) {
                    Log.w(tag, "No events to export for $matchId")
                    return@launch
                }

                // Verify chain integrity before export (catches any DB corruption)
                val chainErrors = EventLog.verifyChain(events)
                if (chainErrors.isNotEmpty()) {
                    Log.e(tag, "Chain integrity errors before export: $chainErrors")
                    _state.value = AnalysisState.Error("Hash chain integrity failed: ${chainErrors.first()}")
                    return@launch
                }

                val terminalHash  = events.last().hash
                val publicKeyB64  = KeystoreSigner.getOrCreatePublicKey()
                val signatureB64  = KeystoreSigner.sign(terminalHash)

                val export = MatchExport(
                    matchId        = matchId,
                    exportedAt     = nowIso(),
                    sport          = match.sport,
                    deviceModel    = android.os.Build.MODEL,
                    appVersion     = BuildConfig.VERSION_NAME,
                    devicePublicKey = publicKeyB64,
                    terminalHash   = terminalHash,
                    signature      = signatureB64,
                    events         = events,
                )

                val json = GsonBuilder().setPrettyPrinting().create().toJson(export)

                val dir = appContext.getExternalFilesDir(null)
                    ?: appContext.filesDir
                val file = File(dir, "nyaaykhel_match_${matchId.take(8)}.json")
                file.writeText(json, Charsets.UTF_8)

                db.matchDao().markExported(matchId)
                _exportPath.value = file.absolutePath
                Log.i(tag, "Exported: ${file.absolutePath} (${events.size} events)")

            } catch (e: Exception) {
                Log.e(tag, "Export failed", e)
                _state.value = AnalysisState.Error("Export failed: ${e.message}")
            }
        }
    }

    // ── Events for match record screen ───────────────────────────────────────

    fun getEventsFlow(matchId: String) = db.eventDao().getEventsForMatchFlow(matchId)

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun nowIso(): String {
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
            .withZone(ZoneId.systemDefault())
        return formatter.format(Instant.now())
    }

    // ── Factory ──────────────────────────────────────────────────────────────

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return MainViewModel(context.applicationContext) as T
        }
    }
}
