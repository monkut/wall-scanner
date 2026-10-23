# /// script
# requires-python = ">=3.11"
# dependencies = ["pyyaml>=6", "numpy>=1.26", "pillow>=10"]
# ///
"""Builds the two-class (`hold`, `tape`) YOLO-seg dataset from the public downloads and our captures.

    uv run tools/holds_model/merge.py [--val 0.2]

Reads data/public/*/{train,valid,test} and data/ours; a class named `tape` becomes 1 and every other class 0
(the Pwals set labels holds by colour, PanClimb by hold type; the Roboflow set's floor and person classes are
dropped). Every image goes through clahe.equalize, as the app's input does. Roboflow exports hold several
augmented copies of one photo (`<name>_jpg.rf.<hash>`), so images are grouped by their source photo and the
groups are split into train / val (seed 42); the split is written to data/holds/{images,labels}/{train,val}
and data/holds.yaml points at it.
"""
import argparse
import random
import shutil
from pathlib import Path

import numpy as np
import yaml
from PIL import Image

from clahe import equalize

ROOT = Path("tools/holds_model/data")
DROP = {"floor", "person"}


TAPE = "tape"
NAMES = {0: "hold", 1: TAPE}


def class_map(dataset: Path):
    """Label index → our class (0 hold, 1 tape) or None to drop, from the dataset's data.yaml names."""
    cfg = yaml.safe_load((dataset / "data.yaml").read_text())
    names = cfg["names"] if isinstance(cfg["names"], list) else [cfg["names"][k] for k in sorted(cfg["names"])]
    return {i: None if n.lower() in DROP else 1 if n.lower() == TAPE else 0 for i, n in enumerate(names)}


def source_photo(img: Path) -> str:
    """`IMG_1234_jpg.rf.<hash>` and `IMG_1234_png.rf.<hash>` are augmentations of IMG_1234."""
    stem = img.stem.split(".rf.")[0]
    for suffix in ("_jpg", "_jpeg", "_png"):
        if stem.endswith(suffix):
            stem = stem[: -len(suffix)]
    return stem


def convert(label_file: Path, classes):
    out = []
    for line in label_file.read_text().splitlines():
        parts = line.split()
        cls = classes.get(int(parts[0]), 0) if parts else None
        if len(parts) < 7 or cls is None:
            continue
        out.append(f"{cls} " + " ".join(parts[1:]))
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
            Image.fromarray(equalize(np.asarray(Image.open(img).convert("RGB")))).save(target / "images" / split / name, quality=95)
            (target / "labels" / split / f"{source}-{img.stem}.txt").write_text("\n".join(lines) + "\n")
    (ROOT / "holds.yaml").write_text(yaml.safe_dump({"path": str(target.resolve()), "train": "images/train", "val": "images/val", "names": NAMES}))
    by_source = {}
    for _, _, s in pairs:
        by_source[s] = by_source.get(s, 0) + 1
    print(f"{len(pairs)} images in {len(keys)} source photos ({len(pairs) - n_val} train / {n_val} val): {by_source} → {ROOT / 'holds.yaml'}")


if __name__ == "__main__":
    main()
