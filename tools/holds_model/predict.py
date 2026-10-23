# /// script
# requires-python = ">=3.11,<3.13"
# dependencies = ["ultralytics>=8.3", "pillow>=10", "numpy>=1.26", "opencv-python-headless>=4.9"]
# ///
"""Runs a hold model on captures' orthophotos and writes scene-model.json beside each, for eval_holds.py.

    uv run tools/holds_model/predict.py <weights.pt|.tflite> captures/<id> [captures/<id2> …] [--imgsz 1024] [--conf 0.25]

Each predicted mask becomes a hold with its outline in wall metres (the photo's bounds and px come from the
capture's scene.json, else the harness's scene-new.json); the rest of the scene (wall, routes) is copied from scene.json so the viewer tools can
read the file.
"""
import argparse
import json
from pathlib import Path

import cv2
import numpy as np
from PIL import Image
from ultralytics import YOLO


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("weights")
    ap.add_argument("captures", nargs="+", type=Path)
    ap.add_argument("--imgsz", type=int, default=1024)
    ap.add_argument("--conf", type=float, default=0.25)
    args = ap.parse_args()
    model = YOLO(args.weights)
    for cap in args.captures:
        # The phone's scene.json has the photo's bounds; older pulls only have them in the harness's scene-new.json.
        scene = next((sc for f in ("scene.json", "scene-new.json") if (cap / f).exists()
                      for sc in [json.loads((cap / f).read_text())] if sc["wall"].get("photo")), None)
        if scene is None:
            print(f"{cap.name}: no orthophoto bounds in scene.json or scene-new.json, skipped")
            continue
        photo = scene["wall"]["photo"]
        min_u, min_v, max_u, max_v = photo["bounds"]
        px = photo["px"]
        image = next(f for f in (cap / "wall.png", cap / "wall-new.ppm") if f.exists())
        bgr = np.asarray(Image.open(image).convert("RGB"))[:, :, ::-1].copy()  # ultralytics reads no PPM; arrays are BGR
        result = model.predict(bgr, imgsz=args.imgsz, conf=args.conf, verbose=False)[0]
        holds = []
        if result.masks is not None:
            h, w = result.orig_shape
            for k, mask in enumerate(result.masks.data.cpu().numpy()):
                m = cv2.resize(mask.astype(np.uint8), (w, h), interpolation=cv2.INTER_NEAREST)
                contours, _ = cv2.findContours(m, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
                if not contours:
                    continue
                contour = max(contours, key=cv2.contourArea).reshape(-1, 2)
                if len(contour) < 3:
                    continue
                outline = [[round(min_u + x * px, 3), round(max_v - y * px, 3)] for x, y in contour]
                xs, ys = contour[:, 0], contour[:, 1]
                holds.append({
                    "id": k + 1, "center": [0, 0, 0], "uv": [round(min_u + xs.mean() * px, 4), round(max_v - ys.mean() * px, 4)],
                    "size": [round((xs.max() - xs.min() + 1) * px, 3), round((ys.max() - ys.min() + 1) * px, 3), 0],
                    "color": [0, 0, 0], "hsv": [0, 0, 0], "voxels": 0, "routeId": -1, "routeIds": [], "role": "any", "tape": None,
                    "score": round(float(result.boxes.conf[k]), 3), "outline": outline,
                })
        out = dict(scene, holds=holds, routes=[])
        (cap / "scene-model.json").write_text(json.dumps(out))
        print(f"{cap.name}: {len(holds)} holds → scene-model.json")


if __name__ == "__main__":
    main()
