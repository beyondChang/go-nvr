package com.beyond.nvr.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.beyond.nvr.data.model.Camera
import com.beyond.nvr.data.model.StorageStats
import com.beyond.nvr.data.repository.AuthRepository
import com.beyond.nvr.data.repository.CameraRepository
import com.beyond.nvr.data.repository.StatsRepository
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
    val onlineCount: Int = 0,
    val offlineCount: Int = 0,
)

class DashboardViewModel(
    private val cameraRepository: CameraRepository,
    private val authRepository: AuthRepository,
    private val statsRepository: StatsRepository,
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
                    val online = cameras.count { it.status == "online" || it.status == "active" }
                    val offline = cameras.count { it.status != "online" && it.status != "active" }
                    _uiState.value = _uiState.value.copy(
                        cameras = cameras,
                        onlineCount = online,
                        offlineCount = offline,
                        isLoading = false,
                    )
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        error = e.message ?: "加载失败",
                        isLoading = false,
                    )
                },
            )

            // Load stats (best-effort, don't override error)
            statsRepository.getStats().onSuccess { storageStats ->
                _uiState.value = _uiState.value.copy(stats = storageStats)
            }
        }
    }

    fun logout(onLogout: () -> Unit) {
        viewModelScope.launch {
            authRepository.logout()
            onLogout()
        }
    }
}
