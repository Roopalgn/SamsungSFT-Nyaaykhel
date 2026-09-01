# NyaayKhel — Judge Brief

## 30-second explanation

NyaayKhel is a video-assisted kabaddi officiating system. It is designed to help referees and tournament organizers review difficult moments—especially possible raider–defender contact—more quickly and consistently.

The product is not intended to replace a referee. The referee remains the decision-maker; NyaayKhel highlights moments worth reviewing and provides evidence from the match video. This can reduce missed touches, reduce disputes, and make smaller tournaments more professional without requiring expensive specialist replay equipment.

## What problem are we solving?

Kabaddi decisions can happen very quickly. A touch may be partially blocked by players, the camera may be distant, and a referee may have only one angle. Disputed decisions slow matches down and can affect trust in the result.

Professional competitions may have multiple camera angles and replay staff. Local tournaments generally do not. NyaayKhel is aimed at a lower-cost, camera-based workflow that can work with ordinary match footage and improve over time.

## What I am building

The intended workflow is:

1. A match video is supplied to the system.
2. The system identifies short moments that may contain player contact or another important event.
3. A referee or reviewer checks the highlighted moments instead of searching through the whole recording.
4. The verified decisions become training data for improving the detector.
5. Later versions can add court-line and raid-state logic to support a broader review assistant.

The first focused capability is touch/contact review. This is deliberately narrower and more defensible than claiming that the system can already understand every kabaddi event.

## What has been completed

### Product and problem work

- Defined the target user as referees, tournament organizers, and review staff for kabaddi matches.
- Chosen a human-in-the-loop product: the model recommends moments; a human confirms the decision.
- Identified a possible business path: affordable video review for local tournaments, academies, and event organizers that cannot justify professional replay infrastructure.
- Reframed the initial scope around a measurable first milestone: finding likely touch/contact moments.

### Data pipeline

- Collected full-length kabaddi videos from YouTube.
- Created short five-second clips with three-second stride and a clip index containing source video, timing, camera bucket, and file path.
- Generated candidate contact windows from the clips using pose detections, player tracking, proximity, overlap, keypoint distance, and motion features.
- Created a separate seed-review batch for human verification.
- Added H.264/yuv420p conversion for the small review batch so clips are browser-compatible with Label Studio.
- Added source balancing controls so one match does not automatically dominate the review set.
- Added resumable per-clip checkpoints to the extraction script. During an active Colab runtime, an interrupted run can resume from its last completed clip.

### Current data status

- The Drive dataset contains downloaded full videos and approximately 1,014 five-second side-view clips.
- The candidate-window pipeline has produced working windows and a Label Studio seed bundle.
- Earlier candidate runs found windows mainly in two source videos. This is useful evidence that camera placement and video quality matter, but it is not enough to claim broad generalization.
- A final local full-scan run is the current data-generation step. Its purpose is to improve source diversity and produce a compact batch for verification—not to create ground-truth labels automatically.

### Labeling

The Label Studio interface asks one focused question:

> Is this raider-defender contact?

The available choices are:

- `touch`
- `not_touch`
- `unclear_skip`

`unclear_skip` is excluded from training. Human labels, not the detector's provisional scores, are treated as ground truth.

### Software

- A Python extraction script is in the repository: `scripts/extract_touch_candidates.py`.
- A binary touch-candidate training script is available: `scripts/train_touch_candidate_classifier.py`.
- The Android project builds and its existing unit tests passed earlier.
- A placeholder Android `TouchFeatureClassifier` class exists, but the new binary model is not yet wired into the complete Android runtime.

## What I am doing now

I am running one final, source-balanced candidate scan on a T4 GPU. The output is written to local Colab storage first because writing every temporary MP4 directly to Google Drive caused severe delays in earlier runs.

The scan is configured to:

- inspect all indexed clips rather than an arbitrary filename slice;
- cap the total candidate windows;
- cap candidates per source video;
- select a small seed review batch;
- save progress after each completed clip;
- produce a browser-compatible ZIP for Label Studio after successful completion.

