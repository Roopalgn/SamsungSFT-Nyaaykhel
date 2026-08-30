"""
Extract candidate kabaddi touch windows for active-learning review.

This script is meant to run in Colab after 00_data_collection.ipynb has produced:

    /content/drive/MyDrive/NyaayKhel/data/raw/clip_index.csv
    /content/drive/MyDrive/NyaayKhel/data/raw/side_90/*.mp4

It uses YOLOv8 pose detections, simple IoU tracking, and pairwise proximity features
to produce short 1-2 second windows that are likely to contain raider-defender
contact or hard false positives. The output is not ground truth. The user should
verify the recommended seed windows in Label Studio, then train/evaluate only on
verified labels.
"""

from __future__ import annotations

import argparse
import csv
import json
import math
import os
import shutil
import subprocess
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterable

import cv2
import numpy as np


COCO_HAND_KP = (9, 10)
COCO_TORSO_KP = (5, 6, 11, 12)
MIN_KP_CONF = 0.25


@dataclass
class Detection:
    bbox: np.ndarray
    keypoints: np.ndarray
    conf: float
    track_id: int = -1


@dataclass
class Track:
    track_id: int
    bbox: np.ndarray
    keypoints: np.ndarray
    last_frame: int
    misses: int = 0
    history: list[tuple[int, np.ndarray]] = field(default_factory=list)

    def update(self, det: Detection, frame_idx: int) -> None:
        self.bbox = det.bbox
        self.keypoints = det.keypoints
        self.last_frame = frame_idx
        self.misses = 0
        self.history.append((frame_idx, det.bbox.copy()))
        if len(self.history) > 90:
            self.history = self.history[-90:]


@dataclass
class PairFrame:
    frame_idx: int
    track_a: int
    track_b: int
    iou: float
    min_kp_dist: float
    hand_torso_dist: float
    compression: float
    velocity_drop: float


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-dir", default="/content/drive/MyDrive/NyaayKhel")
    parser.add_argument("--clip-index", default=None)
    parser.add_argument("--angle-bucket", default="side_90")
    parser.add_argument("--model", default="yolov8n-pose.pt")
    parser.add_argument("--out-dir", default=None)
    parser.add_argument("--target-fps", type=float, default=10.0)
    parser.add_argument("--window-sec", type=float, default=1.6)
    parser.add_argument("--max-clips", type=int, default=0, help="0 means all clips")
    parser.add_argument("--max-windows", type=int, default=800)
    parser.add_argument(
        "--max-windows-per-source",
        type=int,
        default=80,
        help="Cap candidate windows from one source video so the review set is not one match.",
    )
    parser.add_argument("--seed-size", type=int, default=50)
    parser.add_argument("--imgsz", type=int, default=640)
    parser.add_argument("--conf", type=float, default=0.25)
    parser.add_argument("--device", default=None, help="Example: 0 for GPU, cpu for CPU")
    return parser.parse_args()


def iou_xyxy(a: np.ndarray, b: np.ndarray) -> float:
    x1 = max(a[0], b[0])
    y1 = max(a[1], b[1])
    x2 = min(a[2], b[2])
    y2 = min(a[3], b[3])
    inter = max(0.0, x2 - x1) * max(0.0, y2 - y1)
    area_a = max(0.0, a[2] - a[0]) * max(0.0, a[3] - a[1])
    area_b = max(0.0, b[2] - b[0]) * max(0.0, b[3] - b[1])
    denom = area_a + area_b - inter
    return float(inter / denom) if denom > 0 else 0.0


def center_xy(bbox: np.ndarray) -> np.ndarray:
    return np.array([(bbox[0] + bbox[2]) / 2.0, (bbox[1] + bbox[3]) / 2.0], dtype=np.float32)


def bbox_diag(bbox: np.ndarray) -> float:
    return float(math.hypot(max(1.0, bbox[2] - bbox[0]), max(1.0, bbox[3] - bbox[1])))


def valid_points(kps: np.ndarray, indices: Iterable[int]) -> np.ndarray:
    points = []
    for idx in indices:
        if idx < len(kps) and kps[idx, 2] >= MIN_KP_CONF:
            points.append(kps[idx, :2])
    return np.array(points, dtype=np.float32) if points else np.empty((0, 2), dtype=np.float32)


