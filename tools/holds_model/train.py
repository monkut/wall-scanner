# /// script
# requires-python = ">=3.11,<3.13"
# dependencies = ["ultralytics>=8.3"]
# ///
"""Fine-tunes a YOLO11 segmentation model on data/holds.yaml.

    uv run tools/holds_model/train.py [--model yolo11s-seg.pt] [--imgsz 1024] [--epochs 100] [--batch 8]

Weights and curves go to tools/holds_model/runs/<name>/ (base weights are downloaded into tools/holds_model/). The orthophotos are ~700 px wide at 5 mm per pixel
and footholds are 8–20 px across, so 1024 keeps them visible; the public gym photos are resized to the same
size. Mosaic is left on; flips keep holds valid; hue augmentation is kept small so hold colours stay true.
"""
import argparse
import os
from pathlib import Path

from ultralytics import YOLO

HERE = Path(__file__).resolve().parent


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="yolo11s-seg.pt")
    ap.add_argument("--data", default="tools/holds_model/data/holds.yaml")
    ap.add_argument("--imgsz", type=int, default=1024)
    ap.add_argument("--epochs", type=int, default=100)
    ap.add_argument("--batch", type=int, default=8)
    ap.add_argument("--name", default="holds")
    args = ap.parse_args()
    data = Path(args.data).resolve()
    # ultralytics downloads base weights into the working directory and nests a relative project under
    # runs/<task>/: work from this folder with an absolute project so everything stays in tools/holds_model/.
    os.chdir(HERE)
    YOLO(args.model).train(
        data=str(data), imgsz=args.imgsz, epochs=args.epochs, batch=args.batch,
        project=str(HERE / "runs"), name=args.name, exist_ok=True,
        hsv_h=0.005, hsv_s=0.4, hsv_v=0.3, fliplr=0.5, flipud=0.0, degrees=5.0, scale=0.4,
        patience=30,
    )


if __name__ == "__main__":
    main()
