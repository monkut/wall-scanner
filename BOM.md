# Wall Scanner — Bill of Materials (A1: capture on device, splat on desktop)

The handheld records camera frames, lidar scans, and IMU data with shared timestamps.
A desktop GPU computes the trajectory and trains the splat afterwards.

Prices: `✓` = verified (2026-10-20 JST), `est.` = estimate, verify before ordering.

## Core

| # | Part | Qty | Line total (USD) | Notes |
|---|---|---|---|---|
| 1 | Raspberry Pi 5, 4 GB | 1 | 110 ✓ | 8 GB ($175 ✓) only if processing on device later |
| 2 | Raspberry Pi 5 Active Cooler | 1 | 5 est. | Required for sustained recording |
| 3 | RPLIDAR A1M8-R5 | 1 | owned | Includes CP2102 USB adapter |
| 4 | Raspberry Pi Camera Module 3 Wide (IMX708, 12 MP) | 1 | 35 est. | Lock focus and use a short exposure to limit motion blur |
| 5 | IMU breakout, raw 6-axis (ISM330DHCX or ICM-42688-P), STEMMA QT/Qwiic | 1 | 20 est. | Raw gyro + accel at ≥200 Hz for fusion; avoid fusion-only parts |
| 6 | Raspberry Pi M.2 HAT+ | 1 | 12 est. | NVMe on PCIe keeps storage off the USB current budget |
| 7 | NVMe SSD, 256 GB, 2230/2242 | 1 | 30 est. | Raw frames fill storage quickly |
| 8 | microSD card, 32 GB | 1 | 10 est. | OS boot; can boot from NVMe later |

## Power

| # | Part | Qty | Line total (USD) | Notes |
|---|---|---|---|---|
| 9 | Pi 5 18650 UPS HAT (e.g. Geekworm X1200) | 1 | 45 est. | Supplies the Pi from the 5 V rail |
| 10 | 18650 cells, protected, ≥3000 mAh | 2 | 15 est. | Check the HAT's cell requirements |
| 11 | 5 V buck converter, ≥1 A | 1 | 5 est. | Only for direct-wired lidar (stage 2) |

Power budget: Pi 5 with a supply under 5 A limits total USB current to 600 mA.
The lidar (~100–350 mA) fits on USB in stage 1. Stage 2 powers the lidar from its own rail.

## Wiring and controls

| # | Part | Qty | Line total (USD) | Notes |
|---|---|---|---|---|
| 12 | Qwiic/STEMMA QT cable + Pi adapter | 1 | 5 est. | IMU on I2C |
| 13 | JST-PH 2.0 mm 5-pin pigtail | 2 | 6 est. | Lidar connector for direct UART (stage 2) |
| 14 | Momentary push button, panel mount | 1 | 2 est. | Start/stop recording |
| 15 | Status LED + resistor | 1 | 1 est. | Recording / error indicator |
| 16 | M2.5 standoffs, screws, heat-set inserts | 1 set | 10 est. | Mount Pi, HATs, and sensors to the printed frame |

## Printed parts

| # | Part | Material | Notes |
|---|---|---|---|
| P1 | Main frame (Pi stack + battery) | PETG | Lidar, camera, and IMU on one rigid plate |
| P2 | Handle with button | PETG/TPU grip | Cable path from button to GPIO |
| P3 | Lidar mount | PETG | Scan plane perpendicular to the wall; keep the 360° view clear of the handle |

Estimated total (excluding the lidar): ~$310.

## Connection stages

1. **Stage 1 (bench):** Lidar via the USB adapter (`/dev/ttyUSB0`), IMU on I2C, camera on CSI. Confirms the data pipeline.
2. **Stage 2 (handheld):** Lidar wired directly to the Pi GPIO:
   - TX → GPIO15 (RXD, pin 10), RX ← GPIO14 (TXD, pin 8), 3.3 V logic
   - `CTRL_MOTO` ← a PWM-capable GPIO (e.g. GPIO18, pin 12)
   - `VCC_5V` and `5V_MOTO` from the buck converter (#11); common ground with the Pi
   - Confirm the A1M8 pin order against the Slamtec datasheet before wiring

## Design constraints

- **Rigid mounting:** Camera, lidar, and IMU on one rigid part. Measure the transforms between them once (extrinsic calibration).
- **Timestamps:** Record all three streams against the Pi's monotonic clock. Log lidar timestamps on packet arrival and measure the offset.
- **Camera shutter:** The IMX708 has a rolling shutter. Move slowly, or switch to the Global Shutter Camera (IMX296, 1.6 MP) if frames skew.

## TODO

- TODO: Verify `est.` prices and stock before ordering.
- TODO: Confirm the A1M8 connector pin order from the datasheet.
- TODO: Choose the IMU part after checking breakout availability and driver support on the Pi 5.