def min_point_distance(a: np.ndarray, b: np.ndarray, scale: float) -> float:
    if len(a) == 0 or len(b) == 0:
        return 1.0
    d = np.linalg.norm(a[:, None, :] - b[None, :, :], axis=2)
    return float(np.min(d) / max(1.0, scale))


def all_keypoint_distance(a: np.ndarray, b: np.ndarray, scale: float) -> float:
    pa = a[a[:, 2] >= MIN_KP_CONF, :2]
    pb = b[b[:, 2] >= MIN_KP_CONF, :2]
    return min_point_distance(pa, pb, scale)


def pose_compression(a: np.ndarray, b: np.ndarray, bbox_a: np.ndarray, bbox_b: np.ndarray) -> float:
    points = np.concatenate(
        [a[a[:, 2] >= MIN_KP_CONF, :2], b[b[:, 2] >= MIN_KP_CONF, :2]],
        axis=0,
    )
    if len(points) < 4:
        return 0.0
    spread = float(np.mean(np.linalg.norm(points - points.mean(axis=0), axis=1)))
    normalizer = (bbox_diag(bbox_a) + bbox_diag(bbox_b)) / 2.0
    compactness = 1.0 - min(1.0, spread / max(1.0, normalizer))
    return float(max(0.0, compactness))


def track_speed(track: Track, frame_idx: int, lookback: int = 4) -> float:
    past = [(f, b) for f, b in track.history if f <= frame_idx]
    if len(past) < 2:
        return 0.0
    now_f, now_b = past[-1]
    old_f, old_b = past[max(0, len(past) - 1 - lookback)]
    dt = max(1, now_f - old_f)
    return float(np.linalg.norm(center_xy(now_b) - center_xy(old_b)) / dt)


def velocity_drop(track: Track, frame_idx: int) -> float:
    hist = [(f, b) for f, b in track.history if f <= frame_idx]
    if len(hist) < 8:
        return 0.0
    before = hist[-8:-4]
    after = hist[-4:]
    def avg_speed(items: list[tuple[int, np.ndarray]]) -> float:
        speeds = []
        for prev, cur in zip(items, items[1:]):
            dt = max(1, cur[0] - prev[0])
            speeds.append(float(np.linalg.norm(center_xy(cur[1]) - center_xy(prev[1])) / dt))
        return float(np.mean(speeds)) if speeds else 0.0
    b = avg_speed(before)
    a = avg_speed(after)
    return float(max(0.0, (b - a) / max(1.0, b)))


class SimpleTracker:
    def __init__(self, iou_threshold: float = 0.25, max_misses: int = 6):
        self.iou_threshold = iou_threshold
        self.max_misses = max_misses
        self.next_id = 1
        self.tracks: list[Track] = []

    def update(self, detections: list[Detection], frame_idx: int) -> list[Detection]:
        unmatched_tracks = set(range(len(self.tracks)))
        unmatched_dets = set(range(len(detections)))
        matches: list[tuple[int, int, float]] = []

        for ti, track in enumerate(self.tracks):
            for di, det in enumerate(detections):
                matches.append((ti, di, iou_xyxy(track.bbox, det.bbox)))
        matches.sort(key=lambda x: x[2], reverse=True)

        for ti, di, score in matches:
            if score < self.iou_threshold:
                break
            if ti not in unmatched_tracks or di not in unmatched_dets:
                continue
            track = self.tracks[ti]
            det = detections[di]
            det.track_id = track.track_id
            track.update(det, frame_idx)
            unmatched_tracks.remove(ti)
            unmatched_dets.remove(di)

        for ti in unmatched_tracks:
            self.tracks[ti].misses += 1

        for di in unmatched_dets:
            det = detections[di]
            det.track_id = self.next_id
            self.tracks.append(
                Track(
                    track_id=self.next_id,
                    bbox=det.bbox,
                    keypoints=det.keypoints,
                    last_frame=frame_idx,
                    history=[(frame_idx, det.bbox.copy())],
                )
            )
            self.next_id += 1

        self.tracks = [t for t in self.tracks if t.misses <= self.max_misses]
        return detections

    def by_id(self) -> dict[int, Track]:
        return {t.track_id: t for t in self.tracks}


