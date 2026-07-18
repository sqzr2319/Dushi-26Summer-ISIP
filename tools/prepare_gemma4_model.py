"""Install the downloaded Gemma 4 GGUF files on a debug Android device.

Usage:
  python tools/prepare_gemma4_model.py --serial emulator-5554
  python tools/prepare_gemma4_model.py --model-dir D:\\models\\Gemma-4-E2B-it-qat-q4_0

The 4.3 GB model stays out of Git and the APK. It is copied into the debug
app's private ``files/models`` directory, which is where GemmaInferenceEngine
looks first at runtime.
"""
from __future__ import annotations

import argparse
import shutil
import subprocess
from pathlib import Path

PACKAGE = "com.example.isip"
DEFAULT_MODEL_DIR = Path(r"D:\models\Gemma-4-E2B-it-qat-q4_0")
MODEL_FILES = (
    "gemma-4-E2B_q4_0-it.gguf",
    "gemma-4-E2B-it-mmproj.gguf",
)


def adb(serial: str | None, *args: str) -> None:
    subprocess.run(["adb", *( ["-s", serial] if serial else [] ), *args], check=True)


def install(model_dir: Path, serial: str | None) -> None:
    if shutil.which("adb") is None:
        raise SystemExit("找不到 adb：请从 Android Studio Terminal 运行，或把 platform-tools 加入 PATH。")
    missing = [name for name in MODEL_FILES if not (model_dir / name).is_file()]
    if missing:
        raise SystemExit(f"模型目录缺少文件: {', '.join(missing)}")

    adb(serial, "shell", "run-as", PACKAGE, "mkdir", "-p", "files/models")
    for name in MODEL_FILES:
        local = model_dir / name
        remote = f"/data/local/tmp/{name}"
        print(f"installing {name} ({local.stat().st_size / 1024 / 1024:.0f} MiB)")
        try:
            adb(serial, "push", str(local), remote)
            adb(serial, "shell", "run-as", PACKAGE, "cp", remote, f"files/models/{name}")
        finally:
            adb(serial, "shell", "rm", "-f", remote)

    print("Gemma 4 installed. Force-stop and reopen the app before analysis.")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", help="adb device serial, e.g. emulator-5554")
    parser.add_argument("--model-dir", type=Path, default=DEFAULT_MODEL_DIR)
    args = parser.parse_args()
    install(args.model_dir, args.serial)


if __name__ == "__main__":
    main()
