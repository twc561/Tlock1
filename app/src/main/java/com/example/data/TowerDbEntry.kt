package com.example.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "tower_db_entries",
    indices = [Index(value = ["mcc", "mnc", "area", "cid"], unique = true)]
)
data class TowerDbEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val radio: String, // "LTE", "NR", "GSM", "UMTS"
    val mcc: String,
    val mnc: String,
    val area: Int, // TAC / LAC
    val cid: Long, // Cell ID (ci for LTE, nci for NR)
    val lat: Double,
    val lon: Double,
    val range: Int, // Confidence range in meters (e.g. 1000)
    val address: String? = null
)
