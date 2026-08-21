# NyaayKhel — Hash Chain Canonical Specification

**Version:** 1.0  
**Status:** LOCKED — do not change without updating both `scripts/verify_chain.py` AND `android/.../data/EventLog.kt` simultaneously.

This document is the single source of truth for how event hashes are computed. Any divergence between the Python verifier and the Kotlin app will cause `verify_chain.py` to flag legitimate records as tampered.

---

## 1. Fields Included in Hash Computation

The hash is computed over exactly these fields, **in this order**:

| # | Field name (JSON key) | Type | Notes |
|---|---|---|---|
| 1 | `event_id` | String (UUID) | e.g. `"evt-0001"` |
| 2 | `match_id` | String (UUID) | Same for all events in a match |
| 3 | `timestamp` | String (ISO 8601) | `"2026-08-21T16:00:05.123+05:30"` — stored as-is, not normalised |
| 4 | `event_type` | String (enum) | One of `raid_start`, `touch`, `escape_return`, `neutral` |
| 5 | `confidence` | **String (formatted float)** | See §2 — must use `"%.6f"` formatting in both Python and Kotlin |
| 6 | `prev_hash` | String (hex) | SHA-256 hex of previous event, or 64 zeros for genesis |

**Fields NOT included:** `hash` itself (self-referential), `device_public_key`, `terminal_hash`, `signature`, any future metadata fields.

---

## 2. Confidence Float Formatting — Critical

`confidence` is a `Float`/`float` value (e.g. `0.84`) that must be converted to a string for hashing. Naive `.toString()` in Kotlin and `str()` in Python can produce different representations for the same value (e.g. scientific notation, trailing zeros, precision differences).

**Canonical format: `"%.6f"` — always 6 decimal places, no scientific notation.**

| Value | Canonical string | Python expression | Kotlin expression |
|---|---|---|---|
| 0.84 | `"0.840000"` | `f"{confidence:.6f}"` | `"%.6f".format(confidence)` |
| 0.7 | `"0.700000"` | `f"{confidence:.6f}"` | `"%.6f".format(confidence)` |
| 1.0 | `"1.000000"` | `f"{confidence:.6f}"` | `"%.6f".format(confidence)` |
| 0.123456789 | `"0.123457"` | `f"{confidence:.6f}"` | `"%.6f".format(confidence)` |

> [!CAUTION]
> The JSON stores confidence as a bare number (`0.84`), NOT as the formatted string. The formatting is applied **only during hash computation**, not in storage or display. Do not store `"0.840000"` in JSON — store `0.84`.

---

## 3. Concatenation Rule

Fields are concatenated **with no separator** between them. The hash input string is:

```
{event_id}{match_id}{timestamp}{event_type}{confidence_formatted}{prev_hash}
```

Example (genesis event):
```
event_id  = "evt-0001"
match_id  = "a3f7c291-4e82-4d1b-b6a3-8c5d9e0f1234"
timestamp = "2026-08-21T16:00:05.123+05:30"
event_type = "raid_start"
confidence = 0.84  →  formatted: "0.840000"
prev_hash = "0000000000000000000000000000000000000000000000000000000000000000"

hash_input = "evt-0001a3f7c291-4e82-4d1b-b6a3-8c5d9e0f12342026-08-21T16:00:05.123+05:30raid_start0.8400000000000000000000000000000000000000000000000000000000000000000000000000"
```

---

## 4. Hash Algorithm

- **Algorithm:** SHA-256
- **String encoding:** UTF-8
- **Output format:** lowercase hex, 64 characters (no prefix)

Python:
```python
import hashlib
digest = hashlib.sha256(hash_input.encode('utf-8')).hexdigest()
```

Kotlin:
```kotlin
import java.security.MessageDigest
val digest = MessageDigest.getInstance("SHA-256")
    .digest(hashInput.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
```

---

## 5. Genesis Event (First Event in a Chain)

The first event's `prev_hash` is always 64 ASCII zero characters:
```
"0000000000000000000000000000000000000000000000000000000000000000"
```

This is a sentinel value — it does not correspond to any real event hash.

---

## 6. Terminal Hash

After all events are written, the `terminal_hash` field in the match record root is set to the `hash` value of the **last event** in the chain. This is then signed by Android Keystore.

`terminal_hash` = `events[last].hash`

---

## 7. Conformance Test Vector

Use this test vector to verify that your Python and Kotlin implementations agree:

**Input:**
```
event_id   = "test-evt-001"
match_id   = "test-match-001"
timestamp  = "2026-01-01T00:00:00.000+05:30"
event_type = "raid_start"
confidence = 0.75   (formatted: "0.750000")
prev_hash  = "0000000000000000000000000000000000000000000000000000000000000000"
```

**Concatenated string (UTF-8):**
```
test-evt-001test-match-0012026-01-01T00:00:00.000+05:30raid_start0.7500000000000000000000000000000000000000000000000000000000000000000000000000
```

**Expected SHA-256 (lowercase hex):**
```
5e77951ee410132a7b635c8fef71b49ab631c6b156f454a26b7ef3b51d5c71bc
```

Verified: computed by `scripts/hash_spec_test.py` on 2026-08-21.

> [!IMPORTANT]
> Run `scripts/hash_spec_test.py` once (Python) and record the output hash here. Then write a Kotlin unit test that produces the same value. If they match, both implementations are conformant.

---

## 8. Change Policy

If the field list, order, formatting, or encoding ever needs to change:
1. Bump the spec version
2. Update this document
3. Update `scripts/verify_chain.py` `HASH_FIELDS` and confidence formatting simultaneously
4. Update `android/.../data/EventLog.kt` `computeHash()` simultaneously
5. Add a `hash_spec_version` field to exported match records so old records can be verified with the old spec
