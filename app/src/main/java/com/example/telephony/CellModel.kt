package com.example.telephony

data class CellModel(
    val tech: String = "Unknown", // "5G SA", "5G NSA", "4G LTE", "Unknown"
    val mcc: String? = null,
    val mnc: String? = null,
    val operatorName: String? = null,
    val cellId: Long = 0, // ci for LTE, nci for NR
    val nodebId: Long = 0, // eNB or gNB
    val sectorId: Int = 0,
    val pci: Int = 0,
    val tac: Int = 0,
    val arfcn: Int = 0, // earfcn or nrarfcn
    val bandName: String = "Unknown",
    val frequencyMhz: Double = 0.0,
    val bandwidthKhz: Int = 0,
    val rsrp: Int = -140, // dBm
    val rsrq: Int = -20, // dB
    val sinr: Int = -10, // dB
    val ssRsrp: Int = -140,
    val ssRsrq: Int = -20,
    val ssSinr: Int = -10,
    val csiRsrp: Int = -140,
    val csiRsrq: Int = -20,
    val csiSinr: Int = -10,
    val timingAdvance: Int = 0, // step count
    val distanceEstimateMeters: Double = 0.0,
    val signalGrade: String = "Poor", // "Excellent", "Good", "Fair", "Poor"
    val signalGradeColorHex: Long = 0xFFE57373, // Color hex
    val description: String = "",
    val activeCarriers: List<CarrierInfo> = emptyList(),
    val neighbors: List<NeighborCell> = emptyList()
)

data class CarrierInfo(
    val band: String,
    val arfcn: Int,
    val rsrp: Int,
    val type: String // "PCC" (Primary), "SCC" (Secondary)
)

data class NeighborCell(
    val pci: Int,
    val band: String,
    val arfcn: Int,
    val rsrp: Int,
    val tech: String
)
