# Wall Scanner

Handheld climbing-wall scanner. The Android app scans a wall or boulder with the phone's ARCore depth camera,
builds a coloured point cloud on the device, finds the wall plane and the holds, groups holds into routes
according to how the gym marks them, and keeps a logbook of sends per confirmed route and location.
The longer-term plan (photoreal splats from a GPU backend) is in `ARCHITECTURE.md`.

## Install

Every merge to `main` publishes a release with the signed APK (GitHub → Releases). How to install it on a phone,
update it without losing scans, and check the download: [`docs/INSTALL-ANDROID.md`](docs/INSTALL-ANDROID.md).
Releases are built by `.github/workflows/release.yml` (pull requests run the tests and a release build only).

## What the app does (v0.1)

| Area | Feature |
|---|---|
| Locations | Register gyms and outdoor areas (name, kind, default grade scale); every capture belongs to one. |
| New scan | Pick the location, then how routes are marked on that wall: hold colour, tape, circuit, tagged, competition, free / spray wall, system board, or outdoor rock. |
| Scan | Live point overlay; Start / Pause / Resume / Finish; the screen stays on; every tracking interval is an anchored segment that is snapped onto the rest at Finish. |
| Processing (on device) | Wall plane fit → holds as bumps in a median relief map of the wall (above the plane, or above the local rock surface for outdoor) → routes by the chosen marking type (colour groups minus the wall's own colour, colour + position for circuits, none for tape / free / board / outdoor). |
| Capture viewer | 3D point cloud with hold boxes; rename, grade (YDS, Japanese Q/D, V-scale, Font), create or delete routes; tap holds to edit membership; mark roles (start, hand, foot, zone, finish); **Confirm** a route once identified. |
| Logbook | Confirmed routes grouped by location with a progress line (routes, sends, flashes, locations); per route a send log (flash / send / attempt, tries, note). |
| Language | English (US) by default, English (UK) and Japanese selectable in the app; dates follow the language and are JST. |
| Diagnostics | Per-capture `scan.log` with button taps, lifecycle pauses, tracking changes, segment snaps and processing results; crash traces; **Share** zips the whole capture. |
| Keyframes | Sparse 1080p JPEG keyframes with poses for image-based tape / placard detection (`tools/tape.py`, prototype). |

Usage, screen by screen, is in [`android/README.md`](android/README.md).

## Repository map

| Path | Content |
|---|---|
| `android/` | The Android app (Kotlin, ARCore). Build, usage, file formats, known limits: `android/README.md`. |
| `ARCHITECTURE.md` | Research landscape, target architecture (capture app + GPU splat backend), route definition taxonomy and the `scene.json` / `capture.json` / `sends.json` / `locations.json` schemas (§9–10). |
| `tools/snap.py` | Offline mirror of the on-device segment snap, for tuning `SNAP_*` thresholds on exported segments. |
| `tools/tape.py` | Tape detection prototype on a capture's keyframes (`--apply` rewrites the scene's routes). |
| `tools/frames_to_ppm.py` | Decodes a pulled capture's keyframes to PPM so the JVM test harness can build its orthophoto. |
| `tools/holds_model/` | Hold detector training: public datasets + corrected captures → YOLO11-seg → `assets/holds.tflite`; `eval_holds.py` scores any scene against a capture's reference (`HOLD-SEGMENTATION.md`). |
| `BOM.md` | Bill of materials for the RPLIDAR / Raspberry Pi handheld variant (not used by the app). |
| `CAPTURE.md` | Earlier capture protocol and scorecard for evaluating third-party splat captures. |
| `android/fonts/` | Licence of the bundled Atkinson Hyperlegible typeface (SIL OFL 1.1). |
| `captures/` | Local copies of captures pulled from the phone (git-ignored). |

## Quick start

```bash
cd android
./gradlew :app:testDebugUnitTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Then on the phone: **Capture** mode → **New scan** → pick or register the location → pick the wall type →
**Start scan**, sweep the wall in slow overlapping rows from 1–3 m → **Finish & process** → identify and
**Confirm** routes in the viewer → **Save**. Switch to **Logbook** to log sends against confirmed routes.

Pull a capture for offline analysis (replace `<id>` with the capture's JST timestamp name):

```bash
mkdir -p captures/<id>
adb exec-out run-as io.github.monkut.wallscanner tar -C files/captures -cf - <id> | tar -C captures -xf -
uv run tools/snap.py captures/<id>     # segment snap check: snapped.ply, snap.png
uv run tools/tape.py captures/<id>     # tape candidates from keyframes: tape.json, tape-debug/
```

Dates and timestamps in the app and its files are JST.
