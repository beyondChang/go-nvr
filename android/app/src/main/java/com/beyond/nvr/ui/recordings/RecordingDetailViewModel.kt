package com.beyond.nvr.ui.recordings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.beyond.nvr.data.model.FrameInfo
import com.beyond.nvr.data.model.Recording
import com.beyond.nvr.data.repository.CameraRepository
import com.beyond.nvr.data.repository.RecordingRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class RecordingDetailUiState(
    val recording: Recording? = null,
    val frames: List<FrameInfo> = emptyList(),
    val cameraRecordings: List<Recording> = emptyList(),
    val currentIndex: Int = -1,
    val isLoading: Boolean = true,
    val error: String? = null,
    val deleted: Boolean = false,
)

class RecordingDetailViewModel(
    private val recordingRepository: RecordingRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RecordingDetailUiState())
    val uiState: StateFlow<RecordingDetailUiState> = _uiState.asStateFlow()

    fun loadRecording(recordingId: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            recordingRepository.getRecording(recordingId).fold(
                onSuccess = { recording ->
                    _uiState.value = _uiState.value.copy(
                        recording = recording,
                        isLoading = false,
                    )
                    // Load frames for MJPEG recordings
                    if (recording.format == "mjpeg") {
                        loadFrames(recordingId)
                    }
                    // Load all recordings for the same camera
                    loadCameraRecordings(recording.cameraId, recordingId)
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        error = e.message ?: "Failed to load recording",
                        isLoading = false,
                    )
                },
            )
        }
    }

    private fun loadCameraRecordings(cameraId: String, currentRecordingId: String) {
        viewModelScope.launch {
            recordingRepository.listRecordings(cameraId = cameraId).fold(
                onSuccess = { response ->
                    val recordings = response.recordings.sortedBy { it.startedAt }
                    val currentIndex = recordings.indexOfFirst { it.id == currentRecordingId }
                    _uiState.value = _uiState.value.copy(
                        cameraRecordings = recordings,
                        currentIndex = currentIndex,
                    )
                },
                onFailure = { /* ignore */ },
            )
        }
    }

    fun selectRecording(recordingId: String) {
        loadRecording(recordingId)
    }

    fun previousRecording() {
        val state = _uiState.value
        val index = state.currentIndex
        if (index > 0) {
            val prevRecording = state.cameraRecordings[index - 1]
            selectRecording(prevRecording.id)
        }
    }

    fun nextRecording() {
        val state = _uiState.value
        val index = state.currentIndex
        if (index < state.cameraRecordings.size - 1) {
            val nextRecording = state.cameraRecordings[index + 1]
            selectRecording(nextRecording.id)
        }
    }

    private fun loadFrames(recordingId: String) {
        viewModelScope.launch {
            recordingRepository.listFrames(recordingId).fold(
                onSuccess = { response ->
                    _uiState.value = _uiState.value.copy(frames = response.frames)
                },
                onFailure = { /* ignore */ },
            )
        }
    }

    fun deleteRecording() {
        val recordingId = _uiState.value.recording?.id ?: return
        viewModelScope.launch {
            recordingRepository.deleteRecording(recordingId).fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(deleted = true)
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        error = e.message ?: "Failed to delete recording",
                    )
                },
            )
        }
    }
}
