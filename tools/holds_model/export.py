# /// script
# requires-python = ">=3.11,<3.13"
# dependencies = ["ultralytics>=8.3", "tensorflow>=2.16", "onnx>=1.16", "onnx2tf>=1.26", "onnxslim", "tf_keras"]
# ///
"""Exports trained weights to TFLite for the app.

    uv run tools/holds_model/export.py tools/holds_model/runs/holds/weights/best.pt [--imgsz 1024]

int8 weights with float32 activations and float I/O (`quantize="w8a32"`: a quarter of the fp32 size, no
calibration), which is what io/HoldModel.kt reads. Full-int8 exports (int8 tensors) are not supported by the
app. The exported file is copied to android/app/src/main/assets/holds.tflite.
"""
import argparse
import shutil
from pathlib import Path

from ultralytics import YOLO

ASSET = Path("android/app/src/main/assets/holds.tflite")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("weights", type=Path)
    ap.add_argument("--imgsz", type=int, default=1024)
    args = ap.parse_args()
    exported = Path(YOLO(str(args.weights)).export(format="tflite", imgsz=args.imgsz, quantize="w8a32"))
    ASSET.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy(exported, ASSET)
    print(f"{exported} → {ASSET} ({ASSET.stat().st_size / 1e6:.1f} MB)")


if __name__ == "__main__":
    main()
