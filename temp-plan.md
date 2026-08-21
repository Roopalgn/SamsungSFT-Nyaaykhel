NyaayKhel — Agent Briefing (Immediate Action)

Quick reference for the agent building the prototype

What you're building

A working prototype that flags candidate kabaddi scoring events (raid start, touch, escape/return) from a phone camera view and produces a tamper-evident match record. Not a production system, referee replacement, or multi-camera setup.

Core pipeline:

Video (file or camera) 
  → YOLOv8-pose extraction (keypoints)
  → GRU/TCN classifier (trained on labeled clips)
  → Event log (with timestamps + confidence)
  → Hash-chain + signing
  → Export as JSON

Accuracy target: 70–80% (honest + defensible). Include a confusion matrix.

Constraints & scope
Single side-angle view only (~90° or 60–90° if training data includes angle variety).
Kabaddi only (wrestling/kho-kho are roadmap, not code).
Event detection only — athlete performance cards and federation dashboards are Phase 2 (optional mock-ups if time allows).
Frame at 5–10 fps for classification if on-device speed is tight — raid events span multiple frames.
No multi-camera, no live production backend, no ground-truth contact claims.
Tech stack
Layer	Choice
Pose extraction	YOLOv8-pose (multi-person, TFLite export)
Fallback	MediaPipe Pose (single-person, if YOLOv8 too slow)
Classifier	GRU or 1D-CNN/TCN (PyTorch, ~100–200k params)
Training	Google Colab GPU (free tier)
On-device runtime	TensorFlow Lite (GPU/NNAPI delegate)
Mobile app	Kotlin + CameraX + Room (SQLite)
Annotation	CVAT or Label Studio
Tamper-evidence	SHA-256 hash chain + Android Keystore signing
Version control	GitHub private repo
Tasks (priority order)
1. Foundation (unblock development)
 Python env + Colab notebook + GitHub repo
 Run YOLOv8-pose on a sample online kabaddi clip (confirm keypoints extract)
 Download ~150–300 short clips (2–5 sec each) from YouTube, side-angle footage, include angle variety (~60–90°)

Exit: Pose extraction pipeline working, clips ready to label

2. Data & model
 Label clips in CVAT: raid_start, touch, escape_return, neutral
 Build keypoint dataset (fixed-length windows, normalized coordinates)
 Train GRU/TCN in PyTorch on Colab; hold out ~20% for testing
 Export to TFLite; check inference speed on laptop (~100–200ms per window is fine)
 Save confusion matrix + write one-page eval (what it confuses, why)

Exit: .tflite model + confusion matrix doc

