package com.beyond.nvr.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.beyond.nvr.data.model.*
import com.beyond.nvr.data.repository.PreferencesRepository
import com.beyond.nvr.data.repository.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SettingsUiState(
    val serverUrl: String = "",
    val theme: String = "system",
    val cleanup: CleanupConfig? = null,
    val webdav: WebDAVConfig? = null,
    val merge: MergeConfig? = null,
    val mergeStatus: MergeStatus? = null,
    val mergePending: MergePending? = null,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val error: String? = null,
    val saveSuccess: Boolean = false,
)

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val prefsRepo: PreferencesRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadSettings()
    }

    fun loadSettings() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            val serverUrl = prefsRepo.getServerUrl()
            // Theme will be observed separately in the UI via the preferences repository

            _uiState.value = _uiState.value.copy(
                serverUrl = serverUrl,
                isLoading = false,
            )

            // Load cleanup/webdav settings
            settingsRepository.getSettings().fold(
                onSuccess = { settings ->
                    _uiState.value = _uiState.value.copy(
                        cleanup = settings.cleanup,
                        webdav = settings.webdav,
                    )
                },
                onFailure = { /* non-critical */ },
            )

            // Load merge settings
            settingsRepository.getMergeSettings().fold(
                onSuccess = { merge ->
                    _uiState.value = _uiState.value.copy(merge = merge)
                },
                onFailure = { /* non-critical */ },
            )

            settingsRepository.getMergeStatus().fold(
                onSuccess = { status ->
                    _uiState.value = _uiState.value.copy(mergeStatus = status)
                },
                onFailure = { /* non-critical */ },
            )
        }
    }

    fun updateServerUrl(url: String) {
        _uiState.value = _uiState.value.copy(serverUrl = url)
        viewModelScope.launch {
            prefsRepo.setServerUrl(url)
        }
    }

    fun setTheme(theme: String) {
        _uiState.value = _uiState.value.copy(theme = theme)
        viewModelScope.launch {
            prefsRepo.setTheme(theme)
        }
    }

    fun saveSettings(cleanup: CleanupConfig, webdav: WebDAVConfig) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true, error = null)
            val config = SettingsConfig(cleanup = cleanup, webdav = webdav)
            settingsRepository.updateSettings(config).fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(
                        isSaving = false,
                        saveSuccess = true,
                    )
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        isSaving = false,
                        error = e.message ?: "Failed to save settings",
                    )
                },
            )
        }
    }
}
