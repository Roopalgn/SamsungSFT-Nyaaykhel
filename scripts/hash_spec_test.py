#!/usr/bin/env python3
"""
NyaayKhel — Hash Spec Conformance Test
=======================================
Computes the canonical test vector hash defined in docs/hash_chain_spec.md §7.

Run this in Python to get the expected hash value, then write a matching
Kotlin unit test in the Android project to confirm both sides agree.

Usage:
    python scripts/hash_spec_test.py
"""

import hashlib

# ── Test vector inputs (from docs/hash_chain_spec.md §7) ──────────────────────
EVENT_ID   = "test-evt-001"
MATCH_ID   = "test-match-001"
TIMESTAMP  = "2026-01-01T00:00:00.000+05:30"
EVENT_TYPE = "raid_start"
CONFIDENCE = 0.75          # stored as float; formatted as "%.6f" → "0.750000"
PREV_HASH  = "0" * 64      # genesis sentinel

# ── Canonical computation ──────────────────────────────────────────────────────
confidence_str = "%.6f" % CONFIDENCE
hash_input = EVENT_ID + MATCH_ID + TIMESTAMP + EVENT_TYPE + confidence_str + PREV_HASH
expected_hash = hashlib.sha256(hash_input.encode('utf-8')).hexdigest()

print("NyaayKhel — Hash Spec Conformance Test Vector")
print("=" * 60)
print(f"event_id:         {EVENT_ID!r}")
print(f"match_id:         {MATCH_ID!r}")
print(f"timestamp:        {TIMESTAMP!r}")
print(f"event_type:       {EVENT_TYPE!r}")
print(f"confidence:       {CONFIDENCE}  →  formatted: {confidence_str!r}")
print(f"prev_hash:        {'0'*16}...  (64 zeros)")
print()
print(f"hash_input (repr): {hash_input!r}")
print()
print(f"SHA-256 (hex):    {expected_hash}")
print()
print("Copy this SHA-256 value into:")
print("  1. docs/hash_chain_spec.md §7 'Expected SHA-256' field")
print("  2. Android EventLogTest.kt conformance unit test")
print()
print("If the Kotlin unit test produces the same hex string → implementations match.")
