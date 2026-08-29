# Active-Learning Touch Detection Plan

## Validity Check

This direction is valid and stronger than the original 4-class pose-only plan.

The important change is that the model no longer pretends that every event is a pose-classification problem:

- `raid_start`: detect by player/court position crossing the midline.
- `escape_return`: detect by returning across the midline.
- `touch`: detect from a short candidate window using proximity, pose, and motion features.

That makes the pitch more defensible. The app flags candidate contact events for referee review instead of claiming fully automatic officiating.

## What Agents Can Automate

The heavy work should be automated:

- run pose detection over all clips;
- track visible players approximately;
- find player-pair proximity windows;
- extract short MP4 review windows;
- compute contact features;
- pre-rank likely positives, likely negatives, and uncertain examples;
- train the binary classifier from verified labels;
- choose the next high-value review batch.

The human work should be reduced to reviewing 30-50 short windows at a time, not labeling hundreds of full clips.

## What Cannot Be Removed

Some verified labels are still necessary. If all labels come from rules or a vision model, the system only learns the rules/model's mistakes and the evaluation becomes circular.

Do not report metrics on provisional labels. Report metrics only on manually verified windows.

## Current Implementation

Generate candidate review windows:

```bash
pip install ultralytics opencv-python
python scripts/extract_touch_candidates.py \
  --base-dir /content/drive/MyDrive/NyaayKhel \
  --max-windows 800 \
  --seed-size 50
```

Train after exporting verified Label Studio labels:

```bash
pip install scikit-learn joblib tensorflow
python scripts/train_touch_candidate_classifier.py \
  --base-dir /content/drive/MyDrive/NyaayKhel \
  --labels /content/drive/MyDrive/NyaayKhel/data/processed/touch_active_learning/seed_review_labels.json
```

## Outputs

Candidate extraction writes:

- `data/processed/touch_active_learning/windows/*.mp4`
- `data/processed/touch_active_learning/touch_candidate_features.csv`
- `data/processed/touch_active_learning/seed_review.csv`
- `data/processed/touch_active_learning/label_studio_seed_import.json`
- `data/processed/touch_active_learning/summary.json`

Training writes:

- `data/processed/touch_active_learning/model/touch_candidate_classifier.joblib`
- `data/processed/touch_active_learning/model/touch_candidate_classifier.tflite`
- `data/processed/touch_active_learning/model/verified_eval_metrics.json`
- `data/processed/touch_active_learning/model/next_review_batch.csv`

## Known Gaps

- The current extractor uses simple IoU tracking, not ByteTrack. This is enough for fast iteration but less stable in occlusion.
- Team identification is not solved yet. The first model treats all close player pairs as candidates and lets verified labels teach real contact versus false positives.
- Foot touches and heavy occlusion remain weak for a single camera.
- Android still has the old 4-class classifier path. The next integration step is to add the feature-vector touch classifier and event logging for `touch` candidates only.

## Pitch Framing

Use:

> NyaayKhel uses on-device pose detection and motion features to flag likely raider-defender contact moments for referee review, then stores reviewed events in a tamper-evident match record.

Avoid:

> NyaayKhel automatically decides all kabaddi scoring events.
