package com.example.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.analysis.TowerObservations
import com.example.data.CellLog
import com.example.data.TowerDbEntry
import com.example.ui.theme.TlTheme
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Per-tower history built from this device's own logs, grouped by physical
 * tower (eNB/gNB decoded from the CID): bands, sectors, signal stats, plus an
 * observation-fit position and sector bearings when enough points exist.
 * Shared by the map's tower sheet and the Towers tab.
 */
@Composable
fun TowerObservationSection(tower: TowerDbEntry, logs: List<CellLog>) {
    val tl = TlTheme.colors
    val context = LocalContext.current

    val gnbBits = remember {
        context.getSharedPreferences("TowerLockPrefs", Context.MODE_PRIVATE)
            .getInt("gnb_bits", 24)
    }
    val towerNodeb = remember(tower) {
        if (tower.radio == "NR") tower.cid shr (36 - gnbBits) else tower.cid shr 8
    }
    val towerLogs = remember(tower, logs) {
        if (towerNodeb > 0) logs.filter { it.nodebId == towerNodeb } else emptyList()
    }
    val summary = remember(towerLogs) { TowerObservations.summarize(towerLogs) }
        ?: return

    Spacer(modifier = Modifier.height(16.dp))
    HorizontalDivider(color = tl.surfaceVariant)
    Spacer(modifier = Modifier.height(12.dp))
    Text(
        text = "YOUR OBSERVATIONS (${summary.observationCount})",
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp),
        color = tl.textMuted,
        fontWeight = FontWeight.Bold
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = "Bands seen: " + summary.bands.joinToString { "${it.first} ×${it.second}" },
        style = MaterialTheme.typography.bodySmall,
        color = tl.textSecondary
    )
    Text(
        text = "Signal: avg ${summary.avgRsrp} dBm • best ${summary.bestRsrp} dBm • " +
                summary.techs.joinToString("/"),
        style = MaterialTheme.typography.bodySmall,
        color = tl.textSecondary
    )
    val dateFmt = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    Text(
        text = "First seen ${dateFmt.format(Date(summary.firstSeen))} • " +
                "last ${dateFmt.format(Date(summary.lastSeen))}",
        style = MaterialTheme.typography.bodySmall,
        color = tl.textSecondary
    )

    val obsPoints = remember(towerLogs) {
        towerLogs.map {
            TowerObservations.Observation(
                lat = it.lat,
                lon = it.lon,
                rsrp = it.rsrp,
                sectorId = it.sectorId,
                distanceMeters = it.timingAdvanceMeters.takeIf { d -> d > 0.0 }
            )
        }
    }
    if (obsPoints.size >= 3) {
        val refined = remember(obsPoints) { TowerObservations.refinePosition(obsPoints) }
        refined?.let { (rlat, rlon) ->
            val deltaM = TowerObservations.distanceMeters(rlat, rlon, tower.lat, tower.lon)
            Text(
                text = String.format(
                    Locale.US,
                    "Observation-fit position: %.5f, %.5f (Δ %.0f m from mapped point)",
                    rlat, rlon, deltaM
                ),
                style = MaterialTheme.typography.bodySmall,
                color = tl.sky
            )
        }
        val bearings = remember(obsPoints) {
            TowerObservations.sectorBearings(tower.lat, tower.lon, obsPoints)
        }
        if (bearings.isNotEmpty()) {
            Text(
                text = "Sectors: " + bearings.joinToString {
                    "S${it.sectorId} faces ${it.compass} (${it.bearingDegrees}°, ${it.samples} obs)"
                },
                style = MaterialTheme.typography.bodySmall,
                color = tl.sky
            )
        }
    }
}
