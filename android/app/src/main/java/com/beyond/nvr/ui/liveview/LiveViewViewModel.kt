package com.beyond.nvr.ui.liveview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.beyond.nvr.data.model.Camera
import com.beyond.nvr.data.repository.CameraRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class LiveViewUiState(
    val camera: Camera? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
    val snapshotUrl: String = "",
)

class LiveViewViewModel(
    private val cameraRepository: CameraRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LiveViewUiState())
    val uiState: StateFlow<LiveViewUiState> = _uiState.asStateFlow()

    fun loadCamera(cameraId: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            cameraRepository.getCamera(cameraId).fold(
                onSuccess = { camera ->
                    _uiState.value = _uiState.value.copy(
                        camera = camera,
                        isLoading = false,
                        snapshotUrl = cameraRepository.getSnapshotUrl(camera.id),
                    )
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        error = e.message ?: "Failed to load camera",
                        isLoading = false,
                    )
                },
            )
        }
    }
}
