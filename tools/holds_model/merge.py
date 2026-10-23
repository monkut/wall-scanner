# /// script
# requires-python = ">=3.11"
# dependencies = ["pyyaml>=6"]
# ///
"""Builds one single-class (`hold`) YOLO-seg dataset from the public downloads and our captures.

    uv run tools/holds_model/merge.py [--val 0.2]

Reads data/public/*/{train,valid,test} and data/ours; every label class becomes 0 (the Pwals set labels holds
by colour, the Roboflow set has floor and person classes, which are dropped). Roboflow exports hold several
augmented copies of one photo (`<name>_jpg.rf.<hash>`), so images are grouped by their source photo and the
groups are split into train / val (seed 42); the split is written to data/holds/{images,labels}/{train,val}
and data/holds.yaml points at it.
"""
import argparse
import random
import shutil
from pathlib import Path

import yaml

ROOT = Path("tools/holds_model/data")
DROP = {"floor", "person"}


def class_map(dataset: Path):
    """Label index → keep (True) or drop, from the dataset's data.yaml names."""
    cfg = yaml.safe_load((dataset / "data.yaml").read_text())
    names = cfg["names"] if isinstance(cfg["names"], list) else [cfg["names"][k] for k in sorted(cfg["names"])]
    return {i: n not in DROP for i, n in enumerate(names)}


def source_photo(img: Path) -> str:
    """`IMG_1234_jpg.rf.<hash>` and `IMG_1234_png.rf.<hash>` are augmentations of IMG_1234."""
    stem = img.stem.split(".rf.")[0]
    for suffix in ("_jpg", "_jpeg", "_png"):
        if stem.endswith(suffix):
            stem = stem[: -len(suffix)]
    return stem


def convert(label_file: Path, keep):
    out = []
    for line in label_file.read_text().splitlines():
        parts = line.split()
        if len(parts) < 7 or not keep.get(int(parts[0]), True):
            continue
        out.append("0 " + " ".join(parts[1:]))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--val", type=float, default=0.2)
    args = ap.parse_args()
    pairs = []  # (image path, label lines, source)
    for dataset in sorted((ROOT / "public").glob("*")):
        if not (dataset / "data.yaml").exists():
            continue
        keep = class_map(dataset)
        for split in ("train", "valid", "test"):
            for img in sorted((dataset / split / "images").glob("*")):
                lbl = dataset / split / "labels" / (img.stem + ".txt")
                if lbl.exists():
                    pairs.append((img, convert(lbl, keep), dataset.name))
    for img in sorted((ROOT / "ours" / "images").glob("*")):
        lbl = ROOT / "ours" / "labels" / (img.stem + ".txt")
        if lbl.exists():
            pairs.append((img, lbl.read_text().splitlines(), "ours"))
    groups = {}
    for pair in pairs:
        groups.setdefault((pair[2], source_photo(pair[0])), []).append(pair)
    keys = sorted(groups)
    random.Random(42).shuffle(keys)
    val_keys = set(keys[: int(len(keys) * args.val)])
    target = ROOT / "holds"
    if target.exists():
        shutil.rmtree(target)
    n_val = 0
    for key in keys:
        split = "val" if key in val_keys else "train"
        for img, lines, source in groups[key]:
            n_val += split == "val"
            (target / "images" / split).mkdir(parents=True, exist_ok=True)
            (target / "labels" / split).mkdir(parents=True, exist_ok=True)
            name = f"{source}-{img.stem}{img.suffix}"
            shutil.copy(img, target / "images" / split / name)
            (target / "labels" / split / f"{source}-{img.stem}.txt").write_text("\n".join(lines) + "\n")
    (ROOT / "holds.yaml").write_text(yaml.safe_dump({"path": str(target.resolve()), "train": "images/train", "val": "images/val", "names": {0: "hold"}}))
    by_source = {}
    for _, _, s in pairs:
        by_source[s] = by_source.get(s, 0) + 1
    print(f"{len(pairs)} images in {len(keys)} source photos ({len(pairs) - n_val} train / {n_val} val): {by_source} → {ROOT / 'holds.yaml'}")


if __name__ == "__main__":
    main()
