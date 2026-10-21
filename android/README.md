# Wall Scanner — Android app (v0.1)

On-device climbing wall scanner: ARCore raw depth → coloured voxel cloud → wall plane → hold clusters →
routes by marking type → 3D viewer for route identification → logbook of sends per confirmed route.

## Requirements

- Android 9+ (minSdk 28) with ARCore **Depth API** support (tested target: Pixel 9a)
- Build: JDK 17+, Android SDK platform 34, Gradle wrapper (included)

## Build and install

```bash
cd android
./gradlew :app:testDebugUnitTest     # synthetic wall / rock tests for the core pipeline
./gradlew :app:assembleDebug         # app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Usage

### Home screen: Capture and Logbook modes

A **Capture | Logbook** switch at the top selects the mode; the choice is remembered. The button to its right
shows the app language (EN-US, EN-UK or JA) and opens the language picker.

- **Capture** mode is for scanning and identifying routes: the **New scan** button and the list of captures
  (thumbnail, timestamp, location, wall type, hold / route / confirmed counts). Tap a capture to open it in the
  viewer. Long-press for **Set location**, **Share** (zip of the capture) and **Delete**. Captures without a
  3D scene are listed as "incomplete · diagnostics only" and can still be shared.
- **Logbook** mode is for recording progress: a summary line (routes · sends · flashes · locations) and the
  confirmed routes grouped under a header per location (name, kind, grade scale, route and send counts).
  Tap a route to open its record.

### Language

The app has its own language setting, independent of the device language: English (US) is the default on
first launch, English (UK) differs only in spelling (colour, initialising), and Japanese translates every
screen including the capture viewer. Dates and times follow the chosen language (Oct 20, 2026, 2:31 PM /
20 Oct 2026, 14:31 / 2026/10/20 14:31) and are always JST. The choice is stored by AppCompat's per-app
locale support (`Language.kt`) and applied to the viewer page through its `lang` query parameter.

### New scan

1. **Where are you scanning?** Pick a registered location or **New location…** (name, Gym / Outdoor area,
   grade scale Q/D, V, Font or YDS). The last used location is preselected.
2. **How are routes marked on this wall or rock?** Hold colour, tape, circuit, tagged, competition,
   free / spray wall, system board, or outdoor rock (preselected for outdoor areas). See `ARCHITECTURE.md` §9
   for what each type means and how it is processed.

Both choices are written to the capture's `capture.json` before any data is recorded.

### Scan screen

Point at the wall from 1–3 m, tap **Start scan** and sweep slowly in overlapping rows. The status line shows
point count, depth frames and the current segment. **Pause** / **Resume** stop and restart recording; when
resuming, start on an area that is already scanned so the new segment overlaps the old data. The screen stays
on while scanning. **Finish & process** runs the on-device pipeline and opens the viewer; it refuses to finish
with too few points, and if no wall plane is found it still saves the points for analysis.

### Capture viewer (identification)

Orbit, pan and pinch the point cloud; holds are drawn as boxes coloured by route. The **Routes** panel lists
the routes with a colour swatch, name, grade dropdown, hold count, **Confirm** and delete.

- Tap a swatch (or the hold count) to select a route, then tap holds on the wall to add or remove them. A hold
  may belong to several routes (tape, free walls).
- The grade dropdown is grouped by scale with the location's scale first: YDS (5.5–5.15d), Japanese Q/D
  (10Q … 1Q, 1D … 6D), V-scale (VB–V17), Font (3–9A). The scale is stored with the grade.
- Role buttons (start, hand, foot, zone, finish): tap a role, then tap holds to mark them (tap again to clear).
  Roles show as coloured outlines.
- **+ New route** creates an empty route; **✕** deletes one.
- **Confirm** marks a route as identified; only confirmed routes appear in the Logbook. Tap again to unconfirm.
- **Save** writes `scene.json` and returns to the home screen. **Share** zips the capture.

### Route record (Logbook)

Shows the route's swatch, name, grade, location, wall type, source capture and hold count with the capture
thumbnail. **Log send** records a result (Flash / Send / Attempt), the number of tries and a note, timestamped
in JST. Sends are listed newest first; long-press one to delete it. **Capture** opens the source capture in the
viewer for re-editing; the send log is kept separately and is not affected.

### Segments and snapping

Every contiguous ARCore tracking interval is a **segment** with its own ARCore anchor; points are stored
relative to that anchor, so when ARCore relocalises or corrects drift the segment moves with it. A new segment
starts whenever tracking is regained (tracking loss, screen off, app switch).

On **Finish** each segment is written to `segments/segment-NN.ply` (world coordinates from its anchor) and then
registered onto the merged earlier segments by `core/SegmentSnapper.kt`: wall-plane alignment (tilt + depth)
followed by a relief-map cross-correlation search for in-plane shift and roll. A snap is applied only when the
overlap and correlation pass the `SNAP_*` thresholds in `Tuning.kt`; otherwise the anchor pose is kept and the
log says why.

### Keyframes

During the scan sparse 1080p JPEG keyframes are saved to `frames/` with `frames.jsonl` (segment id,
segment-local pose, intrinsics): one every ≥ 0.7 s when the camera moved ≥ 0.25 m or turned ≥ 15°, or a new
segment started. At Finish they are resolved to world poses in `frames.json` for image-based tape and placard
detection (`tools/tape.py`).

### Diagnostics and sharing

Each capture has a `scan.log`. Key lines, all with JST timestamps:

| Line | Meaning |
|---|---|
| `capture …`, `device …`, `camera config chosen …` | Environment and ARCore configuration |
| `location: <id>`, `route definition: <type> …` | Choices made at New scan |
| `USER tapped Start/Pause/Resume/Finish button …` | Button presses with segment, voxel and pose state |
| `LIFECYCLE onPause … / onResume (pausedFor …)` | Android pauses (screen off, app switch) that interrupt ARCore |
| `TRACKING lost (…) / regained after …` | ARCore tracking changes with camera poses |
| `SEGMENT n started …`, `SEGMENT n: voxels=… snap=accepted/rejected/skipped(…)` | Segment anchors and snap outcome |
| `STATUS segments=… pos=…` | Periodic position and processing counters |
| `RESULT: plane … holds=… routes=…` or `RESULT: no wall plane` | Processing outcome |

Uncaught exceptions are written to `files/crashes/` and included in every share. **Share** (long-press a
capture, or the viewer's Share button) zips the capture directory, including `sends.json`, for export. Pull a
capture over USB with:

```bash
adb exec-out run-as io.github.monkut.wallscanner tar -C files/captures -cf - <id> | tar -C captures -xf -
```

## Files on the device

```
files/locations.json                      { version, locations: [{ id, name, kind: gym|outdoor, gradeSystem }] }
files/captures/<JST timestamp>/
  capture.json                            { version, createdAt, location, definition: { type, rules } }
  scan.log                                diagnostics (always written)
  segments/segment-NN.ply                 per-segment points, world coordinates before snapping
  frames/frame-NNNN.jpg, frames.jsonl     keyframes and segment-local poses
  frames.json                             keyframes with world poses (after snapping)
  points.ply                              merged cloud (binary little-endian: float x y z, uchar r g b)
  scene.json                              wall frame, holds, routes (schema: ARCHITECTURE.md §9)
  sends.json                              { version, sends: [{ id, routeId, at, result, attempts, note }] }
  thumbnail.png                           front view used in the lists
