package com.example.isip.data.ai

/**
 * The two GGUF files that make up the on-device Qwen3.5 vision-language model.
 *
 * Models are intentionally not packaged in the APK. Install them into
 * `files/models/` with `adb shell run-as ...` before running analysis.
 * See tools/setup_models.ps1 for details.
 */
object QwenModel {
    const val MODEL_FILE_NAME = "Qwen3.5-2B_Q4_K_M.gguf"
    const val MMPROJ_FILE_NAME = "Qwen3.5-2B.mmproj-f16.gguf"

    // Default search paths for the model files
    const val MODEL_ASSET_PATH = "/data/data/com.example.isip/files/models/$MODEL_FILE_NAME"
    const val MMPROJ_ASSET_PATH = "/data/data/com.example.isip/files/models/$MMPROJ_FILE_NAME"

    const val MODEL_NAME = "qwen3.5-2b-q4-k-m"
    const val MODEL_VERSION = "gguf"
}
