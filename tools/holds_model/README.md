# Hold detector training (Phase 2 of HOLD-SEGMENTATION.md)

Fine-tunes YOLO11-seg on public climbing-hold datasets plus this app's own corrected walls, and exports it
for the phone. Every script is a `uv` script; run them from the repository root.

| Script | What |
|---|---|
| `download.py` | Pulls the CC BY 4.0 hold datasets from Roboflow Universe (`ROBOFLOW_API_KEY`, free account) in YOLOv8-seg format into `data/public/`. |
| `ours_to_yolo.py captures/<id>…` | Turns a capture's `wall.png` and its reference (`reference.json`, else `scene.json`) into YOLO-seg labels under `data/ours/`. |
| `merge.py` | Builds `data/holds/` (one class, `hold`) from `data/public/*` and `data/ours`, split by source image, and writes `data/holds.yaml`. |
| `train.py` | Fine-tunes `yolo11s-seg` on `data/holds.yaml`; weights land in `runs/`. |
| `export.py runs/<run>/weights/best.pt` | Exports TFLite (fp16) and copies it to `android/app/src/main/assets/holds.tflite`. |
| `predict.py <weights> captures/<id>…` | Runs the model on a capture's orthophoto and writes `scene-model.json` (holds with outlines in wall metres) for `eval_holds.py`. |
| `eval_holds.py captures/<id>… [--pred scene-new.json]` | Precision / recall / mean IoU of a scene's hold outlines against the capture's reference. |

`data/` and `runs/` are git-ignored. Attribution for the public sets (CC BY 4.0): Climb AI "Hold Detector",
Pwals "Bouldering holds", Roboflow "Climbing" on Roboflow Universe.
