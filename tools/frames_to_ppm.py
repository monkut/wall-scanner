# /// script
# requires-python = ">=3.11"
# dependencies = ["pillow>=10"]
# ///
"""Decodes a capture's keyframe JPEGs to half-size binary PPM files for the JVM test harnesses, and its
orthophoto `wall.png` (when pulled) to `wall.ppm`.

Android unit tests compile against android.jar, which has no JPEG or PNG decoder (no javax.imageio, no
BitmapFactory), so `RealCaptureHarness` reads `frames-ppm/frame-NNNN.ppm` and `StartHarness` reads
`wall.ppm` instead when present.

    uv run tools/frames_to_ppm.py captures/<id>
"""
import sys
from pathlib import Path

from PIL import Image

SCALE = 2  # same as ScenePipeline.KEYFRAME_SUBSAMPLE

for cap in sys.argv[1:]:
    src = Path(cap) / "frames"
    dst = Path(cap) / "frames-ppm"
    dst.mkdir(exist_ok=True)
    n = 0
    for jpg in sorted(src.glob("*.jpg")):
        im = Image.open(jpg).convert("RGB")
        im = im.resize((im.width // SCALE, im.height // SCALE), Image.BILINEAR)
        im.save(dst / (jpg.stem + ".ppm"))
        n += 1
    print(f"{cap}: {n} keyframes -> {dst}")
    wall = Path(cap) / "wall.png"
    if wall.is_file():
        Image.open(wall).convert("RGB").save(Path(cap) / "wall.ppm")
        print(f"{cap}: wall.png -> wall.ppm")
