"""Install a Qwen3.5 GGUF pair (main model + vision projector) on a debug device.

Why a dedicated script instead of reusing ``prepare_gemma4_model.py``: the 2B and 4B
releases of Qwen3.5 share the **same** mmproj file name (``mmproj-F16.gguf``) but ship
different contents

    Qwen3.5-2B  mmproj-F16.gguf  668,227,264 bytes
    Qwen3.5-4B  mmproj-F16.gguf  672,423,616 bytes

so replacing only the main model leaves a mismatched vision tower behind, and the
failure mode is silent (the model keeps running, it just produces nonsense). This
script therefore installs the pair and then verifies the sizes that actually landed
in the app's private directory.

Usage:
    python tools/deploy_qwen35.py --variant 4b
    python tools/deploy_qwen35.py --variant 2b --model-dir C:\\path\\to\\files
"""

from __future__ import annotations

import argparse
import shutil
import subprocess
import sys
from pathlib import Path

PACKAGE = "com.example.isip"
REMOTE_DIR = "files/models"
STAGING = "/data/local/tmp"

# Expected exact byte counts, used to prove the right revision landed on device.
# Sizes come from the Hugging Face API for unsloth/Qwen3.5-{0.8B,2B,4B}-GGUF.
VARIANTS: dict[str, dict[str, int]] = {
    # 0.8B 的视觉塔只有 196 MB（2B 是 668 MB、4B 是 672 MB），而视觉塔编码是单张
    # 耗时里最长的一段 —— 这是它值得一试的主要原因，不只是 LLM 更小。
    "0.8b": {
        "Qwen3.5-0.8B-Q4_0.gguf": 507_154_688,
        "mmproj-F16.gguf": 204_987_232,
    },
    "2b": {
        "Qwen3.5-2B-Q4_0.gguf": 1_214_873_856,
        "mmproj-F16.gguf": 668_227_264,
    },
    "4b": {
        "Qwen3.5-4B-Q4_0.gguf": 2_583_221_408,
        "mmproj-F16.gguf": 672_423_616,
    },
}


def run(cmd: list[str], *, capture: bool = False) -> str:
    result = subprocess.run(cmd, check=True, text=True,
                            capture_output=capture)
    return (result.stdout or "").strip() if capture else ""


def adb(serial: str | None, *args: str, capture: bool = False) -> str:
    return run(["adb", *(["-s", serial] if serial else []), *args], capture=capture)


def remote_size(serial: str | None, path: str) -> int | None:
    """Byte size of ``path`` inside the app's private dir, or None if absent."""
    try:
        out = adb(serial, "shell", "run-as", PACKAGE, "stat", "-c", "%s", path,
                  capture=True)
        return int(out.strip())
    except (subprocess.CalledProcessError, ValueError):
        return None


def install(variant: str, model_dir: Path, serial: str | None, keep_others: bool) -> int:
    if shutil.which("adb") is None:
        print("adb not found on PATH", file=sys.stderr)
        return 1

    expected = VARIANTS[variant]
    missing = [n for n in expected if not (model_dir / n).is_file()]
    if missing:
        print(f"missing in {model_dir}: {', '.join(missing)}", file=sys.stderr)
        return 1

    # Warn about size mismatches before spending minutes pushing 2.6 GB.
    for name, want in expected.items():
        got = (model_dir / name).stat().st_size
        if got != want:
            print(f"WARNING {name}: local size {got:,} != expected {want:,}")

    adb(serial, "shell", "run-as", PACKAGE, "mkdir", "-p", REMOTE_DIR)

    for name in expected:
        local = model_dir / name
        staged = f"{STAGING}/{name}"
        print(f"pushing {name} ({local.stat().st_size / 1e9:.2f} GB) ...", flush=True)
        adb(serial, "push", str(local), staged)
        # run-as cannot see /data/local/tmp directly for writes, so copy then drop the stage.
        adb(serial, "shell", "run-as", PACKAGE, "cp", staged, f"{REMOTE_DIR}/{name}")
        adb(serial, "shell", "rm", "-f", staged)

    print("\nverifying what landed in the app's private dir:")
    ok = True
    for name, want in expected.items():
        got = remote_size(serial, f"{REMOTE_DIR}/{name}")
        status = "OK  " if got == want else "BAD "
        if got != want:
            ok = False
        print(f"  {status} {name}: {got:,}" + ("" if got == want else f" (expected {want:,})"))

    if not keep_others:
        # Stale files from the other variant are harmless for loading (the candidate
        # list picks one), but they waste 3+ GB and make it ambiguous which model ran.
        print("\ninstalled files now in", REMOTE_DIR)
        listing = adb(serial, "shell", "run-as", PACKAGE, "ls", "-l", REMOTE_DIR,
                      capture=True)
        print(listing)

    print("\nQwen3.5 variant '%s' installed. Force-stop the app before analysis." % variant)
    return 0 if ok else 2


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--variant", choices=sorted(VARIANTS), required=True)
    ap.add_argument("--model-dir", type=Path, required=True,
                    help="local directory holding the GGUF pair")
    ap.add_argument("--serial", help="adb device serial")
    ap.add_argument("--keep-others", action="store_true",
                    help="do not report about files from other variants")
    args = ap.parse_args()
    return install(args.variant, args.model_dir, args.serial, args.keep_others)


if __name__ == "__main__":
    raise SystemExit(main())
