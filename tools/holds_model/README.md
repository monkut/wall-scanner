# Hold detector training (Phase 2 of HOLD-SEGMENTATION.md)

Fine-tunes YOLO11-seg on public climbing-hold datasets plus this app's own corrected walls, and exports it
for the phone. Every script is a `uv` script; run them from the repository root.

| Script | What |
|---|---|
| `download.py` | Pulls the public hold datasets from Roboflow Universe (`ROBOFLOW_API_KEY`, free account) in YOLOv8-seg format into `data/public/`. |
| `ours_to_yolo.py captures/<id>…` | Turns a capture's `wall.png` and its reference (`reference.json`, else `scene.json`) into YOLO-seg labels under `data/ours/`. |
| `merge.py` | Builds `data/holds/` (classes `hold` and `tape`) from `data/public/*` and `data/ours`, split by source image, every image through `clahe.py`, and writes `data/holds.yaml`. |
| `train.py` | Fine-tunes `yolo11s-seg` on `data/holds.yaml` with hard colour augmentation (any hue, one image in five grey) so holds are found by shape, not colour; weights land in `runs/<name>/`. |
| `clahe.py` | The CLAHE step every model input goes through; `android/.../core/Clahe.kt` is its exact port (`ClaheTest` pins the two together). |
| `export.py runs/<run>/weights/best.pt` | Exports TFLite (int8 weights, float32 activations: `w8a32`) and copies it to `android/app/src/main/assets/holds.tflite`. |
| `predict.py <weights> captures/<id>… [--no-clahe]` | Runs the model on a capture's orthophoto (CLAHE first; `--no-clahe` for models trained before it) and writes `scene-model.json` (holds, and `tape` outlines, in wall metres) for `eval_holds.py`. |
| `eval_holds.py captures/<id>… [--pred scene-new.json]` | Precision / recall / mean IoU of a scene's hold outlines against the capture's reference. |

`data/` and `runs/` are git-ignored. Attribution for the public sets (CC BY 4.0): Climb AI "Hold Detector",
Pwals "Bouldering holds", Roboflow "Climbing" on Roboflow Universe. PanClimb "Climbing Segmentation" (public
domain) supplies the `tape` class (its eleven hold types, volumes included, all become `hold`).
