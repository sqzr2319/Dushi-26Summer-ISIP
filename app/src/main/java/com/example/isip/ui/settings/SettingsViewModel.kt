package com.example.isip.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val hasMediaPermission: Boolean = false,
    val hasMicPermission: Boolean = false,
    val inferenceMode: String = "本地优先",
    val modelLoaded: Boolean = false,
    val allowCloudFallback: Boolean = false,
    val protectSensitivePhotos: Boolean = true,
    val cacheSize: String = "0 MB",
    val versionName: String = "1.0.0"
)

sealed interface SettingsUiEvent {
    data object RequestMediaPermission : SettingsUiEvent
    data object RequestMicPermission : SettingsUiEvent
    data object ChangeInferenceMode : SettingsUiEvent
    data object ToggleCloudFallback : SettingsUiEvent
    data object ToggleSensitiveProtection : SettingsUiEvent
    data object ClearCache : SettingsUiEvent
    data object ClearAllAnalysis : SettingsUiEvent
}

class SettingsViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadSettings()
    }

    fun onEvent(event: SettingsUiEvent) {
        when (event) {
            is SettingsUiEvent.RequestMediaPermission -> requestMediaPermission()
            is SettingsUiEvent.RequestMicPermission -> requestMicPermission()
            is SettingsUiEvent.ChangeInferenceMode -> changeInferenceMode()
            is SettingsUiEvent.ToggleCloudFallback -> toggleCloudFallback()
            is SettingsUiEvent.ToggleSensitiveProtection -> toggleSensitiveProtection()
            is SettingsUiEvent.ClearCache -> clearCache()
            is SettingsUiEvent.ClearAllAnalysis -> clearAllAnalysis()
        }
    }

    private fun loadSettings() {
        viewModelScope.launch {
            // TODO: Load actual settings from repository
            _uiState.update {
                it.copy(
                    hasMediaPermission = true,
                    hasMicPermission = false,
                    modelLoaded = true,
                    cacheSize = "42.5 MB"
                )
            }
        }
    }

    private fun requestMediaPermission() {
        // TODO: Implement permission request
    }

    private fun requestMicPermission() {
        // TODO: Implement permission request
    }

    private fun changeInferenceMode() {
        // TODO: Show mode selection dialog
    }

    private fun toggleCloudFallback() {
        _uiState.update {
            it.copy(allowCloudFallback = !it.allowCloudFallback)
        }
    }

    private fun toggleSensitiveProtection() {
        _uiState.update {
            it.copy(protectSensitivePhotos = !it.protectSensitivePhotos)
        }
    }

    private fun clearCache() {
        viewModelScope.launch {
            // TODO: Clear cache
            _uiState.update { it.copy(cacheSize = "0 MB") }
        }
    }

    private fun clearAllAnalysis() {
        viewModelScope.launch {
            // TODO: Clear all analysis results
        }
    }
}
