package com.beyond.nvr.ui.recordings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.beyond.nvr.data.model.Camera
import com.beyond.nvr.data.model.Recording
import com.beyond.nvr.data.repository.CameraRepository
import com.beyond.nvr.data.repository.RecordingRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class RecordingsUiState(
    val recordings: List<Recording> = emptyList(),
    val cameras: List<Camera> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val selectedCameraId: String? = null,
    val searchQuery: String = "",
    val currentPage: Int = 0,
    val totalRecordings: Int = 0,
    val pageSize: Int = 20,
    val selectedIds: Set<String> = emptySet(),
)

class RecordingsViewModel(
    private val recordingRepository: RecordingRepository,
    private val cameraRepository: CameraRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RecordingsUiState())
    val uiState: StateFlow<RecordingsUiState> = _uiState.asStateFlow()

    init {
        loadCameras()
        loadRecordings()
    }

    private fun loadCameras() {
        viewModelScope.launch {
            cameraRepository.listCameras().fold(
                onSuccess = { cameras ->
                    _uiState.value = _uiState.value.copy(cameras = cameras)
                },
                onFailure = { /* ignore */ },
            )
        }
    }

    fun loadRecordings() {
        val state = _uiState.value
        viewModelScope.launch {
            _uiState.value = state.copy(isLoading = true, error = null)
            recordingRepository.listRecordings(
                cameraId = state.selectedCameraId,
                offset = state.currentPage * state.pageSize,
                limit = state.pageSize,
                search = state.searchQuery.ifBlank { null },
            ).fold(
                onSuccess = { response ->
                    _uiState.value = _uiState.value.copy(
                        recordings = response.recordings,
                        totalRecordings = response.total ?: response.recordings.size,
                        isLoading = false,
                    )
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        error = e.message ?: "Failed to load recordings",
                        isLoading = false,
                    )
                },
            )
        }
    }

    fun selectCamera(cameraId: String?) {
        _uiState.value = _uiState.value.copy(
            selectedCameraId = cameraId,
            currentPage = 0,
            selectedIds = emptySet(),
        )
        loadRecordings()
    }

    fun search(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query, currentPage = 0)
        loadRecordings()
    }

    fun nextPage() {
        _uiState.value = _uiState.value.copy(currentPage = _uiState.value.currentPage + 1)
        loadRecordings()
    }

    fun previousPage() {
        if (_uiState.value.currentPage > 0) {
            _uiState.value = _uiState.value.copy(currentPage = _uiState.value.currentPage - 1)
            loadRecordings()
        }
    }

    fun toggleSelection(id: String) {
        val selected = _uiState.value.selectedIds.toMutableSet()
        if (selected.contains(id)) selected.remove(id) else selected.add(id)
        _uiState.value = _uiState.value.copy(selectedIds = selected)
    }

    fun deleteSelected() {
        viewModelScope.launch {
            val ids = _uiState.value.selectedIds.toList()
            recordingRepository.batchDeleteRecordings(ids).fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(selectedIds = emptySet())
                    loadRecordings()
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        error = e.message ?: "Failed to delete recordings",
                    )
                },
            )
        }
    }

    fun deleteRecording(id: String) {
        viewModelScope.launch {
            recordingRepository.deleteRecording(id).fold(
                onSuccess = { loadRecordings() },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        error = e.message ?: "Failed to delete recording",
                    )
                },
            )
        }
    }
}
