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
  (thumbnail, timestamp, location, set, wall type, hold / route / confirmed counts). Tap a capture to open it in the
  viewer. Long-press for **Set location** (location and, at a gym, set), **Reprocess** (runs hold and route detection again on the saved
  point cloud, replacing route edits and confirmations; use after a detector change), **Share** (zip of the
  capture) and **Delete**. Captures without a 3D scene are listed as "incomplete · diagnostics only" and can
  still be shared.
- **Logbook** mode is for recording progress. It opens on the registered locations (name; kind, grade scale
  and GPS when coordinates are set; route and send counts) under a summary line (routes · sends · flashes ·
  locations); a "No location" row appears when confirmed routes belong to no registered location. **Add
  location**, the primary button in the slot New scan uses in Capture mode, registers a gym or crag without
  scanning and remembers it for the next New scan. Tap a location to list its confirmed routes under its
  header; Back returns to the locations. The header's **Edit** button opens the location form: name (must be
  unique), kind, grade scale and coordinates ("latitude, longitude" in decimal degrees, or **Use phone
  position** for a fix; a hand-entered position is stored with accuracy 0), plus **Delete**, which asks first
  and leaves captures, routes and sends in place as "No location". The same form registers a new location
  from New scan's "New location…" and from Add location. At a gym the routes are grouped by **set** (the
  routes on the wall between two resets): open sets first, newest first, then retired ones, then "No set" for
  routes scanned without one; a set header shows its dates and route count, and its **Edit** renames the set
  or marks it retired once the wall has been reset (its routes and sends stay). Tap a route to open its record.

### Language

The app has its own language setting, independent of the device language: English (US) is the default on
first launch, English (UK) differs only in spelling (colour, initialising), and Japanese translates every
screen including the capture viewer. Dates and times follow the chosen language (Oct 20, 2026, 2:31 PM /
20 Oct 2026, 14:31 / 2026/10/20 14:31) and are always JST. The choice is stored by AppCompat's per-app
locale support (`Language.kt`) and applied to the viewer page through its `lang` query parameter.

### New scan

1. **Where are you scanning?** Pick a registered location or **New location…** (name, Gym / Outdoor area,
   grade scale Q/D, V, Font or YDS). The last used location is preselected.
2. **Which set is this wall?** (gyms only) Pick one of the gym's open sets or **New set…**, named after the
   month ("October Set") unless renamed; **Without a set** goes on with none. The last used set is preselected.
3. **How are routes marked on this wall or rock?** Hold colour, hold colour with taped start and finish, tape, circuit, tagged, competition,
   free / spray wall, system board, or outdoor rock (preselected for outdoor areas). See `ARCHITECTURE.md` §9
   for what each type means and how it is processed.

The choices are written to the capture's `capture.json` before any data is recorded. If the location has no coordinates yet, the scan asks for location permission once ARCore is running and records the best position fix (network first, GPS when available) in `capture.json` and on the location; the location list then shows "GPS" in its detail line.

### Scan screen

Point at the wall from 1–3 m, tap **Start scan** and sweep slowly in overlapping rows. The status line shows
point count, depth frames and the current segment. **Pause** / **Resume** stop and restart recording; when
resuming, start on an area that is already scanned so the new segment overlaps the old data. The screen stays
on while scanning. **Finish & process** runs the on-device pipeline and opens the viewer; it refuses to finish
with too few points, and if no wall plane is found it still saves the points for analysis.

### Capture viewer (identification)

The wall is shown as photographed: the orthophoto (`wall.png`) lies on the wall plane and each hold is drawn
as its outline, edged in its route's colour (chalk when unassigned) and lit when its route is selected; the
point cloud is behind it, one tap away (**Points**). Captures without a photo show the cloud with holds as
boxes, as before. Orbit, pan and pinch. The **Routes** panel lists the routes with a colour swatch, name,
grade dropdown, hold count, **Confirm** and delete.

