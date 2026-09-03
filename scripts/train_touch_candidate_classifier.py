"""
Train a binary touch classifier from verified active-learning labels.

Inputs:
    data/processed/touch_active_learning/touch_candidate_features.csv
    data/processed/touch_active_learning/seed_review_labels.json

The feature CSV is produced by scripts/extract_touch_candidates.py. The label JSON
should be exported from Label Studio using the config in docs/touch_labeling_guide.md.

Only verified labels are used for training/evaluation. Provisional pre-labels are used
only to rank future review candidates.
"""

from __future__ import annotations

import argparse
import csv
import json
from pathlib import Path
from typing import Any

import numpy as np


FEATURE_COLUMNS = [
    "max_iou",
    "mean_iou",
    "min_kp_dist",
    "min_hand_torso_dist",
    "max_compression",
    "max_velocity_drop",
    "duration_sec",
]

LABEL_TO_INT = {"not_touch": 0, "touch": 1}
INT_TO_LABEL = {0: "not_touch", 1: "touch"}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-dir", default="/content/drive/MyDrive/NyaayKhel")
    parser.add_argument("--al-dir", default=None)
    parser.add_argument("--features", default=None)
    parser.add_argument("--labels", default=None)
    parser.add_argument("--out-dir", default=None)
    parser.add_argument("--next-batch-size", type=int, default=50)
    parser.add_argument("--random-seed", type=int, default=42)
    parser.add_argument(
        "--pilot-mode",
        action="store_true",
        help=(
            "Fit one clearly marked demonstration-only model with 10+ verified labels. "
            "It uses every label for fitting and produces no held-out accuracy claim."
        ),
    )
    return parser.parse_args()


def read_feature_rows(path: Path) -> list[dict[str, str]]:
    with path.open("r", encoding="utf-8", newline="") as f:
        return list(csv.DictReader(f))


def task_window_id(task: dict[str, Any]) -> str | None:
    meta = task.get("meta") or {}
    if isinstance(meta, dict) and meta.get("window_id"):
        return str(meta["window_id"])

    data = task.get("data") or {}
    for key in ("video", "data"):
        value = data.get(key)
        if value:
            return Path(str(value)).stem
    return None


def extract_choice(task: dict[str, Any]) -> str | None:
    annotations = task.get("annotations") or []
    if not annotations and task.get("completions"):
        annotations = task.get("completions") or []

    for ann in annotations:
        for result in ann.get("result") or []:
            value = result.get("value") or {}
            choices = value.get("choices")
            if choices:
                return str(choices[0])
    return None


def read_labelstudio_labels(path: Path) -> dict[str, str]:
    data = json.loads(path.read_text(encoding="utf-8"))
    labels: dict[str, str] = {}
    for task in data:
        window_id = task_window_id(task)
        choice = extract_choice(task)
        if not window_id or not choice:
            continue
        if choice in LABEL_TO_INT or choice == "unclear_skip":
            labels[window_id] = choice
    return labels


def label_for_window(window_id: str, labels: dict[str, str]) -> str | None:
    if window_id in labels:
        return labels[window_id]
    for key, value in labels.items():
        key_stem = Path(key).stem
        if key_stem == window_id or key_stem.endswith(window_id) or window_id in key_stem:
            return value
    return None


def build_dataset(rows: list[dict[str, str]], labels: dict[str, str]) -> tuple[np.ndarray, np.ndarray, np.ndarray, list[str]]:
    x_rows = []
    y_rows = []
    groups = []
    ids = []
    for row in rows:
        window_id = row["window_id"]
        label = label_for_window(window_id, labels) or row.get("verified_label", "").strip()
        if label == "unclear_skip" or label not in LABEL_TO_INT:
            continue
        x_rows.append([float(row[col]) for col in FEATURE_COLUMNS])
        y_rows.append(LABEL_TO_INT[label])
        groups.append(row.get("source_video_id") or row.get("clip_id") or window_id)
        ids.append(window_id)
    return np.asarray(x_rows, dtype=np.float32), np.asarray(y_rows, dtype=np.int64), np.asarray(groups), ids


