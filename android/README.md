# Wall Scanner — Android app (v0.1)

On-device climbing wall scanner: ARCore raw depth → colored voxel cloud → wall plane → hold clusters → color-grouped routes → 3D viewer with route editing and send logging.

## Requirements

- Android 9+ (minSdk 28) with ARCore **Depth API** support (tested target: Pixel 9a)
- Build: JDK 17+, Android SDK platform 34, Gradle wrapper (included)

## Build and install

```bash
cd android
./gradlew :app:testDebugUnitTest     # synthetic-wall tests for the core pipeline
./gradlew :app:assembleDebug         # app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Use

1. **New scan** → choose how routes are marked (hold colour, tape, circuit, tagged, competition, free, board, or **outdoor rock** for a boulder or crag route; see `ARCHITECTURE.md` §9). The choice is saved to `capture.json` and drives processing at Finish. Point at the wall from 1–3 m, tap **Start scan**, sweep slowly in overlapping rows. The status line shows point count, depth frames and the current segment.
2. **Finish & process** → segments are snapped together, then wall plane fit, hold detection and route detection for the chosen type (colour grouping for hold-colour/tagged walls, colour + position for circuits, none for tape/competition/free/board walls until tape detection or manual editing). Outdoor rock uses a coarse reference plane (10 cm, slabs allowed) and measures each hold against the local rock surface instead of the plane; the line is then marked by hand in the viewer.
3. Viewer: orbit/pinch; **Routes** panel to rename, grade, delete, or create routes; tap a swatch then tap holds to edit membership; **✓** logs a send; **Save** persists and returns to the capture list; **Share** exports a zip (`scene.json`, `points.ply`, `segments/`, `frames/`, `scan.log`). Role buttons (start, hand, foot, zone, finish) mark hold roles; a hold may belong to several routes.

### Segments and snapping

Every contiguous ARCore tracking interval is a **segment** with its own ARCore anchor; points are stored relative to that anchor, so when ARCore relocalises or corrects drift the segment moves with it. A new segment starts whenever tracking is regained (tracking loss, screen off, app switch). The scan screen keeps the screen on (no dimming or sleep) while a capture is in progress to avoid the lifecycle pause.

On **Finish** each segment is written to `segments/segment-NN.ply` (world coordinates from its anchor) and then registered onto the merged earlier segments by `core/SegmentSnapper.kt`: wall-plane alignment (tilt + depth) followed by a relief-map cross-correlation search for in-plane shift and roll. A snap is applied only when the overlap and correlation pass the `SNAP_*` thresholds in `Tuning.kt`; otherwise the anchor pose is kept and the log says why. Resume scanning on an area that was already captured so the new segment overlaps the old data.

During the scan sparse 1080p keyframes with poses are saved to `frames/` + `frames.jsonl`, resolved to world poses in `frames.json` at Finish, for image-based tape and placard detection (`tools/tape.py`, prototype).

`tools/snap.py` (run with `uv run tools/snap.py captures/<id>`) mirrors the on-device snap on the exported `segments/` so thresholds can be tuned offline; it writes `snapped.ply` and a before/after `snap.png`.

## Layout

| Path | Role |
|---|---|
| `core/` | Pure Kotlin pipeline (no Android deps): `VoxelMap`, `PlaneFitter`, `SegmentSnapper`, `Rigid`, `HoldDetector`, `RouteGrouper`, `SceneJson`, `PlyWriter`. Thresholds in `Tuning.kt`. |
| `scan/` | ARCore session, GL camera background, per-segment point overlay, `Segment` (anchor + voxel map), `DepthFrameProcessor` (raw depth → anchor-local points). |
| `viewer/` | WebView host for `assets/viewer/index.html` (three.js) with a JS bridge for saving/sharing. |
| `io/CaptureStore.kt` | `files/captures/<JST timestamp>/{capture.json, scan.log, segments/segment-NN.ply, frames/*.jpg, frames.json, points.ply, scene.json, thumbnail.png}`; the capture list renders a front-view thumbnail (`core/Thumbnail.kt`) and generates it for older captures on first view |

## Known limits (v0.1)

- Single dominant wall plane; multi-panel walls (slab + overhang) get holds only on the dominant panel. Outdoor rock gets one coarse reference plane per scan, so a boulder should be scanned one face at a time; the thumbnail and (u, v) hold layout follow that face.
- Depth-from-motion resolution limits detection of low-profile holds (< 2 cm) and footchips.
- Route grouping is color-only; mixed-color sets need manual editing in the viewer. Gym lighting often pushes hold saturation below `MIN_ROUTE_SATURATION`, so sets come out as Gray/White/Black.
- Snap resolution is one relief cell (2 cm) and one roll step (0.5°); segments without ≥ 0.2 m² of overlap stay on their anchor pose.
- `MAX_VOXELS` (400k per segment) is reached after roughly 40–60 s of continuous scanning; later points in that segment are dropped.
- TODO: multi-plane segmentation, ML hold segmentation, capture bundle export (frames + poses) for the splat backend.