The practical goal is to obtain a useful, diverse review batch quickly and preserve the result before the Colab runtime expires.

## What remains before calling this a working prototype

### Minimum credible prototype milestone

1. Finish the candidate scan.
2. Inspect the source distribution and remove obviously unusable windows.
3. Human-label a compact seed set in Label Studio.
4. Export the labels and train the binary touch/not-touch classifier.
5. Evaluate it on held-out verified examples and report the result honestly as an early pilot evaluation.
6. Demonstrate the workflow on a short match segment: video in, highlighted candidate moments out, human confirmation in the loop.

That is sufficient for a milestone demonstration. It does not require claiming a production-ready autonomous referee.

### Later engineering work

- Add runtime feature extraction and tracking to the Android application.
- Replace the current placeholder integration with the trained binary model.
- Add court geometry and midline-crossing logic for raid start and escape/return events.
- Add confidence thresholds, abstention, and an audit trail so uncertain cases go to human review.
- Test on multiple camera placements, lighting conditions, tournaments, and devices.
- Measure reviewer time saved, false positives, missed touches, and latency.

## What is already working versus what is not

### Working now

- Video collection and indexing.
- Five-second clip generation.
- Pose-based candidate generation.
- Candidate-window packaging.
- Label Studio review workflow.
- H.264 conversion for browser playback.
- Binary classifier training code.
- A clear human-in-the-loop product workflow.

### Not yet proven

- Robust performance across many different matches and camera angles.
- Production-level touch accuracy.
- Fully automatic referee decisions.
- End-to-end Android inference using the newly trained binary model.
- Reliable raid-start and escape/return event detection.

These are deliberately stated as remaining milestones rather than hidden limitations.

## Honest status for a judge

The project is beyond an idea and beyond a static demo: the data pipeline, candidate detector, review interface, and training path exist. The current stage is an early working prototype of a human-assisted touch-review system.

The next proof point is not “the model is perfect.” It is:

> Given a match video, NyaayKhel can narrow a long recording to a small set of plausible contact moments that a referee can verify quickly.

The evidence currently supports that workflow on selected side-view footage. Broader validation and Android integration are the next steps.

## Suggested answers to likely judge questions

### “Are you replacing referees?”

No. The system is decision support. It prioritizes review moments and preserves human authority, especially when the view is blocked or confidence is low.

### “Why not just use a generic action-recognition model?”

The first version separates the problem by event type. Touch needs player proximity and contact reasoning. Raid start and escape/return depend more on court geometry and line crossing. Treating all of them as one pose-classification problem would be less reliable.

### “Why is human labeling necessary?”

Visual proximity is not always contact. Players can be close without touching, and an occlusion can make a true touch ambiguous. Human verification is needed for trustworthy training data and for a safe product.

### “What makes this commercially useful?”

Local tournaments and academies often lack expensive multi-camera replay systems. A lower-cost video review assistant could be sold as event software, a tournament service, or an academy analytics tool. The value proposition is faster review, fewer disputes, and better evidence—not eliminating officials.

### “What is your current accuracy?”

I will report accuracy only after verified labels and a held-out evaluation exist. The current candidate scores are heuristic/provisional and must not be presented as model accuracy.

### “Why are many clips from one or two sources?”

The current detector is most effective when players are visible at a useful scale, especially in close side-view footage. That exposed a real deployment constraint: camera placement is part of the product requirement. The next data pass explicitly measures and improves source diversity.

### “What can you demonstrate today?”

I can show the pipeline: a match recording is converted into short candidate windows, a reviewer labels those windows in Label Studio, and verified labels are used to train the first touch detector. I can also show the data artifacts and explain which parts are pilot-stage rather than production claims.

## One-sentence status summary

NyaayKhel currently has an early, honest working prototype of a human-in-the-loop kabaddi touch-review pipeline; the remaining work is to verify a diverse seed set, train and evaluate the first binary detector, and then connect that detector to the Android demonstration.
