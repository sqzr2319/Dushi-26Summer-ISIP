"""Resume-download the official Qwen3.5-4B vision-language model to the local model store."""

from pathlib import Path

from huggingface_hub import snapshot_download


MODEL_ID = "Qwen/Qwen3.5-4B"
TARGET_DIR = Path(r"D:\models\Qwen3.5-4B")


if __name__ == "__main__":
    TARGET_DIR.mkdir(parents=True, exist_ok=True)
    snapshot_download(
        repo_id=MODEL_ID,
        local_dir=TARGET_DIR,
        max_workers=4,
    )
