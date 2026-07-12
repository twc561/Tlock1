package com.example.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.service.TowerMonitoringService
import com.example.telephony.CellModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Unified data representation for all incoming cell and tower sensor telemetry.
 */
data class TelemetryUiState(
    val currentCell: CellModel = CellModel(),
    val rsrpHistory: List<Int> = emptyList(),
    val userLocation: Pair<Double, Double>? = null,
    val towerLocation: Pair<Double, Double>? = null,
    val resolvedAddress: String = "Locating serving tower...",
    val confidenceRange: Int = 0,
    val deviceHeading: Float = 0f,
    val towerSource: String = "Unmapped"
)

/**
 * Centralized state management ViewModel that aggregates reactive telemetry streams
 * from the tower monitoring service.
 */
class TelemetryViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(TelemetryUiState())
    val uiState: StateFlow<TelemetryUiState> = _uiState.asStateFlow()

    private var connectionJob: Job? = null

    /**
     * Connects to the active TowerMonitoringService and gathers sensor / location streams
     * concurrently into the single, immutable TelemetryUiState.
     */
    fun connectService(service: TowerMonitoringService) {
        connectionJob?.cancel()
        connectionJob = viewModelScope.launch {
            launch {
                service.currentCell.collectLatest { cell ->
                    updateCurrentCell(cell)
                }
            }
            launch {
                service.userLocation.collectLatest { location ->
                    updateUserLocation(location?.let { it.latitude to it.longitude })
                }
            }
            launch {
                service.towerLocation.collectLatest { towerLoc ->
                    updateTowerLocation(towerLoc)
                }
            }
            launch {
                service.resolvedAddress.collectLatest { address ->
                    updateResolvedAddress(address)
                }
            }
            launch {
                service.confidenceRange.collectLatest { range ->
                    updateConfidenceRange(range)
                }
            }
            launch {
                service.deviceHeading.collectLatest { heading ->
                    updateDeviceHeading(heading)
                }
            }
            launch {
                service.towerSource.collectLatest { source ->
                    updateTowerSource(source)
                }
            }
        }
    }

    /**
     * Stops collecting from the service when the service is unbound or stopped.
     */
    fun disconnectService() {
        connectionJob?.cancel()
        connectionJob = null
    }

    private fun updateCurrentCell(cell: CellModel) {
        _uiState.update { currentState ->
            val updatedHistory = if (cell.rsrp != -140) {
                currentState.rsrpHistory.toMutableList().apply {
                    add(cell.rsrp)
                    if (size > 30) removeAt(0)
                }
            } else {
                currentState.rsrpHistory
            }
            currentState.copy(
                currentCell = cell,
                rsrpHistory = updatedHistory
            )
        }
    }

    private fun updateUserLocation(location: Pair<Double, Double>?) {
        _uiState.update { it.copy(userLocation = location) }
    }

    private fun updateTowerLocation(location: Pair<Double, Double>?) {
        _uiState.update { it.copy(towerLocation = location) }
    }

    private fun updateResolvedAddress(address: String) {
        _uiState.update { it.copy(resolvedAddress = address) }
    }

    private fun updateConfidenceRange(range: Int) {
        _uiState.update { it.copy(confidenceRange = range) }
    }

    private fun updateDeviceHeading(heading: Float) {
        _uiState.update { it.copy(deviceHeading = heading) }
    }

    private fun updateTowerSource(source: String) {
        _uiState.update { it.copy(towerSource = source) }
    }
}
