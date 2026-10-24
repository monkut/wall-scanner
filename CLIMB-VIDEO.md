# Climb video — design

Record a climber's attempts on a confirmed route as video, attached to the route's send log, with the
smallest useful set of edits. Status: design agreed 2026-10-23 (CameraX in-app recording; v1 edits = trim,
delete, outcome, share one attempt; 1080p at ~8 Mb/s); hands-free attempt detection and the route comment
added 2026-10-24. **v1 implemented 2026-10-24** on `feature/climb-video` (tested on a Pixel 9a: hands-free and
one-attempt recording at 1080p, finish/keep/discard, playback, trim, outcome, delete, share); the detector's
thresholds still need tuning on a filmed session. Builds on the logbook
(`RouteActivity`, `io/RouteStore.kt`, `sends.json`, ARCHITECTURE §10).

## Terms

- **Climb**: one session on one route, logged as one `Send` entry (result, tries, note) — the existing
  logbook record. A climb owns zero or more clips.
- **Attempt**: one go from the start hold until the climber tops or comes off. Each attempt is one clip.
  A climb's `attempts` count stays the number of tries; clips are the attempts that were filmed.

## User flow

1. Route record (`RouteActivity`) gets **Record climb** beside **Log send**.
2. **Record climb** opens `ClimbRecordActivity`: full-screen camera preview, route name and the next
   attempt number ("Attempt 3") on top, one large REC / STOP button, a strip of recorded attempts below
   (thumbnail, duration). Record → stop = one attempt; the strip grows; the climber rests; record again.
   The screen stays on while the activity is open.
3. **Finish climb** opens the existing send dialog (`dialog_send`) with tries prefilled to the attempt
   count and result defaulting to Send; Save writes the `Send` with its clips. Back without finishing
   asks whether to keep the attempts (saved as a climb with result Attempt) or discard them.
4. In the send list (`item_send`) a climb with clips shows a film glyph and "n clips"; tap → playback.
5. `ClimbPlaybackActivity`: attempt tabs, a `VideoView`, play / pause, a `RangeSlider` for trim, **Delete
   attempt**, **Share clip**. Trim is applied on playback (seek to in-point, stop at out-point) and on
   share (see Edits).

## Hands-free attempts (pose detection) — research 2026-10-24, proposed

A climber filming themselves cannot press STOP on the wall, and one session holds several attempts. Proposal:
the phone records continuously ("Session" mode) while a pose model watches a low-rate copy of the preview; the
attempts are cut out of the recording by where the climber's feet leave and return to the ground. Manual REC/STOP
per attempt stays as the fallback (and for a partner filming).