def split_verified(
    x: np.ndarray,
    y: np.ndarray,
    groups: np.ndarray,
    seed: int,
) -> tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray]:
    from sklearn.model_selection import GroupShuffleSplit, train_test_split

    if len(np.unique(groups)) >= 3 and min(np.bincount(y, minlength=2)) >= 3:
        splitter = GroupShuffleSplit(n_splits=1, test_size=0.25, random_state=seed)
        train_idx, test_idx = next(splitter.split(x, y, groups))
        if len(np.unique(y[train_idx])) == 2 and len(np.unique(y[test_idx])) == 2:
            return x[train_idx], x[test_idx], y[train_idx], y[test_idx]

    stratify = y if min(np.bincount(y, minlength=2)) >= 2 else None
    return train_test_split(x, y, test_size=0.25, random_state=seed, stratify=stratify)


def write_csv(path: Path, rows: list[dict[str, str]]) -> None:
    if not rows:
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        writer.writeheader()
        writer.writerows(rows)


def export_tflite_from_logreg(model: Any, out_path: Path) -> str:
    try:
        import tensorflow as tf
    except ImportError:
        return "TensorFlow not installed; skipped TFLite export."

    scaler = model.named_steps["scale"]
    clf = model.named_steps["clf"]

    inputs = tf.keras.Input(shape=(len(FEATURE_COLUMNS),), name="touch_features")
    norm = tf.keras.layers.Normalization(
        mean=scaler.mean_.astype(np.float32),
        variance=scaler.var_.astype(np.float32),
        name="feature_normalization",
    )(inputs)
    outputs = tf.keras.layers.Dense(1, activation="sigmoid", name="touch_probability")(norm)
    keras_model = tf.keras.Model(inputs=inputs, outputs=outputs)
    dense = keras_model.get_layer("touch_probability")
    dense.set_weights([clf.coef_.astype(np.float32).T, clf.intercept_.astype(np.float32)])

    converter = tf.lite.TFLiteConverter.from_keras_model(keras_model)
    tflite = converter.convert()
    out_path.write_bytes(tflite)
    return f"TFLite exported: {out_path}"


