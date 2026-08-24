package com.nyaaykhel.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A kabaddi match session. Created when the user starts a new recording.
 */
@Entity(tableName = "matches")
data class Match(
    @PrimaryKey val matchId: String,
    val createdAt: String,       // ISO-8601 timestamp
    val sport: String = "kabaddi",
    val venueNote: String = "",  // optional free-text note
    val isExported: Boolean = false,
)

/**
 * A single candidate event detected in a match video.
 *
 * Hash chain fields: event_id, match_id, timestamp, event_type, confidence, prev_hash
 * See docs/hash_chain_spec.md for the canonical spec — this order is locked.
 */
@Entity(
    tableName = "events",
    foreignKeys = [ForeignKey(
        entity = Match::class,
        parentColumns = ["matchId"],
        childColumns = ["matchId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("matchId")],
)
data class EventRecord(
    @PrimaryKey val eventId: String,
    val matchId: String,
    val timestamp: String,           // ISO-8601 with millis + offset
    val eventType: String,           // raid_start | touch | escape_return | neutral
    val confidence: Float,           // raw float [0.0, 1.0]
    val prevHash: String,            // SHA-256 hex of previous event (64 zeros for genesis)
    val hash: String,                // SHA-256 hex of this event (per hash_chain_spec.md)
    val frameIndex: Int = -1,        // source video frame (informational only)
    val videoTimestampMs: Long = 0L,  // source-video position, excluded from locked hash spec
    val reviewStatus: String = "pending", // pending | approved | rejected, excluded from hash
)

/** Human-readable event type labels for UI display. */
enum class EventType(val label: String, val colorResId: Int) {
    RAID_START("Candidate Raid",    android.R.color.holo_orange_light),
    TOUCH("Candidate Contact",      android.R.color.holo_red_light),
    ESCAPE_RETURN("Candidate Return", android.R.color.holo_green_light),
    NEUTRAL("Neutral",          android.R.color.darker_gray);

    companion object {
        fun fromString(s: String): EventType = when (s.lowercase()) {
            "raid_start"    -> RAID_START
            "touch"         -> TOUCH
            "escape_return" -> ESCAPE_RETURN
            else            -> NEUTRAL
        }
    }
}

/** Full match export payload for signing and JSON serialisation. */
data class MatchExport(
    val matchId: String,
    val exportedAt: String,
    val sport: String,
    val deviceModel: String,
    val appVersion: String,
    val devicePublicKey: String,
    val terminalHash: String,
    val signature: String,
    val events: List<EventRecord>,
)
