# NyaayKhel

> **Samsung Solve for Tomorrow 2026 — Sport & Tech | Top 40**
> Turn every phone into the referee's ally, so no grassroots kabaddi match in India goes unrecorded.

---

## What It Does

NyaayKhel is an Android app that turns any phone camera into a real-time match intelligence system for grassroots kabaddi tournaments.

**Core pipeline:**
```
Video file / Camera feed
    → YOLOv8-pose (multi-person keypoint extraction, TFLite, fully on-device)
    → Sliding-window buffer (1–2 sec of frames)
    → GRU/TCN classifier (trained by us, exported to TFLite)
    → Event log: raid_start | touch | escape_return | neutral
    → SHA-256 hash chain + Android Keystore signing (tamper-evident record)
    → Export as signed JSON (the "verified match record")
```

Works **fully offline** — no internet connection required at the venue.

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
│   ├── qa_defense.md                  # Judge Q&A answers
│   ├── architecture_diagram.png       # Pipeline visual
│   ├── sample_match_record.json       # Example signed JSON output
│   └── backup_demo.mp4                # Recorded demo (gitignored if large)
├── scripts/
│   └── verify_chain.py                # Hash-chain integrity checker
└── README.md
```

---

## Build Phases

| Phase | Description | Status |
|-------|-------------|--------|
| A | Foundation: env setup, pose extraction test, clip download | 🔄 In Progress |
| B | Model: data labeling, GRU/TCN training, TFLite export | ⏳ Pending |
| C | Android app: Kotlin + CameraX + TFLite + hash chain | ⏳ Pending |
| D | Demo prep: integration testing, QA defense, backup video | ⏳ Pending |
| E | Optional polish: live camera, athlete cards, dashboard mockup | ⏳ Optional |

---

## Tech Stack

| Layer | Choice | Reason |
|-------|--------|--------|
| Pose extraction | YOLOv8-pose (TFLite) | Multi-person, pretrained, on-device |
| Fallback pose | MediaPipe Pose | Single-person, faster if YOLOv8 too slow |
| Classifier | GRU / 1D-TCN (PyTorch → TFLite) | Cheap to train, lightweight on-device |
| Training compute | Google Colab free GPU | No local GPU needed |
| Mobile app | Kotlin + CameraX + Room | Native performance on Android |
| Tamper-evidence | SHA-256 hash chain + Android Keystore | Simple, defensible, not overclaimed |

---

## Accuracy Target

**70–80% on held-out test clips.** A documented confusion matrix at this level is more credible under judge questioning than unverified higher claims. See `docs/model_eval.md` for full evaluation writeup.

---

## Sourcing Disclaimer

Training data and demo footage are sourced from publicly available YouTube kabaddi match footage. See `docs/model_eval.md` for details.

---

## Demo-Day Deliverables Checklist

- [ ] `model/classifier.tflite` + confusion matrix PNG
- [ ] `docs/model_eval.md`
- [ ] Android APK (video file → tamper-evident event log)
- [ ] `docs/sample_match_record.json` (signed JSON)
- [ ] `docs/architecture_diagram.png`
- [ ] `docs/backup_demo.mp4`
- [ ] `docs/qa_defense.md`

---

## Key Q&A Defenses

See [`docs/qa_defense.md`](docs/qa_defense.md) for full written answers. Quick summary:

- **"How do you detect contact?"** → Spatial proximity + movement patterns consistent with scoring events. Referee verifies using flagged events and audit trail.
- **"How offline?"** → Both TFLite models run on-device via GPU/NNAPI delegate; event log stored in Room (SQLite) locally. Zero network calls.
- **"Isn't this just action recognition?"** → Pose-sequence classification — fewer parameters, faster on-device, more interpretable, and more honest to what the model actually learns.
- **"What's your accuracy?"** → ~70–80% on held-out test split. Confusion matrix available.

---

*Solo builder: Roopal Guha Neogi | B.E. CSE, CMR Institute of Technology, Bengaluru*