- Tap a swatch (or the hold count) to select a route, then tap holds on the wall to add or remove them. A hold
  may belong to several routes (tape, free walls). Where outlines overlap, the smallest hold under the finger
  is the one tapped (a foothold inside a volume's outline).
- Long-press the photo for the hold edits the detector cannot make for you: on empty wall, **Add hold here**
  grows a hold from the colour under the finger (within RGB 48 of it and unlike the wall's colour, up to
  60 cm, outer boundary traced and smoothed as `HoldOutline.kt` does); on a hold, **Split hold here** carves
  the pressed colour out of it as a hold of its own (the rest keeps the id, role and tape), and **Remove
  hold** deletes it from the scene and every route. A new hold joins the selected route. Hand-made holds are
  image-only (`voxels: 0`); a press on the wall itself is refused.
- The grade dropdown is grouped by scale with the location's scale first: YDS (5.5–5.15d), Japanese Q/D
  (10Q … 1Q, 1D … 6D), V-scale (VB–V17), Font (3–9A). The scale is stored with the grade.
- Role buttons (start, hand, foot, zone, finish): tap a role, then tap holds to mark them (tap again to clear).
  Roles show as coloured outlines.
- **+ New route** creates an empty route; **✕** deletes one.
- **Confirm** marks a route as identified; only confirmed routes appear in the Logbook. Tap again to unconfirm.
- **Save** writes `scene.json` and returns to the home screen. **Share** zips the capture.

### Route record (Logbook)

Shows the route's swatch, name, grade, location, wall type, source capture and hold count above a large
front view of the route (`routes/RouteImage.kt`), orthographic in the wall plane (true distances, up = the
wall's up). With an orthophoto (`wall.png`, built at Finish from the scan's keyframes) the wall is shown as
photographed, dimmed everywhere except inside the route's holds, which are outlined in chalk along their
image-segmented shapes; without one (older captures) the wall is a flat surface colour with a faint
half-metre grid, the route's holds filled in their own colours and every other hold a grey silhouette. The
start hold(s) and top hold are ringed in tape yellow; the wall angle (overhang / vertical / slab from the plane
normal) and a 1 m scale sit in the corners. Start and top come from hold roles when
any are set (solid rings) — from the viewer, tape detection, or a tap on this image, which offers **Set as
start / Set as top / Clear mark** for the tapped route hold — otherwise the lowest one or two holds and the
highest are guessed (dashed rings). Pinch to zoom, drag to pan, double-tap to toggle. Captures made before
outlines existed draw holds as ellipses until reprocessed. **Log send** records a result (Flash / Send / Attempt), the number of tries and a note, timestamped
in JST. Sends are listed newest first; long-press one to delete it. The star row rates the climb 0–5
(optional: "Not rated" until set, **Clear** removes it; the rating shows as "★ n" in the logbook route row).
**Capture** opens the source capture in the viewer for re-editing; the send log and rating are kept separately
and are not affected.

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
| `gps: requesting fix from …`, `gps: lat, lon (±m) from provider`, `gps: location … coordinates set` | Position fix for a location without coordinates (`scan/GpsFix.kt`) |
| `STATUS segments=… pos=… integrate(…) upload(…)` | Periodic position, processing counters and per-frame timings |
| `COVERAGE thin depth in (row r, col c) …` | Cells of the 3 × 3 wall grid with little depth (row 0 = top, col 0 = left) |
| `orthophoto: WxH px …`, `hold shapes: …`, `hold search: n image-only holds …`, `tape marks: n start holds …` | Photo-based stages of processing with timings |
| `RESULT: plane … wallColor=… holds=… unassigned=… routes=…` or `RESULT: no wall plane` | Processing outcome (unassigned = holds left out of every route) |

Uncaught exceptions are written to `files/crashes/` and included in every share. **Share** (long-press a
capture, or the viewer's Share button) zips the capture directory, including `sends.json`, for export. Pull a
capture over USB with:

```bash
adb exec-out run-as io.github.monkut.wallscanner tar -C files/captures -cf - <id> | tar -C captures -xf -
```

## Files on the device

```
files/locations.json                      { version, nextId, locations: [{ id, name, kind: gym|outdoor, gradeSystem, coords?: { lat, lon, accuracyM, at }, sets: [{ id, name, createdAt, retiredAt? }] }] }
files/captures/<JST timestamp>/
  capture.json                            { version, createdAt, location, set, definition: { type, rules }, gps?: { lat, lon, accuracyM, at } }
  scan.log                                diagnostics (always written)
  segments/segment-NN.ply                 per-segment points, world coordinates before snapping
  frames/frame-NNNN.jpg, frames.jsonl     keyframes and segment-local poses
  frames.json                             keyframes with world poses (after snapping)
  points.ply                              merged cloud (binary little-endian: float x y z, uchar r g b)
  scene.json                              wall frame, holds, routes (schema: ARCHITECTURE.md §9)
  sends.json                              { version, sends: [{ id, routeId, at, result, attempts, note }], ratings: { "<routeId>": 0..5 } }
  thumbnail.png                           front view used in the lists
  wall.png                                orthophoto of the wall from the keyframes (5 mm/px; bounds in scene.json wall.photo)
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
- Holds are bumps in a 2 cm relief map of the wall (per-cell median plane distance, smoothed 3 × 3, `core/HoldDetector.kt`): raw depth leaves the wall 2–3 cm thick per voxel, so per-voxel clustering merged holds with the speckle around them. Touching holds are separated by re-thresholding at rising levels (`HOLD_SPLIT_LEVELS`) and keeping prominent peaks. A coloured hold's footprint then grows into neighbouring cells of the same colour (`HOLD_GROW_*`): flat slopers and jug edges sit at the depth noise floor, where relief alone finds only part of the hold. Each hold's outline is traced from its cells (`core/HoldOutline.kt`). Mats, side walls and the floor at the wall's edges still produce false holds, mostly grey or white; delete them in the viewer.
- Hold shapes come from the photos, not the depth: at Finish (and on Reprocess) `io/ScenePipeline.kt` builds an orthophoto of the wall from the saved keyframes (`core/Orthophoto.kt`: every 5 mm wall pixel, placed at its measured relief, projected through each keyframe's pose; the most frontal, closest view wins; keyframes decoded at half size). `core/ColorPatches.kt` splits the photo into patches of one hue (saturated, not the wall colour, 3 cm gaps bridged so highlights and chalk do not cut a hold). `core/HoldShapes.kt` makes a coloured hold the patch its depth seed lies on, plus same-hue patches that share enough border with it and grey, white or dark parts wrapped by it (two-tone holds, shaded undersides), closed by 4 cm and hole-filled, then smoothed; two depth seeds on one patch merge into one hold. Grey, white and black holds grow from their seed over pixels closer in Lab colour to the hold than to the wall, at most 10 cm beyond the seed. The depth outline stays when the hold is not separable from the wall. White volumes on a light wall are the weak case.
- Start holds from tape (`TapeMarks` in `core/HoldSearch.kt`): a rectangle of tape from a hold down to the route's white placard marks that hold as the start; the tape's colour is the gym's grade, not the hold colour, and is recorded on the hold (`tape`). Placards are white components 5–25 × 2.5–10 cm; from 1.5 cm above each card's top edge the tape is grown upward (any colour, grey and black included, within RGB distance 48 of where it leaves the card, up to 40 cm, under 5 cm wide) to the hold it reaches. Two strips leaving one placard mark a two-hold start. Without a placard, a thin strip of coloured tape still counts: strips are tinted or saturated pixels left after an opening removes anything wider than tape, 5–40 cm long and under 5 cm wide along their own axis, within 50° of vertical, whose lower half hangs in clear wall; the hold whose outline is within 6 cm above the strip's top gets the `start` role unless one was already set. Top holds are still guessed by height or marked by hand.
- Holds the depth missed are searched for in the orthophoto (`core/HoldSearch.kt`): saturated patches of one hue (or dark and dull ones whose hue is at least 30° from a coloured wall's: a dark green foothold reads as saturation 0.4, brightness 0.2 at 5 mm), 4 cm or more across along their own axes (a slanted tape strip has a square bounding box), not tape-shaped, not the wall colour, not below the wall surface's bottom edge (crash pads) and not covered by a detected hold become image-only holds (`voxels: 0`, height from the depth relief under them or 0). This recovers flat footholds, holds mounted on volumes and anything in a corner the sweep skipped. Volumes themselves count as holds up to `MAX_HOLD_HEIGHT` (0.8 m); above 10 cm of relief a hold on a volume only needs `HOLD_PROMINENCE_HIGH` (4 cm) over the volume's surface to split from it.
- Where the wall turns into another face or a volume, everything stands far off the plane and the relief merges neighbouring holds into one blob (0.7 m of relief at the top of the noborock wall). `core/HoldSplit.kt` splits such a hold by the photo: the hue histogram of the colour patches inside its outline gives one group per peak (≥ 30° apart); each group with ≥ 4% of the pixels, ≥ 16 cm² and ≥ 4 cm across becomes a hold (the largest keeps the id, role, tape and voxels). Two-tone holds of two different hues split too; rejoin them in the viewer by removing one and growing the other.
- Depth coverage: at Finish the wall extent is checked as a 3 × 3 grid (`core/Coverage.kt`); cells with under 15% of the median cell's points are logged as `COVERAGE thin …` and shown in a toast ("Little depth data at the top right of the wall …"), since holds there come only from the photo.
- Route grouping is colour-only; mixed-colour sets need manual editing in the viewer. A hold's colour comes from the voxels of its core (cells at least half its peak height) minus voxels that match the wall's own colour (`core/WallColor.kt`: hue, saturation and value tolerances taken from the wall surface), then the mean of the more saturated half. Holds that still look like the wall stay unassigned. Hue clusters wider than `MAX_ROUTE_HUE_SPAN` (35°) split at their largest gap, so red and orange sets stay apart. Genuinely grey, white and black holds group by value only.
- Snap resolution is one relief cell (2 cm) and one roll step (0.5°); segments without ≥ 0.2 m² of overlap stay on their anchor pose.
- Raw depth noise smears the wall about 3 voxels thick and fills 60–70% of a scan with off-wall points. The voxel map evicts least-hit voxels past `MAX_VOXELS` (1.4M per segment, logged as `SEGMENT n full`) and Finish drops voxels under `MIN_VOXEL_HITS` (about 40% of a real wall scan). A 90 s gym scan reaches 1.1M voxels without hitting the cap. Gym scans reject depth beyond `MAX_DEPTH_RANGE` (3.5 m), so the top of a tall wall needs the phone raised or a step back.
- `RealCaptureHarness` (unit test) rebuilds every capture pulled into `captures/` with the current pipeline and writes `scene-new.json` (and `wall-new.ppm`) beside each: `./gradlew :app:testDebugUnitTest --tests '*RealCaptureHarness*' -i | grep HARNESS`. Use it to compare hold and route counts before and after a detector change. The orthophoto stage needs the keyframes decoded first with `uv run tools/frames_to_ppm.py captures/<id>` (unit tests compile against android.jar, which has no JPEG decoder).
- The live overlay draws at most 300k points per segment (`ArRenderer.PREVIEW_MAX_POINTS`, every k-th voxel); the full cloud is only in the viewer. Depth integration runs at about half the raw depth rate (`skipped(busy=…)` in the log); `integrate(avg/max)` and `upload(avg/max)` in the `STATUS` line show where the time goes.
- Locations can be registered and assigned but not renamed or deleted; grades must come from one of the four scales (no free text).
- Routes and sends live with the capture they were identified in; there is no merging of the same route across captures yet.
- The viewer exposes `window.viewerDebug` (`scene`, `project(u, v)` → CSS px, `hitAt(u, v)`, `probe(id)`) for driving it from WebView DevTools (`adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>`).
- TODO: multi-plane segmentation, ML hold segmentation, capture bundle export (frames + poses) for the splat backend, tape detection on device.
