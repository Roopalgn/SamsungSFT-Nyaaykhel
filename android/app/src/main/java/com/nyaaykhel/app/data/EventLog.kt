package com.nyaaykhel.app.data

import java.security.MessageDigest

/**
 * SHA-256 hash chain for NyaayKhel match records.
 *
 * CANONICAL SPEC: docs/hash_chain_spec.md — DO NOT modify field order or
 * confidence formatting without updating verify_chain.py simultaneously.
 *
 * Hash input (no separator, UTF-8 encoded):
 *   event_id + match_id + timestamp + event_type + "%.6f".format(confidence) + prev_hash
 *
 * Conformance test vector (see docs/hash_chain_spec.md §7):
 *   Input:    "test-evt-001" + "test-match-001" + "2026-01-01T00:00:00.000+05:30"
 *             + "raid_start" + "0.750000" + "000...000" (64 zeros)
 *   Expected: 5e77951ee410132a7b635c8fef71b49ab631c6b156f454a26b7ef3b51d5c71bc
 */
object EventLog {

    /** Genesis event sentinel: first event's prev_hash is 64 ASCII zeros. */
    const val GENESIS_PREV_HASH = "0000000000000000000000000000000000000000000000000000000000000000"

    /**
     * Compute SHA-256 hash for a single event record.
     *
     * Field order (locked per spec v1.0):
     *   1. eventId
     *   2. matchId
     *   3. timestamp
     *   4. eventType
     *   5. confidence  ← formatted as "%.6f" (6 decimal places, no scientific notation)
     *   6. prevHash
     */
    fun computeHash(
        eventId: String,
        matchId: String,
        timestamp: String,
        eventType: String,
        confidence: Float,
        prevHash: String,
    ): String {
        // "%.6f".format(confidence) matches Python's "%.6f" % confidence exactly.
        // This is the canonical format defined in hash_chain_spec.md §2.
        val confidenceStr = "%.6f".format(confidence)

        val input = eventId + matchId + timestamp + eventType + confidenceStr + prevHash
        return sha256Hex(input)
    }

    /** Convenience overload that takes a fully populated [EventRecord]. */
    fun computeHash(event: EventRecord, prevHash: String): String =
        computeHash(
            eventId    = event.eventId,
            matchId    = event.matchId,
            timestamp  = event.timestamp,
            eventType  = event.eventType,
            confidence = event.confidence,
            prevHash   = prevHash,
        )

    /** SHA-256 of UTF-8 encoded [input], returned as lowercase hex. */
    fun sha256Hex(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Verify that the hash chain in [events] is intact.
     * Returns a list of error strings (empty list = chain is valid).
     */
    fun verifyChain(events: List<EventRecord>): List<String> {
        val errors = mutableListOf<String>()
        var expectedPrevHash = GENESIS_PREV_HASH

        events.forEachIndexed { i, event ->
            if (event.prevHash != expectedPrevHash) {
                errors += "Event $i (${event.eventId}): prev_hash mismatch"
            }
            val recomputed = computeHash(event, expectedPrevHash)
            if (recomputed != event.hash) {
                errors += "Event $i (${event.eventId}): hash mismatch (data altered)"
            }
            expectedPrevHash = event.hash
        }
        return errors
    }
}