def read_clip_index(path: Path, max_clips: int) -> list[dict[str, str]]:
    with path.open("r", encoding="utf-8", newline="") as f:
        rows = list(csv.DictReader(f))
    for row in rows:
        if not row.get("path") and row.get("file"):
            row["path"] = row["file"]
    rows = [r for r in rows if r.get("path") and Path(r["path"]).exists()]
    return rows[:max_clips] if max_clips > 0 else rows


def load_pose_model(model_name: str):
    try:
        from ultralytics import YOLO
    except ImportError as exc:
        raise SystemExit("Install ultralytics first: pip install ultralytics") from exc
    return YOLO(model_name)


def detections_from_result(result, frame_w: int, frame_h: int, conf_threshold: float) -> list[Detection]:
    detections = []
    boxes = result.boxes
    keypoints = result.keypoints
    if boxes is None or keypoints is None:
        return detections

    xyxy = boxes.xyxy.cpu().numpy()
    confs = boxes.conf.cpu().numpy()
    kps = keypoints.data.cpu().numpy()
    for bbox, conf, kp in zip(xyxy, confs, kps):
        if conf < conf_threshold:
            continue
        bbox = np.array(
            [
                np.clip(bbox[0], 0, frame_w - 1),
                np.clip(bbox[1], 0, frame_h - 1),
                np.clip(bbox[2], 0, frame_w - 1),
                np.clip(bbox[3], 0, frame_h - 1),
            ],
            dtype=np.float32,
        )
        detections.append(Detection(bbox=bbox, keypoints=kp.astype(np.float32), conf=float(conf)))
    return detections


def pair_features(a: Detection, b: Detection, tracks: dict[int, Track], frame_idx: int) -> PairFrame:
    scale = (bbox_diag(a.bbox) + bbox_diag(b.bbox)) / 2.0
    hand_a = valid_points(a.keypoints, COCO_HAND_KP)
    hand_b = valid_points(b.keypoints, COCO_HAND_KP)
    torso_a = valid_points(b.keypoints, COCO_TORSO_KP)
    torso_b = valid_points(a.keypoints, COCO_TORSO_KP)
    hand_torso = min(
        min_point_distance(hand_a, torso_a, scale),
        min_point_distance(hand_b, torso_b, scale),
    )
    drop = max(
        velocity_drop(tracks[a.track_id], frame_idx) if a.track_id in tracks else 0.0,
        velocity_drop(tracks[b.track_id], frame_idx) if b.track_id in tracks else 0.0,
    )
    return PairFrame(
        frame_idx=frame_idx,
        track_a=a.track_id,
        track_b=b.track_id,
        iou=iou_xyxy(a.bbox, b.bbox),
        min_kp_dist=all_keypoint_distance(a.keypoints, b.keypoints, scale),
        hand_torso_dist=hand_torso,
        compression=pose_compression(a.keypoints, b.keypoints, a.bbox, b.bbox),
        velocity_drop=drop,
    )


def is_candidate(pair: PairFrame) -> bool:
    return pair.iou >= 0.015 or pair.min_kp_dist <= 0.10 or pair.hand_torso_dist <= 0.16


def prelabel(row: dict[str, float]) -> tuple[str, float]:
    positive_score = 0
    positive_score += row["max_iou"] >= 0.12
    positive_score += row["min_kp_dist"] <= 0.08
    positive_score += row["min_hand_torso_dist"] <= 0.12
    positive_score += row["max_velocity_drop"] >= 0.20
    positive_score += 0.25 <= row["duration_sec"] <= 2.0

    if positive_score >= 4:
        return "provisional_touch", 0.75
    if row["max_iou"] < 0.03 and row["min_kp_dist"] > 0.14 and row["min_hand_torso_dist"] > 0.20:
        return "provisional_not_touch", 0.75
    return "needs_review", 0.50


