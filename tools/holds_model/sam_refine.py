# /// script
# requires-python = ">=3.11,<3.13"
# dependencies = ["ai-edge-litert", "numpy>=1.26", "opencv-python-headless>=4.9"]
# ///
"""Runs the phone's outline refinement (core/SamRefine.kt) on captures with the exported MobileSAM models and
writes scene-sam.json beside each, for eval_holds.py and the overlays; the JVM harness cannot run LiteRT.

    uv run tools/holds_model/sam_refine.py captures/<id> [captures/<id2> …] [--scene scene.json] [--photo wall.png] [--stats]

Same steps as the app: the photo resized (longer side 1024, bilinear) and normalised, one encoder run; per hold
its outline's bounding box as the prompt; the mask logits sampled bilinearly at each photo pixel, > 0 is hold;
the part overlapping the old outline most, holes filled; the new outline kept only when the predicted IoU, the
overlap with the old outline and the area against the old pass the same limits as SamRefine; pieces inside a
larger hold's mask merge into it; a mask taking from other holds is refused; image-only holds without contrast to
the wall around them are dropped. --stats prints each hold's numbers.
"""
import argparse
import json
from pathlib import Path

import cv2
import numpy as np
from ai_edge_litert.interpreter import Interpreter

ASSETS = Path(__file__).resolve().parents[2] / "android/app/src/main/assets"
SIZE = 1024
MEAN = np.array([123.675, 116.28, 103.53], np.float32)
STD = np.array([58.395, 57.12, 57.375], np.float32)
# Mirrors core/SamRefine.kt.
MIN_SCORE = 0.7
MIN_OVERLAP = 0.2
MIN_AREA = 0.25
MARGIN = 0.25  # the mask is read in the box grown by this share of its size, which also bounds how far it grows
MAX_OTHERS = 0.2  # a mask taking more than this from other holds, outside its old outline, is refused
MERGE_INSIDE = 0.6  # a mask this much inside a larger one is a piece of that hold
MIN_CONTRAST = 6.0  # CIE ΔE between an image-only hold and the wall ring around it
RING_IN, RING_OUT = 2, 6


def load(name):
    it = Interpreter(model_path=str(ASSETS / name), num_threads=4)
    it.allocate_tensors()
    return it


def run(it, *args):
    for d, a in zip(sorted(it.get_input_details(), key=lambda d: d["index"]), args):
        it.set_tensor(d["index"], a)
    it.invoke()
    return [it.get_tensor(d["index"]) for d in it.get_output_details()]


def mask_in(logits, scale, w, h, box):
    """Hold pixels in the box (± MARGIN) of a photo w × h: logits (256 × 256 over the 1024 input) sampled at
    each pixel centre mapped to the input, bilinear, edges clamped."""
    x0, y0, x1, y1 = box
    mx, my = (x1 - x0) * MARGIN, (y1 - y0) * MARGIN
    bx0, by0 = max(0, int(x0 - mx)), max(0, int(y0 - my))
    bx1, by1 = min(w - 1, int(x1 + mx)), min(h - 1, int(y1 + my))
    xs = np.arange(bx0, bx1 + 1)
    ys = np.arange(by0, by1 + 1)
    k = logits.shape[-1] / SIZE  # 0.25
    sx = np.clip((xs + 0.5) * scale * k - 0.5, 0, logits.shape[-1] - 1)
    sy = np.clip((ys + 0.5) * scale * k - 0.5, 0, logits.shape[-2] - 1)
    xa = sx.astype(int); xb = np.minimum(xa + 1, logits.shape[-1] - 1); fx = sx - xa
    ya = sy.astype(int); yb = np.minimum(ya + 1, logits.shape[-2] - 1); fy = (sy - ya)[:, None]
    top = logits[ya][:, xa] * (1 - fx) + logits[ya][:, xb] * fx
    bottom = logits[yb][:, xa] * (1 - fx) + logits[yb][:, xb] * fx
    out = np.zeros((h, w), bool)
    out[by0:by1 + 1, bx0:bx1 + 1] = (top * (1 - fy) + bottom * fy) > 0
    return out


def raster(pts, w, h):
    """Pixels whose centres lie inside the polygon (photo-pixel coordinates), as Raster.fillPolygon fills them."""
    m = np.zeros((h, w), np.uint8)
    cv2.fillPoly(m, [np.round((pts - 0.5) * 16).astype(np.int32)], 1, shift=4)
    return m.astype(bool)


