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

## Face masks (issue #12)

Two switches in Settings, both on by default: **My face** (the climber) and **Bystanders' faces**. The profile
picture is never masked.

- **Detection while filming.** The frames already sampled for the pose model (~10 a second, 360 px wide) also
  go to MediaPipe's short-range BlazeFace (`io/FaceFinder.kt`, Apache-2.0, `assets/blaze_face_short_range.tflite`),
  run on the whole frame and a 2 × 2 grid of tiles, merged by IoU. The sampler runs in both modes when a mask is
  on. The pose model also runs when the climber mask is on (even in One attempt mode): the face nearest its head
  landmarks (nose, eyes, ears, mouth, grown by 60%) is the climber's, the rest are bystanders'; a head without a
  face found is masked from the landmarks (back of the head, too far for the face model).
- **Storage.** Boxes are written beside each video as `<name>.faces.json` (`io/FaceStore.kt`), normalised to the
  upright recorded frame: the preview is a centred crop of the camera stream (`PreviewView` FILL_CENTER), which
  now takes the recording's 16:9 aspect, so `FaceMask.previewToFrame` undoes the crop. The video is never
  changed; the sidecar moves and goes with its file (`RouteStore.addSend` / `deleteClip`).
- **Playback.** `climbs/ClipOverlayView.kt` lies over the VideoView and draws `FaceMask.boxesAt(t)`: the sample at
  or before t moved towards its match in the next one, faces held 0.5 s after their last sample, boxes grown by
  25%. Solid rounded rectangles in the panel colour with a chalk hairline (`MaskPainter`); blur is not worth a
  moving 1080p frame.
- **Share.** `ClipExport.trimmedCopy` still copies when nothing in the range would be masked. Otherwise
  `climbs/OverlayExport.kt` re-encodes the trimmed range with media3 Transformer: a `BitmapOverlay` the size of
  the upright frame, redrawn every 40 ms of clip time (frame times are relative to the trim start; the decoder
  delivers upright frames). Audio is copied. Progress is shown; the screen must stay open.
- **Not scans.** Scan keyframes are kept as shot: a face mosaic baked into a keyframe reached the orthophoto and
  pixelated holds (noborock 1F, 2026-11-10: a start hold among them), and Reprocess cannot undo it.
- **Limits.** The short-range model misses small and turned-away faces (then only the pose covers the climber);
  recording-time masking (never storing a face) is not offered; the mask look is fixed.

## Caption (issue #13)

The route (name and grade) over the gym, wall and set, written in a corner of the video so a shared clip carries its
context. Settings → **Caption**: on by default, **Corner** (upper / lower × left / right, default lower left) and
**Font** (the app's Atkinson Hyperlegible, or a family every Android ships: sans, condensed, serif, monospace,
casual, handwriting; the picker shows each in its own typeface).

- `core/Caption.kt`: `Corner`, `CaptionFont`, the `Caption` text and `CaptionLayout` (title 3.5% and detail 2.5% of
  the frame height, inset 3% of the shorter side, both lines shrunk together when the wider would pass 90% of the
  width). `Labels.caption` builds the text in the app language; parts left unset are left out.
- `climbs/CaptionPainter.kt` draws it: chalk text on a 70% panel-colour backdrop, left-aligned in a left corner and
  right-aligned in a right one. `ClipOverlayView` (playback) and `OverlayExport` (shared copy) use the same painter
  after the face masks, so both look the same; the export bitmap is uploaded again only when the masks move, so a
  caption alone costs one upload. The copy-without-re-encode path is taken only when neither masks nor caption
  would draw anything.
- Not yet: the date and attempt number in the caption, per-clip hiding.

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
| Face masks (#12): settings, detection, sidecar, playback overlay, masked export | `Settings.kt`, `res/layout/dialog_face_masks.xml`, `core/FaceMask.kt`, `io/FaceFinder.kt`, `io/FaceStore.kt`, `io/PoseTracker.kt` (head box), `climbs/ClimbRecordActivity.kt`, `climbs/ClipOverlayView.kt`, `climbs/MaskPainter.kt`, `climbs/OverlayExport.kt`; tests `FaceMaskTest`, `FaceStoreTest` |
| Caption (#13): settings (on, corner, font), painter, playback and export | `Settings.kt`, `res/layout/dialog_caption.xml`, `core/Caption.kt`, `climbs/CaptionPainter.kt`, `Labels.kt` (caption text, setting labels), `climbs/ClimbPlaybackActivity.kt`; test `CaptionTest` |

Estimated size: ~600 lines of Kotlin and layouts. Order: data model and tests → recording → playback with
trim and delete → share → docs.
