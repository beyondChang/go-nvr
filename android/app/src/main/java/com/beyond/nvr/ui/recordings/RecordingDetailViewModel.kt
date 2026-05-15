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

    fun deleteRecording(recordingId: String) {
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