def merge_pair_runs(pair_frames: list[PairFrame], fps: float, window_sec: float) -> list[dict[str, float]]:
    if not pair_frames:
        return []
    pair_frames.sort(key=lambda p: (p.track_a, p.track_b, p.frame_idx))
    runs = []
    cur = [pair_frames[0]]
    cur_pair = tuple(sorted((pair_frames[0].track_a, pair_frames[0].track_b)))

    for pf in pair_frames[1:]:
        pair = tuple(sorted((pf.track_a, pf.track_b)))
        if pair == cur_pair and pf.frame_idx - cur[-1].frame_idx <= max(2, int(fps * 0.4)):
            cur.append(pf)
        else:
            runs.append(cur)
            cur = [pf]
            cur_pair = pair
    runs.append(cur)

    windows = []
    half = int(round(fps * window_sec / 2.0))
    for run in runs:
        frames = [p.frame_idx for p in run]
        center = int(round(float(np.mean(frames))))
        start = max(0, center - half)
        end = center + half
        row = {
            "start_frame": start,
            "end_frame": end,
            "center_frame": center,
            "duration_sec": max(1, len(set(frames))) / fps,
            "track_a": run[0].track_a,
            "track_b": run[0].track_b,
            "max_iou": float(max(p.iou for p in run)),
            "mean_iou": float(np.mean([p.iou for p in run])),
            "min_kp_dist": float(min(p.min_kp_dist for p in run)),
            "min_hand_torso_dist": float(min(p.hand_torso_dist for p in run)),
            "max_compression": float(max(p.compression for p in run)),
            "max_velocity_drop": float(max(p.velocity_drop for p in run)),
        }
        label, confidence = prelabel(row)
        row["pre_label"] = label
        row["pre_label_confidence"] = confidence
        windows.append(row)
    return windows


def extract_window(video_path: Path, out_path: Path, start_frame: int, end_frame: int, fps: float) -> bool:
    cap = cv2.VideoCapture(str(video_path))
    if not cap.isOpened():
        return False
    source_fps = cap.get(cv2.CAP_PROP_FPS) or fps
    frame_w = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
    frame_h = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    out_path.parent.mkdir(parents=True, exist_ok=True)
    writer = cv2.VideoWriter(
        str(out_path),
        cv2.VideoWriter_fourcc(*"mp4v"),
        fps,
        (frame_w, frame_h),
    )
    cap.set(cv2.CAP_PROP_POS_FRAMES, int(start_frame * source_fps / fps))
    total_out = max(1, end_frame - start_frame)
    source_step = max(1, int(round(source_fps / fps)))
    written = 0
    while written < total_out:
        ok, frame = cap.read()
        if not ok:
            break
        writer.write(frame)
        written += 1
        for _ in range(source_step - 1):
            cap.grab()
    writer.release()
    cap.release()
    return written > 0


