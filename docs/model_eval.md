# NyaayKhel Model Evaluation Report

> Status: template. Fill in the measured values after training the active-learning touch classifier.

## 1. Model Overview

| Property | Value |
|---|---|
| Task | Candidate raider-defender touch detection |
| Output | `touch` vs `not_touch` probability for a short candidate window |
| Candidate generator | YOLOv8-pose + approximate IoU tracking + proximity window extraction |
| Features | box overlap, keypoint distance, hand-torso distance, pose compression, velocity drop, duration |
| Classifier | Logistic regression / small dense model exported to TFLite |
| Input shape | `(1, 7)` feature vector |
| Evaluation data | Manually verified active-learning windows only |
| Inference framing | Referee-assist candidate flag, not final automatic officiating |

## 2. Data Sourcing Full Disclosure

All training and test data is sourced from publicly available YouTube kabaddi match footage.

- Source: YouTube search queries including `kabaddi match full`, `kabaddi tournament district`, `kabaddi raid`, etc.
- No footage from real grassroots tournaments was used yet.
- Demo footage used in the backup demo video is also YouTube-sourced.

This is sufficient for a prototype because the demonstrated capability is the pipeline: pose extraction, candidate window generation, verified-label training, and tamper-evident review export. Production accuracy on real grassroots footage remains future validation work.

## 3. Dataset Statistics

| Property | Value |
|---|---|
| Total clips downloaded | 797 short clips from 25 full videos |
| Candidate windows extracted | [X] |
| Verified labels used | [X] |
| Verified `touch` labels | [X] |
| Verified `not_touch` labels | [X] |
| Skipped unclear windows | [X] |
| Train / test split | Source-video grouped where possible |
| Clip/window duration | Candidate windows around 1-2 seconds |

## 4. Evaluation Results

Report these only on manually verified labels.

| Metric | Value |
|---|---|
| Test accuracy | [X] |
| Touch precision | [X] |
| Touch recall | [X] |
| Touch F1 | [X] |
| ROC AUC | [X] |

Confusion matrix labels:

|  | Predicted `not_touch` | Predicted `touch` |
|---|---:|---:|
| True `not_touch` | [X] | [X] |
| True `touch` | [X] | [X] |

## 5. What The Model Confuses And Why

Expected hard cases:

- Crowded raids where players overlap in the image but do not actually touch.
- Defender-defender overlap near the raider.
- Occluded hand/foot tags.
- Tackles where contact continues longer than the scoring moment.
- Perspective illusions from single-angle footage.

The model should be presented as a candidate contact detector. Final review remains with the referee.

## 6. Leakage Controls

- Do not treat provisional `pre_label` values as ground truth.
- Do not report metrics on auto-labeled or heuristic-labeled windows.
- Keep windows from the same source video grouped where the verified set is large enough.
- Keep one or more source videos aside for final demo sanity checking.

## 7. On-Device Inference Benchmarks

Fill after Android integration.

| Component | Avg latency | Notes |
|---|---:|---|
| YOLOv8-pose frame inference | [X] ms | CPU / NNAPI status: [X] |
| Candidate feature extraction | [X] ms | Tracking/proximity logic |
| Touch classifier | [X] ms | `touch_candidate_classifier.tflite` |

## 8. Known Limitations

1. YouTube-only training data means real grassroots performance is still unmeasured.
2. Single-camera footage cannot reliably resolve all occlusions or depth ambiguities.
3. Foot touches are likely weaker than upper-body grabs/collisions.
4. Team identification is not fully solved in the first implementation.
5. Current Android app still needs the runtime candidate feature extractor wired into the new TFLite classifier.

## 9. Phase 2 Improvements

1. Better tracking with ByteTrack or DeepSORT.
2. Team-color/jersey appearance model.
3. Manual court-line calibration for raid start and escape return.
4. Second-angle phone support for occlusion and depth.
5. Real grassroots validation set.
