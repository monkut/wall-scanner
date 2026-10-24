# /// script
# requires-python = ">=3.11,<3.13"
# dependencies = ["litert-torch>=0.10", "ultralytics>=8.3", "ai-edge-litert", "ai-edge-quantizer", "numpy>=1.26", "opencv-python-headless>=4.9"]
# ///
"""Exports MobileSAM for the phone's outline refinement (core/SamRefine.kt):

    uv run tools/holds_model/sam_export.py [--fp32] [--check captures/<id> …]

- sam_encoder.tflite: [1, 3, 1024, 1024] RGB, normalised ((x - mean) / std on 0..255), the photo resized so its
  longer side is 1024 and padded at the right and bottom → [1, 256, 64, 64] image embedding.
- sam_decoder.tflite: the embedding and one box [1, 4] (x0, y0, x1, y1 in 1024-input pixels) → [1, 1, 256, 256]
  mask logits over the 1024 input and [1, 1] predicted IoU.

Weights are int8 with float32 activations (--fp32 keeps float weights); both files are copied to
android/app/src/main/assets/. --check runs PyTorch and the exported models on a capture's holds (boxes from its
scene.json outlines) and prints the mask IoU between them.
"""
import argparse
import json
import shutil
from pathlib import Path

import cv2
import numpy as np
import torch
from ultralytics import SAM

HERE = Path(__file__).resolve().parent
ASSETS = HERE.parents[1] / "android/app/src/main/assets"
SIZE = 1024
MEAN = np.array([123.675, 116.28, 103.53], np.float32)
STD = np.array([58.395, 57.12, 57.375], np.float32)


class Encoder(torch.nn.Module):
    def __init__(self, sam):
        super().__init__()
        self.encoder = sam.image_encoder

    def forward(self, x):
        return self.encoder(x)


class Decoder(torch.nn.Module):
    """One box prompt, single-mask output (as ultralytics predicts for boxes)."""

    def __init__(self, sam):
        super().__init__()
        self.prompt = sam.prompt_encoder
        self.decoder = sam.mask_decoder

    def forward(self, embedding, box):
        sparse = self.prompt._embed_boxes(box)
        dense = self.prompt.no_mask_embed.weight.reshape(1, -1, 1, 1).expand(1, -1, 64, 64)
        masks, iou = self.decoder(embedding, self.prompt.get_dense_pe(), sparse, dense, multimask_output=False)
        return masks, iou


def prepare(rgb):
    """The photo as the encoder takes it, and the scale from photo pixels to 1024-input pixels."""
    h, w = rgb.shape[:2]
    scale = SIZE / max(h, w)
    resized = cv2.resize(rgb, (int(w * scale + 0.5), int(h * scale + 0.5)), interpolation=cv2.INTER_LINEAR)
    canvas = np.zeros((SIZE, SIZE, 3), np.float32)
    canvas[: resized.shape[0], : resized.shape[1]] = (resized.astype(np.float32) - MEAN) / STD
    return canvas.transpose(2, 0, 1)[None], scale


def boxes_of(cap):
    scene = json.loads((cap / "scene.json").read_text())
    p = scene["wall"]["photo"]
    min_u, _, _, max_v = p["bounds"]
    out = []
    for hold in scene["holds"]:
        pts = np.array([((u - min_u) / p["px"], (max_v - v) / p["px"]) for u, v in hold.get("outline") or []])
        if len(pts) >= 3:
            out.append([*pts.min(0), *pts.max(0)])
    return np.array(out, np.float32)


def mask_of(logits, scale, h, w):
    up = cv2.resize(logits[0, 0], (SIZE, SIZE), interpolation=cv2.INTER_LINEAR)
    crop = up[: int(h * scale + 0.5), : int(w * scale + 0.5)]
    return cv2.resize(crop, (w, h), interpolation=cv2.INTER_LINEAR) > 0


def check(caps, enc_pt, dec_pt, enc_path, dec_path):
    from ai_edge_litert.interpreter import Interpreter

    def runner(path):
        it = Interpreter(model_path=str(path))
        it.allocate_tensors()
        ins = sorted(it.get_input_details(), key=lambda d: d["index"])
        outs = it.get_output_details()

        def run(*args):
            for d, a in zip(ins, args):
                it.set_tensor(d["index"], a)
            it.invoke()
            return [it.get_tensor(d["index"]) for d in outs]

        return run, ins, outs

    enc, _, _ = runner(enc_path)
    dec, dec_in, dec_out = runner(dec_path)
    print("decoder inputs", [(d["name"], tuple(d["shape"])) for d in dec_in], "outputs", [(d["name"], tuple(d["shape"])) for d in dec_out])
    for cap in caps:
        rgb = cv2.cvtColor(cv2.imread(str(cap / "wall.png")), cv2.COLOR_BGR2RGB)
        h, w = rgb.shape[:2]
        x, scale = prepare(rgb)
        with torch.no_grad():
            emb_pt = enc_pt(torch.from_numpy(x))
        emb_tf = enc(x)[0]
        print(f"{cap.name}: embedding max |diff| {np.abs(emb_tf - emb_pt.numpy()).max():.4f} (range {emb_pt.abs().max():.2f})")
        ious = []
        for box in boxes_of(cap):
            b = (box * scale)[None].astype(np.float32)
            with torch.no_grad():
                m_pt, _ = dec_pt(emb_pt, torch.from_numpy(b))
            # Output order is not guaranteed; the mask is the 4-D output.
            out = dec(emb_tf, b) if dec_in[0]["shape"][-1] == 64 else dec(b, emb_tf)
            m_tf = next(o for o in out if o.ndim == 4)
            a, c = mask_of(m_pt.numpy(), scale, h, w), mask_of(m_tf, scale, h, w)
            ious.append((a & c).sum() / max(1, (a | c).sum()))
        print(f"{cap.name}: {len(ious)} holds, mask IoU PyTorch vs exported: min {min(ious):.3f} median {np.median(ious):.3f}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--fp32", action="store_true")
    ap.add_argument("--check", nargs="*", type=Path, default=[])
    args = ap.parse_args()
    import litert_torch

    sam = SAM(str(HERE / "weights/mobile_sam.pt")).model.eval()
    enc_pt, dec_pt = Encoder(sam).eval(), Decoder(sam).eval()
    out = HERE / "runs/sam"
    out.mkdir(parents=True, exist_ok=True)
    x = torch.zeros(1, 3, SIZE, SIZE)
    emb = enc_pt(x)
    box = torch.tensor([[100.0, 100.0, 300.0, 300.0]])
    paths = {}
    for name, module, sample in (("encoder", enc_pt, (x,)), ("decoder", dec_pt, (emb, box))):
        fp32 = out / f"sam_{name}_fp32.tflite"
        litert_torch.convert(module, sample).export(str(fp32))
        paths[name] = fp32
        if not args.fp32:
            from ai_edge_quantizer import quantizer, recipe

            q = quantizer.Quantizer(str(fp32))
            q.load_quantization_recipe(recipe.weight_only_wi8_afp32())
            int8 = out / f"sam_{name}.tflite"
            q.quantize().export_model(str(int8), overwrite=True)
            paths[name] = int8
        print(f"{name}: {paths[name]} ({paths[name].stat().st_size / 1e6:.1f} MB)")
    if args.check:
        check(args.check, enc_pt, dec_pt, paths["encoder"], paths["decoder"])
    for name, path in paths.items():
        shutil.copy(path, ASSETS / f"sam_{name}.tflite")
    print(f"copied to {ASSETS}")


if __name__ == "__main__":
    main()
