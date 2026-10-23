# /// script
# requires-python = ">=3.11,<3.13"
# dependencies = ["ultralytics>=8.3", "albumentations>=1.4"]
# ///
"""Fine-tunes a YOLO11 segmentation model on data/holds.yaml.

    uv run tools/holds_model/train.py [--model yolo11s-seg.pt] [--imgsz 1024] [--epochs 100] [--batch 8]

Weights and curves go to tools/holds_model/runs/<name>/ (base weights are downloaded into tools/holds_model/). The orthophotos are ~700 px wide at 5 mm per pixel
and footholds are 8–20 px across, so 1024 keeps them visible; the public gym photos are resized to the same
size. Mosaic is left on; flips keep holds valid. Colour is augmented hard (any hue, strong saturation and
brightness changes, one image in five grey) so the model finds holds by shape and relief of the photo, not
by colour: colour picks the route afterwards, and shading or a two-tone hold must not split or hide a hold.
The images are already CLAHE-equalised by merge.py, as the app's input is.
"""
import argparse
import os
from pathlib import Path

import albumentations as A
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
        hsv_h=0.5, hsv_s=0.9, hsv_v=0.6, fliplr=0.5, flipud=0.0, degrees=5.0, scale=0.4,
        augmentations=[A.Blur(p=0.01), A.MedianBlur(p=0.01), A.ToGray(p=0.2)],
        patience=30,
    )


if __name__ == "__main__":
    main()