def fill_holes(m):
    flood = m.astype(np.uint8).copy()
    pad = np.pad(flood, 1)
    cv2.floodFill(pad, None, (0, 0), 1)
    return m | (pad[1:-1, 1:-1] == 0)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("captures", nargs="+", type=Path)
    ap.add_argument("--scene", default="scene.json")
    ap.add_argument("--photo", default="wall.png", help="older pulls keep the phone's photo as wall-phone.png")
    ap.add_argument("--stats", action="store_true")
    args = ap.parse_args()
    enc, dec = load("sam_encoder.tflite"), load("sam_decoder.tflite")
    for cap in args.captures:
        scene = json.loads((cap / args.scene).read_text())
        p = scene["wall"]["photo"]
        min_u, _, _, max_v = p["bounds"]; px = p["px"]
        rgb = cv2.cvtColor(cv2.imread(str(cap / args.photo)), cv2.COLOR_BGR2RGB)
        h, w = rgb.shape[:2]
        scale = SIZE / max(w, h)
        rw, rh = int(w * scale + 0.5), int(h * scale + 0.5)  # as Kotlin's roundToInt
        x = np.zeros((SIZE, SIZE, 3), np.float32)
        x[:rh, :rw] = (cv2.resize(rgb, (rw, rh), interpolation=cv2.INTER_LINEAR).astype(np.float32) - MEAN) / STD
        emb = run(enc, x.transpose(2, 0, 1)[None].copy())[0]
        polys = [np.array([((u - min_u) / px, (max_v - v) / px) for u, v in hd.get("outline") or []], np.float32) for hd in scene["holds"]]
        # ImageHold.owners: each pixel's smallest covering hold (by width × tall).
        owner = np.full((h, w), -1)
        area = [hd["size"][0] * hd["size"][1] for hd in scene["holds"]]
        for k in sorted(range(len(polys)), key=lambda k: -area[k]):
            if len(polys[k]) >= 3:
                owner[raster(polys[k], w, h)] = k
        olds, masks = [], []
        for index, (hold, pts) in enumerate(zip(scene["holds"], polys)):
            olds.append(raster(pts, w, h) if len(pts) >= 3 else np.zeros((h, w), bool))
            masks.append(None)
            if len(pts) < 3:
                continue
            old = olds[-1]
            box = [*pts.min(0), *pts.max(0)]
            out = run(dec, emb, (np.array(box, np.float32) * scale)[None])
            logits = next(o for o in out if o.ndim == 4)[0, 0]
            score = float(next(o for o in out if o.ndim == 2)[0, 0])
            m = mask_in(logits, scale, w, h, box)
            n, lab = cv2.connectedComponents(m.astype(np.uint8), connectivity=4)
            if n > 1:
                best = max(range(1, n), key=lambda k: ((lab == k) & old).sum() * 1e6 + (lab == k).sum())
                m = fill_holes(lab == best)
            overlap = (m & old).sum() / max(1, (m | old).sum())
            area = m.sum() / max(1, old.sum())
            ok = score >= MIN_SCORE and overlap >= MIN_OVERLAP and area >= MIN_AREA
            if args.stats:
                print(f"{cap.name} hold {hold['id']:3d} score {score:.2f} overlap {overlap:.2f} area {area:.2f} {'accepted' if ok else 'kept'}")
            if ok:
                masks[-1] = m
        # SamRefine.mergeInto: a mask at least MERGE_INSIDE inside a larger one is a piece of that hold.
        into = [-1] * len(masks); hosts = []
        for j in sorted((k for k in range(len(masks)) if masks[k] is not None and masks[k].any()), key=lambda k: -masks[k].sum()):
            host = next((i for i in hosts if (masks[j] & masks[i]).sum() >= MERGE_INSIDE * masks[j].sum()), None)
            if host is None: hosts.append(j)
            else: into[j] = host
        group = [into[k] if into[k] >= 0 else k for k in range(len(masks))]
        for i, m in enumerate(masks):  # SamRefine.takesFromOthers
            if m is None or into[i] >= 0: continue
            taken = m & ~olds[i] & (owner >= 0)
            if sum(1 for o in owner[taken] if group[o] != group[i]) > MAX_OTHERS * m.sum():
                masks[i] = None
                for j in range(len(masks)):
                    if into[j] == i: into[j] = -1; group[j] = j
        final = []
        for i, hold in enumerate(scene["holds"]):
            if into[i] >= 0:
                print(f"  hold {hold['id']} merged into {scene['holds'][into[i]]['id']}")
                continue
            if masks[i] is None:
                final.append((hold, olds[i], False)); continue
            m = masks[i].copy()
            for j in range(len(masks)):
                if into[j] == i: m |= masks[j]; hold["voxels"] = hold.get("voxels", 0) + scene["holds"][j].get("voxels", 0)
            cs, _ = cv2.findContours(m.astype(np.uint8), cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
            c = max(cs, key=cv2.contourArea)[:, 0, :].astype(np.float32) + 0.5
            hold["outline"] = [[round(float(min_u + cx * px), 3), round(float(max_v - cy * px), 3)] for cx, cy in c]
            final.append((hold, m, True))
        # SamRefine.contrast: image-only holds that do not stand out from the wall ring around them are dropped.
        lab = cv2.cvtColor(rgb.astype(np.float32) / 255, cv2.COLOR_RGB2LAB)
        covered = np.zeros((h, w), bool)
        for _, m, _ in final: covered |= m
        sq = np.ones((3, 3), np.uint8)
        kept, redrawn = [], 0
        for hold, m, new in final:
            if hold.get("voxels", 0) == 0 and m.any():
                ring = (cv2.dilate(m.astype(np.uint8), sq, iterations=RING_OUT) > 0) & ~(cv2.dilate(m.astype(np.uint8), sq, iterations=RING_IN) > 0)
                wall = ring & ~covered if (ring & ~covered).sum() >= 10 else ring
                d_e = float(np.linalg.norm(np.median(lab[m], 0) - np.median(lab[wall], 0))) if wall.any() else 1e9
                if d_e < MIN_CONTRAST:
                    print(f"  hold {hold['id']} dropped: contrast {d_e:.1f}")
                    continue
            kept.append(hold); redrawn += new
        merged = sum(1 for k in into if k >= 0)
        scene["holds"] = kept
        (cap / "scene-sam.json").write_text(json.dumps(scene))
        print(f"{cap.name}: {redrawn} redrawn, {merged} merged, {len(final) - len(kept)} dropped → {len(kept)} holds in scene-sam.json")

if __name__ == "__main__":
    main()