def transcode_for_label_studio(source: Path, destination: Path) -> bool:
    """Create a browser-compatible H.264 copy for the small human review batch."""
    result = subprocess.run(
        [
            "ffmpeg", "-y", "-loglevel", "error",
            "-i", str(source),
            "-c:v", "libx264", "-pix_fmt", "yuv420p",
            "-an", "-movflags", "+faststart", str(destination),
        ],
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        print(f"ffmpeg error while writing {destination.name}: {result.stderr.strip()}")
        destination.unlink(missing_ok=True)
        return False
    return True


def select_seed_set(rows: list[dict[str, str]], seed_size: int) -> list[str]:
    if len(rows) <= seed_size:
        return [r["window_id"] for r in rows]

    buckets = {
        "needs_review": [],
        "provisional_touch": [],
        "provisional_not_touch": [],
    }
    for row in rows:
        buckets.setdefault(row["pre_label"], []).append(row)

    for bucket in buckets.values():
        bucket.sort(
            key=lambda r: (
                abs(float(r["pre_label_confidence"]) - 0.5),
                -float(r["max_iou"]),
                float(r["min_hand_torso_dist"]),
            )
        )

    # A first model must see more than one match/camera.  At least ten sources
    # are preferred when available; this cap is relaxed automatically if there
    # are fewer sources in the candidate pool.
    sources = {row.get("source_video_id", "") for row in rows}
    target_source_count = max(1, min(10, len(sources)))
    max_per_source = max(1, math.ceil(seed_size / target_source_count))

    quotas = {
        "needs_review": int(seed_size * 0.60),
        "provisional_touch": int(seed_size * 0.25),
        "provisional_not_touch": seed_size,
    }
    chosen: list[str] = []
    chosen_per_source: dict[str, int] = {}
    for name, quota in quotas.items():
        for row in buckets.get(name, []):
            if len([c for c in chosen if any(r["window_id"] == c and r["pre_label"] == name for r in rows)]) >= quota:
                break
            source = row.get("source_video_id", "")
            if chosen_per_source.get(source, 0) >= max_per_source:
                continue
            chosen.append(row["window_id"])
            chosen_per_source[source] = chosen_per_source.get(source, 0) + 1
            if len(chosen) >= seed_size:
                return chosen

    for row in rows:
        source = row.get("source_video_id", "")
        if row["window_id"] not in chosen and chosen_per_source.get(source, 0) < max_per_source:
            chosen.append(row["window_id"])
            chosen_per_source[source] = chosen_per_source.get(source, 0) + 1
        if len(chosen) >= seed_size:
            break

    # If a small pool cannot fill the requested seed size within the diversity
    # cap, fill the remainder rather than returning fewer review tasks.
    if len(chosen) < seed_size:
        for row in rows:
            if row["window_id"] not in chosen:
                chosen.append(row["window_id"])
            if len(chosen) >= seed_size:
                break
    return chosen


def interleave_clips_by_source(clips: list[dict[str, str]]) -> list[dict[str, str]]:
    """Return clips in round-robin source order rather than filename order."""
    by_source: dict[str, list[dict[str, str]]] = {}
    for clip in clips:
        source = clip.get("source_video_id") or clip.get("source_video") or clip.get("clip_id", "")
        by_source.setdefault(source, []).append(clip)

    ordered: list[dict[str, str]] = []
    position = 0
    while True:
        added = False
        for source_clips in by_source.values():
            if position < len(source_clips):
                ordered.append(source_clips[position])
                added = True
        if not added:
            return ordered
        position += 1


def main() -> None:
    args = parse_args()
    base_dir = Path(args.base_dir)
    raw_dir = base_dir / "data" / "raw"
    clip_index = Path(args.clip_index) if args.clip_index else raw_dir / "clip_index.csv"
    out_dir = Path(args.out_dir) if args.out_dir else base_dir / "data" / "processed" / "touch_active_learning"
    windows_dir = out_dir / "windows"
    out_dir.mkdir(parents=True, exist_ok=True)
    windows_dir.mkdir(parents=True, exist_ok=True)

    clips = interleave_clips_by_source(read_clip_index(clip_index, args.max_clips))
    print(f"Loaded {len(clips)} existing clips from {clip_index}")
    print(f"Writing active-learning artifacts to {out_dir}")

    model = load_pose_model(args.model)
    all_rows: list[dict[str, str]] = []
    windows_per_source: dict[str, int] = {}

    for clip_num, clip in enumerate(clips, start=1):
        if len(all_rows) >= args.max_windows:
            break
        clip_path = Path(clip["path"])
        cap = cv2.VideoCapture(str(clip_path))
        if not cap.isOpened():
            print(f"Skipping unreadable clip: {clip_path}")
            continue
        source_fps = cap.get(cv2.CAP_PROP_FPS) or args.target_fps
        source_step = max(1, int(round(source_fps / args.target_fps)))
        frame_w = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
        frame_h = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
        tracker = SimpleTracker()
        pair_hits: list[PairFrame] = []
        frame_idx = 0
        sampled_idx = 0

        while True:
            ok, frame = cap.read()
            if not ok:
                break
            if frame_idx % source_step != 0:
                frame_idx += 1
                continue
            result = model.predict(
                frame,
                imgsz=args.imgsz,
                conf=args.conf,
                verbose=False,
                device=args.device,
            )[0]
            detections = detections_from_result(result, frame_w, frame_h, args.conf)
            detections = tracker.update(detections, sampled_idx)
            track_map = tracker.by_id()

            for i in range(len(detections)):
                for j in range(i + 1, len(detections)):
                    pf = pair_features(detections[i], detections[j], track_map, sampled_idx)
                    if is_candidate(pf):
                        pair_hits.append(pf)
            sampled_idx += 1
            frame_idx += 1
        cap.release()

        windows = merge_pair_runs(pair_hits, args.target_fps, args.window_sec)
        source_video_id = clip.get("source_video_id") or clip.get("source_video") or clip.get("clip_id", "")
        for local_idx, win in enumerate(windows):
            if len(all_rows) >= args.max_windows or windows_per_source.get(source_video_id, 0) >= args.max_windows_per_source:
                break
            window_id = f"{clip.get('clip_id', clip_path.stem)}_w{local_idx:03d}"
            out_video = windows_dir / f"{window_id}.mp4"
            ok = extract_window(
                clip_path,
                out_video,
                int(win["start_frame"]),
                int(win["end_frame"]),
                args.target_fps,
            )
            if not ok:
                continue
            row = {
                "window_id": window_id,
                "clip_id": clip.get("clip_id", clip_path.stem),
                "source_video_id": source_video_id,
                "source_clip_path": str(clip_path),
                "window_path": str(out_video),
                "verified_label": "",
                **{k: str(v) for k, v in win.items()},
            }
            all_rows.append(row)
            windows_per_source[source_video_id] = windows_per_source.get(source_video_id, 0) + 1
        print(f"[{clip_num}/{len(clips)}] {clip_path.name}: {len(windows)} candidates, total={len(all_rows)}")

    if not all_rows:
        raise SystemExit("No candidate windows found. Lower --conf or proximity thresholds in the script.")

    seed_ids = set(select_seed_set(all_rows, min(args.seed_size, len(all_rows))))
    for row in all_rows:
        row["review_priority"] = "seed" if row["window_id"] in seed_ids else ""

    feature_csv = out_dir / "touch_candidate_features.csv"
    with feature_csv.open("w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=list(all_rows[0].keys()))
        writer.writeheader()
        writer.writerows(all_rows)

    seed_csv = out_dir / "seed_review.csv"
    with seed_csv.open("w", encoding="utf-8", newline="") as f:
        seed_rows = [r for r in all_rows if r["review_priority"] == "seed"]
        writer = csv.DictWriter(f, fieldnames=list(all_rows[0].keys()))
        writer.writeheader()
        writer.writerows(seed_rows)

    # Keep the human-facing review batch separate so it can be downloaded and
    # uploaded to Label Studio without manually searching through candidates.
    seed_windows_dir = out_dir / "seed_windows_only"
    seed_windows_dir.mkdir(parents=True, exist_ok=True)
    for row in seed_rows:
        source = Path(row["window_path"])
        destination = seed_windows_dir / source.name
        if not transcode_for_label_studio(source, destination):
            raise RuntimeError(f"Could not create Label Studio clip: {source}")
    seed_bundle = Path(shutil.make_archive(str(out_dir / "seed_windows_only_bundle"), "zip", str(seed_windows_dir)))

    label_studio_json = out_dir / "label_studio_seed_import.json"
    seed_import = [{"data": {"video": row["window_path"]}, "meta": {"window_id": row["window_id"]}} for row in seed_rows]
    label_studio_json.write_text(json.dumps(seed_import, indent=2), encoding="utf-8")

    summary = {
        "total_candidate_windows": len(all_rows),
        "seed_review_windows": len(seed_ids),
        "candidate_sources": len(windows_per_source),
        "candidate_windows_per_source": windows_per_source,
        "seed_sources": len({r.get("source_video_id", "") for r in seed_rows}),
        "seed_windows_per_source": {
            source: sum(1 for row in seed_rows if row.get("source_video_id", "") == source)
            for source in sorted({r.get("source_video_id", "") for r in seed_rows})
        },
        "seed_windows_dir": str(seed_windows_dir),
        "seed_bundle": str(seed_bundle),
        "feature_csv": str(feature_csv),
        "seed_csv": str(seed_csv),
        "label_studio_seed_import": str(label_studio_json),
        "windows_dir": str(windows_dir),
        "warning": "pre_label values are provisional and must not be treated as ground truth",
    }
    (out_dir / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")

    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
