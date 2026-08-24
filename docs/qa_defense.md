# NyaayKhel — Judge Q&A Defense

> **Update this file after Phase C is done** — answers below are written for what *will* be built.
> Before demo day, verify every answer against what is *actually* in the APK.

---

## About This Document

This file prepares rehearsed, technically honest answers to the questions most likely to come up from Samsung SFT judges at the bootcamp pitch and final demo. The rule: **every answer must be true for the actual build, not just the pitch language.**

---

## Confirmed Constraints to State Plainly (Do Not Obscure)

> [!IMPORTANT]
> These are confirmed limitations that must be stated honestly if asked. They are not embarrassing — a 22-year-old solo builder being transparent about prototype scope is more credible than vague overclaims.

1. **Test device is not low-end.** The prototype was developed and tested on an 8GB RAM Android phone — not the "low-end Android" target claimed in the pitch. The honest framing: *"Phase 1 target hardware is low-end Android <=4GB RAM. Our prototype is designed for 10fps on-device analysis, but low-end device validation is Phase 1 work we haven't completed yet."*

2. **YouTube-only footage.** All training data and demo footage is sourced from publicly available YouTube kabaddi match videos. There is no footage from real grassroots tournaments. This is disclosed in `docs/model_eval.md`. Honest framing: *"We trained on public YouTube footage — the model works on real kabaddi matches, but we haven't yet filmed a grassroots tournament ourselves. That's Phase 1 deployment work."*

3. **Accuracy is ~70–80%, not higher.** The confusion matrix shows where the model fails. This is documented and intentional. *Do not claim higher accuracy without a confusion matrix to back it.*

---

## Q&A Answers

---

### "How do you detect actual contact? Can a camera really tell if someone was touched?"

**Answer:**
> We don't claim to detect contact as a ground truth. The model detects spatial proximity and movement patterns consistent with kabaddi scoring events — specifically: the raider crossing the mid-line, body parts entering the defensive zone, and the directional movement pattern of a return escape.
>
> At ~70–80% accuracy on held-out test clips, it flags *candidate* scoring events with confidence scores. The referee then reviews those flagged moments using the audit trail — which is exactly how we positioned this: as a *referee's tool*, not a referee replacement.
>
> Phase 2 adds multi-angle views for stereo proximity estimation, which gets closer to true contact verification.

---

### "How is it actually offline? What runs on-device?"

**Answer:**
> Both TFLite models run entirely on-device using TensorFlow Lite:
> - YOLOv8n-pose (pose extraction): ~[X] MB, currently CPU-only in the prototype.
> - GRU/TCN classifier: ~[X] KB (very small — ~100–200k parameters), also TFLite.
>
> The event log is written to Room (SQLite) on the device. Export to JSON is also local. Zero network calls are made during a match. Internet is only used for the initial app install.
>
> [Fill in actual model sizes after Phase C is complete.]

---

### "Isn't this just standard action recognition? What's novel?"

**Answer:**
> It's pose-sequence classification, which is a specific and deliberate design choice — not generic action recognition.
>
> Full video action-recognition models (I3D, SlowFast, VideoMAE) require large labeled video datasets and compute budgets that aren't realistic for this use case. By going pose-first — extract keypoints with a pretrained model, then train only a small temporal classifier on top — we get:
> - Orders of magnitude less training data needed (~150 clips vs tens of thousands of videos)
> - A model small enough to run on low-end Android (sub-200k parameters)
> - An interpretable output: the model is reasoning about player skeleton positions, not raw pixels
>
> The novelty isn't the ML technique; it's applying it to the specific problem of grassroots kabaddi officiating where no prior solution exists.

---

### "What's your accuracy? Have you tested it on real matches?"

**Answer:**
> On our held-out source-video test split from YouTube footage, we achieve approximately [X]% accuracy across 4 classes. The confusion matrix is in `docs/confusion_matrix.png`.
>
> The model most often confuses `touch` and `neutral` — cases where the raider enters the defensive zone without contact. This is documented and is the primary Phase 2 improvement target (adding a second angle to improve depth estimation).
>
> We have not yet tested on real grassroots footage — all training and testing used publicly available YouTube kabaddi matches. Low-end device validation is also Phase 1 work. We're transparent about this: the prototype demonstrates the pipeline works; deployment validation comes next.

---

### "Why only kabaddi? Wrestling and kho-kho were in your original pitch."

**Answer:**
> Largest grassroots base of the three, most event-rich sport (raids, touches, and escapes are discrete, visually clear events a camera can detect), and judges have a mental model of it from Pro Kabaddi League coverage.
>
> Wrestling and kho-kho are genuine Phase 2 sports — the pipeline architecture is the same, and adding them requires retraining the classifier on different event classes. We scoped to kabaddi first because doing one sport well is more compelling than doing three sports poorly.

---

### "What about occlusions — players blocking the camera view?"

**Answer:**
> Single-angle viewing always has occlusion blind spots. If a defender's body blocks the raider, the pose estimator either misses keypoints (zeros them out with low confidence) or estimates them incorrectly.
>
> Our mitigation: keypoints with confidence below 0.3 are zeroed out at the classifier input, so the classifier learns to be robust to missing keypoints rather than hallucinating. In practice, raids involve enough player movement that partial occlusion in a few frames doesn't kill detection across a 30-frame window.
>
> Phase 2 adds a second phone at a complementary angle — this is the real fix. One angle has inherent blind spots; two angles largely eliminate them.

---

### "This is a hash chain — is that actually tamper-evident? What does it prove?"

**Answer:**
> It proves that the sequence of events was written in order and has not been altered since export. Each event's hash is computed over its own data plus the previous event's hash — so changing any event breaks every hash after it, making alteration detectable.
>
> At export time, the terminal hash is signed using a keypair generated inside the Android Keystore — hardware-backed on most modern Android devices. This lets us say: the record originated from this specific device and hasn't been modified since signing.
>
> What it doesn't prove: it doesn't verify that our AI's event detections were correct. It proves the *record of what the AI detected* is unaltered. That's the right claim — we're creating an audit trail, not a ground-truth oracle.

---

### "How does this reach the 2 crore grassroots athletes you mentioned?"

**Answer:**
> Through the NYKS (Nehru Yuva Kendra Sangathan) ecosystem — 623 districts, 8.5M enrolled youth. We're not building a consumer app that hopes for viral adoption. We're targeting the tournament organizer and district association level: one NyaayKhel installation per tournament, run by one volunteer recorder, generates verified records for all participating athletes.
>
> Each tamper-evident reviewed match record is free to athletes. Revenue comes from per-tournament licensing to organizers (Phase 1), annual subscriptions to federations (Phase 2), and government licensing to SAI/Khelo India (Phase 3).

---

### "Can you manipulate the confidence threshold to make it look more accurate?"

**Answer:**
> Yes — raising the confidence threshold reduces false positives at the cost of missing real events (lower recall). This is a known trade-off that's documented in the eval writeup and settable in the app.
>
> The confusion matrix is computed at a fixed threshold (default 0.65) on a held-out test set the model never saw during training. We're not cherry-picking a threshold that makes numbers look good.

---

## Answers Still Pending Phase C

These answers require knowing actual numbers from the built app — fill in before demo day:

- [ ] Actual YOLOv8n-pose TFLite model size (MB)
- [ ] Actual GRU/TCN TFLite model size (KB)
- [ ] Actual inference fps measured on test device (8GB phone, 10fps video sampling)
- [ ] Actual accuracy number from confusion matrix
- [ ] Actual most-confused class pair (from confusion matrix)
