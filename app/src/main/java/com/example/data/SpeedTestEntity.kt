package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "speed_tests")
data class SpeedTestEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val downloadMbps: Double,
    val latencyMs: Int,
    val label: String // radio configuration at test time, e.g. "3CC n41+n41+n25"
)
