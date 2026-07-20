package com.example.isip.ui.settings

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.isip.BuildConfig
import com.example.isip.data.AppSettingsRepository
import com.example.isip.data.InferenceMode
import com.example.isip.data.PhotoRepository
import com.example.isip.data.ai.ModelManager
import com.example.isip.data.ai.QwenModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsUiState(
    val hasMediaPermission: Boolean = false,
    val hasMicPermission: Boolean = false,
    val inferenceMode: InferenceMode = InferenceMode.LOCAL,
    val modelLoaded: Boolean = false,
    val modelStatus: String = "检查中…",
    val allowCloudFallback: Boolean = false,
    val protectSensitivePhotos: Boolean = true,
    val cacheSize: String = "计算中…",
    val versionName: String = BuildConfig.VERSION_NAME,
    val message: String? = null,
    val isBusy: Boolean = false
)

sealed interface SettingsUiEvent {
    data object Refresh : SettingsUiEvent
    data class MediaPermissionResult(val granted: Boolean) : SettingsUiEvent
    data class MicPermissionResult(val granted: Boolean) : SettingsUiEvent
    data class ChangeInferenceMode(val mode: InferenceMode) : SettingsUiEvent
    data class SetCloudFallback(val enabled: Boolean) : SettingsUiEvent
    data class SetSensitiveProtection(val enabled: Boolean) : SettingsUiEvent
    data object ClearCache : SettingsUiEvent
    data object ClearAllAnalysis : SettingsUiEvent
    data object DismissMessage : SettingsUiEvent
}

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val settingsRepository = AppSettingsRepository(application)
    private val photoRepository = PhotoRepository.getInstance(application)
    private val modelManager = ModelManager.getInstance(application)
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun onEvent(event: SettingsUiEvent) {
        when (event) {
            SettingsUiEvent.Refresh -> refresh()
            is SettingsUiEvent.MediaPermissionResult -> updateMediaPermission(event.granted)
            is SettingsUiEvent.MicPermissionResult -> updateMicPermission(event.granted)
            is SettingsUiEvent.ChangeInferenceMode -> changeInferenceMode(event.mode)
            is SettingsUiEvent.SetCloudFallback -> setCloudFallback(event.enabled)
            is SettingsUiEvent.SetSensitiveProtection -> setSensitiveProtection(event.enabled)
            SettingsUiEvent.ClearCache -> clearCache()
            SettingsUiEvent.ClearAllAnalysis -> clearAllAnalysis()
            SettingsUiEvent.DismissMessage -> _uiState.update { it.copy(message = null) }
        }
    }

    fun hasMediaPermission(): Boolean = mediaPermissions().any(::isGranted)

    fun hasMicPermission(): Boolean = isGranted(Manifest.permission.RECORD_AUDIO)

    private fun refresh() {
        val settings = settingsRepository.read()
        _uiState.update {
            it.copy(
                hasMediaPermission = hasMediaPermission(),
                hasMicPermission = hasMicPermission(),
                inferenceMode = settings.inferenceMode,
                allowCloudFallback = settings.allowCloudFallback,
                protectSensitivePhotos = settings.protectSensitivePhotos,
                modelLoaded = modelReady(),
                modelStatus = modelStatus(),
                cacheSize = cacheSize()
            )
        }
    }

    private fun updateMediaPermission(granted: Boolean) {
        _uiState.update { it.copy(hasMediaPermission = granted) }
    }

    private fun updateMicPermission(granted: Boolean) {
        _uiState.update { it.copy(hasMicPermission = granted) }
    }

    private fun changeInferenceMode(mode: InferenceMode) {
        settingsRepository.setInferenceMode(mode)
        _uiState.update { it.copy(inferenceMode = mode, message = "已切换为${mode.title}") }
    }

    private fun setCloudFallback(enabled: Boolean) {
        settingsRepository.setAllowCloudFallback(enabled)
        _uiState.update {
            it.copy(
                allowCloudFallback = enabled,
                message = if (enabled) "云端兜底已开启，当前版本暂无云端服务" else "云端兜底已关闭"
            )
        }
    }

    private fun setSensitiveProtection(enabled: Boolean) {
        settingsRepository.setProtectSensitivePhotos(enabled)
        _uiState.update { it.copy(protectSensitivePhotos = enabled) }
    }

    private fun clearCache() {
        clearAnalysis("分析缓存已清理")
    }

    private fun clearAllAnalysis() {
        clearAnalysis("所有分析结果已清理")
    }

    private fun clearAnalysis(successMessage: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, message = null) }
            runCatching { photoRepository.clearAllAnalysis() }
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            cacheSize = cacheSize(),
                            message = successMessage
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            message = "清理失败：${error.message ?: "未知错误"}"
                        )
                    }
                }
        }
    }

    private fun mediaPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES
        )
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(getApplication(), permission) == PackageManager.PERMISSION_GRANTED

    private fun modelReady(): Boolean =
        modelManager.isModelAvailable(QwenModel.MODEL_FILE_NAME) &&
            modelManager.isModelAvailable(QwenModel.MMPROJ_FILE_NAME)

    private fun modelStatus(): String = if (modelReady()) {
        "本地视觉模型可用"
    } else {
        "未安装模型，将使用轻量分类"
    }

    private fun cacheSize(): String {
        val database = getApplication<Application>().getDatabasePath("isip_database")
        val bytes = if (database.exists()) database.length() else 0L
        return when {
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "%.1f MB".format(bytes / (1024f * 1024f))
        }
    }
}
