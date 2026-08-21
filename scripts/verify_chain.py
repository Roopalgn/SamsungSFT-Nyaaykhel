#!/usr/bin/env python3
"""
NyaayKhel — Hash Chain Integrity Verifier
==========================================
Verifies that a signed match record JSON exported by the NyaayKhel Android app
has an intact SHA-256 hash chain.

Usage:
    python scripts/verify_chain.py --input docs/sample_match_record.json

Exit codes:
    0  — chain is intact (all hashes verify)
    1  — chain is broken (tampered or corrupted)
    2  — invalid file / parse error
"""

import argparse
import hashlib
import json
import sys
from pathlib import Path


# These fields are included when computing each event's hash.
# Must match the Android app's EventLog.kt computeHash() logic exactly.
HASH_FIELDS = ['event_id', 'match_id', 'timestamp', 'event_type', 'confidence', 'prev_hash']

GENESIS_PREV_HASH = '0' * 64  # First event's prev_hash sentinel value


def compute_event_hash(event: dict) -> str:
    """
    Reproduce the Android app's SHA-256 hash for a single event record.
    Concatenates HASH_FIELDS values in order, then SHA-256s the result.
    """
    content = ''.join(str(event.get(field, '')) for field in HASH_FIELDS)
    return hashlib.sha256(content.encode('utf-8')).hexdigest()


def verify_chain(match_record: dict) -> tuple[bool, list[str]]:
    """
    Verify the hash chain in a match record.

    Returns:
        (is_valid: bool, issues: list[str])
        If is_valid is True, issues is empty.
        If is_valid is False, issues contains descriptions of each failure.
    """
    events = match_record.get('events', [])
    issues = []

    if not events:
        issues.append('No events in record — nothing to verify.')
        return False, issues

    prev_hash = GENESIS_PREV_HASH

    for i, event in enumerate(events):
        event_id = event.get('event_id', f'index_{i}')
        stored_hash = event.get('hash', '')
        stored_prev_hash = event.get('prev_hash', '')

        # 1. Check prev_hash linkage
        if stored_prev_hash != prev_hash:
            issues.append(
                f"Event {i} ({event_id}): prev_hash mismatch.\n"
                f"  Expected: {prev_hash}\n"
                f"  Stored:   {stored_prev_hash}"
            )

        # 2. Recompute hash and compare
        recomputed = compute_event_hash(event)
        if recomputed != stored_hash:
            issues.append(
                f"Event {i} ({event_id}): hash mismatch (event data may have been altered).\n"
                f"  Recomputed: {recomputed}\n"
                f"  Stored:     {stored_hash}"
            )

        prev_hash = stored_hash  # advance chain

    # 3. Check terminal hash matches record-level field
    terminal_hash_in_record = match_record.get('terminal_hash', '')
    if terminal_hash_in_record and terminal_hash_in_record != prev_hash:
        issues.append(
            f"terminal_hash mismatch.\n"
            f"  Expected (last event hash): {prev_hash}\n"
            f"  Stored in record:           {terminal_hash_in_record}"
        )

    return len(issues) == 0, issues


def main():
    parser = argparse.ArgumentParser(
        description='Verify the SHA-256 hash chain integrity of a NyaayKhel match record.'
    )
    parser.add_argument(
        '--input', '-i',
        required=True,
        type=Path,
        help='Path to the exported match record JSON file.'
    )
    parser.add_argument(
        '--verbose', '-v',
        action='store_true',
        help='Print each event as it is verified.'
    )
    args = parser.parse_args()

    # Load file
    if not args.input.exists():
        print(f'ERROR: File not found: {args.input}', file=sys.stderr)
        sys.exit(2)

    try:
        match_record = json.loads(args.input.read_text(encoding='utf-8'))
    except json.JSONDecodeError as e:
        print(f'ERROR: Invalid JSON: {e}', file=sys.stderr)
        sys.exit(2)

    # Print header
    match_id = match_record.get('match_id', 'unknown')
    exported_at = match_record.get('exported_at', 'unknown')
    n_events = len(match_record.get('events', []))

    print(f'NyaayKhel Hash Chain Verifier')
    print(f'{"=" * 50}')
    print(f'Match ID:    {match_id}')
    print(f'Exported at: {exported_at}')
    print(f'Events:      {n_events}')
    print()

    if args.verbose:
        print('Verifying events:')
        for i, ev in enumerate(match_record.get('events', [])):
            recomputed = compute_event_hash(ev)
            stored = ev.get('hash', '')
            status = '✓' if recomputed == stored else '✗'
            print(f'  [{i:03d}] {status} {ev.get("event_type","?"):15s} conf={ev.get("confidence","?"):.2f}  hash={stored[:16]}...')
        print()

    # Verify
    is_valid, issues = verify_chain(match_record)

    if is_valid:
        print(f'✅  CHAIN INTACT — all {n_events} event hashes verify correctly.')
        print(f'    The record has not been tampered with since export.')
        print()
        print('NOTE: This verifies hash-chain integrity only.')
        print('      Signature verification (Android Keystore) requires the device public key')
        print('      and is not performed by this script.')
        sys.exit(0)
    else:
        print(f'❌  CHAIN BROKEN — {len(issues)} issue(s) found:')
        print()
        for issue in issues:
            print(f'  • {issue}')
        print()
        print('The record may have been altered after export.')
        sys.exit(1)


if __name__ == '__main__':
    main()