3. Android app (core)
 Kotlin + CameraX setup; prioritize "load video file" mode first
 Integrate YOLOv8-pose (TFLite) + your classifier; log events with timestamp + confidence
 Implement SHA-256 hash-chain event log (each event's hash includes previous hash)
 Sign final chain with Android Keystore keypair
 UI: show event list + export-to-JSON button
 Test on real low-end Android device (not emulator)

Exit: Working app on real phone, outputs tamper-evident event log

4. Polish (optional, if time)
 Add live camera mode (optional — video file is primary)
 Athlete performance cards (aggregate events into stats)
 Federation dashboard mockup (reads exported JSON)
 Record backup demo video (in case live demo fails)
 One-page architecture diagram

Exit: Demo-ready materials

Footage sourcing

For training (all code): Public YouTube kabaddi match footage is fine. Search "kabaddi match full" or "kabaddi tournament."

For demo (judge-facing):

Best: Get one real clip (~30 sec) from a local contact (coach, club, NYKS) and source it side-angle. Use that in the demo.
Fallback: Use online footage, but note in your writeup: "trained on public footage, demo tested on [source]."

Either is acceptable — the pipeline works the same. The technical demo is what matters.

Key Q&A defenses (update after you build)
"How do you detect actual contact?" → "We detect spatial proximity + movement patterns consistent with contact events at 70–80% accuracy. The referee verifies using the flagged events and audit trail. Phase 2 adds multi-angle views for stereo contact verification."
"Why only kabaddi?" → "Largest grassroots base, most event-rich sport. Wrestling & kho-kho follow in Phase 2."
"What about occlusions?" → "Single-angle has blind spots (defender's back, off-frame contact). Phase 2 adds multiple angles."
**"How offline?" → "YOLOv8-pose + classifier both run as TFLite on-device; event log stored in SQLite locally."
"Isn't this just action recognition?" → "It's pose-sequence classification — cheaper to train (fewer data), faster on low-end devices, more interpretable."
Deliverables for judges
Working APK (runs on low-end Android)
Backup demo video (recorded proof)
Confusion matrix + one-page model eval
Sample exported match record (signed JSON)
One-page architecture diagram
Rehearsed Q&A answers tied to actual build
If things break (priority fixes)
Model too slow on device → Reduce to 5–10 fps, or crop to raider + defender only
Not enough labeled data → Quality > quantity. 150 clips is enough if labeled carefully.
Live camera unreliable → Use "load video file" as primary demo; live is bonus
Can't get angle variety in training → Document that training is ~90° side-view only; expect accuracy drop at other angles (this becomes Phase 2)
Before you ship
Commit code with clean history (shows real work)
Test on a real phone, not emulator
Have confusion matrix + eval writeup ready
Record backup video before judge day
Rehearse Q&A against actual code (not deck language)

NyaayKhel — Prototype Build Plan

Samsung Solve for Tomorrow 2026 — Top 40, Sport & Tech theme Prepared: Aug 21, 2026

0. Ground rules for this plan
Hero sport: kabaddi only. Everything builds for kabaddi. Wrestling/kho-kho stay as roadmap slides, not code.
Single side-angle view only. No multi-camera fusion. Film or source footage from a consistent ~90° side angle (or range ~60–90° if your training data includes angle variety). This matches your pitch's "one phone camera" positioning and avoids unnecessary scope creep.
One hero feature: flag candidate scoring events for referee verification + produce a tamper-evident match record. The AI detects high-confidence spatial interactions consistent with scoring events, not ground-truth contact. This reframing is honest, defensible under Q&A, and positions the product as a referee's tool, not a referee replacement.
Performance cards and dashboards are Phase E (optional stretch), not core demo. Focus core build on event detection + event log.
Accuracy ~70–80% is honest and credible. A documented confusion matrix at that level survives judge questioning far better than an unverifiable higher claim. Include what the model confuses and why (e.g., near-miss vs. touch) — this becomes part of your Phase 2 narrative.
No hard deadline. This is a backlog, not a sprint. Prioritize foundation + core app tasks; polish/Phase E tasks are optional.
1. The build target, precisely

Working Prototype (no deadline pressure):

A side-angle phone camera view of a kabaddi match (live input or pre-recorded clip).
An on-device pipeline extracts player pose keypoints frame-by-frame.
A lightweight temporal classifier reads the keypoint sequence and labels segments: raid_start, touch, escape/return, neutral.
Each detected event (with confidence score) is written to a local event log.
Each event is hashed and chained to the previous event's hash (tamper-evident record, not blockchain — see §6).
The app displays a human-readable match record and can export it as signed JSON.
Optional Phase E additions (time permitting): athlete performance cards aggregated from event logs, basic federation dashboard reading exported JSON.

Core framing: The AI flags candidate scoring events for referee verification, not definitive contact. Accuracy ~70–80% is honest and defensible. The value is the audit trail + event flags for human review, not referee replacement.

Deliberately excluded from core demo: full-match understanding, multi-camera/multi-angle fusion, live production backend, or claim of ground-truth contact detection.

2. Why this architecture (pose-first, not raw-video action recognition)

Full video action-recognition models (I3D, SlowFast, VideoMAE) need large labeled video datasets and heavy compute — wrong choice for a solo build in 10 days, and too heavy for low-end Android regardless.

Instead: pose extraction → temporal classifier on keypoints.

Cuts the input from raw pixels to a small skeleton (a few dozen numbers per person per frame) — orders of magnitude less data needed to train something that works.
The pose extractor itself is pretrained and free (MediaPipe / YOLOv8-pose) — you're not training that part.
You only need to train a small classifier on top, which is realistic with a few hundred labeled clips.
This is also the honest story for judges: "we use a pretrained pose model plus a lightweight classifier we trained ourselves" is a specific, defensible technical claim — much stronger under Q&A than a vague "our AI detects everything."
3. Tech stack
Layer	Choice	Why
Pose extraction	YOLOv8-pose (Ultralytics)	Multi-person keypoint detection out of the box — kabaddi always has multiple players in frame, and MediaPipe Pose is single-person. Exports cleanly to TFLite/ONNX.
Fallback pose option	MediaPipe Pose (single-person)	If multi-person proves too slow on low-end hardware, crop to raider vs. nearest defender and run this instead — simpler, faster, still on-device.
Temporal classifier	Small GRU or 1D-CNN/TCN over keypoint sequences (train in PyTorch, export via ONNX → TFLite)	Cheap to train, cheap to run, appropriate for a few-hundred-clip dataset. Avoid transformers here — overkill and harder to get working with little data.
Training compute	Google Colab (free GPU tier)	No local GPU needed; sufficient for this model size.
On-device runtime	TensorFlow Lite, GPU/NNAPI delegate	Matches the "offline, low-end Android" claim directly; this is the same runtime family judges will assume if they ask "how does offline inference actually work."
Mobile app	Kotlin + CameraX + Room (SQLite)	Native gives you real control over camera frame rate and TFLite performance on low-end devices — matters for your specific claim. Flutter is a fallback if Kotlin ramp-up eats too much time, but native is the stronger demo story.
Annotation tool	CVAT (self-hosted, free) or Label Studio	Frame/segment labeling for your action classes.
Tamper-evidence	SHA-256 hash chain + Android Keystore signing	See §6 — deliberately simple and honestly scoped.
Version control	Git/GitHub (private repo)	Standard; also gives you a clean commit history you can point to as evidence of real work if asked.
4. Data collection & labeling

Footage sourcing strategy:

Primary source for training: Public online kabaddi match footage (YouTube, sports archives, etc.). This unblocks model development immediately and is perfectly valid for training — judges understand publicly available data is used in ML practice.
Optional for judge credibility: If you can reach out to one local contact (CMR sports dept, a coach, or NYKS connection) and get 1–2 short video clips (~30 seconds, side angle) from a real grassroots match, use that in the final demo. You don't need to film it yourself; a contact providing footage is enough to show ecosystem engagement. If you get this, use it as your primary demo video. If not, proceed with online footage only.
How to source online footage: Search YouTube for "kabaddi match full" or "kabaddi tournament" — most have extended footage with multiple raids visible. Download clips at side angles (~90° or 60–70°).

Angle robustness: Include footage from a range of viewing angles in your training set (straight side ~90°, quarter-angles ~60–70°, etc.). This teaches the classifier to tolerate real-world angle variation. Document in your eval writeup which angle ranges your training covers and any accuracy degradation you observe beyond those ranges.

Labeling target: 150–300 short clips (2–5 seconds each) across 4 classes: raid_start, touch, escape_return, neutral. Label in CVAT or Label Studio. A documented confusion matrix at 70–80% accuracy is more credible than unverifiable claims of higher accuracy. Don't chase perfection; honest numbers + clear reasoning survive judge questions.

5. Task breakdown (no deadline pressure — unordered backlog)

Foundation tasks (do first):

 Set up Python env, Colab notebook, GitHub repo.
 Run YOLOv8-pose on a sample online kabaddi clip — confirm multi-person keypoints extract cleanly.
 Identify and download 150–300 short clips from online sources, ensuring a range of angles (~90° to ~60–70°).
Exit: You can run a video through pose extraction and get a keypoint sequence out the other end.

Model development tasks:

 Label all clips in CVAT across 4 classes: raid_start, touch, escape_return, neutral.
 Build the keypoint-sequence dataset (fixed-length windows, normalized coordinates).
 Train a small GRU or 1D-CNN/TCN classifier in PyTorch on Colab; evaluate on held-out split.
 Generate and save confusion matrix + one-page eval writeup (accuracy, what it confuses, why angle robustness matters).
 Export classifier to ONNX, then to TFLite; test inference speed on a laptop.
Exit: Trained .tflite model file + eval writeup with confusion matrix.

Android app tasks (core):

 Set up Kotlin + CameraX + TFLite runtime. Start with "load video file" mode first (more reliable than live camera for demo).
 Integrate YOLOv8-pose (TFLite) + your classifier; log detected events with timestamps and confidence scores.
 Implement hash-chain event log (§6 — SHA-256 chain + Android Keystore signing).
 Build a simple "Match Record" UI screen displaying events and export-to-JSON button.
 Test on an actual low-end Android device, not an emulator — this is your actual claim.
Exit: App that loads a video → outputs viewable, exportable, tamper-evident event log.

Polish & demo prep tasks (optional, time permitting):

 Implement "live camera" mode (optional — "load video file" is sufficient).
 Build athlete performance card aggregation from event logs (Phase E).
 Build basic federation dashboard mockup reading exported JSON (Phase E).
 Record a backup demo video (in case live demo fails in front of judges).
 Create one-page architecture diagram: pose extraction → classifier → hash chain → record.
 Rehearse Q&A answers tied to what you actually built (not just pitch language).
Exit: Polish demo + backup materials + rehearsed talking points.
6. Tamper-evident record — concrete spec (keep this simple)

Don't reach for blockchain — it adds complexity with no real benefit at this scale and is a common overclaim judges will probe. A hash chain plus device-level signing is honest, buildable in under a day, and defensible under questioning.

Event record structure (per event):

{
  "event_id": "...",
  "match_id": "...",
  "timestamp": "...",
  "event_type": "raid_start | touch | escape_return",
  "confidence": 0.0-1.0,
  "prev_hash": "<sha256 of previous event's full record>",
  "hash": "<sha256 of this record minus 'hash' field>"
}
Each new event's hash is computed over its own contents + prev_hash, so altering any past event breaks every hash after it — that's the "tamper-evident" property, and it's simple to explain in one sentence to a judge.
At export time, sign the final chain's terminal hash using a keypair generated in the Android Keystore (hardware-backed on most devices). This lets you truthfully say the record can be verified as originating from that specific device — a concrete, checkable claim.
Store the chain in Room (SQLite); export as signed JSON for the "digital match record" deliverable.
7. Android app — minimal architecture
CameraX / video file input
        │
        ▼
YOLOv8-pose (TFLite, GPU/NNAPI delegate)
        │  keypoints per frame
        ▼
Sliding-window buffer (~1–2 sec windows)
        │
        ▼
GRU/TCN classifier (TFLite)
        │  event label + confidence
        ▼
Event logger → hash chain → Room DB
        │
        ▼
Match Record screen (view / export signed JSON)

Keep the UI minimal — a single-screen "Live/Load Video" toggle, an event feed, and an export button is enough for demo purposes. Don't spend build time on the federation-dashboard or athlete-card mockups; those are static Figma/slide mockups, not app screens.

8. Risk register — things to watch
Angle variation in demo. If your training data is all ~90° side-view and your demo video is shot from 45°, accuracy will drop 10–15 points. Mitigation: include footage from multiple angles (90°, 70°, 60°) in your training set. Document which angles you trained on and expected accuracy degradation outside that range — this becomes a Phase 2 improvement narrative.
Live camera demo risk. Real-time multi-person pose + classification on a low-end phone can stutter. Plan "load pre-recorded video file" as your primary demo path (more stable, same technical story), with live camera as optional bonus. This honest approach matches your deck's "pilot phase" language.
Footage sourcing. Public online footage is fine for all training and model development. For the judge-facing demo: if you can get one real grassroots clip from a contact, use that; if not, proceed with online footage and transparently note it in your writeup. Either is acceptable — the technical demo works the same way.
On-device speed. Multi-person pose at full frame rate may be too slow on low-end hardware. Mitigations (in order of preference): (a) drop classification to 5–10fps (raid events span multiple frames, this is fine); (b) process every Nth frame instead of every frame; (c) crop input to raider + nearest defender only; (d) fall back to single-person MediaPipe Pose if YOLOv8-pose is too heavy.
Tamper-evident framing. It's a SHA-256 hash chain + Android Keystore signing, not cryptographic immutability. Describe it exactly as that — it's a genuine, useful claim that survives technical questioning, unlike inflated claims.
Accuracy expectations. Document and defend ~70–80% accuracy + confusion matrix. This reads as more credible than unverifiable higher claims and becomes an asset for your Phase 2 narrative ("here's what we fix next").
9. Demo-day / bootcamp deliverables checklist
 Trained pose-sequence classifier (.tflite) with eval writeup (accuracy + confusion matrix)
 Android app (APK) that loads video or camera feed → outputs event log + tamper-evident record
 Backup demo video (recorded proof, in case live demo misbehaves)
 One-page architecture diagram
 Sample exported match record (signed JSON) to show as a concrete artifact
 Q&A defense answers updated to match what was actually built (not just what's in the deck)
10. Handoff notes

This plan assumes a coding agent will execute Phases A–D against this spec. Suggested order of first actions for that agent:

Repo + environment setup (Phase A, item 1).
Pose extraction test on a sample clip (Phase A, item 2) — this de-risks the riskiest technical assumption first.
Everything else follows the phase order above; each phase's exit criteria is the gate to move on.

If footage collection (§4) slips, that's the one dependency that can stall the whole pipeline — worth tracking separately from the coding tasks and chasing early.