```

## Tools (run from the repository root with `uv`)

- `uv run tools/snap.py captures/<id>` re-runs the segment snap on the exported `segments/` and writes
  `snapped.ply` plus a before / after `snap.png`; `--no-snap` merges by anchor pose only. Thresholds at the
  top of the script mirror `Tuning.kt`.
- `uv run tools/tape.py captures/<id>` finds tape candidates in the keyframes, lifts them onto the wall plane
  and groups holds by tape colour into `tape.json` with annotated `tape-debug/*.jpg`; `--apply` rewrites the
  scene's routes from the result; `--selftest` checks the pixel → wall lifting on a synthetic frame.

## Design

Tokens live in `res/values/colors.xml` and `themes.xml` and are mirrored in the viewer's CSS: a dark wall
(`#15191D`) with panel (`#212830`) and hairline (`#303A44`) surfaces, chalk (`#EFEBE3`) and dust (`#9AA4AD`)
text, one tape-yellow accent (`#F2C14E`) reserved for the primary action and the selected mode, and send
green (`#5DB974`) for confirmed state. Route colours come from the data (swatches, hold boxes) and are the only
other colour on screen. The typeface is Atkinson Hyperlegible (bundled in `res/font/` and `assets/viewer/fonts/`,
SIL Open Font License in `android/fonts/`), chosen for legibility in dim gyms and bright sun; Japanese text falls
back to the system CJK font. Buttons are sentence case, lists use hairline dividers instead of cards, and
counts are written out with locale plurals ("1 try", "2 tries", "記録 2 件").

## Layout

| Path | Role |
|---|---|
| `core/` | Pure Kotlin pipeline (no Android deps): `VoxelMap`, `PlaneFitter`, `SegmentSnapper`, `Rigid`, `HoldDetector`, `RouteGrouper`, `RouteDefinition`, `GradeSystem`, `SceneJson`, `PlyWriter`, `Thumbnail`. Thresholds in `Tuning.kt`. |
| `scan/` | ARCore session, GL camera background, per-segment point overlay, `Segment` (anchor + voxel map), `DepthFrameProcessor` (raw depth → anchor-local points), `KeyframeWriter`. |
| `viewer/` | WebView host for `assets/viewer/index.html` (three.js) with a JS bridge for saving and sharing. |
| `routes/RouteActivity.kt` | Route record screen: send log for one confirmed route. |
| `io/CaptureStore.kt` | Capture directories, `capture.json`, PLY / scene / thumbnail files, share zip. |
| `io/RouteStore.kt`, `io/LocationStore.kt` | Confirmed routes read from `scene.json`; sends in `sends.json`; locations in `locations.json`. |
| `MainActivity.kt` | Home screen: mode switch, language button, capture list, logbook, New scan dialogs. |
| `Labels.kt`, `Language.kt` | Localized names for the core enums, dates in the app language; per-app locale handling. |
| `res/values*/strings.xml` | English (US) defaults, `values-en-rGB` spellings, `values-ja` translations. |

## Known limits (v0.1)

- Single dominant wall plane; multi-panel walls (slab + overhang) get holds only on the dominant panel. Outdoor rock gets one coarse reference plane per scan, so a boulder should be scanned one face at a time; the thumbnail and (u, v) hold layout follow that face.
- Depth-from-motion resolution limits detection of low-profile holds (< 2 cm) and footchips.
- Route grouping is colour-only; mixed-colour sets need manual editing in the viewer. A hold's colour is the mean of the more saturated half of its voxels, which keeps wall bleed and shadow from pulling it under `MIN_ROUTE_SATURATION` (0.3); genuinely grey, white and black holds still group by value only. Clusters of 10–15 voxels at the noise floor can still form small false routes.
- Snap resolution is one relief cell (2 cm) and one roll step (0.5°); segments without ≥ 0.2 m² of overlap stay on their anchor pose.
- Raw depth noise smears the wall about 3 voxels thick and fills 60–70% of a scan with off-wall points. The voxel map evicts least-hit voxels past `MAX_VOXELS` (1.5M per segment, logged as `SEGMENT n full`) and Finish drops voxels under `MIN_VOXEL_HITS`; both thresholds are untested on a real wall. Gym scans reject depth beyond `MAX_DEPTH_RANGE` (3.5 m), so the top of a tall wall needs the phone raised or a step back.
- Locations can be registered and assigned but not renamed or deleted; grades must come from one of the four scales (no free text).
- Routes and sends live with the capture they were identified in; there is no merging of the same route across captures yet.
- TODO: multi-plane segmentation, ML hold segmentation, capture bundle export (frames + poses) for the splat backend, tape detection on device.
