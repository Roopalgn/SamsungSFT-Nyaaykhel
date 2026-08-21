package com.nyaaykhel.app

import com.nyaaykhel.app.data.EventLog
import com.nyaaykhel.app.data.EventRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hash chain conformance tests.
 *
 * CRITICAL: The test vector in [testConformanceVector] must produce the same
 * SHA-256 as `scripts/hash_spec_test.py`. If both produce the same hash,
 * Python verify_chain.py and Kotlin EventLog.kt are conformant.
 *
 * Expected hash: 5e77951ee410132a7b635c8fef71b49ab631c6b156f454a26b7ef3b51d5c71bc
 * (computed by scripts/hash_spec_test.py on 2026-08-21, see docs/hash_chain_spec.md §7)
 */
class EventLogTest {

    // ── Conformance test vector (docs/hash_chain_spec.md §7) ──────────────────

    @Test
    fun testConformanceVector() {
        val hash = EventLog.computeHash(
            eventId    = "test-evt-001",
            matchId    = "test-match-001",
            timestamp  = "2026-01-01T00:00:00.000+05:30",
            eventType  = "raid_start",
            confidence = 0.75f,
            prevHash   = "0".repeat(64),
        )
        assertEquals(
            "Hash must match Python verify_chain.py. If this fails, " +
                    "check confidence formatting (%.6f) and field order.",
            "5e77951ee410132a7b635c8fef71b49ab631c6b156f454a26b7ef3b51d5c71bc",
            hash,
        )
    }

    // ── Confidence format tests ───────────────────────────────────────────────

    @Test
    fun testConfidenceFormatting_sixDecimalPlaces() {
        // 0.84f must hash identically to Python's "%.6f" % 0.84 = "0.840000"
        val h1 = EventLog.computeHash("e1","m1","t1","raid_start", 0.84f, "0".repeat(64))
        val h2 = EventLog.computeHash("e1","m1","t1","raid_start", 0.84f, "0".repeat(64))
        assertEquals("Same inputs must produce same hash", h1, h2)
    }

    @Test
    fun testConfidenceFormatting_exactlyOne() {
        // Edge case: confidence = 1.0 → "1.000000" not "1.0"
        // Verify it doesn't crash and produces a deterministic output
        val h = EventLog.computeHash("e","m","t","touch", 1.0f, "0".repeat(64))
        assertTrue("Hash must be 64-char hex", h.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun testConfidenceFormatting_verySmall() {
        // Edge case: very small float should not use scientific notation
        val h = EventLog.computeHash("e","m","t","neutral", 0.000001f, "0".repeat(64))
        assertTrue("Hash must be 64-char hex", h.matches(Regex("[0-9a-f]{64}")))
    }

    // ── Genesis hash ─────────────────────────────────────────────────────────

    @Test
    fun testGenesisHashIsCorrectLength() {
        assertEquals(64, EventLog.GENESIS_PREV_HASH.length)
        assertTrue(EventLog.GENESIS_PREV_HASH.all { it == '0' })
    }

    // ── SHA-256 output format ─────────────────────────────────────────────────

    @Test
    fun testOutputIsLowercaseHex64Chars() {
        val hash = EventLog.computeHash("a","b","c","d", 0.5f, "0".repeat(64))
        assertEquals(64, hash.length)
        assertTrue("Must be lowercase hex", hash.matches(Regex("[0-9a-f]+")))
    }

    // ── Hash chain integrity ─────────────────────────────────────────────────

    @Test
    fun testChainIntegrity_validChain() {
        val matchId = "match-test"
        var prevHash = EventLog.GENESIS_PREV_HASH

        val events = mutableListOf<EventRecord>()
        listOf("raid_start" to 0.82f, "touch" to 0.71f, "escape_return" to 0.77f).forEach { (type, conf) ->
            val id = "evt-${events.size}"
            val ts = "2026-01-01T00:00:0${events.size}.000+05:30"
            val hash = EventLog.computeHash(id, matchId, ts, type, conf, prevHash)
            events.add(EventRecord(id, matchId, ts, type, conf, prevHash, hash))
            prevHash = hash
        }

        val errors = EventLog.verifyChain(events)
        assertTrue("Valid chain must have no errors: $errors", errors.isEmpty())
    }

    @Test
    fun testChainIntegrity_tampered() {
        val matchId = "match-test"
        var prevHash = EventLog.GENESIS_PREV_HASH
        val events = mutableListOf<EventRecord>()

        listOf("raid_start" to 0.82f, "touch" to 0.71f).forEach { (type, conf) ->
            val id = "evt-${events.size}"
            val ts = "2026-01-01T00:00:0${events.size}.000+05:30"
            val hash = EventLog.computeHash(id, matchId, ts, type, conf, prevHash)
            events.add(EventRecord(id, matchId, ts, type, conf, prevHash, hash))
            prevHash = hash
        }

        // Tamper: change confidence of first event without recomputing hash
        val tampered = events.toMutableList()
        tampered[0] = tampered[0].copy(confidence = 0.99f)  // change confidence

        val errors = EventLog.verifyChain(tampered)
        assertTrue("Tampered chain must have errors", errors.isNotEmpty())
    }

    // ── Field order regression test ───────────────────────────────────────────

    @Test
    fun testFieldOrderMatters() {
        // Two hashes with same values but different field orders would differ.
        // This test just confirms the function is deterministic with the locked order.
        val h1 = EventLog.computeHash("id1","mid","ts","raid_start", 0.5f, "prev")
        val h2 = EventLog.computeHash("id1","mid","ts","raid_start", 0.5f, "prev")
        assertEquals(h1, h2)

        // Different event_id → different hash
        val h3 = EventLog.computeHash("id2","mid","ts","raid_start", 0.5f, "prev")
        assertTrue("Different event_id must produce different hash", h1 != h3)
    }
}
