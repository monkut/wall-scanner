# /// script
# requires-python = ">=3.11"
# dependencies = ["pillow>=10", "numpy>=1.26"]
# ///
"""Scores a scene's hold outlines against a capture's reference outlines.

    uv run tools/holds_model/eval_holds.py captures/<id> [captures/<id2> …] [--pred scene-new.json] [--ref reference.json]

Outlines are rasterised on the orthophoto grid (wall.photo.bounds / px from the scene); predicted and reference
holds are matched greedily by IoU (a match needs IoU >= 0.5). Prints precision, recall and the mean IoU of the
matches per capture, then over all captures. The reference defaults to `reference.json` (a corrected scene
exported from the app) and falls back to `scene.json`.
"""
import argparse
import json
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

MATCH_IOU = 0.5


def photo_grid(scene):
    photo = scene["wall"]["photo"]
    min_u, min_v, max_u, max_v = photo["bounds"]
    px = photo["px"]
    return min_u, max_v, px, int(round((max_u - min_u) / px)), int(round((max_v - min_v) / px))


def masks(scene, grid):
    min_u, max_v, px, w, h = grid
    out = []
    for hold in scene["holds"]:
        pts = [((u - min_u) / px, (max_v - v) / px) for u, v in hold.get("outline") or []]
        if len(pts) < 3:
            continue
        im = Image.new("1", (w, h), 0)
        ImageDraw.Draw(im).polygon(pts, fill=1)
        out.append((hold["id"], np.asarray(im, dtype=bool)))
    return out


def score(pred, ref):
    ious = np.zeros((len(pred), len(ref)))
    for i, (_, a) in enumerate(pred):
        for j, (_, b) in enumerate(ref):
            inter = np.logical_and(a, b).sum()
            if inter:
                ious[i, j] = inter / np.logical_or(a, b).sum()
    matched, used_p, used_r = [], set(), set()
    for i, j in sorted(((i, j) for i in range(len(pred)) for j in range(len(ref))), key=lambda ij: -ious[ij]):
        if ious[i, j] < MATCH_IOU or i in used_p or j in used_r:
            continue
        matched.append(ious[i, j]); used_p.add(i); used_r.add(j)
    return len(matched), len(pred), len(ref), float(np.mean(matched)) if matched else 0.0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("captures", nargs="+", type=Path)
    ap.add_argument("--pred", default="scene-new.json")
    ap.add_argument("--ref", default="reference.json")
    args = ap.parse_args()
    totals = np.zeros(3)
    for cap in args.captures:
        ref_file = cap / args.ref if (cap / args.ref).exists() else cap / "scene.json"
        pred = json.loads((cap / args.pred).read_text())
        ref = json.loads(ref_file.read_text())
        grid = photo_grid(ref)
        m, p, r, iou = score(masks(pred, grid), masks(ref, grid))
        totals += (m, p, r)
        print(f"{cap.name}: precision {m / p if p else 0:.2f} ({m}/{p}) recall {m / r if r else 0:.2f} ({m}/{r}) mean IoU {iou:.2f}  [{args.pred} vs {ref_file.name}]")
    m, p, r = totals
    if len(args.captures) > 1:
        print(f"all: precision {m / p if p else 0:.2f} recall {m / r if r else 0:.2f}")


if __name__ == "__main__":
    main()
