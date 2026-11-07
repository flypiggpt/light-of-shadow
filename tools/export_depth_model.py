#!/usr/bin/env python3
"""Depth Anything V2 Metric Indoor Small → TFLite 转换脚本（一次性模型资产准备）。

用法（Python 3.11，独立 venv/conda 环境）：
    pip install torch --index-url https://download.pytorch.org/whl/cpu
    pip install transformers litert-torch onnx onnxruntime pillow numpy
    python tools/export_depth_model.py --out light-of-shadow-client/app/src/main/assets/depth_metric.tflite
    python tools/export_depth_model.py --check <一张实拍图>   # 数值对拍

流程（已在 2026-11 实测跑通）：
    1. HuggingFace 拉 depth-anything/Depth-Anything-V2-Metric-Indoor-Small-hf
       （室内米制权重 Hypersim 系；**没有不带 -hf 的 transformers 原生版**）
    2. litert-torch 直接把 torch 模型转 TFLite（256×256 固定输入）
    3. --check 数值对拍：torch 与 tf.lite 同图输出相对误差 ≤2% 才放行

已知坑（踩过的，勿再走弯路）：
- ONNX 路线（torch.onnx → onnx2tf）对 ViT 报
  "Output tensors of a Functional model must be the output of a Layer"——onnx2tf 与
  TF 2.16+/Keras3 不兼容，已弃用；`ai-edge-torch` 包已改名 `litert-torch`
- torch.ao.quantization.quantize_dynamic 的旧量化算子过不了 pt2 导出（LinearPackedParamsBase）
- litert 的 pt2e 动态量化配方对本模型未生效（产物体积与 FP32 相同）；fp16 半精度在
  LayerNorm 处 MLIR 类型不兼容失败。**体积优化（INT8/FP16）留作后续专项**，当前产物 FP32 ≈95MB
"""

import argparse
from pathlib import Path

MEAN = [0.485, 0.456, 0.406]
STD = [0.229, 0.224, 0.225]
MODEL_ID = "depth-anything/Depth-Anything-V2-Metric-Indoor-Small-hf"  # 室内米制（Hypersim）
INPUT_SIZE = 256


def export_tflite(out_path: Path, size: int = INPUT_SIZE) -> None:
    import torch
    import litert_torch
    from transformers import AutoModelForDepthEstimation

    model = AutoModelForDepthEstimation.from_pretrained(MODEL_ID)
    model.eval()

    class Wrapper(torch.nn.Module):
        def __init__(self, m):
            super().__init__()
            self.m = m

        def forward(self, pixel_values):
            return self.m(pixel_values).predicted_depth

    sample = torch.randn(1, 3, size, size)
    converted = litert_torch.convert(Wrapper(model), (sample,))
    out_path.parent.mkdir(parents=True, exist_ok=True)
    converted.export(str(out_path))
    print(f"[tflite] {out_path} ({out_path.stat().st_size / 1e6:.1f} MB)")


def check_parity(tflite_path: Path, image: Path, tol: float = 0.02) -> None:
    """数值对拍：torch 与 TFLite 同图输出相对误差检查（转换正确性检查点）。"""
    import numpy as np
    import torch
    from PIL import Image
    from transformers import AutoModelForDepthEstimation

    img = Image.open(image).convert("RGB").resize((INPUT_SIZE, INPUT_SIZE))
    arr = np.asarray(img, dtype=np.float32) / 255.0
    arr = (arr - np.array(MEAN)) / np.array(STD)
    x = arr.transpose(2, 0, 1)[None].astype(np.float32)

    model = AutoModelForDepthEstimation.from_pretrained(MODEL_ID).eval()
    with torch.no_grad():
        ref = model(torch.from_numpy(x)).predicted_depth.numpy()

    import tensorflow as tf

    interp = tf.lite.Interpreter(model_path=str(tflite_path))
    interp.allocate_tensors()
    inp = interp.get_input_details()[0]
    out = interp.get_output_details()[0]
    interp.set_tensor(inp["index"], x.astype(inp["dtype"]))
    interp.invoke()
    got = interp.get_tensor(out["index"])

    rel = float(np.abs(ref - got).max() / max(float(np.abs(ref).max()), 1e-6))
    print(f"[check] max relative error = {rel:.4f} (tol {tol})")
    if rel > tol:
        raise SystemExit(f"对拍失败：误差 {rel:.4f} > {tol}，TFLite 产物不可信")


def main() -> int:
    ap = argparse.ArgumentParser(description="DA-v2 Metric Indoor Small → TFLite")
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--size", type=int, default=INPUT_SIZE)
    ap.add_argument("--check", type=Path, default=None, help="对拍用测试图片（实拍图）")
    args = ap.parse_args()

    if args.check:
        check_parity(args.out, args.check)
    else:
        export_tflite(args.out, args.size)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
