package com.example.isip.data.ai

/**
 * The two GGUF files that make up the on-device Gemma 4 vision-language model.
 *
 * Models are intentionally not packaged in the APK. Install them into
 * `files/models/` with `tools/prepare_gemma4_model.py` before running analysis.
 */
object Gemma4Model {
    const val MODEL_FILE_NAME = "gemma-4-E2B_q4_0-it.gguf"
    const val MMPROJ_FILE_NAME = "gemma-4-E2B-it-mmproj.gguf"

    const val MODEL_ASSET_PATH = "models/$MODEL_FILE_NAME"
    const val MMPROJ_ASSET_PATH = "models/$MMPROJ_FILE_NAME"

    const val MODEL_NAME = "gemma-4-e2b-it-qat"
    const val MODEL_VERSION = "q4_0-gguf"
}
