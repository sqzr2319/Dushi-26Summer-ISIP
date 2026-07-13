"""Local Qwen3.5-VL analysis service for the Android photo app.

Run after the model download completes:
    python tools/qwen35_vl_server.py

The Android Emulator reaches this service through http://10.0.2.2:8000/analyze.
"""

from __future__ import annotations

import base64
import io
import json
import os
import re
import threading
from functools import lru_cache
from typing import Any

import torch
import uvicorn
from fastapi import FastAPI, HTTPException
from PIL import Image
from pydantic import BaseModel, Field
from transformers import AutoModelForImageTextToText, AutoProcessor


MODEL_DIR = os.environ.get("QWEN35_MODEL_DIR", r"D:\models\Qwen3.5-4B")
MAX_IMAGE_BYTES = 8 * 1024 * 1024
MAX_NEW_TOKENS = 1024

PROMPT = """不要展示思考过程。立即分析这张图片，并且只输出一个合法 JSON 对象，不要 Markdown、不要解释、不要在 JSON 前后添加文字。
JSON 格式：
{
  "categories": ["最多三个中文分类"],
  "tags": ["3到8个精确中文标签，不要带#"],
  "description": "不超过50字的中文画面描述",
  "ocr_text": "图片中清晰可读的文字；没有则为空字符串",
  "confidence": 0.0
}
标签必须描述这张具体图片，不要输出泛泛的“照片”“图像”等词。
"""


class AnalyzeRequest(BaseModel):
    image_base64: str = Field(min_length=16)
    mime_type: str = "image/jpeg"


class AnalyzeResponse(BaseModel):
    categories: list[str]
    tags: list[str]
    description: str
    ocr_text: str
    confidence: float


app = FastAPI(title="ISIP Qwen3.5-VL local analyzer")
_model_lock = threading.Lock()


@lru_cache(maxsize=1)
def load_model() -> tuple[Any, Any, torch.device]:
    if not os.path.exists(MODEL_DIR):
        raise RuntimeError(f"Model directory does not exist: {MODEL_DIR}")

    use_cuda = torch.cuda.is_available()
    device = torch.device("cuda:0" if use_cuda else "cpu")
    dtype = torch.bfloat16 if use_cuda else torch.float32
    processor = AutoProcessor.from_pretrained(MODEL_DIR)
    model_kwargs: dict[str, Any] = {
        "dtype": dtype,
        "low_cpu_mem_usage": True,
    }
    if use_cuda:
        # Qwen3.5-4B 的 BF16 权重约 8.7GB，无法完整放入 8GB RTX 4060。
        # NF4 双重量化可把权重降到约 3GB，并为视觉编码和生成缓存保留显存。
        from transformers import BitsAndBytesConfig

        model_kwargs.update({
            "device_map": "auto",
            "quantization_config": BitsAndBytesConfig(
                load_in_4bit=True,
                bnb_4bit_quant_type="nf4",
                bnb_4bit_compute_dtype=torch.bfloat16,
                bnb_4bit_use_double_quant=True,
            ),
        })
    model = AutoModelForImageTextToText.from_pretrained(MODEL_DIR, **model_kwargs)
    if not use_cuda:
        model = model.to(device)
    model.eval()
    return model, processor, device


@app.get("/health")
def health() -> dict[str, Any]:
    return {
        "model_path": MODEL_DIR,
        "model_loaded": load_model.cache_info().currsize == 1,
        "cuda": torch.cuda.is_available(),
        "gpu": torch.cuda.get_device_name(0) if torch.cuda.is_available() else None,
        "inference_mode": "cuda-nf4" if torch.cuda.is_available() else "cpu-fp32",
    }


@app.post("/analyze", response_model=AnalyzeResponse)
def analyze(request: AnalyzeRequest) -> AnalyzeResponse:
    print("收到 Android 图片分析请求，正在加载/运行 Qwen3.5…", flush=True)
    try:
        image_bytes = base64.b64decode(request.image_base64, validate=True)
    except ValueError as error:
        raise HTTPException(status_code=400, detail="image_base64 is invalid") from error

    if len(image_bytes) > MAX_IMAGE_BYTES:
        raise HTTPException(status_code=413, detail="compressed image is too large")

    try:
        image = Image.open(io.BytesIO(image_bytes)).convert("RGB")
    except Exception as error:  # Pillow has several possible decode exceptions.
        raise HTTPException(status_code=400, detail="image cannot be decoded") from error

    try:
        with _model_lock, torch.inference_mode():
            model, processor, device = load_model()
            messages = [
                {
                    "role": "user",
                    "content": [
                        {"type": "image", "image": image},
                        {"type": "text", "text": PROMPT},
                    ],
                }
            ]
            inputs = processor.apply_chat_template(
                messages,
                add_generation_prompt=True,
                enable_thinking=False,
                tokenize=True,
                return_dict=True,
                return_tensors="pt",
            ).to(device)
            generated = model.generate(
                **inputs,
                max_new_tokens=MAX_NEW_TOKENS,
                do_sample=False,
            )
            prompt_length = inputs["input_ids"].shape[-1]
            text = processor.decode(
                generated[0][prompt_length:],
                skip_special_tokens=True,
            )
        return AnalyzeResponse(**normalize_response(text))
    except HTTPException:
        raise
    except Exception as error:
        raise HTTPException(status_code=503, detail=f"Qwen3.5-VL unavailable: {error}") from error


def normalize_response(raw_text: str) -> dict[str, Any]:
    payload = extract_json_object(raw_text)

    def strings(name: str, limit: int) -> list[str]:
        value = payload.get(name, [])
        if not isinstance(value, list):
            return []
        return list(dict.fromkeys(
            item.strip().lstrip("#")
            for item in value
            if isinstance(item, str) and item.strip()
        ))[:limit]

    confidence = payload.get("confidence", 0.7)
    try:
        confidence = float(confidence)
    except (TypeError, ValueError):
        confidence = 0.7

    return {
        "categories": strings("categories", 3),
        "tags": strings("tags", 8),
        "description": str(payload.get("description", "")).strip()[:120],
        "ocr_text": str(payload.get("ocr_text", "")).strip()[:1200],
        "confidence": min(max(confidence, 0.0), 1.0),
    }


def extract_json_object(raw_text: str) -> dict[str, Any]:
    """Accept bare JSON, fenced JSON and JSON encoded as a string.

    Some Transformers/Qwen combinations preserve the surrounding markdown or
    escape the decoded answer. The Android contract remains a plain object, so
    normalize those presentation differences at the service boundary.
    """
    candidates = [raw_text.strip()]
    try:
        decoded = json.loads(candidates[0])
        if isinstance(decoded, dict):
            return decoded
        if isinstance(decoded, str):
            candidates.append(decoded.strip())
    except json.JSONDecodeError:
        pass

    for candidate in list(candidates):
        unfenced = re.sub(r"^```(?:json)?\s*|\s*```$", "", candidate, flags=re.IGNORECASE)
        candidates.append(unfenced.strip())
        if r'\"' in candidate or r'\n' in candidate:
            candidates.append(
                candidate.replace(r'\"', '"').replace(r'\n', '\n')
                .replace(r'\r', '\r').replace(r'\t', '\t')
            )

    for candidate in candidates:
        start = candidate.find("{")
        end = candidate.rfind("}")
        if start < 0 or end <= start:
            continue
        try:
            payload = json.loads(candidate[start:end + 1])
        except json.JSONDecodeError:
            continue
        if isinstance(payload, dict):
            return payload

    raise ValueError(f"Model did not return complete JSON: {raw_text[:400]!r}")


if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8000)