- **Literature**: no published method detects a climb's start and end from pose alone. Climbing studies trim
  attempts by hand ([EPFL bouldering project](https://www.epfl.ch/labs/mlo/wp-content/uploads/2022/10/crpmlcourse-paper1272.pdf));
  speed-climbing tools use timing lights and sound ([ClimbIQ](https://github.com/ericqin0816/ClimbIQ)); a technique
  study lists "beginning and end of the route" as its own step on top of MediaPipe pose
  ([Sensors 2023](https://www.mdpi.com/1424-8220/23/19/8216)). Occlusion is the hard case: with the climber's back
  to the camera, hands are hidden most (81% vs 98% recall on occluded vs visible frames,
  [arXiv 2609.30026](https://arxiv.org/html/2609.30026)) — feet and hips, which this needs, are the visible parts.
- **Model**: MediaPipe Pose Landmarker *lite* ([Android guide](https://developers.google.com/edge/mediapipe/solutions/vision/pose_landmarker/android);
  BlazePose, 33 landmarks including heels and toes, image + 3D world coordinates, live-stream mode, Apache 2.0,
  > 30 fps on a Pixel 2 per the BlazePose paper). Alternatives: MoveNet Lightning on the LiteRT we already ship
  (17 COCO keypoints, no heels/toes, no new runtime); YOLO11-pose (17 keypoints; AGPL-3.0 like our hold model).
- **Signal** (per analysed frame, ~10 fps): lowest foot point (heel / toe / ankle, whichever is visible), hip
  centre, leg length (hip–ankle, the scale), visibility. The **floor line** is learned from the frames where the
  person stands (feet low and still) at the start of the session and between attempts.
- **Start**: both feet more than 0.15 × leg length above the floor line for ≥ 0.7 s → the attempt starts where
  they left it, minus 2 s pre-roll (the climber chalking and stepping on).
- **End**: both feet back at the floor line for ≥ 0.7 s, or the person leaves the frame or is lost for ≥ 2 s →
  end plus 2 s post-roll. Short hops (< 1.5 s off the floor) are not attempts.
- **Outcome hint**: a fall shows the hips dropping near free-fall speed (> ~1.5 leg lengths per 0.25 s) from
  high on the wall; a top shows both wrists at their highest, held, then a controlled down-climb or a jump.
  Shown as a suggestion (Top / Fall), the climber confirms. Later: map wrists to the route's finish hold with the
  scan (needs the camera's pose against the wall).
- **Output**: one recording file per session; each detected attempt is a clip with `trimStartMs` / `trimEndMs`
  into it (no re-encoding; the trim already planned below), editable in playback like a manual one.
- **To validate first**: a test session filmed at the gym (phone on the floor or a ledge, whole problem in frame,
  2–4 attempts incl. a fall and a top). The detector is tuned on it offline (the same MediaPipe model in Python)
  before it goes into the app.

## Route comment

Besides the per-climb note (`Send.note`, e.g. "fell at the crux twice"), a route gets one optional comment that
stays with it (beta, conditions, "start is the left tape"): `sends.json` gains `comments: { "<routeId>": text }`
next to `ratings`, edited from the route record. A climb's note can still be entered without one.

## Edits (v1)

| Edit | Where | How |
|---|---|---|
| Trim an attempt | Playback, `RangeSlider` | Stores `trimStartMs` / `trimEndMs` in `sends.json`; the file is never changed. Share copies the sample range between the nearest sync frames with `MediaExtractor` + `MediaMuxer` (no re-encode, < 1 s). |
| Delete an attempt | Playback, long-press or button | Deletes the file and the clip entry; later attempts keep their numbers. |
| Mark an attempt's outcome | Playback or record strip | `outcome: top \| fall \| null` per clip; the climb's result is still the climber's call. |
| Share one attempt | Playback | Trimmed copy via `FileProvider` (`file_paths.xml` gains a `files-path` for `captures/`). |

Deferred (v2): join all attempts into one video (same encoder settings, so `MediaMuxer` concatenation
works without re-encoding); tap the hold reached on the route image per attempt (high point); attach video
to a send logged earlier; slow motion.

## Recording

- **CameraX** (`androidx.camera:camera-video`, `camera-view`, `camera-lifecycle`, `camera-camera2` 1.4.x,
  ~3 MB): `PreviewView` + `VideoCapture<Recorder>`. In-app control is what makes "record, rest, record"
  one flow; the system camera intent (`ACTION_VIDEO_CAPTURE`) would leave the app for every attempt and
  gives no control over quality. ARCore owns the camera only inside `ScanActivity`; no conflict.
- Quality: `Quality.FHD` with the bitrate capped at ~8 Mb/s (≈ 60 MB per minute); fall back through
  `QualitySelector.from(FHD, FallbackStrategy.lowerQualityOrHigherThan(HD))`. Orientation follows the
  sensor (walls are tall; most clips will be portrait), rotation goes into the file's metadata.
- Audio: `RECORD_AUDIO` permission, requested with the camera one; recording continues without audio if
  it is denied.
- Lifecycle: leaving the activity while recording stops and keeps the current attempt.

## Storage

- Files: `captures/<captureId>/climbs/<sendId>/attempt-NN.mp4` (app-private, no storage permission,
  deleted with the send and with the capture). Thumbnails are made on demand with
  `MediaMetadataRetriever` and cached in `cacheDir`, as the capture thumbnails are.
- Before the send id exists (attempts recorded, climb not yet finished) clips go to
  `climbs/pending/` and move on Save.
- **Share** of a capture zips the whole directory today; `climbs/` is excluded from that zip (a climb
  can be several hundred MB).
- The route record shows the climb's total size next to "n clips"; the logbook summary gets a
  "videos: n, x MB" line so the climber can see what the phone is holding.

## Data (`sends.json` version 2)

```
sends[]: { id, routeId, at, result, attempts, note,
           clips: [ { id, attempt, file, durationMs, trimStartMs, trimEndMs, outcome: top|fall|null, auto: bool, at } ] }
ratings: { "<routeId>": 0..5 }
comments: { "<routeId>": text }
```

`clips` is optional; a version-1 file reads unchanged. `file` is relative to the capture directory.
`attempt` is the attempt number at recording time and does not renumber after a delete.

## Code

| Change | Files |
|---|---|
| Dependencies, permissions, activities | `android/app/build.gradle.kts`, `AndroidManifest.xml` (`RECORD_AUDIO`, two activities) |
| `Clip` model, read/write, `addClip` / `updateClip` / `deleteClip`, clip paths, delete-with-send, trimmed copy | `io/RouteStore.kt`, new `io/ClipExport.kt` |
| Recording screen | new `climbs/ClimbRecordActivity.kt`, `res/layout/activity_climb_record.xml`, `item_attempt.xml` |
| Playback and edits | new `climbs/ClimbPlaybackActivity.kt`, `res/layout/activity_climb_playback.xml` |
| Entry points | `routes/RouteActivity.kt` (button, list row tap), `res/layout/activity_route.xml`, `item_send.xml` |
| Strings | `values/strings.xml`, `values-en-rGB`, `values-ja` |
| Share zip exclusion, FileProvider path | `io/CaptureStore.kt`, `res/xml/file_paths.xml` |
| Docs | `android/README.md` (route record, data), `ARCHITECTURE.md` §10 |
| Tests | `RouteStoreTest`: clip round-trip, version-1 file reads, trim clamp; `ClipExportTest` on a tiny generated MP4 if the JVM can build one, else a device test |

Estimated size: ~600 lines of Kotlin and layouts. Order: data model and tests → recording → playback with
trim and delete → share → docs.
