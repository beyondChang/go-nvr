package com.beyond.nvr.ui.cameras

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.beyond.nvr.data.model.Camera
import com.beyond.nvr.data.model.CreateCameraRequest
import com.beyond.nvr.data.model.UpdateCameraRequest
import com.beyond.nvr.data.repository.CameraRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CamerasUiState(
    val cameras: List<Camera> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val showAddDialog: Boolean = false,
    val savingCamera: Boolean = false,
)

class CamerasViewModel(
    private val cameraRepository: CameraRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CamerasUiState())
    val uiState: StateFlow<CamerasUiState> = _uiState.asStateFlow()

    init {
        loadCameras()
    }

    fun loadCameras() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
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

    fun showAddCameraDialog() {
        _uiState.value = _uiState.value.copy(showAddDialog = true)
    }

    fun hideAddCameraDialog() {
        _uiState.value = _uiState.value.copy(showAddDialog = false)
    }

    fun addCamera(
        name: String,
        url: String,
        protocol: String = "rtsp",
        username: String?,
        password: String?,
        onSuccess: () -> Unit,
    ) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(savingCamera = true)
            val request = CreateCameraRequest(
                name = name,
                url = url,
                protocol = protocol,
                username = username,
                password = password,
            )
            cameraRepository.createCamera(request).fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(
                        savingCamera = false,
                        showAddDialog = false,
                    )
                    loadCameras()
                    onSuccess()
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        savingCamera = false,
                        error = e.message ?: "Failed to create camera",
                    )
                },
            )
        }
    }

    fun toggleCamera(camera: Camera) {
        viewModelScope.launch {
            val request = UpdateCameraRequest(enabled = !camera.enabled)
            cameraRepository.updateCamera(camera.id, request).fold(
                onSuccess = { loadCameras() },
                onFailure = { /* ignore */ },
            )
        }
    }

    fun deleteCamera(cameraId: String) {
        viewModelScope.launch {
            cameraRepository.deleteCamera(cameraId).fold(
                onSuccess = { loadCameras() },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        error = e.message ?: "Failed to delete camera",
                    )
                },
            )
        }
    }
}
