package com.example.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cell_logs")
data class CellLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val tech: String,
    val mcc: String,
    val mnc: String,
    val operatorName: String,
    val cellId: Long,
    val nodebId: Long,
    val sectorId: Int,
    val pci: Int,
    val tac: Int,
    val arfcn: Int,
    val band: String,
    val rsrp: Int,
    val rsrq: Int,
    val sinr: Int,
    val lat: Double,
    val lon: Double,
    val towerLat: Double?,
    val towerLon: Double?,
    val address: String,
    val source: String,
    // Schema v3: capacity and geometry context captured per sample.
    // timingAdvanceMeters feeds trilateration in TowerObservations; the CA
    // fields let history answer "when do I actually get carrier aggregation".
    @ColumnInfo(defaultValue = "0") val timingAdvanceMeters: Double = 0.0,
    @ColumnInfo(defaultValue = "1") val caCarrierCount: Int = 1,
    @ColumnInfo(defaultValue = "0") val aggregateBandwidthKhz: Int = 0
)
