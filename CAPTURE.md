# P1 — Capture one route with Scaniverse (Android) and review the splat

Goal: decide whether phone camera capture alone gives route splats good enough to build on.
No custom code beyond the viewer in `viewer/index.html`.

## 1. Prepare

- [ ] Scaniverse installed on the Android phone; the phone has ARCore support
- [ ] Lens cleaned; battery > 50%; ≥ 2 GB free storage
- [ ] Wall section chosen: one route, start hold to top hold, plus ~1 m margin each side
- [ ] Scale reference taped to the wall near the start hold: a ruler, or a printed square of known size (e.g. 200 mm)
- [ ] No climbers in the section during capture (people moving through the frame leave smeared artifacts in the splat)
- [ ] Even lighting; note any spotlights or backlit windows behind the wall

## 2. Capture

Choose Gaussian splat output when Scaniverse asks for the processing type (check the exact label in the app version).

| Pass | Distance | Path | Purpose |
|---|---|---|---|
| A | 1–1.5 m | Horizontal rows, bottom to top, ~60% overlap between rows | Hold detail and texture |
| B | 1–1.5 m | Same rows, phone angled ~30° left, then ~30° right | Hold sides and edges |
| C | 3–4 m | Slow arc across the whole section | Overall shape; ties the close passes together |
| D (overhang only) | 1–2 m | From below, phone angled up | Undercuts and hold undersides |

- Move slowly: about one step per second; stop for a moment at row ends
- Keep the wall filling most of the frame; avoid pointing at the floor or ceiling for long
- If tracking is lost, back up to an area already captured and continue
- Capture 2–3 attempts per route at different speeds or pass orders, and record each one in the log below

## 3. Export

1. Process on the device (splat mode)
2. Export as `.ply` (or `.spz` if offered)
3. Copy to the laptop: `wall-scanner/captures/<YYYYMMDD>-<route>-<attempt>.ply`

## 4. Review

Open `viewer/index.html` in Chrome and drop the file in. To view on the phone over the LAN:

```bash
cd /home/shane/temp/wall-scanner
python3 -m http.server 8000 --bind 0.0.0.0
# phone: http://<laptop-lan-ip>:8000/viewer/   (then pick the file from phone storage)
# or load a laptop capture directly:
#        http://<laptop-lan-ip>:8000/viewer/?url=../captures/<file>.ply
```

Use **Flip** if the wall appears upside down, and **Reset view** to reframe the camera.

## 5. Score each capture

| Criterion | Pass if |
|---|---|
| Hold edges | Hold outlines are sharp from 1 m in the viewer |
| Hold colors | Route holds are distinguishable from neighboring holds by color |
| Hold shape | Jugs, crimps, and slopers are recognizable from the side |
| Floaters | Few stray splats in front of the wall |
| Coverage | No holes on the route's holds |
| Scale | The scale reference reads within ±5% (check by measuring in SuperSplat) |
| Phone viewing | Smooth orbiting on the phone in the viewer |
| Time | Capture + processing < 15 min per route |

## Capture log

| Date (JST) | Route | Attempt | Passes | Capture time | Processing time | File size | Score (x/8) | Notes |
|---|---|---|---|---|---|---|---|---|
| | | | | | | | | |

## Decision after P1

- Score ≥ 6/8 on most captures → proceed to **P2** (hold detection + route annotation on exported splats)
- Fails on detail or scale → test **O3** (photos + desktop training), then consider **P3** (custom ARCore capture app)
