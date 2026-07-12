package com.example.telephony

import kotlinx.coroutines.flow.StateFlow

interface ITelephonyTracker {
    val cellState: StateFlow<CellModel>
    fun startMonitoring()
    fun stopMonitoring()
    fun setPollIntervalSeconds(seconds: Int)
    fun setGnbBitLength(bits: Int)
    fun updateSimulatedCell(mockCell: CellModel) // Useful for mock simulation / tests
}
