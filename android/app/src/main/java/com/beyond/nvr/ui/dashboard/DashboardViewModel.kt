package com.beyond.nvr.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.beyond.nvr.data.model.Camera
import com.beyond.nvr.data.model.StorageStats
import com.beyond.nvr.data.repository.AuthRepository
import com.beyond.nvr.data.repository.CameraRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DashboardUiState(
    val cameras: List<Camera> = emptyList(),
    val stats: StorageStats? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
    val username: String = "",
)

class DashboardViewModel(
    private val cameraRepository: CameraRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    fun loadData() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            // Load username
            val creds = authRepository.getCredentials()
            _uiState.value = _uiState.value.copy(username = creds?.first ?: "")

            // Load cameras
            cameraRepository.listCameras().fold(
                onSuccess = { cameras ->
                    _uiState.value = _uiState.value.copy(cameras = cameras, isLoading = false)
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        error = e.message ?: "Failed to load cameras",
                        isLoading = false,
                    )
                },
            )
        }
    }

    fun logout(onLogout: () -> Unit) {
        viewModelScope.launch {
            authRepository.logout()
            onLogout()
        }
    }
}
