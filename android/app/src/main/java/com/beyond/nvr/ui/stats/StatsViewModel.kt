package com.beyond.nvr.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.beyond.nvr.data.model.*
import com.beyond.nvr.data.repository.PreferencesRepository
import com.beyond.nvr.data.repository.StatsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class StatsUiState(
    val storageStats: StorageStats? = null,
    val systemStats: SystemStats? = null,
    val health: HealthResponse? = null,
    val trends: List<DailyStats> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
)

class StatsViewModel(
    private val statsRepository: StatsRepository,
    private val prefsRepo: PreferencesRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(StatsUiState())
    val uiState: StateFlow<StatsUiState> = _uiState.asStateFlow()

    init {
        loadStats()
    }

    fun loadStats() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            // Load health (no auth needed)
            statsRepository.healthCheck().fold(
                onSuccess = { health ->
                    _uiState.value = _uiState.value.copy(health = health)
                },
                onFailure = { /* non-critical */ },
            )

            // Load storage stats
            statsRepository.getStats().fold(
                onSuccess = { stats ->
                    _uiState.value = _uiState.value.copy(storageStats = stats)
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(error = e.message)
                },
            )

            // Load system stats
            statsRepository.getSystemStats().fold(
                onSuccess = { sysStats ->
                    _uiState.value = _uiState.value.copy(systemStats = sysStats)
                },
                onFailure = { /* non-critical */ },
            )

            // Load trends
            statsRepository.getStatsTrends(7).fold(
                onSuccess = { trends ->
                    _uiState.value = _uiState.value.copy(trends = trends)
                },
                onFailure = { /* non-critical */ },
            )

            _uiState.value = _uiState.value.copy(isLoading = false)
        }
    }
}
