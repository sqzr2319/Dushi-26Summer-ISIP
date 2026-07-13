"""Download MobileCLIP-S2 LiteRT encoders and install them on a debug Android device.

Usage:
  python tools/prepare_mobileclip.py --download-only
  python tools/prepare_mobileclip.py --serial emulator-5554

The 400 MB model is deliberately kept out of Git and the APK. The second command
uses `adb push` + `run-as` to copy it into the app-private files/clip directory.
"""
from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import urllib.request
from pathlib import Path

REPO = "https://huggingface.co/plainhub/mobileclip-s2-tflite/resolve/main"
FILES = {
    "mobileclip_s2_image.tflite": "mobileclip_image.tflite",
    "mobileclip_s2_text.tflite": "mobileclip_text.tflite",
    "tokenizer.json": "tokenizer.json",
}
PACKAGE = "com.example.isip"


def download(url: str, target: Path) -> None:
    if target.is_file() and target.stat().st_size > 0:
        print(f"exists: {target}")
        return
    target.parent.mkdir(parents=True, exist_ok=True)
    partial = target.with_suffix(target.suffix + ".part")
    print(f"downloading: {url}")
    urllib.request.urlretrieve(url, partial)
    partial.replace(target)


def extract_tokenizer(tokenizer_file: Path, output: Path) -> None:
    root = json.loads(tokenizer_file.read_text(encoding="utf-8"))
    model = root["model"]
    (output / "vocab.json").write_text(
        json.dumps(model["vocab"], ensure_ascii=False), encoding="utf-8"
    )
    (output / "merges.txt").write_text(
        "#version: 0.2\n" + "\n".join(
            " ".join(item) if isinstance(item, list) else item for item in model["merges"]
        ),
        encoding="utf-8",
    )


def adb(serial: str | None, *args: str) -> None:
    command = ["adb"] + (["-s", serial] if serial else []) + list(args)
    subprocess.run(command, check=True)


def install(model_dir: Path, serial: str | None) -> None:
    if shutil.which("adb") is None:
        raise SystemExit("adb 不在 PATH 中，请从 Android Studio Terminal 运行或配置 platform-tools")
    remote = "/data/local/tmp/isip-mobileclip"
    adb(serial, "shell", "rm", "-rf", remote)
    adb(serial, "push", str(model_dir), remote)
    adb(serial, "shell", "run-as", PACKAGE, "mkdir", "-p", "files/clip")
    for name in ("mobileclip_image.tflite", "mobileclip_text.tflite", "vocab.json", "merges.txt"):
        adb(serial, "shell", "run-as", PACKAGE, "cp", f"{remote}/{name}", f"files/clip/{name}")
    adb(serial, "shell", "rm", "-rf", remote)
    print("MobileCLIP installed. Force-stop and reopen the app before analysis.")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--download-only", action="store_true")
    parser.add_argument("--serial", help="adb device serial")
    args = parser.parse_args()
    model_dir = Path(__file__).resolve().parent / ".models" / "mobileclip-s2"
    for source, target in FILES.items():
        download(f"{REPO}/{source}?download=true", model_dir / target)
    extract_tokenizer(model_dir / "tokenizer.json", model_dir)
    if not args.download_only:
        install(model_dir, args.serial)


if __name__ == "__main__":
    main()
