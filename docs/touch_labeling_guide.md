# Touch Candidate Labeling Guide

Use this guide for the active-learning touch detector.

The current design is:

- `raid_start` and `escape_return` are handled later by court-line / movement rules.
- ML learns only whether a short candidate window contains visible raider-defender contact.
- You do not label every normal moment as neutral.
- Auto-generated labels are provisional only. Ground truth comes from your verified review set.

## Step 1: Generate Candidate Windows

Run this in Colab after `00_data_collection.ipynb` has created `clip_index.csv` and clips:

```bash
pip install ultralytics opencv-python
python scripts/extract_touch_candidates.py \
  --base-dir /content/drive/MyDrive/NyaayKhel \
  --max-windows 800 \
  --seed-size 50
```

The script writes:

- `data/processed/touch_active_learning/windows/*.mp4`
- `data/processed/touch_active_learning/touch_candidate_features.csv`
- `data/processed/touch_active_learning/seed_review.csv`
- `data/processed/touch_active_learning/label_studio_seed_import.json`
- `data/processed/touch_active_learning/summary.json`

## Step 2: Label Only The Seed Windows

Import `label_studio_seed_import.json` or upload the `windows/*.mp4` seed files into a new Label Studio project.

Paste this into Settings -> Labeling Interface -> Code:

```xml
<View>
  <Header value="Is this raider-defender contact?" />
  <Video name="video" value="$video" />

  <Choices name="verified_label" toName="video" choice="single" showInline="true">
    <Choice value="touch" />
    <Choice value="not_touch" />
    <Choice value="unclear_skip" />
  </Choices>
</View>
```

If Label Studio imported the field as `$data` instead of `$video`, change `value="$video"` to `value="$data"`.

## Labeling Rule

Choose `touch` when the raider and a defender visibly make body contact, including a tag, grab, block, collision, or tackle attempt.

Choose `not_touch` when the window is only player proximity, defender-defender contact, same-team overlap, no visible contact, referee/crowd motion, or normal movement near the raider.

Choose `unclear_skip` when the video is too occluded, too blurry, or too ambiguous to use as ground truth.

Do not mark `touch` just because boxes overlap. The point of this review set is to teach the classifier the difference between real contact and crowded false positives.

## What To Export

Export Label Studio annotations as JSON and save them under:

```text
data/processed/touch_active_learning/
```

Suggested filename:

```text
seed_review_labels.json
```

After the first 30-50 verified windows, train the first binary model and use active learning to choose the next most useful batch.

For a small early seed set (at least 10 usable labels with both classes), the
training script also provides `--pilot-mode`. It fits a demonstration-only
artifact and deliberately does **not** calculate or report held-out accuracy.
Use it to demonstrate the end-to-end workflow; collect a larger, more diverse
set before making any performance claim.
