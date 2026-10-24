# Hold segmentation — research and plan

Research done 2026-10-23 after hold detection on the noborock wall kept needing per-hold fixes. Status:
plan, not implemented. The current detector is in `android/README.md` → Known limits.

## What we have that others do not

Every published hold detector works on a single gym photo. This app has, per wall: an orthophoto at 5 mm
per pixel (perspective-free, the wall plane known), a relief map from depth (how far each pixel stands off
the wall), depth seeds for most holds, and a climber who taps the holds while confirming routes. That
changes the problem: shapes can come from a *promptable* segmenter driven by those seeds and taps, and only
the holds the depth missed need a detector.

## What the field does

- **Trained instance segmentation on gym photos.** [ClimbNet](https://github.com/cydivision/climbnet)
  fine-tuned Mask R-CNN (Detectron2) on 210 internet photos (7,307 holds, 520 volumes); weights are
  published, the images are not, and it is "large and slow on CPU". A student project
  ([bouldering_route_classification](https://github.com/tonylay7/bouldering_route_classification)) got usable
  masks from 40 LabelMe-labelled images (1,060 holds) plus augmentation. A
  [UCSD project](https://kastner.ucsd.edu/ryan/wp-content/uploads/sites/5/2022/06/admin/rock-climbing-coach.pdf)
  used YOLO boxes and found background outside the wall hurt until they masked the wall first (our
  orthophoto is wall-only by construction). A
  [2024 paper](https://pmc.ncbi.nlm.nih.gov/articles/PMC11479384/) detects all holds in a climber's view.
  Public labelled sets are small and CC BY 4.0 on Roboflow Universe:
  [Climb AI hold detector](https://universe.roboflow.com/climb-ai/hold-detector-rnvkl) (125 images, mAP50
  86.5%), [Pwals bouldering holds](https://universe.roboflow.com/pwals/bouldering-holds-9wavr) (50, labelled
  by colour), [Roboflow climbing](https://universe.roboflow.com/roboflow-4rfmv/climbing-y56wy) (43), and
  [robworkspace holds and volumes](https://universe.roboflow.com/robworkspace-xfv3r/climbing-holds-and-volumes-c91sa)
  (1,717 images, boxes only). No one reports a minimum image count; the
  [Ultralytics answer](https://community.ultralytics.com/t/yolo11-training-with-custom-dataset/697) is to add
  labels until the held-out score is good enough, and one
  [small-dataset report](https://farmaker47.medium.com/enhancing-a-small-dataset-to-improve-a-tflite-segmentation-model-ddcd8de4d51)
  took 400 images to 93% mIoU with augmentation. YOLO11-seg trains in minutes and exports to TFLite
  ([Roboflow guide](https://blog.roboflow.com/train-yolo11-segmentation/)); INT8 export needs ~100
  representative images.
- **Promptable segmentation on device.** [MobileSAM](https://github.com/ChaoningZhang/MobileSAM) keeps SAM's
  prompt decoder with a 5M-parameter encoder (9.7M total, Apache-2.0, ONNX export; ~3 s per image on a laptop
  CPU, milliseconds per point prompt). Community apps run
  [SAM / SAM 2 on Android](https://github.com/shubham0204/Segment-Anything-Android) through ONNX Runtime
  (slow even in fp16). Google's
  [MediaPipe Interactive Image Segmenter](https://developers.google.com/edge/mediapipe/solutions/vision/interactive_segmenter/android)
  is the supported Android route: a point or stroke prompt → mask, bundled model, `tasks-vision` dependency.
  Newer distillations ([MobileSAM2](https://arxiv.org/pdf/2607.12297), the
  [efficient-SAM list](https://github.com/czg1225/Awesome-Efficient-Segment-Anything)) keep shrinking this.
- **Using depth.** RGB-D segmentation papers agree that RGB over-segments and depth under-segments, and that
  naive channel concatenation is the weakest fusion: gated or attention fusion at several levels, depth
  denoising first, and resolution-preserving upsampling for small instances
  ([DA-Fusion](https://arxiv.org/html/2607.17754), [TrunkInst](https://www.sciencedirect.com/science/article/abs/pii/S0168169925012657),
  [real-time RGB-D](https://link.springer.com/article/10.1007/s12555-025-0465-z)). For our data that argues
  for depth as *prompts and a filter* first, and as a fused input channel only once there is enough labelled data.
- **Labelling.** [CVAT](https://www.cvat.ai/resources/blog/best-open-source-data-annotation-tools) is the
  most complete open-source tool (polygons, COCO export, SAM-assisted labelling since late 2025);
  [LabelMe](https://labelyourdata.com/articles/data-annotation/polygon-annotation) is the lightest. Both are
  desktop-first. A corrected `scene.json` from this app's viewer is already a polygon label set on `wall.png`.

## Plan

| Phase | What | Needs | Gain |
|---|---|---|---|
| 0 | **Measure.** `tools/eval_holds.py`: match detected outlines to reference outlines by IoU (≥ 0.5), report precision / recall / per-hold IoU per wall; a **Mark as reference** action in the app that copies a corrected `scene.json` to `reference.json`; the harness prints the scores. | The 3 noborock walls, corrected in the viewer (an hour). | Every detector change gets a number across all walls. |
| 1 | **Promptable shapes.** Run a promptable segmenter on the orthophoto once at Finish (encoder) and prompt it with every depth seed (automatic) and every long-press in the viewer (Add / Split). Keep the relief as a filter (reject masks with no relief and no colour difference) and the hue split for merged masks. Candidates: MediaPipe Interactive Segmenter (supported, small) first; MobileSAM via ONNX Runtime if its masks are better. | No labels. ~1 week: model in `assets/`, a `core/PromptSegmenter` behind the harness, viewer taps as prompts. | Replaces the hand-tuned patch growing (the dark foothold, the two-tone holds, the merged blob) without training. The user's taps become the ground truth they already are. |
| 2 | **Find the missed holds.** Fine-tune YOLO11-seg (or the Phase 1 model's detector head) on the public CC BY sets plus our references; export to TFLite; run on the orthophoto for holds without a depth seed; Phase 1 refines their shapes. | 30+ labelled walls over time (Phase 0 references + CVAT for bulk). | Flat footholds, holds on volumes, wall colours the heuristics cannot separate. |
| 3 | **Depth as input.** Add the relief map as a fused channel once Phase 2 has data to train on. | Hundreds of walls. | Holds the photo alone cannot see (same-colour-as-wall). |

Start with Phase 0 and Phase 1 together: Phase 0 is small and makes Phase 1 measurable from the first day.

## Answers to the open question

A label set helps at every phase, but what to label changes: for Phase 0 and 1 the app's own corrections are
enough (confirm routes, fix holds, Mark as reference); Phase 2 wants 30+ walls, so CVAT with SAM assistance on
exported `wall.png` files is the tool, with hold / volume / tape / placard as classes.

## Colour-robust detection (research 2026-10-23, afternoon)

Real walls showed colour and shading driving the errors: a hold split at a keyframe seam, same-colour volumes
merged, grade tape taken for holds. Findings and what was done:

- **Shape first, colour after.** RGB beats grayscale when colour separates object from background
  ([TU Wien, SPIE 2023](https://repositum.tuwien.at/handle/20.500.12708/208109): ~5 mAP points), so the input
  stays RGB; colour invariance comes from augmentation instead (any hue, strong saturation / brightness, one
  image in five grey). Class-agnostic detection then per-instance attributes generalises better
  ([Jaiswal, WACV 2021](https://arxiv.org/pdf/2011.14204)). Route colour should become a robust per-mask
  chroma statistic (not yet done). **Done (G1):** `train.py` augmentation.
- **Illumination.** CLAHE has the most consistent record for segmentation under uneven light; Retinex and
  intrinsic decomposition cost much more for little gain. **Done (G1):** `clahe.py` / `core/Clahe.kt` on luma,
  in training and on the phone.
- **Touching same-colour holds.** Marker watershed on a relief-curvature + grey-gradient energy is the standard
  split; depth jumps are weak where volumes touch, creases are not. Not done (G3). **Done instead:** model masks
  split merged holds (`HoldMerge.withModel`), masks touching along ≥ 35% of an outline stay one hold.
- **Tape.** Geometric filters fail here (outlined strips are 13 × 7 cm like small holds); PanClimb's public-domain
  set labels `tape` (1,851 polygons). **Done (G1):** two classes, `hold` and `tape`; holds under tape dropped.
- No published work on colour-agnostic hold detection was found;
  [xiaoxiae/Indoor-Climbing-Hold-and-Route-Segmentation](https://github.com/xiaoxiae/Indoor-Climbing-Hold-and-Route-Segmentation)
  segments first and groups by colour after.

## Outlines by SAM (2026-10-24, Phase 1 for the automatic outlines)

Outlines from relief and colour took in shadows, chalk and bare wall: `HoldShapes.absorbGrey` absorbs grey
pixels touching a coloured hold (its own shadow among them), the 4 cm closing fills notches up to 8 cm wide,
and `segmentAchromatic` grows black and grey holds into shadow, which is nearer a dark hold than the lit wall.
Compared on the two Oct 23 walls by the mean photo-edge strength on each outline (the references carry the app's
own outlines, so they cannot score tightness) and by eye:

| Outlines | 2:00 PM | 2:04 PM | By eye |
|---|---|---|---|
| App (detector + G1 merge) | 0.66 | 0.76 | loose on volumes and black holds, shadows in |
| G1 alone | 0.59 | 0.72 | tight on black holds, junk boxes on concrete, volumes in fragments |
| G1 on a grey photo | 0.56 | 0.71 | finds fewer holds (60 vs 69, 37 vs 49): grey input rejected |
| SAM2.1-L / SAM2.1-tiny / MobileSAM, app boxes as prompts | .70 / .72 / .68 | .84 / .84 / .82 | tight everywhere, no shadows |
| Shipped: MobileSAM int8 weights + guards (`core/SamRefine.kt`) | 0.66 | 0.81 | as MobileSAM |

MobileSAM runs in the app after the prune stage (`io/HoldSam.kt`): the box prompt keeps it on the hold the
detector found, so the detector still decides *what* is a hold and SAM only *where its edge is*. Grayscale stays
where it helps: as training augmentation (G1) and as the edge map of the viewer's split.

## Wall vs hold (2026-10-24, from the climber's corrections of Oct 23 2:04 PM)

Why shadows passed as holds: `WallColor` is one global range (hue ± 10°, the wall's saturation and value spread)
measured on the depth camera's point colours and applied to orthophoto pixels. A shadow keeps the plywood's tint
but is darker, and at low brightness the hue turns to noise; bolt holes, grain and keyframe exposure seams fail it
too. On 2:04 PM 23% of the photo was "not wall" outside every hold (hue 76%, saturation 56%, too dark 25%).
A photo-derived model — chromaticity (r, g)/(r+g+b) within the wall's own 90th-percentile spread of the local
wall (median per 20 cm tile, outside holds), brightness 0.3–1.4 of it, specks under 2 cm² ignored — leaves 9%,
nearly all of it real: tape, placards, concrete, mats and hold parts outside their outlines (prototype in the
session scratchpad, not in the app yet).

What the app does now (after SAM): pieces merge when one's SAM mask lies ≥ 60% inside another's; image-only holds
need CIE ΔE ≥ 6 against the wall ring around them. On the climber's list: 6 of 6 merges, both wall "holds" gone,
48 → 40 holds (climber: ~44). Left: tape 48 (the tape class missed it), volume pair 26 and the holds on it, the hold
above 55, the black hold 24 only partly outlined. A search of uncovered not-wall blobs with SAM finds the hold
above 55 and the rest of 24, but also ~15 tape strips, placards and edge blobs: it needs tape and placard
suppression first.

## Train run 3 (2026-10-24 evening): our own walls

G1 fine-tuned with the climber-corrected Oct 23 walls (`merge.py --ours-repeat 6`, 45 epochs). Honest check first
(run 3a: 2:00 PM only, scored on the 2:04 PM reference it never saw): G1 P .80 R .91 IoU .84 → G3a P .93 R .88 IoU
.85 — false holds 10 → 3 for one more miss. Run 3 (both walls) is installed (assets/holds.tflite). By eye on Oct 21 6:21 PM: olive volume and big blue hold whole, white volume fuller, top junk gone; the
crash pad still counts as a hold. On the phone, 2:00 PM with run 3 finds all 68 reference holds, including the two
edge holds the climber added (+3 extra; it trained on that wall). Oct 21 5:20 PM (same wall face as 2:04 PM, other
scan): volumes right, outlines tight; junk left on the concrete column, two loose depth outlines on bare wall, red
tape, neighbouring faces at the edges; the black banana's dark part still missing.
