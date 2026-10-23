# /// script
# requires-python = ">=3.11,<3.13"
# dependencies = ["roboflow>=1.1"]
# ///
"""Downloads the public CC BY 4.0 climbing-hold datasets from Roboflow Universe in YOLOv8-seg format.

    ROBOFLOW_API_KEY=… uv run tools/holds_model/download.py [--out tools/holds_model/data/public]

A free Roboflow account's API key is enough for public Universe projects. Each dataset lands in its own
directory with the usual train/valid/test folders and a data.yaml.
"""
import argparse
import os
import sys
from pathlib import Path

from roboflow import Roboflow

DATASETS = [  # workspace, project (Universe slugs); the latest version of each is taken
    ("climb-ai", "hold-detector-rnvkl"),
    ("pwals", "bouldering-holds-9wavr"),
    ("roboflow-4rfmv", "climbing-y56wy"),
]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", type=Path, default=Path("tools/holds_model/data/public"))
    args = ap.parse_args()
    key = os.environ.get("ROBOFLOW_API_KEY")
    if not key:
        sys.exit("set ROBOFLOW_API_KEY (Roboflow → Settings → API keys)")
    rf = Roboflow(api_key=key)
    for workspace, project in DATASETS:
        p = rf.workspace(workspace).project(project)
        version = max(v.version for v in p.versions())
        target = args.out / project
        if target.exists():
            print(f"{project}: already at {target}")
            continue
        p.version(version).download("yolov8", location=str(target))
        print(f"{project}: version {version} → {target}")


if __name__ == "__main__":
    main()