def main() -> None:
    args = parse_args()

    try:
        import joblib
        from sklearn.linear_model import LogisticRegression
        from sklearn.metrics import classification_report, confusion_matrix, roc_auc_score
        from sklearn.pipeline import Pipeline
        from sklearn.preprocessing import StandardScaler
    except ImportError as exc:
        raise SystemExit(
            "Install training dependencies first: pip install scikit-learn joblib tensorflow"
        ) from exc

    base_dir = Path(args.base_dir)
    al_dir = Path(args.al_dir) if args.al_dir else base_dir / "data" / "processed" / "touch_active_learning"
    feature_path = Path(args.features) if args.features else al_dir / "touch_candidate_features.csv"
    label_path = Path(args.labels) if args.labels else al_dir / "seed_review_labels.json"
    out_dir = Path(args.out_dir) if args.out_dir else al_dir / "model"
    out_dir.mkdir(parents=True, exist_ok=True)

    rows = read_feature_rows(feature_path)
    labels = read_labelstudio_labels(label_path)
    x, y, groups, verified_ids = build_dataset(rows, labels)

    print(f"Candidate rows: {len(rows)}")
    print(f"Verified labels parsed: {len(labels)}")
    print(f"Usable verified labels: {len(y)}")
    print(f"Class counts: not_touch={int((y == 0).sum())}, touch={int((y == 1).sum())}")

    minimum_labels = 10 if args.pilot_mode else 20
    if len(y) < minimum_labels:
        raise SystemExit(
            f"Need at least ~{minimum_labels} usable verified labels "
            f"{'for pilot mode' if args.pilot_mode else 'before training'}."
        )
    if len(np.unique(y)) < 2:
        raise SystemExit("Need both touch and not_touch verified examples before training.")

    if args.pilot_mode:
        # With only a tiny, two-source seed set, a random train/test split
        # would create a misleading accuracy number.  Fit all verified labels
        # so the artifact can demonstrate the end-to-end pipeline, while the
        # output explicitly records that it is not an evaluated model.
        x_train, y_train = x, y
        x_test = np.empty((0, x.shape[1]), dtype=np.float32)
        y_test = np.empty((0,), dtype=np.int64)
    else:
        x_train, x_test, y_train, y_test = split_verified(x, y, groups, args.random_seed)

    model = Pipeline(
        [
            ("scale", StandardScaler()),
            (
                "clf",
                LogisticRegression(
                    class_weight="balanced",
                    max_iter=2000,
                    random_state=args.random_seed,
                ),
            ),
        ]
    )
    model.fit(x_train, y_train)

    cm = None
    report = None
    auc = None
    if not args.pilot_mode:
        prob_test = model.predict_proba(x_test)[:, 1]
        pred_test = (prob_test >= 0.5).astype(np.int64)
        cm = confusion_matrix(y_test, pred_test, labels=[0, 1])
        report = classification_report(
            y_test,
            pred_test,
            labels=[0, 1],
            target_names=["not_touch", "touch"],
            output_dict=True,
            zero_division=0,
        )
        if len(np.unique(y_test)) == 2:
            auc = float(roc_auc_score(y_test, prob_test))

    joblib_path = out_dir / "touch_candidate_classifier.joblib"
    joblib.dump(model, joblib_path)
    tflite_message = export_tflite_from_logreg(model, out_dir / "touch_candidate_classifier.tflite")

    verified_set = set(verified_ids)
    unlabeled_rows = [row for row in rows if row["window_id"] not in verified_set]
    if unlabeled_rows:
        x_unlabeled = np.asarray([[float(row[col]) for col in FEATURE_COLUMNS] for row in unlabeled_rows], dtype=np.float32)
        probs = model.predict_proba(x_unlabeled)[:, 1]
        for row, prob in zip(unlabeled_rows, probs):
            row["model_touch_probability"] = f"{prob:.6f}"
            row["model_uncertainty"] = f"{abs(prob - 0.5):.6f}"
        unlabeled_rows.sort(key=lambda row: (float(row["model_uncertainty"]), -float(row["max_iou"])))
        write_csv(out_dir / "next_review_batch.csv", unlabeled_rows[: args.next_batch_size])
        write_csv(out_dir / "all_unverified_ranked.csv", unlabeled_rows)

    metrics = {
        "model_status": "pilot_only_no_held_out_evaluation" if args.pilot_mode else "evaluated_seed_model",
        "verified_labels": int(len(y)),
        "train_size": int(len(y_train)),
        "test_size": int(len(y_test)),
        "feature_columns": FEATURE_COLUMNS,
        "class_counts": {
            "not_touch": int((y == 0).sum()),
            "touch": int((y == 1).sum()),
        },
        "confusion_matrix_labels": ["not_touch", "touch"],
        "confusion_matrix": cm.tolist() if cm is not None else None,
        "classification_report": report,
        "roc_auc": auc,
        "model_path": str(joblib_path),
        "tflite_export": tflite_message,
        "evaluation_warning": (
            "Pilot mode fitted all verified labels and intentionally produced no held-out metrics. "
            "It is a workflow demonstration, not an accuracy claim."
            if args.pilot_mode
            else "Metrics are valid only for manually verified labels, not provisional pre-labels."
        ),
    }
    metrics_path = out_dir / "verified_eval_metrics.json"
    metrics_path.write_text(json.dumps(metrics, indent=2), encoding="utf-8")

    print(json.dumps(metrics, indent=2))
    if args.pilot_mode:
        print("PILOT ONLY: no held-out accuracy was calculated or should be presented.")
    print()
    print(f"Next review batch: {out_dir / 'next_review_batch.csv'}")
    print(tflite_message)


if __name__ == "__main__":
    main()
