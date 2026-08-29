# NyaayKhel

> **Samsung Solve for Tomorrow 2026 — Sport & Tech | Top 40**
> Turn every phone into the referee's ally, so no grassroots kabaddi match in India goes unrecorded.

---

## What It Does

NyaayKhel is an Android prototype that turns a phone-recorded kabaddi video into a reviewable candidate-event timeline for grassroots tournaments.

**Core pipeline:**
```
Video file / optional camera feed
    → YOLOv8-pose (multi-person keypoint extraction, TFLite, fully on-device)
    → Approximate multi-person tracking + candidate proximity windows
    → Binary touch classifier (trained from manually verified active-learning windows)
    → Candidate touch event log for referee review
    → Referee review status: pending | approved | rejected
    → SHA-256 hash chain + Android Keystore signing (tamper-evident record)
    → Export as signed JSON (tamper-evident reviewed match record)
```

Designed to work **fully offline** once the TFLite models are bundled — no internet connection required at the venue.

---

## Repository Structure

```
NyaayKhel/
├── colab/
│   ├── 00_data_collection.ipynb       # yt-dlp clip downloader
│   ├── 01_pose_extraction_test.ipynb  # YOLOv8-pose smoke test
│   ├── 02_dataset_builder.ipynb       # Keypoints → numpy windows
│   └── 03_train_classifier.ipynb      # GRU/TCN training + TFLite export
├── data/
│   ├── raw/                           # Downloaded clips (gitignored)
│   └── processed/                     # Keypoint windows (gitignored)
├── model/
│   ├── classifier.tflite              # Trained classifier (gitignored if large)
│   └── yolov8n_pose.tflite            # Pose model (download separately)
├── android/                           # Android Studio project
├── docs/
│   ├── model_eval.md                  # Accuracy, confusion matrix, limitations
│   ├── active_learning_touch_detection.md # Touch detection redesign
│   ├── touch_labeling_guide.md        # Label Studio config for seed review
│   ├── qa_defense.md                  # Judge Q&A answers
│   ├── architecture_diagram.png       # Pipeline visual
│   ├── sample_match_record.json       # Example signed JSON output
│   └── backup_demo.mp4                # Recorded demo (gitignored if large)
├── scripts/
│   ├── extract_touch_candidates.py    # Candidate window extraction
│   ├── train_touch_candidate_classifier.py # Binary touch model training
│   └── verify_chain.py                # Hash-chain integrity checker
└── README.md
```

---

## Build Phases

| Phase | Description | Status |
|-------|-------------|--------|
| A | Foundation: env setup, pose extraction test, clip download | 🔄 In Progress |
| B | Model: active-learning touch labels, binary classifier, TFLite export | ⏳ Pending |
| C | Android app: Kotlin + CameraX + TFLite + hash chain | ⏳ Pending |
| D | Demo prep: integration testing, QA defense, backup video | ⏳ Pending |
| E | Optional polish: live camera, athlete cards, dashboard mockup | ⏳ Optional |

---

## Tech Stack

| Layer | Choice | Reason |
|-------|--------|--------|
| Pose extraction | YOLOv8-pose (TFLite) | Multi-person, pretrained, on-device |
| Fallback pose | MediaPipe Pose | Single-person, faster if YOLOv8 too slow |
| Classifier | Binary feature classifier (scikit-learn / Keras → TFLite) | Small data, interpretable, lightweight on-device |
| Training compute | Google Colab free GPU | No local GPU needed |
| Mobile app | Kotlin + CameraX + Room | Native performance on Android |
| Tamper-evidence | SHA-256 hash chain + Android Keystore | Simple, defensible, not overclaimed |

---

## Accuracy Target

**Target: useful candidate touch recall on source-video-held-out review windows.** Report metrics only on manually verified windows, not provisional auto-labels. See `docs/model_eval.md` for the full evaluation writeup.

---

## Sourcing Disclaimer

Training data and demo footage are sourced from publicly available YouTube kabaddi match footage. See `docs/model_eval.md` for details.

---

## Demo-Day Deliverables Checklist

- [ ] `model/touch_candidate_classifier.tflite` + verified-label confusion matrix
- [ ] `docs/model_eval.md`
- [ ] Android APK (video file → reviewable tamper-evident event log)
- [ ] `docs/sample_match_record.json` (signed JSON)
- [ ] `docs/architecture_diagram.png`
- [ ] `docs/backup_demo.mp4`
- [ ] `docs/qa_defense.md`

---

## Key Q&A Defenses

See [`docs/qa_defense.md`](docs/qa_defense.md) for full written answers. Quick summary:

- **"How do you detect contact?"** → Pose, bounding-box proximity, and motion features flag candidate contact windows. Referee verifies using the app and audit trail.
- **"How offline?"** → Both TFLite models run on-device; event log stored in Room (SQLite) locally. Zero network calls during analysis/export.
- **"Isn't this just action recognition?"** → The prototype uses event-specific rules/features, not a broad black-box action classifier.
- **"What's your accuracy?"** → Report only the verified-label test split after active-learning review. Confusion matrix available after training.

---

*Solo builder: Roopal Guha Neogi | B.E. CSE, CMR Institute of Technology, Bengaluru*
