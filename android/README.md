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

A **Capture | Logbook** switch at the top selects the mode; the choice is remembered. The circle to its right is
the climber: their photo, else the first letter of their username, else a gear before a profile exists; it opens
**Settings** (see below).

- **Capture** mode is for scanning and identifying routes: the **New scan** button and the list of captures
  (thumbnail, timestamp, location, wall, set, wall type, hold / route / confirmed counts). Tap a capture to open it in the
  viewer. Long-press for **Set location** (location and, at a gym, wall and set), **Join with…** (another capture
  of the same wall is registered onto this one and a joined capture built from both, see below), **Reprocess** (runs hold and route detection again on the saved
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
  or marks it retired once the wall has been reset (its routes and sends stay). Once the gym has registered
  **walls** ("Main wall", "Cave": the fixed structures that outlive resets), the routes are grouped by wall first
  and by set inside each wall; a wall header shows the open sets covering it and its route count, and its
  **Edit** renames or deletes the wall (its captures and routes then show under "No wall", which also collects
  routes scanned without a wall). A set covers the walls ticked in its form, or the whole gym when none is.
  A wall's Edit form also holds its **Wall map** (#8 phase 4): the wall's size, the plywood sheets it is built
  from and the T-nut grid, typed in and drawn to scale as you type. Nothing is measured from scans; later scans
  will be placed on this map.
  Tap a route to open its record.

### Settings and profile

Settings shows the climber first (picture, name, height and reach), then the app's preferences, each with its current
value:

- **Profile**: picture, username, height and reach, all optional. **Choose photo** (or tapping the picture) opens
  the system photo picker (no permission needed); the photo is decoded at no more than 4 megapixels, cropped to a
  centred 512 px square (JPEG, typically 40–120 KB) and stored as
  `files/profile.jpg` on Save, and Cancel keeps the previous one. Reach is standing reach (floor to fingertips, arm
  raised). Lengths are entered in the chosen units (cm, or feet and inches) and stored in centimetres in
  `files/profile.json` (`io/ProfileStore.kt`); a length outside 50–300 cm keeps the form open with an error.
- **Face masks** (issue #12): **My face** and **Bystanders' faces**, both on by default. While a climb is filmed the
  frames the pose model sees also go to a face model (MediaPipe BlazeFace short-range, `io/FaceFinder.kt`, the
  frame whole and in 2 × 2 tiles); the face on the pose's head is the climber's, every other face a bystander's,
  and a head the face model missed (back turned, too far) is masked from the pose alone. The boxes are saved
  beside each video as `<name>.faces.json` (`io/FaceStore.kt`); the recording itself is never changed and never
  leaves the phone unmasked. Playback draws the masks (solid panel-colour pills, `climbs/ClipOverlayView.kt`) and
  Share re-encodes the trimmed range with them burnt in (`climbs/OverlayExport.kt`, media3 Transformer, progress
  shown; a clip with nothing to draw is still copied without re-encoding). A scan with the bystander mask on
  pixelates faces in its keyframes at Finish (`io/KeyframeFaces.kt`), before the orthophoto is built. Switching a
  mask on later masks clips filmed with it on; clips filmed with both off have no face data.
- **Caption** (issue #13): the route (name and grade) over the gym, wall and set, written in a corner of climb
  videos on playback and in shared copies, on by default. **Corner** picks upper or lower, left or right (default
  lower left); **Font** picks the app's Atkinson Hyperlegible or a system family (sans, condensed, serif,
  monospace, casual, handwriting), each shown in itself. Drawn by `climbs/CaptionPainter.kt` on the same overlay
  as the face masks (CLIMB-VIDEO.md § Caption).
- **Units**: Metric (cm) or Imperial (ft, in), the units the profile's lengths are shown and entered in
  (`Settings.kt`, "settings" shared preferences; default metric). Switching units does not change stored values.
- **Language**: see below.

### Language

The app has its own language setting, independent of the device language: English (US) is the default on
first launch, English (UK) differs only in spelling (colour, initialising), and Japanese translates every
screen including the capture viewer. Dates and times follow the chosen language (Oct 20, 2026, 2:31 PM /
20 Oct 2026, 14:31 / 2026/10/20 14:31) and are always JST. The choice is stored by AppCompat's per-app
locale support (`Language.kt`) and applied to the viewer page through its `lang` query parameter.

### New scan

1. **Where are you scanning?** Pick a registered location or **New location…** (name, Gym / Outdoor area,
   grade scale Q/D, V, Font or YDS). The last used location is preselected.
2. **Which wall are you scanning?** (gyms only) Pick one of the gym's registered walls or **New wall…** (a name:
   "Main wall", "Cave"); a gym without walls goes straight to the New wall form, whose **Without a wall** goes on
   with none. The last used wall is preselected.
3. **Which set is this wall?** (gyms only) Pick one of the gym's open sets covering that wall or **New set…**,
   named after the month ("October Set") unless renamed and covering the chosen wall (untick every wall for the
   whole gym); **Without a set** goes on with none. The last used set is preselected.
4. **How are routes marked on this wall or rock?** Hold colour, hold colour with taped start and finish, tape, circuit, tagged, competition,
   free / spray wall, system board, or outdoor rock (preselected for outdoor areas). See `ARCHITECTURE.md` §9
   for what each type means and how it is processed.

The choices are written to the capture's `capture.json` before any data is recorded. If the location has no coordinates yet, the scan asks for location permission once ARCore is running and records the best position fix (network first, GPS when available) in `capture.json` and on the location; the location list then shows "GPS" in its detail line.

### Scan screen

Stand about 2 m from the wall, face it squarely, tap **Start scan** and sweep slowly in overlapping horizontal
rows at a walking pace, turning the phone rather than stepping in; keep the floor or a wall edge in frame at the
row ends, and overlap a second scan of the same wall by a metre. A guide line under the status (#36,
`core/ScanGuide.kt`, `scan/GuideSampler.kt`) reads the depth frame's central window a few times a second, before
and during recording, and says when to step back (under 0.8 m: closer, a plain panel fills the frame and tracking
drifts, as the 2026-11-06 12:25 scan did at 0.4 m), move in (beyond 85% of the depth range: 3 m in a gym, 4.3 m
outdoors), face the wall (turned over 50° from the surface's normal about the vertical, so looking up at the top
row does not count) or slow down (the window sweeping the wall faster than 2 m/s: the camera's speed plus its
turning rate times the distance, smoothed over a second); a cue appears and clears after two agreeing readings. The status line shows point count, depth frames and the current segment. **Pause** / **Resume** stop and restart recording; when
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
- The wall is shown in 3D: the photo pushed out by `relief.png` (each pixel's height off the wall from the point
  cloud, 2 cm cells, written at Finish and Reprocess; older captures stay flat until reprocessed), one vertex per
  1 cm. Holds are drawn on a transparent copy of that surface (fill, edge in the route colour, role ring) and a tap
  picks the smallest hold whose outline holds the point it lands on.
- Each hold shows its number (its id in `scene.json`) at its centre, a fixed size on screen at any zoom; **#**
  hides them. Ids stay put while editing and change on Reprocess.
- Long-press the photo for the hold edits the detector cannot make for you. **Draw a hold** (on the wall or
  on a hold): tap around the hold's edge, then the first point or **Done** (**Undo**, **Cancel**); the taps
  are its outline. On a hold: **Split hold here** cuts it through the pressed point along the nearest seam
  the photo shows (cheapest path over photo edges to the outline on both sides; of the candidate cuts, the
  least cost per √(smaller part) wins, so a cut that only nicks a corner loses, and a part thinner than 2 px —
  a strip of shadow along the rim — is never split off; without a seam it is the shortest cut); the larger part keeps the id, role and tape. **Merge with another hold** joins the next hold
  tapped into it (one outline, a seam up to 1.5 cm closed; it keeps its id and takes the other's routes, and
  its role and tape when it has none; holds that do not touch are not merged); **Redraw outline** replaces
  its outline by taps as Draw does (id, routes, role kept); **Remove hold** deletes it from the scene and
  every route. A new hold joins the selected route. Hand-made holds are image-only (`voxels: 0`).
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
Under the rating, the route's own **comment** (beta, conditions; tap to add or edit) is kept beside the rating, apart
from each climb's note.

**Record climb** films the climb (`climbs/ClimbRecordActivity.kt`, CameraX, 1080p at ~8 Mb/s, sound when the
microphone is allowed). **Hands-free** (default): press REC once and leave the phone where it sees the whole problem;
the pose model (MediaPipe Pose Landmarker lite, `io/PoseTracker.kt`, ~10 preview frames a second — a third camera
stream would cap the recording at 720p) shows whether the climber is in view and how many attempts it has found;
on STOP, `core/AttemptDetector.kt` cuts the attempts out of the recording (feet off the floor line for 0.7 s to
feet back on it, 2 s before and after; outcome hint Top when both hands were held at their highest for 1 s, Fall
when the hips dropped fast). **One attempt**: REC/STOP per attempt. **Finish climb** logs the climb with the send
dialog (tries prefilled); Back with filmed attempts offers to keep them as an attempt or discard them. A climb
with clips shows "n filmed attempts"; tap it for playback (`climbs/ClimbPlaybackActivity.kt`): a tab per attempt,
the trim range (stored, the file is never changed), outcome Top / Fall, Delete, and Share (a trimmed copy without
re-encoding, from the keyframe at or before the in-point; `io/ClipExport.kt`). Detector thresholds are a first
guess until tuned on filmed sessions (CLIMB-VIDEO.md). With face masks on (Settings) the same sampled frames go
to the face model too, in both modes; playback masks the faces and Share burns the masks into the copy (see
Settings above and CLIMB-VIDEO.md § Face masks).

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
detection (`tools/tape.py`). With the bystander face mask on (Settings) each keyframe is then turned upright by
its pose, searched for faces and the faces pixelated in the stored JPEG before the orthophoto is built
(`io/KeyframeFaces.kt`; `keyframe faces: …` in `scan.log`).

### Diagnostics and sharing

Each capture has a `scan.log`. Key lines, all with JST timestamps:

| Line | Meaning |
|---|---|
| `capture …`, `device …`, `camera config chosen …` | Environment and ARCore configuration |
| `location: <id> · wall: <id> · set: <id>`, `route definition: <type> …` | Choices made at New scan |
| `USER tapped Start/Pause/Resume/Finish button …` | Button presses with segment, voxel and pose state |
| `LIFECYCLE onPause … / onResume (pausedFor …)` | Android pauses (screen off, app switch) that interrupt ARCore |
| `TRACKING lost (…) / regained after …` | ARCore tracking changes with camera poses |
| `SEGMENT n started …`, `SEGMENT n: voxels=… snap=accepted/rejected/skipped(…)` | Segment anchors and snap outcome |
| `gps: requesting fix from …`, `gps: lat, lon (±m) from provider`, `gps: location … coordinates set` | Position fix for a location without coordinates (`scan/GpsFix.kt`) |
| `STATUS segments=… pos=… integrate(…) upload(…)` | Periodic position, processing counters and per-frame timings |
| `COVERAGE thin depth in (row r, col c) …` | Cells of the 3 × 3 wall grid with little depth (row 0 = top, col 0 = left) |
| `GUIDE <cue|ok> after Ns · distance=… facing=… speed=…` | The scan guide's cue changed (#36); `STATUS` lines carry the latest measurement as `guide(…)` |
| `QUALITY plane share n% holds n/m² (→ drifted)` | Scan quality verdict (#35): share of the cloud on the wall plane and holds per m² of wall |
| `orthophoto: WxH px …`, `hold shapes: …`, `hold search: n image-only holds …`, `hold model: …`, `hold split: …`, `hold prune: n dropped …`, `hold outlines: n of m redrawn by SAM …`, `hold depth merge: n pieces joined …`, `tape marks: n start holds …` | Photo-based stages of processing with timings |
| `RESULT: plane … wallColor=… holds=… unassigned=… routes=…` or `RESULT: no wall plane` | Processing outcome (unassigned = holds left out of every route) |

Uncaught exceptions are written to `files/crashes/` and included in every share. **Share** (long-press a
capture, or the viewer's Share button) zips the capture directory, including `sends.json`, for export (climb videos stay out). Pull a
capture over USB with:

```bash
adb exec-out run-as io.github.monkut.wallscanner tar -C files/captures -cf - <id> | tar -C captures -xf -
```

## Files on the device

```
files/locations.json                      { version, nextId, locations: [{ id, name, kind: gym|outdoor, gradeSystem, coords?: { lat, lon, accuracyM, at }, sets: [{ id, name, createdAt, retiredAt? }] }] }
files/captures/<JST timestamp>/
  capture.json                            { version, createdAt, location, wall, set, definition: { type, rules }, gps?: { lat, lon, accuracyM, at } }
  scan.log                                diagnostics (always written)
  segments/segment-NN.ply                 per-segment points, world coordinates before snapping
  frames/frame-NNNN.jpg, frames.jsonl     keyframes and segment-local poses
  frames.json                             keyframes with world poses (after snapping)
  points.ply                              merged cloud (binary little-endian: float x y z, uchar r g b)
  scene.json                              wall frame (plus faces when the wall bends), holds, routes (schema: ARCHITECTURE.md §9)
  sends.json                              { version: 2, sends: [{ id, routeId, at, result, attempts, note, clips?: [...] }], ratings: { "<routeId>": 0..5 }, comments: { "<routeId>": text } }
  climbs/<sendId>/*.mp4                   filmed attempts (left out of the capture's share zip)
  climbs/<sendId>/<name>.faces.json       faces seen while filming <name>.mp4: { version: 1, frames: [{ t, faces: [{ box: [x, y, w, h], role: climber|bystander }] }] }
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
| `core/` | Pure Kotlin pipeline (no Android deps): `VoxelMap`, `PlaneFitter`, `SegmentSnapper`, `Rigid`, `HoldDetector`, `RouteGrouper`, `RouteDefinition`, `GradeSystem`, `SceneJson`, `PlyWriter`, `Thumbnail`, `Length` (cm ↔ ft, in). Thresholds in `Tuning.kt`. |
| `scan/` | ARCore session, GL camera background, per-segment point overlay, `Segment` (anchor + voxel map), `DepthFrameProcessor` (raw depth → anchor-local points), `KeyframeWriter`. |
| `viewer/` | WebView host for `assets/viewer/index.html` (three.js) with a JS bridge for saving and sharing. |
| `routes/RouteActivity.kt` | Route record screen: send log for one confirmed route. |
| `io/CaptureStore.kt` | Capture directories, `capture.json`, PLY / scene / thumbnail files, share zip. |
| `io/RouteStore.kt`, `io/LocationStore.kt`, `io/ProfileStore.kt` | Confirmed routes read from `scene.json`; sends in `sends.json`; locations in `locations.json`; the user profile in `profile.json`. |
| `MainActivity.kt` | Home screen: mode switch, Settings (profile, units, language), capture list, logbook, New scan dialogs. |
| `Labels.kt`, `Language.kt`, `Settings.kt` | Localized names for the core enums, dates in the app language; per-app locale handling; the units setting. |
| `res/values*/strings.xml` | English (US) defaults, `values-en-rGB` spellings, `values-ja` translations. |

## Known limits (v0.1)

- A gym wall is one or more planar faces (`core/Facets.kt`, `core/WallSurface.kt`, scene.json v4 `wall.faces`): regions of like local normals of at least 1 m² turned at least 10° from the main plane (a top that kicks back, a corner) become faces of their own, unfolded about their seam with the main face so holds, outlines and the orthophoto keep one 2D layout while each point is measured against its own face. The main face keeps the fitted plane, so (u, v) on it match earlier scenes. Faces smaller than 1 m² or within 10° (volumes, a short side panel) stay part of the main plane and read as relief. Outdoor rock gets one coarse reference plane per scan, so a boulder should be scanned one face at a time; the thumbnail and (u, v) hold layout follow that face.
- Depth-from-motion resolution limits detection of low-profile holds (< 2 cm) and footchips.
- Holds are bumps in a 2 cm relief map of the wall (per-cell median plane distance, smoothed 3 × 3, `core/HoldDetector.kt`): raw depth leaves the wall 2–3 cm thick per voxel, so per-voxel clustering merged holds with the speckle around them. Touching holds are separated by re-thresholding at rising levels (`HOLD_SPLIT_LEVELS`) and keeping prominent peaks. A coloured hold's footprint then grows into neighbouring cells of the same colour (`HOLD_GROW_*`): flat slopers and jug edges sit at the depth noise floor, where relief alone finds only part of the hold. Each hold's outline is traced from its cells (`core/HoldOutline.kt`). A component at least `STEP_MIN_EXTENT` (0.6 m) across whose cells inside the wall's extent reach `MAX_HOLD_HEIGHT` (0.8 m) is the rim of a step of the wall (a boxed-out section, a hangboard panel), not a hold, and is dropped (#28). The steps' outlines go into `scene.json` as `steps` (a step reaching within `STEP_BOTTOM_MARGIN` of the extent's bottom is the mat: claimed, not reported, so the bottom row's image-only holds are not taken for its paint; a step standing on the mat line goes unreported the same way), and after SAM an image-only hold with half its pixels on one is the step's paint or wood, not a hold (`HoldMerge.dropOnSteps`, #29 A; a hold with voxels stays). Shallower steps come through, as do scraps of the riser under 0.6 m and a box standing on its own (the Main wall's campus panel side, 0.28 × 0.56 m rising to 1 m): the Oct 21 reference has a corner volume 0.77 m proud of the same shape, so height alone cannot tell them apart. Mats, side walls and the floor at the wall's edges still produce false holds, mostly grey or white; delete them in the viewer.
- Hold shapes come from the photos, not the depth: at Finish (and on Reprocess) `io/ScenePipeline.kt` builds an orthophoto of the wall from the saved keyframes (`core/Orthophoto.kt`: every 5 mm wall pixel, placed at its measured relief, projected through each keyframe's pose; the most frontal, closest view wins; keyframes decoded at half size). `core/ColorPatches.kt` splits the photo into patches of one hue (saturated, not the wall colour, 3 cm gaps bridged so highlights and chalk do not cut a hold). `core/HoldShapes.kt` makes a coloured hold the patch its depth seed lies on, plus same-hue patches that share enough border with it and grey, white or dark parts wrapped by it (two-tone holds, shaded undersides), closed by 4 cm and hole-filled, then smoothed; two depth seeds on one patch merge into one hold. Grey, white and black holds grow from their seed over pixels closer in Lab colour to the hold than to the wall, at most 10 cm beyond the seed. The depth outline stays when the hold is not separable from the wall. White volumes on a light wall are the weak case.
- Start holds from tape (`TapeMarks` in `core/HoldSearch.kt`): a rectangle of tape from a hold down to the route's white placard marks that hold as the start; the tape's colour is the gym's grade, not the hold colour, and is recorded on the hold (`tape`). Placards are white components 5–25 × 2.5–10 cm, at least half of the box white once enclosed holes (grade text, a coloured header) count as card; from 1.5 cm above each card's top edge the tape is grown upward (any colour, grey and black included, within RGB distance 48 of where it leaves the card, up to 40 cm, under 5 cm wide) to the hold it reaches. Only hold outlines stop the tape: tape the same colour as the card's header joins it into one hold-sized colour patch. Two strips leaving one placard mark a two-hold start. Without a placard, a thin strip of coloured tape still counts: strips are tinted or saturated pixels left after an opening removes anything wider than tape, 5–40 cm long and under 5 cm wide along their own axis, within 50° of vertical, whose lower half hangs in clear wall; the hold whose outline is within 6 cm above the strip's top gets the `start` role unless one was already set. Top holds are still guessed by height or marked by hand.
- Holds the depth missed are searched for in the orthophoto (`core/HoldSearch.kt`): saturated patches of one hue (or dark and dull ones whose hue is at least 30° from a coloured wall's: a dark green foothold reads as saturation 0.4, brightness 0.2 at 5 mm), 4 cm or more across along their own axes (a slanted tape strip has a square bounding box), not tape-shaped, not the wall colour, not below the wall surface's bottom edge (crash pads) and not covered by a detected hold become image-only holds (`voxels: 0`, height from the depth relief under them or 0). This recovers flat footholds, holds mounted on volumes and anything in a corner the sweep skipped. A patch 30% covered by a found hold is that hold, unless the uncovered remainder is 40% of the patch, compact (half its box) and hold-sized: then it is a second hold of the same colour that the 3 cm gap bridging joined to the first (#29 C: on 1F two red volumes 2 cm apart, the lower one too flat for depth). Volumes themselves count as holds up to `MAX_HOLD_HEIGHT` (0.8 m); above 10 cm of relief a hold on a volume only needs `HOLD_PROMINENCE_HIGH` (4 cm) over the volume's surface to split from it.
- Where the wall turns into another face or a volume, everything stands far off the plane and the relief merges neighbouring holds into one blob (0.7 m of relief at the top of the noborock wall). `core/HoldSplit.kt` splits such a hold by the photo: the hue histogram of the colour patches inside its outline gives one group per peak (≥ 30° apart); each group with ≥ 4% of the pixels, ≥ 16 cm² and ≥ 4 cm across becomes a hold (the largest keeps the id, role, tape and voxels). Two-tone holds of two different hues split too; rejoin them in the viewer by removing one and growing the other.
- A trained hold detector runs on the orthophoto when `assets/holds.tflite` is in the build (`io/HoldModel.kt`: YOLO11-seg from `tools/holds_model/`, letterboxed input, LiteRT 1.4.2 on 4 threads — TF Lite 2.16 cannot run the int8-weight `transpose_conv`, and LiteRT 2.x needs Kotlin ≥ 2.4 metadata; about 1.2 s per wall on a Pixel 9a; `core/SegDecoder.kt` decodes boxes, NMS and the prototype masks). The photo goes through CLAHE on its luma first (`core/Clahe.kt`, identical to `tools/holds_model/clahe.py`, which the training images went through). `core/HoldMerge.kt` combines its masks with the other holds: a hold containing two or more distinct masks (volumes or big holds the relief joined) is replaced by them, unless the masks share at least 35% of the smaller one's outline (one hold cut at a keyframe seam); masks no hold covers are added as image-only holds. Two-class models also outline grade tape: a hold mostly under tape is dropped. Then `HoldMerge.prune` drops holds that are neither coloured nor seen by the model when they lie at the wall's edge (or off the photo) or are mostly the wall's own colour: the mat, concrete, beams and the next wall face. After the final outlines, `HoldMerge.dropStrips` drops strips under 3 cm across and at least 6 times as long (column and panel edges; real holds in the references are at least 4 cm across and at most 4.7 times as long as wide). Without the asset the pipeline logs `hold model: none in this build` and goes on. The JVM harness cannot run it; `tools/holds_model/predict.py` + `eval_holds.py` score a model on pulled captures instead.
- Last, MobileSAM redraws every hold's outline from its box (`io/HoldSam.kt`, `core/SamRefine.kt`; `assets/sam_encoder.tflite` + `sam_decoder.tflite` from `tools/holds_model/sam_export.py`, int8 weights, 16 MB): the photo is encoded once (longer side 1024), each hold's outline box prompts one mask, read in the box grown by a quarter. The outlines from relief and colour take in shadows, chalk and bare wall; SAM's follow the hold's edge (mean photo-edge strength on the outline 0.66 → 0.66 on Oct 23 2:00 PM, whose outlines were already fair, and 0.76 → 0.81 on 2:04 PM; by eye black holds, volumes and skulls without their shadows). The new outline is kept when SAM's predicted IoU is ≥ 0.7, it overlaps the old outline (IoU ≥ 0.2), keeps ≥ 25% of its area and takes at most 20% of its pixels from other holds outside the old outline (both halves of a hold split upstream would otherwise get the whole); a partial outline grows to the whole hold within the box. Then pieces of one hold merge: a hold whose SAM mask lies at least 60% inside a larger hold's mask joins it (the detector cuts holds at shading, colour and relief changes; on Oct 23 2:04 PM this merged exactly the six pairs the climber named), and a new outline taking more than 20% of itself from holds outside its group is refused. Last, an image-only hold whose median colour differs from the wall ring 2–6 px around it by less than CIE ΔE 6 is dropped: bolt holes, grain or an exposure seam (1.3 and 1.6 there; real holds ≥ 18). After SAM, `HoldMerge.nested` merges a hold whose outline lies 90% inside a larger hold's, of about its colour (ΔE ≤ 25) and at least 20% of its area, into it (#29 B: the depth finds a volume's upper face, the photo search the whole trapezoid; 1F #12 in #10); a small hold mounted on a volume stays its own (3% and 1% of their hosts in the Oct 21 and Oct 23 references, both real). `HoldMerge.byDepth` joins touching holds of about one colour (ΔE ≤ 25) that both stand ≥ 1.5 cm off the wall and whose shared edge lies ≥ 5 mm above the lower one: no depth valley, so one object (a volume's faces, a hold's lit horn and base); separate holds meet in a valley. On Oct 23 2:04 PM it joins exactly the four pairs the climber named and none of the 19 other touching pairs. On a Pixel 9a it takes about 6 s per wall (encoder 1.9 s once; the decoder about 110 ms per hold, three copies on two threads each sharing the holds out). The GPU delegate takes under 10% of either graph's operations and its masks came out empty, so SAM runs on the CPU. Without the assets it logs `hold outlines: SAM none in this build`. `tools/holds_model/sam_refine.py` runs the same steps on the desktop (`scene-sam.json`).
- Depth coverage: at Finish the wall extent is checked as a 3 × 3 grid (`core/Coverage.kt`); cells with under 15% of the median cell's points are logged as `COVERAGE thin …` and shown in a toast ("Little depth data at the top right of the wall …"), since holds there come only from the photo.
- Scan quality (#35, `core/ScanQuality.kt`): ARCore can drift mid-scan without reporting a tracking loss (noborock Main wall, 2026-11-06 12:25: the orthophoto repeats its right half, every hold found twice, the scan registers onto no other). After processing, the share of the cloud within 2 cm of the wall surface (every face counted) and the holds per m² of wall extent judge the scan: under `DRIFT_MAX_PLANE_SHARE` (11%) and over `DRIFT_MIN_HOLD_DENSITY` (10/m²) together mean `drifted` (good scans: 15.7–37.5% and 3.6–7.6/m²; the drifted one 8.8% and 14.6/m²; either alone is a wall of many volumes or a busy spray wall). Outdoor scans and scans without an orthophoto are not judged (`QUALITY not judged: …`): the thresholds hold for the photo pipeline's hold counts on a gym wall. The verdict is logged as `QUALITY …`, written to `scene.json` (`quality: { planeShare, holdDensity, drifted }`), toasted after Finish ("Low confidence: the wall surface is smeared … Rescan this part of the wall"), appended to the Reprocess summary and shown on the capture row and in the viewer's status line as "low confidence". A keyframe-disagreement map to point at the drifted region is the next step on the issue.
- Route grouping is colour-only; mixed-colour sets need manual editing in the viewer. A hold's colour comes from the voxels of its core (cells at least half its peak height) minus voxels that match the wall's own colour (`core/WallColor.kt`: hue, saturation and value tolerances taken from the wall surface), then the mean of the more saturated half. Holds that still look like the wall stay unassigned. Hue clusters wider than `MAX_ROUTE_HUE_SPAN` (35°) split at their largest gap, so red and orange sets stay apart when a gap separates them; where red, salmon and orange holds fill the hue range without one (noborock 2026-10-29: 346–20°), they stay one route and need splitting in the viewer. Only holds of saturation 0.3 or more start a route. Warm gym light washes greens and purples out to 0.2–0.3, so such pale holds join the hue cluster whose range is within 15° (`PALE_*` in `Tuning.kt`), unless they are dark (saturation × value under 0.1), in the white band (value 0.65 or more), or within 20° of a coloured wall's hue. Genuinely grey, white and black holds group by value only. Pale holds (saturation under `MIN_ROUTE_SATURATION`) that show a hue join the nearest saturated hue cluster; when a route's worth of them (`PALE_ROUTE_MIN_HOLDS`, chroma at least `PALE_ROUTE_MIN_CHROMA`) agree in hue with no cluster to join they are a route of their own, like the dusty purples of noborock 1F; fewer stay Gray, since grey holds under gym light read bluish or purplish at the same saturation (#27). Below a tape-marked start hold, pale holds of its hue within `START_REACH` to either side are its route's foot holds, whatever their saturation. `RouteHarness` (unit test) regroups the holds stored in every pulled `scene.json` with the current grouper and scores the result against each `reference.json`'s route labels: `./gradlew :app:testDebugUnitTest --tests '*RouteHarness*' -i | grep ROUTES`. On a wall at least `DOWN_CLIMB_MIN_WALL_HEIGHT` (3.4 m) high the grey group is the wall's down-climb holds, not a route: it is kept in `routes` with `kind: downclimb` and the name "Down climb", shown in the viewer with a dashed swatch and no grade or Confirm, and never counted as a route nor offered in the logbook; the "↓ Down climb" button on any route row turns it into the down-climb group and back, keeping its grade and confirmation hidden rather than cleared. The height comes from the scan's extent; a partial scan of a tall wall can fall under the threshold (the wall map's height should take over once scans are placed on walls, #8).
- Snap resolution is one relief cell (2 cm) and one roll step (0.5°); segments without ≥ 0.2 m² of overlap stay on their anchor pose.
- Raw depth noise smears the wall about 3 voxels thick and fills 60–70% of a scan with off-wall points. The voxel map evicts least-hit voxels past `MAX_VOXELS` (1.4M per segment, logged as `SEGMENT n full`) and Finish drops voxels under `MIN_VOXEL_HITS` (about 40% of a real wall scan). A 90 s gym scan reaches 1.1M voxels without hitting the cap. Gym scans reject depth beyond `MAX_DEPTH_RANGE` (3.5 m), so the top of a tall wall needs the phone raised or a step back.
- `RealCaptureHarness` (unit test) rebuilds every capture pulled into `captures/` with the current pipeline and writes `scene-new.json` (and `wall-new.ppm`) beside each: `./gradlew :app:testDebugUnitTest --tests '*RealCaptureHarness*' -i | grep HARNESS`. Use it to compare hold and route counts before and after a detector change. The orthophoto stage needs the keyframes decoded first with `uv run tools/frames_to_ppm.py captures/<id>` (unit tests compile against android.jar, which has no JPEG decoder).
- Finish and Reprocess show a determinate bar with the current stage (#26): `core/Processing.kt` weights each `ProcessingStage` by its measured seconds on the Pixel 9a (face masking 9, plane and depth holds 11, SAM outlines 11, the rest 1–5), `ScenePipeline.build` reports the stage under way and, inside the long ones, each keyframe decoded for the orthophoto and each hold decoded by SAM; `ProcessingPanel` binds `view_processing.xml` (included by the scan overlay and the Reprocess dialog). The weights pace the bar; they are not a time estimate.
- The live overlay draws at most 300k points per segment (`ArRenderer.PREVIEW_MAX_POINTS`, every k-th voxel); the full cloud is only in the viewer. Depth integration runs at about half the raw depth rate (`skipped(busy=…)` in the log); `integrate(avg/max)` and `upload(avg/max)` in the `STATUS` line show where the time goes.
- Face masks follow the models: the short-range face model misses faces smaller than about a twentieth of a tile (a bystander far behind the climber) and faces turned away; the climber's mask then comes from the pose's head landmarks alone, a bystander's not at all. Faces are sampled about ten times a second from the preview and held half a second, so a fast head can show an edge. Masks are drawn where the preview showed the frame (a centred crop of the recording's 16:9 stream); keyframe faces are found on the frame turned upright by its pose, which assumes the climber held the phone roughly level with gravity.
- Locations can be registered and assigned but not renamed or deleted; grades must come from one of the four scales (no free text).
- **Join with…** (#7, #8 phase 3) registers a second capture of the same wall onto this one from their holds (`core/CaptureRegistration.kt`: every pair of like-coloured, like-sized holds votes for a yaw and shift; the best-supported poses are judged by how many of the moved holds among the reference holds land on one; least squares over the matches; a short point ICP may correct it by up to 8 cm and 2°, further is the overlay of two flat walls and is discarded) and writes a new capture (`io/CaptureJoin.kt`: merged cloud, both sets of keyframes with the second's poses carried over, `capture.json` with `joined: [ids]` and `registration`) that goes through the normal pipeline, so a wall that bends between the two becomes two faces and a route that runs from one scan into the other is one route. The originals stay. A join needs at least 4 matched holds, half of the moved holds in the overlap matching, and 0.8 m of horizontal span between them: the two Oct 29 noborock scans share only 0.4 m of cut-off holds and are refused ("the scans do not overlap enough"). Scan with a metre of overlap when a wall needs two captures. Clouds alone cannot register two wall scans: a flat plane overlays itself anywhere. `JoinHarness.registerPairsOfEachWall` (unit test) registers every ordered pair of pulled captures that name the same wall in `capture.json` and prints each outcome, to see which scans of a wall can be joined and in which order: `./gradlew :app:testDebugUnitTest --tests '*JoinHarness.registerPairsOfEachWall*' -i | grep PAIR`.
- Routes and sends of the original captures stay with them; the joined capture starts its own identification.
- The viewer exposes `window.viewerDebug` (`scene`, `project(u, v)` → CSS px, `hitAt(u, v)`, `probe(id)`) for driving it from WebView DevTools (`adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>`).
- TODO: multi-plane segmentation, ML hold segmentation, capture bundle export (frames + poses) for the splat backend, tape detection on device.
