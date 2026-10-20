# /// script
# requires-python = ">=3.11"
# dependencies = ["numpy>=2", "opencv-python-headless>=4.10"]
# ///
"""Tape detection prototype: finds tape strips in a capture's keyframes, lifts them onto the wall plane and
groups holds by tape colour into routes (route definition types `tape` and `comp`).

    uv run tools/tape.py captures/<id>            # writes <id>/tape.json and <id>/tape-debug/*.jpg
    uv run tools/tape.py captures/<id> --apply    # also rewrites scene.json routes from the tape groups
    uv run tools/tape.py --selftest               # checks the pixel → wall-plane lifting on a synthetic frame

Inputs: frames.json (world poses + intrinsics, written at Finish), scene.json (wall plane, holds).
Mirrors the plan in ARCHITECTURE.md §9; thresholds below are starting points to tune on real keyframes.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np

# Thresholds (pixels at 1080p unless noted)
MIN_SATURATION = 90          # 0..255; tape is strongly coloured
MIN_VALUE = 60
MIN_AREA, MAX_AREA = 60, 2500
MIN_ASPECT = 2.2             # length / width of the minimum-area rectangle
MAX_DISTANCE_M = 5.0         # ignore detections lifted further than this from the camera
WALL_BAND_M = 0.08           # candidate must lift within this distance of the wall plane (tape is flat on the wall)
CELL_M = 0.02                # clustering cell in the wall frame
MIN_FRAMES = 2               # a tape cluster must be seen in this many keyframes
TAPE_HOLD_RADIUS_M = 0.12    # tape sits beside its hold
HUE_GAP_DEG = 25


def load_json(path: Path) -> dict:
    return json.loads(path.read_text())


def lift(px: np.ndarray, intr: dict, world_r: np.ndarray, world_t: np.ndarray, origin: np.ndarray, normal: np.ndarray):
    """Pixel (N,2) → world points on the wall plane (N,3) and camera distances (N,). Camera looks down -Z."""
    x = (px[:, 0] - intr["cx"]) / intr["fx"]
    y = -(px[:, 1] - intr["cy"]) / intr["fy"]
    d_cam = np.stack([x, y, -np.ones_like(x)], 1)
    d_world = d_cam @ world_r.T
    denom = d_world @ normal
    t = ((origin - world_t) @ normal) / np.where(np.abs(denom) < 1e-9, np.nan, denom)
    pts = world_t + d_world * t[:, None]
    return pts, t * np.linalg.norm(d_cam, axis=1)


def detect_candidates(bgr: np.ndarray) -> list[dict]:
    import cv2
    hsv = cv2.cvtColor(bgr, cv2.COLOR_BGR2HSV)
    mask = ((hsv[:, :, 1] >= MIN_SATURATION) & (hsv[:, :, 2] >= MIN_VALUE)).astype(np.uint8)
    mask = cv2.morphologyEx(mask, cv2.MORPH_OPEN, np.ones((3, 3), np.uint8))
    n, labels, stats, centroids = cv2.connectedComponentsWithStats(mask, 8)
    out = []
    for i in range(1, n):
        area = stats[i, cv2.CC_STAT_AREA]
        if area < MIN_AREA or area > MAX_AREA:
            continue
        ys, xs = np.nonzero(labels == i)
        rect = cv2.minAreaRect(np.stack([xs, ys], 1).astype(np.float32))
        w, h = rect[1]
        if min(w, h) < 1 or max(w, h) / min(w, h) < MIN_ASPECT:
            continue
        hue = float(np.median(hsv[ys, xs, 0])) * 2  # OpenCV hue is 0..179
        out.append({"px": centroids[i].tolist(), "area": int(area), "hue": hue, "angle": float(rect[2]), "len_px": float(max(w, h))})
    return out


def hue_name(h: float) -> str:
    names = [(0, "Red"), (30, "Orange"), (55, "Yellow"), (90, "Green"), (170, "Cyan"), (210, "Blue"), (275, "Purple"), (320, "Pink"), (360, "Red")]
    for i in range(len(names) - 1):
        if names[i][0] <= h < names[i + 1][0]:
            return names[i][1]
    return "Red"


def group_by_hue(hues: list[float]) -> list[int]:
    """Circular hue clustering like RouteGrouper.hueClusters; returns a group index per hue."""
    if not hues:
        return []
    order = np.argsort(hues)
    sorted_h = np.array(hues)[order]
    gaps = (np.roll(sorted_h, -1) - sorted_h) % 360
    start = (int(np.argmax(gaps)) + 1) % len(hues)
    groups = np.zeros(len(hues), int)
    g = 0
    for k in range(1, len(hues)):
        prev, cur = sorted_h[(start + k - 1) % len(hues)], sorted_h[(start + k) % len(hues)]
        if (cur - prev) % 360 > HUE_GAP_DEG:
            g += 1
        groups[order[(start + k) % len(hues)]] = g
    groups[order[start]] = 0
    return groups.tolist()


def run(capture: Path, apply: bool) -> int:
    import cv2
    frames = load_json(capture / "frames.json")["frames"]
    scene = load_json(capture / "scene.json")
    wall = scene["wall"]
    origin, normal = np.array(wall["origin"]), np.array(wall["normal"])
    right, up = np.array(wall["right"]), np.array(wall["up"])
    debug = capture / "tape-debug"
    debug.mkdir(exist_ok=True)

    cells: dict[tuple[int, int], list] = {}
    for f in frames:
        img = cv2.imread(str(capture / f["file"]))
        if img is None:
            continue
        cands = detect_candidates(img)
        if not cands:
            continue
        r = np.array(f["worldFromCamera"]["r"]).reshape(3, 3)
        t = np.array(f["worldFromCamera"]["t"])
        px = np.array([c["px"] for c in cands])
        pts, dist = lift(px, f["intrinsics"], r, t, origin, normal)
        kept = 0
        for c, p, d in zip(cands, pts, dist):
            if not np.isfinite(d) or d <= 0 or d > MAX_DISTANCE_M:
                continue
            # Scale check: a 2–5 cm strip at distance d spans ~ fx * size / d pixels.
            size_m = c["len_px"] * d / f["intrinsics"]["fx"]
            if size_m < 0.015 or size_m > 0.12:
                continue
            u, v = float((p - origin) @ right), float((p - origin) @ up)
            key = (int(np.floor(u / CELL_M)), int(np.floor(v / CELL_M)))
            cells.setdefault(key, []).append({"frame": f["file"], "hue": c["hue"], "u": u, "v": v})
            cv2.circle(img, tuple(int(x) for x in c["px"]), 10, (0, 255, 255), 2)
            kept += 1
        cv2.imwrite(str(debug / Path(f["file"]).name), img, [cv2.IMWRITE_JPEG_QUALITY, 70])
        print(f"{f['file']}: {len(cands)} candidates, {kept} on the wall")

    tapes = []
    for key, obs in cells.items():
        if len({o['frame'] for o in obs}) < MIN_FRAMES:
            continue
        hues = np.radians([o["hue"] for o in obs])
        hue = float(np.degrees(np.arctan2(np.sin(hues).mean(), np.cos(hues).mean())) % 360)
        tapes.append({"u": float(np.mean([o["u"] for o in obs])), "v": float(np.mean([o["v"] for o in obs])), "hue": hue, "frames": len(obs)})

    holds = scene["holds"]
    for tp in tapes:
        best, best_d = None, TAPE_HOLD_RADIUS_M
        for h in holds:
            d = float(np.hypot(h["uv"][0] - tp["u"], h["uv"][1] - tp["v"]))
            if d < best_d:
                best, best_d = h["id"], d
        tp["holdId"] = best
    groups = group_by_hue([tp["hue"] for tp in tapes])
    routes: dict[int, dict] = {}
    for tp, g in zip(tapes, groups):
        route = routes.setdefault(g, {"hue": tp["hue"], "holdIds": set(), "tapes": 0})
        route["tapes"] += 1
        if tp["holdId"] is not None:
            route["holdIds"].add(tp["holdId"])
    result = {
        "tapes": tapes,
        "routes": [{"name": hue_name(r["hue"]), "hue": r["hue"], "holdIds": sorted(r["holdIds"]), "tapes": r["tapes"]} for r in routes.values()],
    }
    (capture / "tape.json").write_text(json.dumps(result, indent=1))
    print(f"{len(tapes)} tape marks → {len(result['routes'])} routes: " +
          ", ".join(f"{r['name']}({len(r['holdIds'])} holds)" for r in result["routes"]))

    if apply:
        for h in holds:
            h["routeIds"], h["routeId"] = [], -1
        scene["routes"] = []
        for i, r in enumerate(result["routes"], 1):
            if len(r["holdIds"]) < 2:
                continue
            bgr = cv2.cvtColor(np.uint8([[[r["hue"] / 2, 220, 220]]]), cv2.COLOR_HSV2RGB)[0, 0].tolist()
            scene["routes"].append({"id": i, "name": r["name"], "color": bgr, "grade": "", "holdIds": r["holdIds"]})
            for h in holds:
                if h["id"] in r["holdIds"]:
                    h["routeIds"].append(i)
                    h["routeId"] = h["routeIds"][0]
        (capture / "scene.json").write_text(json.dumps(scene, indent=1))
        print(f"scene.json routes replaced ({len(scene['routes'])} routes)")
    return 0


def selftest() -> int:
    """A camera 2 m in front of the wall z=0 looking at -Z: pixel of a known wall point must lift back to it."""
    intr = {"fx": 1390.0, "fy": 1387.0, "cx": 954.0, "cy": 542.0}
    r, t = np.eye(3), np.array([0.3, 1.0, 2.0])
    target = np.array([0.8, 1.4, 0.0])
    cam = target - t                     # camera space == world (identity rotation)
    px = np.array([[intr["cx"] + intr["fx"] * cam[0] / -cam[2], intr["cy"] - intr["fy"] * cam[1] / -cam[2]]])
    pts, dist = lift(px, intr, r, t, np.zeros(3), np.array([0, 0, 1.0]))
    err = float(np.linalg.norm(pts[0] - target))
    print(f"lift error {err:.2e} m, distance {dist[0]:.3f} m (expected {np.linalg.norm(cam):.3f})")
    assert err < 1e-9 and abs(dist[0] - np.linalg.norm(cam)) < 1e-9
    assert group_by_hue([10, 15, 120, 125, 350]) == [0, 0, 1, 1, 0]
    print("selftest ok")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("capture", nargs="?", type=Path)
    ap.add_argument("--apply", action="store_true")
    ap.add_argument("--selftest", action="store_true")
    args = ap.parse_args()
    if args.selftest:
        return selftest()
    if args.capture is None:
        ap.error("capture directory required")
    return run(args.capture, args.apply)


if __name__ == "__main__":
    sys.exit(main())
