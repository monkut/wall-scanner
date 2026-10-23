# /// script
# requires-python = ">=3.11"
# dependencies = ["pillow>=10"]
# ///
"""Turns captures into YOLO-seg training data: the orthophoto as the image, hold outlines as polygons.

    uv run tools/holds_model/ours_to_yolo.py captures/<id> [captures/<id2> …] [--out tools/holds_model/data/ours]

Uses `reference.json` (a corrected scene) when present, else `scene.json`. Every hold is class 0 (`hold`).
Writes images/<id>.png and labels/<id>.txt; the split into train / val happens in merge.py.
"""
import argparse
import json
from pathlib import Path

from PIL import Image


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("captures", nargs="+", type=Path)
    ap.add_argument("--out", type=Path, default=Path("tools/holds_model/data/ours"))
    args = ap.parse_args()
    (args.out / "images").mkdir(parents=True, exist_ok=True)
    (args.out / "labels").mkdir(parents=True, exist_ok=True)
    for cap in args.captures:
        scene_file = cap / "reference.json" if (cap / "reference.json").exists() else cap / "scene.json"
        scene = json.loads(scene_file.read_text())
        photo = scene["wall"].get("photo")
        image = next((f for f in (cap / "wall.png", cap / "wall-new.ppm") if f.exists()), None)
        if not photo or image is None:
            print(f"{cap.name}: no orthophoto, skipped")
            continue
        min_u, min_v, max_u, max_v = photo["bounds"]
        im = Image.open(image).convert("RGB")
        w, h = im.size
        lines = []
        for hold in scene["holds"]:
            pts = hold.get("outline") or []
            if len(pts) < 3:
                continue
            coords = []
            for u, v in pts:
                x = min(max((u - min_u) / (max_u - min_u), 0.0), 1.0)
                y = min(max((max_v - v) / (max_v - min_v), 0.0), 1.0)
                coords.append(f"{x:.5f} {y:.5f}")
            lines.append("0 " + " ".join(coords))
        im.save(args.out / "images" / f"{cap.name}.png")
        (args.out / "labels" / f"{cap.name}.txt").write_text("\n".join(lines) + "\n")
        print(f"{cap.name}: {w}x{h}, {len(lines)} holds from {scene_file.name}")


if __name__ == "__main__":
    main()
