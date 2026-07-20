package com.example.isip.data

import android.content.Context

enum class InferenceMode(val title: String) {
    LOCAL("本地模型"),
    RULES("轻量分类")
}

data class AppSettings(
    val inferenceMode: InferenceMode = InferenceMode.LOCAL,
    val allowCloudFallback: Boolean = false,
    val protectSensitivePhotos: Boolean = true
)

class AppSettingsRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun read(): AppSettings = AppSettings(
        inferenceMode = runCatching {
            InferenceMode.valueOf(
                preferences.getString(KEY_INFERENCE_MODE, InferenceMode.LOCAL.name)
                    ?: InferenceMode.LOCAL.name
            )
        }.getOrDefault(InferenceMode.LOCAL),
        allowCloudFallback = preferences.getBoolean(KEY_ALLOW_CLOUD_FALLBACK, false),
        protectSensitivePhotos = preferences.getBoolean(KEY_PROTECT_SENSITIVE_PHOTOS, true)
    )

    fun setInferenceMode(mode: InferenceMode) {
        preferences.edit().putString(KEY_INFERENCE_MODE, mode.name).apply()
    }

    fun setAllowCloudFallback(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_ALLOW_CLOUD_FALLBACK, enabled).apply()
    }

    fun setProtectSensitivePhotos(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_PROTECT_SENSITIVE_PHOTOS, enabled).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "isip_settings"
        const val KEY_INFERENCE_MODE = "inference_mode"
        const val KEY_ALLOW_CLOUD_FALLBACK = "allow_cloud_fallback"
        const val KEY_PROTECT_SENSITIVE_PHOTOS = "protect_sensitive_photos"
    }
}
