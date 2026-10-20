# /// script
# requires-python = ">=3.11"
# dependencies = ["numpy>=2", "matplotlib>=3.9"]
# ///
"""Offline segment snap: registers each segments/segment-NN.ply of a capture onto the previous ones.

Mirrors android core/SegmentSnapper.kt (plane alignment, then relief-map cross-correlation for in-plane
shift and roll) so snap behaviour can be tuned on captured data before changing the app.

    uv run tools/snap.py captures/<id>            # writes <id>/snapped.ply and <id>/snap.png
    uv run tools/snap.py captures/<id> --no-snap  # merge by anchor pose only (what the app shows live)
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

import numpy as np

# Mirror of Tuning.kt (segment snap + plane fit)
VOXEL = 0.01
SNAP_MIN_VOXELS = 2000
SNAP_CELL = 0.02
SNAP_MAX_SHIFT = 0.5
SNAP_MAX_ROLL_DEG = 3.0
SNAP_ROLL_STEP_DEG = 0.5
SNAP_MIN_RELIEF = -0.03
SNAP_MAX_RELIEF = 0.30
SNAP_MIN_OVERLAP_CELLS = 500
SNAP_MIN_SCORE = 0.35
SNAP_MAX_TILT_DEG = 35.0
SNAP_MAX_DEPTH = 0.6
PLANE_INLIER_DIST = 0.02
MAX_WALL_NORMAL_Y = 0.7
RANSAC_ITERATIONS = 600
RANSAC_SAMPLE_POINTS = 60_000


def read_ply(path: Path) -> tuple[np.ndarray, np.ndarray]:
    with open(path, "rb") as f:
        header = b""
        while not header.endswith(b"end_header\n"):
            header += f.readline()
        n = int(re.search(rb"element vertex (\d+)", header).group(1))
        data = np.frombuffer(f.read(n * 15), dtype=np.dtype([("xyz", "<f4", 3), ("rgb", "u1", 3)]))
    return data["xyz"].astype(np.float64), data["rgb"].copy()


def write_ply(path: Path, xyz: np.ndarray, rgb: np.ndarray) -> None:
    out = np.empty(len(xyz), dtype=np.dtype([("xyz", "<f4", 3), ("rgb", "u1", 3)]))
    out["xyz"] = xyz
    out["rgb"] = rgb
    header = (
        f"ply\nformat binary_little_endian 1.0\nelement vertex {len(xyz)}\n"
        "property float x\nproperty float y\nproperty float z\n"
        "property uchar red\nproperty uchar green\nproperty uchar blue\nend_header\n"
    )
    with open(path, "wb") as f:
        f.write(header.encode("ascii"))
        f.write(out.tobytes())


def fit_plane(xyz: np.ndarray, toward: np.ndarray, rng: np.random.Generator) -> tuple[np.ndarray, np.ndarray] | None:
    """RANSAC near-vertical plane, least-squares refined, normal oriented toward `toward`. Returns (origin, normal)."""
    n = len(xyz)
    if n < 100:
        return None
    sample = xyz if n <= RANSAC_SAMPLE_POINTS else xyz[rng.choice(n, RANSAC_SAMPLE_POINTS, replace=False)]
    best, best_inliers = None, 0
    for _ in range(RANSAC_ITERATIONS):
        a, b, c = xyz[rng.choice(n, 3, replace=False)]
        nrm = np.cross(b - a, c - a)
        length = np.linalg.norm(nrm)
        if length < 1e-6:
            continue
        nrm /= length
        if abs(nrm[1]) > MAX_WALL_NORMAL_Y:
            continue
        inliers = int(np.sum(np.abs((sample - a) @ nrm) < PLANE_INLIER_DIST))
        if inliers > best_inliers:
            best, best_inliers = (a, nrm), inliers
    if best is None:
        return None
    a, nrm = best
    inl = xyz[np.abs((xyz - a) @ nrm) < PLANE_INLIER_DIST]
    if len(inl) < 3:
        return None
    origin = inl.mean(0)
    _, _, vt = np.linalg.svd(inl - origin, full_matrices=False)
    normal = vt[2]
    if (toward - origin) @ normal < 0:
        normal = -normal
    return origin, normal


def rotation_about(axis: np.ndarray, angle: float, pivot: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    length = np.linalg.norm(axis)
    if length < 1e-12:
        return np.eye(3), np.zeros(3)
    x, y, z = axis / length
    c, s, k = np.cos(angle), np.sin(angle), 1 - np.cos(angle)
    r = np.array([
        [c + x * x * k, x * y * k - z * s, x * z * k + y * s],
        [y * x * k + z * s, c + y * y * k, y * z * k - x * s],
        [z * x * k - y * s, z * y * k + x * s, c + z * z * k],
    ])
    return r, pivot - r @ pivot


def compose(a: tuple, b: tuple) -> tuple:
    """a ∘ b (apply b first)."""
    return a[0] @ b[0], a[0] @ b[1] + a[1]


def apply(t: tuple, xyz: np.ndarray) -> np.ndarray:
    return xyz @ t[0].T + t[1]


def wall_frame(origin: np.ndarray, normal: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    """(right, up) in-plane axes matching WallFrame.kt: up = world Y projected, right = up × normal."""
    up = np.array([0.0, 1.0, 0.0]) - normal[1] * normal
    if np.linalg.norm(up) < 0.1:
        up = np.array([0.0, 0.0, 1.0]) - normal[2] * normal
    up /= np.linalg.norm(up)
    right = np.cross(up, normal)
    return right, up


def relief(u: np.ndarray, v: np.ndarray, d: np.ndarray, u0: float, v0: float, w: int, h: int) -> np.ndarray:
    """Max relief per cell; NaN where empty."""
    i = np.floor((u - u0) / SNAP_CELL).astype(int)
    j = np.floor((v - v0) / SNAP_CELL).astype(int)
    ok = (i >= 0) & (j >= 0) & (i < w) & (j < h)
    grid = np.full(h * w, -np.inf)
    np.maximum.at(grid, j[ok] * w + i[ok], d[ok])
    grid[np.isinf(grid)] = np.nan
    return grid.reshape(h, w)


def best_shift(ref: np.ndarray, mov: np.ndarray, max_shift: int) -> tuple[int, int, int, float] | None:
    h, w = ref.shape
    best = None
    for dy in range(-max_shift, max_shift + 1):
        for dx in range(-max_shift, max_shift + 1):
            # moving cell (i, j) is compared with reference cell (i + dx, j + dy)
            ys = slice(max(0, -dy), min(h, h - dy))
            xs = slice(max(0, -dx), min(w, w - dx))
            m = mov[ys, xs]
            r = ref[ys.start + dy:ys.stop + dy, xs.start + dx:xs.stop + dx]
            both = ~np.isnan(m) & ~np.isnan(r)
            n = int(both.sum())
            if n < SNAP_MIN_OVERLAP_CELLS:
                continue
            a, b = m[both], r[both]
            va, vb = a.var(), b.var()
            if va <= 1e-12 or vb <= 1e-12:
                continue
            score = float(((a - a.mean()) * (b - b.mean())).mean() / np.sqrt(va * vb))
            if best is None or score > best[3]:
                best = (dx, dy, n, score)
    return best


def snap(reference: np.ndarray, moving: np.ndarray, toward: np.ndarray, rng: np.random.Generator) -> dict | None:
    if len(reference) < SNAP_MIN_VOXELS or len(moving) < SNAP_MIN_VOXELS:
        return None
    ref = fit_plane(reference, toward, rng)
    mov = fit_plane(moving, toward, rng)
    if ref is None or mov is None:
        return None
    (ro, rn), (mo, mn) = ref, mov

    # Stage 1: tilt moving normal onto reference normal about the moving origin, then fix depth.
    angle = float(np.arccos(np.clip(mn @ rn, -1, 1)))
    tilt = rotation_about(np.cross(mn, rn), angle, mo)
    depth = float((mo - ro) @ rn)
    stage1 = compose((np.eye(3), -depth * rn), tilt)
    moved = apply(stage1, moving)
    result = {"tilt_deg": np.degrees(angle), "depth": depth, "transform": stage1, "accepted": False,
              "shift": (0.0, 0.0), "roll_deg": 0.0, "overlap": 0, "score": 0.0}
    if np.degrees(angle) > SNAP_MAX_TILT_DEG or abs(depth) > SNAP_MAX_DEPTH:
        return result

    # Stage 2: relief maps in the reference wall frame.
    right, up = wall_frame(ro, rn)

    def project(xyz):
        rel = xyz - ro
        d = rel @ rn
        keep = (d >= SNAP_MIN_RELIEF) & (d <= SNAP_MAX_RELIEF)
        return rel[keep] @ right, rel[keep] @ up, d[keep]

    ru, rv, rd = project(reference)
    mu, mv, md = project(moved)
    if len(ru) < 3 or len(mu) < 3:
        return result
    pad = SNAP_MAX_SHIFT + 2 * SNAP_CELL
    u0 = min(ru.min(), mu.min()) - pad
    v0 = min(rv.min(), mv.min()) - pad
    w = int(np.ceil((max(ru.max(), mu.max()) + pad - u0) / SNAP_CELL)) + 1
    h = int(np.ceil((max(rv.max(), mv.max()) + pad - v0) / SNAP_CELL)) + 1
    ref_map = relief(ru, rv, rd, u0, v0, w, h)
    um, vm = mu.mean(), mv.mean()
    max_shift = int(round(SNAP_MAX_SHIFT / SNAP_CELL))
    best = None
    for roll in np.arange(-SNAP_MAX_ROLL_DEG, SNAP_MAX_ROLL_DEG + 1e-9, SNAP_ROLL_STEP_DEG):
        c, s = np.cos(np.radians(roll)), np.sin(np.radians(roll))
        du, dv = mu - um, mv - vm
        mov_map = relief(um + c * du - s * dv, vm + s * du + c * dv, md, u0, v0, w, h)
        m = best_shift(ref_map, mov_map, max_shift)
        if m is not None and (best is None or m[3] > best[3]):
            best = (*m, roll)
    if best is None:
        return result
    dx, dy, overlap, score, roll = best
    result.update(shift=(dx * SNAP_CELL, dy * SNAP_CELL), roll_deg=float(roll), overlap=overlap, score=score)
    if overlap < SNAP_MIN_OVERLAP_CELLS or score < SNAP_MIN_SCORE:
        return result
    centroid = ro + um * right + vm * up
    roll_t = rotation_about(rn, np.radians(roll), centroid)
    shift_t = (np.eye(3), dx * SNAP_CELL * right + dy * SNAP_CELL * up)
    result.update(transform=compose(shift_t, compose(roll_t, stage1)), accepted=True)
    return result


def voxelize(xyz: np.ndarray, rgb: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    """Average points per 1 cm voxel, like VoxelMap."""
    keys = np.floor(xyz / VOXEL).astype(np.int64)
    _, inv = np.unique(keys, axis=0, return_inverse=True)
    inv = inv.ravel()
    counts = np.bincount(inv)
    sx = np.stack([np.bincount(inv, weights=xyz[:, k]) for k in range(3)], 1) / counts[:, None]
    sc = np.stack([np.bincount(inv, weights=rgb[:, k]) for k in range(3)], 1) / counts[:, None]
    return sx, sc.astype(np.uint8)


def camera_hint(capture: Path) -> np.ndarray | None:
    """Mean camera position from the STATUS lines of scan.log."""
    log = capture / "scan.log"
    if not log.exists():
        return None
    pts = [tuple(map(float, m.groups())) for m in re.finditer(r"pos=t=\(([-\d.]+), ([-\d.]+), ([-\d.]+)\)", log.read_text())]
    return np.array(pts).mean(0) if pts else None


def render(path: Path, before: list, after: list) -> None:
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    fig, axes = plt.subplots(1, 2, figsize=(16, 8))
    for ax, clouds, title in ((axes[0], before, "anchor poses only"), (axes[1], after, "after snap")):
        for xyz, rgb in clouds:
            ax.scatter(xyz[:, 0], xyz[:, 1], c=rgb / 255.0, s=0.2, marker=".")
        ax.set_aspect("equal")
        ax.set_title(title)
    fig.savefig(path, dpi=80)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("capture", type=Path, help="capture directory containing segments/segment-NN.ply")
    ap.add_argument("--no-snap", action="store_true", help="merge by anchor pose only")
    ap.add_argument("--toward", type=float, nargs=3, metavar=("X", "Y", "Z"), help="a point on the climber's side of the wall")
    args = ap.parse_args()

    files = sorted((args.capture / "segments").glob("segment-*.ply"))
    if not files:
        print(f"no segments in {args.capture / 'segments'}", file=sys.stderr)
        return 1
    rng = np.random.default_rng(42)
    toward = np.array(args.toward) if args.toward else camera_hint(args.capture)
    before, after = [], []
    merged_xyz, merged_rgb = np.empty((0, 3)), np.empty((0, 3), dtype=np.uint8)
    for f in files:
        xyz, rgb = read_ply(f)
        before.append((xyz, rgb))
        if len(merged_xyz) and not args.no_snap:
            hint = toward if toward is not None else merged_xyz.mean(0)
            r = snap(merged_xyz, xyz, hint, rng)
            if r is None:
                print(f"{f.name}: {len(xyz)} pts · snap skipped (no wall plane or too few points)")
            else:
                verdict = "accepted" if r["accepted"] else "rejected"
                print(f"{f.name}: {len(xyz)} pts · {verdict} tilt={r['tilt_deg']:.2f}° depth={r['depth']:+.3f} "
                      f"shift=({r['shift'][0]:+.3f}, {r['shift'][1]:+.3f}) roll={r['roll_deg']:+.1f}° "
                      f"overlap={r['overlap']} score={r['score']:.3f}")
                if r["accepted"]:
                    xyz = apply(r["transform"], xyz)
        else:
            print(f"{f.name}: {len(xyz)} pts · reference")
        after.append((xyz, rgb))
        merged_xyz, merged_rgb = voxelize(np.vstack([merged_xyz, xyz]), np.vstack([merged_rgb, rgb]))
    write_ply(args.capture / "snapped.ply", merged_xyz, merged_rgb)
    render(args.capture / "snap.png", before, after)
    print(f"merged {len(merged_xyz)} voxels → {args.capture / 'snapped.ply'}, {args.capture / 'snap.png'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
