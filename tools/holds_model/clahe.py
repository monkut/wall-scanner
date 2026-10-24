"""Contrast-limited adaptive histogram equalisation of brightness, as the app does it (core/Clahe.kt).

The hold model sees every image after this step, in training and on the phone, so uneven light and shading
across a wall matter less. Only luma (Y of YCrCb) changes; the chroma is kept, so hold colours stay. Written
out rather than taken from OpenCV so the Kotlin port can match it exactly:

- the image is cut into GRID × GRID tiles (tile t spans pixels floor(t·w/GRID) .. floor((t+1)·w/GRID));
- each tile's 256-bin luma histogram is clipped at max(1, floor(CLIP · tile pixels / 256)), the clipped
  excess spread evenly over all bins (integer division; the remainder is dropped), and its cumulative sum
  becomes a lookup table lut[v] = round(cdf[v] · 255 / tile pixels);
- each pixel's new luma interpolates the lookup tables of the four nearest tile centres bilinearly
  (gx = (x + 0.5) / tile width − 0.5, clamped at the borders).
"""
import numpy as np

GRID = 8
CLIP = 2.0


def luts(y: np.ndarray) -> np.ndarray:
    """GRID × GRID × 256 lookup tables for a uint8 luma image."""
    h, w = y.shape
    out = np.zeros((GRID, GRID, 256), np.float64)
    for ty in range(GRID):
        y0, y1 = ty * h // GRID, (ty + 1) * h // GRID
        for tx in range(GRID):
            x0, x1 = tx * w // GRID, (tx + 1) * w // GRID
            tile = y[y0:y1, x0:x1]
            n = tile.size
            if n == 0:
                out[ty, tx] = np.arange(256)
                continue
            hist = np.bincount(tile.ravel(), minlength=256).astype(np.int64)
            limit = max(1, int(CLIP * n / 256))
            excess = int(np.maximum(hist - limit, 0).sum())
            hist = np.minimum(hist, limit) + excess // 256
            out[ty, tx] = np.floor(np.cumsum(hist) * 255.0 / n + 0.5)
    return np.clip(out, 0, 255)


def equalize(rgb: np.ndarray) -> np.ndarray:
    """CLAHE on the luma of an H × W × 3 uint8 RGB image; returns uint8 RGB."""
    img = rgb.astype(np.float64)
    r, g, b = img[..., 0], img[..., 1], img[..., 2]
    yf = 0.299 * r + 0.587 * g + 0.114 * b
    y = np.clip(np.floor(yf + 0.5), 0, 255).astype(np.int64)
    h, w = y.shape
    lut = luts(y.astype(np.uint8))
    gx = (np.arange(w) + 0.5) / (w / GRID) - 0.5
    gy = (np.arange(h) + 0.5) / (h / GRID) - 0.5
    tx0 = np.clip(np.floor(gx).astype(int), 0, GRID - 1); tx1 = np.minimum(tx0 + 1, GRID - 1); ax = np.clip(gx - tx0, 0, 1)
    ty0 = np.clip(np.floor(gy).astype(int), 0, GRID - 1); ty1 = np.minimum(ty0 + 1, GRID - 1); ay = np.clip(gy - ty0, 0, 1)
    TY0, TX0 = np.meshgrid(ty0, tx0, indexing="ij"); TY1, TX1 = np.meshgrid(ty1, tx1, indexing="ij")
    AY, AX = np.meshgrid(ay, ax, indexing="ij")
    top = lut[TY0, TX0, y] * (1 - AX) + lut[TY0, TX1, y] * AX
    bottom = lut[TY1, TX0, y] * (1 - AX) + lut[TY1, TX1, y] * AX
    ny = top * (1 - AY) + bottom * AY
    # Keep chroma: shift every channel by the change in luma.
    out = img + (ny - yf)[..., None]
    return np.clip(np.floor(out + 0.5), 0, 255).astype(np.uint8)
