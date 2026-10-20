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

1. **New scan** → point at the wall from 1–3 m, tap **Start scan**, sweep slowly in overlapping rows. The status line shows point count and depth frames.
2. **Finish & process** → wall plane fit, hold detection, route grouping (a few seconds).
3. Viewer: orbit/pinch; **Routes** panel to rename, grade, delete, or create routes; tap a swatch then tap holds to edit membership; **✓** logs a send; **Save** persists; **Share** exports a zip (`scene.json` + `points.ply`).

## Layout

| Path | Role |
|---|---|
| `core/` | Pure Kotlin pipeline (no Android deps): `VoxelMap`, `PlaneFitter`, `HoldDetector`, `RouteGrouper`, `SceneJson`, `PlyWriter`. Thresholds in `Tuning.kt`. |
| `scan/` | ARCore session, GL camera background, point overlay, `DepthFrameProcessor` (raw depth → world points). |
| `viewer/` | WebView host for `assets/viewer/index.html` (three.js) with a JS bridge for saving/sharing. |
| `io/CaptureStore.kt` | `files/captures/<JST timestamp>/{scene.json, points.ply}` |

## Known limits (v0.1)

- Single dominant wall plane; multi-panel walls (slab + overhang) get holds only on the dominant panel.
- Depth-from-motion resolution limits detection of low-profile holds (< 2 cm) and footchips.
- Route grouping is color-only; mixed-color sets need manual editing in the viewer.
- TODO: multi-plane segmentation, ML hold segmentation, capture bundle export (frames + poses) for the splat backend.
