"""Send one local test image to the Qwen3.5-VL service and print its JSON response."""

import base64
import json
import unittest
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen


IMAGE_ROOT = Path(r"D:\mobileclip_pc\imagenette2-160\val")
IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".webp", ".bmp"}


def main() -> None:
    image = next(
        path for path in IMAGE_ROOT.rglob("*")
        if path.is_file() and path.suffix.lower() in IMAGE_EXTENSIONS
    )
    payload = json.dumps({
        "image_base64": base64.b64encode(image.read_bytes()).decode("ascii"),
        "mime_type": "image/jpeg",
    }).encode("utf-8")
    request = Request(
        "http://127.0.0.1:8000/analyze",
        data=payload,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urlopen(request, timeout=900) as response:
            print(response.read().decode("utf-8"))
    except HTTPError as error:
        print(error.read().decode("utf-8"))
        raise


if __name__ == "__main__":
    main()


class JsonOutputParsingTest(unittest.TestCase):
    def test_fenced_json(self) -> None:
        from tools.qwen35_vl_server import normalize_response

        result = normalize_response(
            '```json\n{"categories":["发票"],"tags":["税务"],'
            '"description":"增值税发票","ocr_text":"43","confidence":0.9}\n```'
        )
        self.assertEqual(["发票"], result["categories"])

    def test_json_encoded_string(self) -> None:
        from tools.qwen35_vl_server import normalize_response

        inner = json.dumps({
            "categories": ["发票"], "tags": ["税务"],
            "description": "增值税发票", "ocr_text": "43", "confidence": 0.9,
        }, ensure_ascii=False)
        result = normalize_response(json.dumps(f"```json\n{inner}\n```", ensure_ascii=False))
        self.assertEqual(["税务"], result["tags"])
