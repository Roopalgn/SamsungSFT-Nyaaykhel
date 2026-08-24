# NyaayKhel — Model Evaluation Report

> **Status: Template** — fill in with real numbers after Phase B training is complete.
> All [PLACEHOLDER] markers must be replaced before demo day.

---

## 1. Model Overview

| Property | Value |
|---|---|
| Task | Pose-sequence classification for candidate kabaddi event detection |
| Classes | `raid_start`, `touch`, `escape_return`, `neutral` |
| Input | Sliding window of 30 frames × (2 players × 17 keypoints × 3 values) = shape (30, 102) |
| Architecture | 2-layer GRU, hidden_dim=128 (or 1D-TCN — whichever performed better in training) |
| Parameters | ~[X]k parameters |
| Training framework | PyTorch |
| Export | ONNX → TFLite float32 |
| Inference speed (Colab GPU) | ~[X] ms/window |
| Inference speed (target device CPU, 10fps sampling) | ~[X] ms/window |

---

## 2. Data Sourcing — Full Disclosure

**All training and test data is sourced from publicly available YouTube kabaddi match footage.**

- Source: YouTube search queries including `"kabaddi match full"`, `"kabaddi tournament district"`, `"kabaddi raid"`, etc.
- No footage from real grassroots tournaments was used. No footage was obtained from local contacts, clubs, coaches, or NYKS.
- Demo footage used in the backup demo video is also YouTube-sourced.

This is the honest, complete statement of data sourcing. It is stated here and in `docs/qa_defense.md` so it does not need to be hidden or worked around.

**Why this is sufficient for a prototype:** The ML pipeline — pose extraction, sliding window, GRU/TCN classification — is functionally identical whether trained on YouTube footage or real grassroots footage. The capability being demonstrated is the pipeline architecture, not production-grade accuracy on a specific distribution. Real-world accuracy validation is Phase 1 deployment work.

---

## 3. Dataset Statistics

| Property | Value |
|---|---|
| Total clips downloaded | [X] |
| Total clips after quality filtering | [X] |
| Clips labeled | [X] |
| Train / test split | Source-video-level split: train / test / one untouched demo video |
| Clip duration | 5 seconds each |
| Angle distribution | ~[X]% side (~90°), ~[X]% quarter (~70°), ~[X]% angled (~60°) |

### Class distribution (labeled clips)

| Class | Train clips | Test clips |
|---|---|---|
| `raid_start` | [X] | [X] |
| `touch` | [X] | [X] |
| `escape_return` | [X] | [X] |
| `neutral` | [X] | [X] |
| **Total** | **[X]** | **[X]** |

---

## 4. Evaluation Results

### Overall Accuracy

**Test set accuracy: [X]%** (at confidence threshold 0.65)

_Computed on held-out source videos. No `source_video_id` appears in more than one of train, test, or demo._

### Per-Class Metrics

| Class | Precision | Recall | F1 |
|---|---|---|---|
| `raid_start` | [X] | [X] | [X] |
| `touch` | [X] | [X] | [X] |
| `escape_return` | [X] | [X] | [X] |
| `neutral` | [X] | [X] | [X] |

### Confusion Matrix

![Confusion Matrix](confusion_matrix.png)

_[Replace this placeholder with the actual confusion matrix image after Phase B training]_

---

## 5. What the Model Confuses and Why

_[Fill in after training — this section is critical for judge credibility. Be specific.]_

**Most common confusions (preliminary expectations):**

- `touch` ↔ `neutral` — near-miss raids where the raider enters the defensive zone without contact. The spatial proximity signal is similar; only timing and player reaction distinguish them.
- `raid_start` ↔ `neutral` — the transition frame when a raider first crosses the mid-line. The model may label this neutral until the movement pattern is established across enough frames in the window.

**Mitigation for Phase 2:**
- Second camera angle to improve depth estimation (the primary source of touch/neutral confusion)
- Longer window size (60 frames) for better temporal context at the start of a raid

---

## 6. Angle Robustness

The model was trained on footage from approximately [X]°–[X]° viewing angles.

Expected accuracy degradation outside this range:

| Angle range | Accuracy (estimated) |
|---|---|
| ~90° (pure side view) | ~[X]% |
| ~70° (quarter view) | ~[X]% |
| ~60° (angled) | ~[X]% |
| <50° (near-frontal) | Significant degradation — not trained |

**Phase 2 fix:** Include near-frontal footage in training set; add second-angle stream.

---

## 7. On-Device Inference Benchmarks

_[Fill in after Phase C Android app testing]_

Test device: [Device model] (8GB RAM — **NOT the target low-end device**. See `qa_defense.md` for explanation of this limitation.)

| Sampling setting | Effective fps processed | Avg inference (ms/window) | Notes |
|---|---|---|---|
| Target video sampling | 10fps | [X]ms | Must match notebook 02 `TARGET_FPS` |

**Low-end device target (≤4GB RAM, no GPU delegate):** CPU-only inference at 10fps. Validation pending; if this is too slow, retrain and runtime sampling must change together.

---

## 8. Known Limitations

1. **YouTube-only training data** — accuracy on real grassroots footage (different lighting, camera quality, viewing angles) is unknown. Expected to degrade; Phase 1 includes real-world validation.
2. **Test device is not low-end** — inference benchmarks are on an 8GB RAM phone. Low-end device performance is projected from Colab CPU benchmarks; not measured directly.
3. **Single-angle only** — occlusion is a fundamental limitation of single-camera capture.
4. **No contact ground truth** — the model detects spatial proximity and movement patterns, not physical contact. The `touch` class should be presented as candidate contact flagged for review.
5. **Fixed window size** — the 30-frame window assumes 10fps processing, i.e. about 3 seconds of video. Slower inference changes the temporal context and requires retraining.

---

## 9. Phase 2 Improvements

1. Multi-angle fusion (second phone at complementary angle) → stereo depth estimation
2. Larger training dataset including real grassroots footage
3. Low-end device hardware validation (≤4GB RAM, no NNAPI support)
4. Per-player tracking using bounding-box IDs → better raider identification across frames
5. Extended window size (60 frames) for better temporal context
