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
MAX_NEW_TOKENS = 240

PROMPT = """分析这张图片，并且只输出一个合法 JSON 对象，不要 Markdown、不要解释。
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

    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    dtype = torch.bfloat16
    processor = AutoProcessor.from_pretrained(MODEL_DIR)
    model = AutoModelForImageTextToText.from_pretrained(
        MODEL_DIR,
        torch_dtype=dtype,
        low_cpu_mem_usage=True,
    ).to(device)
    model.eval()
    return model, processor, device


@app.get("/health")
def health() -> dict[str, Any]:
    return {
        "model_path": MODEL_DIR,
        "model_loaded": load_model.cache_info().currsize == 1,
        "cuda": torch.cuda.is_available(),
    }


@app.post("/analyze", response_model=AnalyzeResponse)
def analyze(request: AnalyzeRequest) -> AnalyzeResponse:
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
    match = re.search(r"\{.*\}", raw_text, flags=re.DOTALL)
    if match is None:
        raise ValueError(f"Model did not return JSON: {raw_text[:240]}")
    payload = json.loads(match.group(0))

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


if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8000)
