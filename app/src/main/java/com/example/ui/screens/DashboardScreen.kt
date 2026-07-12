package com.example.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.telephony.CapacityEstimator
import com.example.telephony.CellModel
import com.example.location.LocationTracker
import com.example.net.SpeedTester
import com.example.ui.theme.TlTheme
import com.example.ui.theme.signalColorForGrade
import com.example.ui.theme.signalColorForRsrp
import com.example.data.CellLog
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.roundToInt
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    cell: CellModel,
    userLat: Double?,
    userLon: Double?,
    towerLat: Double?,
    towerLon: Double?,
    resolvedAddress: String,
    confidenceMeters: Int,
    deviceHeading: Float,
    rsrpHistory: List<Int>,
    logs: List<CellLog>,
    towerSource: String = "Unmapped",
    speedTests: List<SpeedTester.SpeedResult> = emptyList(),
    onSpeedTestCompleted: (SpeedTester.SpeedResult) -> Unit = {},
    onSnapshotClick: () -> Unit
) {
    val tl = TlTheme.colors
    var activeMetricInfo by remember { mutableStateOf<MetricInfo?>(null) }

    val distance = if (userLat != null && userLon != null && towerLat != null && towerLon != null) {
        LocationTracker.calculateDistance(userLat, userLon, towerLat, towerLon)
    } else {
        null
    }

    val bearing = if (userLat != null && userLon != null && towerLat != null && towerLon != null) {
        LocationTracker.calculateBearing(userLat, userLon, towerLat, towerLon)
    } else {
        0f
    }

    val isSuspect = distance != null && cell.distanceEstimateMeters > 0 &&
            (distance > cell.distanceEstimateMeters * 2 || cell.distanceEstimateMeters > distance * 2)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(tl.background)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (cell.cellId <= 0) {
            AcquiringSignalState()
            return@Column
        }

        // Hero Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            colors = CardDefaults.cardColors(containerColor = tl.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ProviderLogo(
                        operatorName = cell.operatorName,
                        modifier = Modifier.size(44.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = cell.operatorName ?: "Carrier",
                            style = MaterialTheme.typography.titleMedium,
                            color = tl.textPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Serving Cell Identity",
                            style = MaterialTheme.typography.bodySmall,
                            color = tl.textSecondary
                        )
                    }
                    // Technology Badge
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(signalColorForGrade(cell.signalGrade))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = cell.tech,
                            color = tl.onAccent,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Street Address Hero Text
                Text(
                    text = if (towerLat != null) resolvedAddress else "Locating Serving Tower...",
                    style = MaterialTheme.typography.headlineSmall,
                    color = tl.textPrimary,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 28.sp
                )

                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.LocationOn,
                        contentDescription = "Accuracy",
                        tint = tl.sky,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (towerLat != null) "Confidence: ±${(confidenceMeters * 3.28084).toInt()} ft" else "Calculating triangulation metrics",
                        style = MaterialTheme.typography.bodySmall,
                        color = tl.sky
                    )
                    if (towerLat != null) {
                        Spacer(modifier = Modifier.width(12.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(tl.sky.copy(alpha = 0.2f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = towerSource,
                                style = MaterialTheme.typography.labelSmall,
                                color = tl.sky,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                if (isSuspect) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(tl.red.copy(alpha = 0.15f))
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Suspect Position",
                            tint = tl.red,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Position suspect: TA distance mismatch (>2x)",
                            style = MaterialTheme.typography.bodySmall,
                            color = tl.red,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        // Metrics Grid (Responsive 2x2)
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                MetricCard(
                    title = "RSRP",
                    value = "${cell.rsrp} dBm",
                    sub = "Ref. Signal Rx Power",
                    color = signalColorForGrade(cell.signalGrade),
                    icon = Icons.Default.SignalCellularAlt,
                    onClick = {
                        activeMetricInfo = MetricInfo(
                            "RSRP (Reference Signal Received Power)",
                            "RSRP is the power of the LTE/5G Reference Signals spread over the full bandwidth. It is the primary metric for signal strength.\n\n" +
                                    "Excellent: > -80 dBm\n" +
                                    "Good: -80 to -95 dBm\n" +
                                    "Fair: -95 to -110 dBm\n" +
                                    "Poor: < -110 dBm"
                        )
                    }
                )
            }
            item {
                MetricCard(
                    title = "SINR",
                    value = "${cell.sinr} dB",
                    sub = "Signal-to-Interference-Noise",
                    color = tl.sky,
                    icon = Icons.Default.NetworkWifi,
                    onClick = {
                        activeMetricInfo = MetricInfo(
                            "SINR (Signal-to-Interference-plus-Noise Ratio)",
                            "SINR measures signal quality, taking into account noise and interference from other towers. High SINR means faster data speeds.\n\n" +
                                    "Excellent: > 15 dB\n" +
                                    "Good: 8 to 15 dB\n" +
                                    "Fair: 2 to 8 dB\n" +
                                    "Poor: < 2 dB"
                        )
                    }
                )
            }
            item {
                MetricCard(
                    title = "Band",
                    value = cell.bandName.substringBefore(" ("),
                    sub = cell.bandName.substringAfter("(").replace(")", ""),
                    color = tl.amber,
                    icon = Icons.Default.SettingsInputAntenna,
                    onClick = {
                        activeMetricInfo = MetricInfo(
                            "Carrier Band",
                            "The operating frequency channel of the serving tower. Low bands (600/700MHz) travel far but are slower. Mid bands (2.5GHz/3.7GHz) offer high speeds."
                        )
                    }
                )
            }
            item {
                MetricCard(
                    title = "Distance",
                    value = if (distance != null) {
                        val feet = distance * 3.28084
                        if (feet < 1000) {
                            String.format(Locale.US, "%.0f ft", feet)
                        } else {
                            String.format(Locale.US, "%.2f mi", feet / 5280.0)
                        }
                    } else "---",
                    sub = if (cell.distanceEstimateMeters > 0) {
                        val feet = cell.distanceEstimateMeters * 3.28084
                        if (feet < 1000) {
                            String.format(Locale.US, "TA Ring: %.0f ft", feet)
                        } else {
                            String.format(Locale.US, "TA Ring: %.2f mi", feet / 5280.0)
                        }
                    } else "TA Ring: ---",
                    color = tl.pink,
                    icon = Icons.Default.DirectionsRun,
                    onClick = {
                        activeMetricInfo = MetricInfo(
                            "Distance & Timing Advance",
                            "Distance calculated using GPS coordinates of your phone and the estimated cell tower location.\n\n" +
                                    "Timing Advance (TA) is a hardware-reported step value representing propagation delay. On 5G, each step is ~9.24m (30.3 ft)."
                        )
                    }
                )
            }

            // Carrier Aggregation Card
            item(span = { GridItemSpan(2) }) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = tl.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        val caActive = cell.activeCarriers.size > 1 || cell.caIndicated
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Layers,
                                    contentDescription = null,
                                    tint = tl.emerald,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Carrier Aggregation (CA)",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = tl.textPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            // Status Badge
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(
                                        if (caActive) tl.emerald.copy(alpha = 0.2f)
                                        else tl.textMuted.copy(alpha = 0.2f)
                                    )
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = when {
                                        cell.activeCarriers.size > 1 -> "${cell.activeCarriers.size}CC ACTIVE"
                                        caActive -> "CA ACTIVE"
                                        else -> "STANDBY"
                                    },
                                    color = if (caActive) tl.emerald else tl.textSecondary,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = if (caActive) "Aggregating multiple frequency channels to increase bandwidth."
                            else "Single carrier link. Secondary component carriers inactive or standby.",
                            style = MaterialTheme.typography.bodySmall,
                            color = tl.textSecondary
                        )

                        // Carrier Aggregation Audit Status
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (caActive) tl.emerald.copy(alpha = 0.08f) else tl.sky.copy(alpha = 0.08f))
                                .border(
                                    width = 1.dp,
                                    color = if (caActive) tl.emerald.copy(alpha = 0.3f) else tl.sky.copy(alpha = 0.3f),
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .padding(12.dp)
                        ) {
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(if (caActive) tl.emerald else tl.sky)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (caActive) "AUDIT RATING: OPTIMAL (MULTI-CHANNEL)" else "AUDIT RATING: STANDBY (EFFICIENT)",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (caActive) tl.emerald else tl.sky,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = if (caActive) {
                                        if (cell.activeCarriers.size > 1) {
                                            "Device is actively aggregating ${cell.activeCarriers.size} frequency bands. This unlocks wider aggregate bandwidth for enhanced gigabit-range throughput and signal redundancy."
                                        } else {
                                            "The network reports carrier aggregation is active, but this device does not expose per-carrier details to apps."
                                        }
                                    } else {
                                        "Using a single carrier channel. Secondary bands are asleep. Cellular base stations transition secondary channels to standby automatically when inactive to extend battery life, activating them on-demand."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = tl.textSecondary
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        cell.activeCarriers.forEachIndexed { index, carrier ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Component Carrier Badge
                                Box(
                                    modifier = Modifier
                                        .width(48.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(
                                            if (carrier.type == "PCC") tl.emerald.copy(alpha = 0.15f)
                                            else tl.sky.copy(alpha = 0.15f)
                                        )
                                        .padding(vertical = 4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = carrier.type,
                                        color = if (carrier.type == "PCC") tl.emerald else tl.sky,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                // Band Details Column
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = carrier.band,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = tl.textPrimary,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "ARFCN: ${carrier.arfcn}" +
                                                if (carrier.bandwidthKhz > 0) " • ${carrier.bandwidthKhz / 1000} MHz" else "",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = tl.textMuted
                                    )
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                // Signal Strength Info & Bar
                                Column(
                                    horizontalAlignment = Alignment.End,
                                    modifier = Modifier.width(90.dp)
                                ) {
                                    Text(
                                        text = "${carrier.rsrp} dBm",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = signalColorForRsrp(carrier.rsrp),
                                        fontWeight = FontWeight.Bold
                                    )

                                    Spacer(modifier = Modifier.height(4.dp))

                                    // Visual signal bar for this carrier
                                    val percent = ((carrier.rsrp + 140f) / 90f).coerceIn(0f, 1f)
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(4.dp)
                                            .clip(CircleShape)
                                            .background(tl.surfaceVariant)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxHeight()
                                                .fillMaxWidth(percent)
                                                .clip(CircleShape)
                                                .background(signalColorForRsrp(carrier.rsrp))
                                        )
                                    }
                                }
                            }

                            if (index < cell.activeCarriers.size - 1) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(vertical = 8.dp),
                                    color = tl.surfaceVariant,
                                    thickness = 1.dp
                                )
                            }
                        }

                        // Connection capacity: aggregate bandwidth + theoretical DL ceiling
                        val capacity = remember(cell.activeCarriers) {
                            CapacityEstimator.estimate(cell.activeCarriers)
                        }
                        if (capacity.totalMhz > 0.0) {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 10.dp),
                                color = tl.surfaceVariant,
                                thickness = 1.dp
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text(
                                        text = "Aggregate Bandwidth",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = tl.textMuted
                                    )
                                    Text(
                                        text = String.format(Locale.US, "%.0f MHz", capacity.totalMhz) +
                                                if (capacity.unknownBwCarriers > 0) "+" else "",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = tl.textPrimary,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        text = "Est. DL Ceiling",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = tl.textMuted
                                    )
                                    Text(
                                        text = "~${capacity.ceilingMbps} Mbps" +
                                                if (capacity.unknownBwCarriers > 0) "+" else "",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = tl.emerald,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        // One-tap throughput test recorded against the current radio config
                        val speedContext = LocalContext.current
                        val speedScope = rememberCoroutineScope()
                        var isSpeedTesting by remember { mutableStateOf(false) }

                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = {
                                if (!isSpeedTesting) {
                                    isSpeedTesting = true
                                    val label = if (cell.activeCarriers.size > 1) {
                                        "${cell.activeCarriers.size}CC " +
                                                cell.activeCarriers.joinToString("+") { it.band.substringBefore(" ") }
                                    } else {
                                        cell.bandName.substringBefore(" ")
                                    }
                                    speedScope.launch {
                                        try {
                                            val result = SpeedTester.run(label)
                                            onSpeedTestCompleted(result)
                                        } catch (e: Exception) {
                                            android.widget.Toast.makeText(
                                                speedContext,
                                                "Speed test failed: ${e.message}",
                                                android.widget.Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                        isSpeedTesting = false
                                    }
                                }
                            },
                            enabled = !isSpeedTesting,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = tl.emerald,
                                contentColor = tl.onAccent
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = if (isSpeedTesting) "Testing…" else "Run Speed Test (uses ~100+ MB data)",
                                fontWeight = FontWeight.Bold
                            )
                        }

                        if (speedTests.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            val timeFmt = remember { SimpleDateFormat("MMM d h:mm a", Locale.US) }
                            speedTests.take(3).forEach { result ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "${timeFmt.format(Date(result.timestamp))} • ${result.label}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = tl.textMuted,
                                        maxLines = 1,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = String.format(Locale.US, "%.0f Mbps • %d ms", result.downloadMbps, result.latencyMs),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = tl.sky,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Connection Sparkline & History Trend Chart
            item(span = { GridItemSpan(2) }) {
                InteractiveSignalTrendWidget(
                    cell = cell,
                    rsrpHistory = rsrpHistory,
                    logs = logs
                )
            }

            // Live Compass Card
            item(span = { GridItemSpan(2) }) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = tl.surface)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Cell Tower Compass",
                                style = MaterialTheme.typography.titleMedium,
                                color = tl.textPrimary,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Pointing to estimated tower coordinate",
                                style = MaterialTheme.typography.bodySmall,
                                color = tl.textSecondary
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Bearing: ${String.format(Locale.US, "%.1f°", bearing)}",
                                style = MaterialTheme.typography.titleSmall,
                                color = tl.sky,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (distance != null) {
                                    val feet = distance * 3.28084
                                    if (feet < 1000) {
                                        String.format(Locale.US, "Range: %.0f ft away", feet)
                                    } else {
                                        String.format(Locale.US, "Range: %.2f mi away", feet / 5280.0)
                                    }
                                } else "Calculating bearing...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = tl.textPrimary
                            )
                        }

                        // Compass Icon Graphic
                        Box(
                            modifier = Modifier
                                .size(90.dp)
                                .clip(CircleShape)
                                .background(tl.background),
                            contentAlignment = Alignment.Center
                        ) {
                            CompassGraphic(bearing = bearing, deviceHeading = deviceHeading)
                        }
                    }
                }
            }

            // AI Signal Security Auditor Card
            item(span = { GridItemSpan(2) }) {
                AiAuditorCard(
                    cell = cell,
                    distance = distance?.toDouble(),
                    address = resolvedAddress,
                    confidenceMeters = confidenceMeters,
                    isSuspect = isSuspect
                )
            }
        }

        // Snapshot Trigger Button
        Button(
            onClick = onSnapshotClick,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = tl.emerald,
                contentColor = tl.onAccent
            ),
            shape = RoundedCornerShape(10.dp)
        ) {
            Icon(imageVector = Icons.Default.Camera, contentDescription = "Snapshot")
            Spacer(modifier = Modifier.width(8.dp))
            Text("Save State Snapshot")
        }
    }

    // Metric Info Dialog
    if (activeMetricInfo != null) {
        AlertDialog(
            onDismissRequest = { activeMetricInfo = null },
            containerColor = tl.surface,
            titleContentColor = tl.textPrimary,
            textContentColor = tl.textSecondary,
            confirmButton = {
                TextButton(onClick = { activeMetricInfo = null }) {
                    Text("Got It", color = tl.emerald)
                }
            },
            title = { Text(text = activeMetricInfo!!.title) },
            text = { Text(text = activeMetricInfo!!.desc) }
        )
    }
}

@Composable
fun MetricCard(
    title: String,
    value: String,
    sub: String,
    color: Color,
    icon: ImageVector,
    onClick: () -> Unit
) {
    val tl = TlTheme.colors
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = tl.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = tl.textSecondary,
                    fontWeight = FontWeight.Bold
                )
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                color = color,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = sub,
                style = MaterialTheme.typography.bodySmall,
                color = tl.textMuted,
                maxLines = 1
            )
        }
    }
}

@Composable
fun RsfphistorySparkline(history: List<Int>) {
    val lineColor = TlTheme.colors.emerald
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
    ) {
        if (history.size < 2) return@Canvas
        val maxVal = -50f
        val minVal = -120f
        val range = maxVal - minVal

        val points = history.takeLast(30)
        val stepX = size.width / (points.size - 1)
        val path = Path()

        points.forEachIndexed { index, valDbm ->
            val x = index * stepX
            val ratio = (valDbm.toFloat() - minVal) / range
            val y = size.height - (ratio * size.height)
            if (index == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }

        // Draw line
        drawPath(
            path = path,
            color = lineColor,
            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
        )

        // Draw Gradient Filling underneath
        val fillPath = Path().apply {
            addPath(path)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(
                    lineColor.copy(alpha = 0.3f),
                    Color.Transparent
                )
            )
        )
    }
}

@Composable
fun CompassGraphic(bearing: Float, deviceHeading: Float) {
    val ringColor = TlTheme.colors.surfaceVariant
    val cardinalColor = TlTheme.colors.textPrimary.toArgb()
    val arrowColor = TlTheme.colors.sky
    Canvas(modifier = Modifier.fillMaxSize()) {
        val radius = size.minDimension / 2f
        val center = Offset(size.width / 2f, size.height / 2f)

        // Outer Ring
        drawCircle(
            color = ringColor,
            radius = radius - 4.dp.toPx(),
            style = Stroke(width = 2.dp.toPx())
        )

        // Cardinal Letters (North oriented relative to device)
        val textPaint = Paint().asFrameworkPaint().apply {
            color = cardinalColor
            textSize = 10.dp.toPx()
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        val directions = listOf("N" to 0f, "E" to 90f, "S" to 180f, "W" to 270f)
        directions.forEach { (text, angle) ->
            val rotatedAngle = Math.toRadians((angle - deviceHeading).toDouble())
            val x = center.x + (radius - 12.dp.toPx()) * sin(rotatedAngle).toFloat()
            val y = center.y - (radius - 12.dp.toPx()) * cos(rotatedAngle).toFloat()
            drawContext.canvas.nativeCanvas.drawText(text, x, y + 4.dp.toPx(), textPaint)
        }

        // Target Tower Pointer Arrow
        val arrowAngle = Math.toRadians((bearing - deviceHeading).toDouble())
        val arrowLength = radius - 18.dp.toPx()
        val arrowTip = Offset(
            center.x + arrowLength * sin(arrowAngle).toFloat(),
            center.y - arrowLength * cos(arrowAngle).toFloat()
        )

        // Side wings of arrow
        val leftWingAngle = arrowAngle - Math.toRadians(150.0)
        val rightWingAngle = arrowAngle + Math.toRadians(150.0)
        val wingLength = 12.dp.toPx()

        val leftWing = Offset(
            arrowTip.x + wingLength * sin(leftWingAngle).toFloat(),
            arrowTip.y - wingLength * cos(leftWingAngle).toFloat()
        )
        val rightWing = Offset(
            arrowTip.x + wingLength * sin(rightWingAngle).toFloat(),
            arrowTip.y - wingLength * cos(rightWingAngle).toFloat()
        )

        // Draw connecting line to tower
        drawLine(
            color = arrowColor,
            start = center,
            end = arrowTip,
            strokeWidth = 3.dp.toPx()
        )

        val arrowPath = Path().apply {
            moveTo(arrowTip.x, arrowTip.y)
            lineTo(leftWing.x, leftWing.y)
            lineTo(rightWing.x, rightWing.y)
            close()
        }

        drawPath(
            path = arrowPath,
            color = arrowColor
        )
    }
}

@Composable
fun AcquiringSignalState() {
    val tl = TlTheme.colors
    val infiniteTransition = rememberInfiniteTransition(label = "acquiringSignal")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "acquiringSignalAlpha"
    )

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.SignalCellularAlt,
            contentDescription = null,
            tint = tl.emerald.copy(alpha = pulseAlpha),
            modifier = Modifier.size(64.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Acquiring Signal...",
            style = MaterialTheme.typography.titleMedium,
            color = tl.textPrimary,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Waiting for the device to report a serving cell tower.",
            style = MaterialTheme.typography.bodyMedium,
            color = tl.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp)
        )
    }
}

data class MetricInfo(val title: String, val desc: String)

@Composable
fun ProviderLogo(operatorName: String?, modifier: Modifier = Modifier) {
    val name = operatorName?.lowercase(Locale.ROOT) ?: "unknown"
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(TlTheme.colors.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        // Brand marks below intentionally keep their real-world carrier colors in
        // both light and dark themes; only the fallback chip follows the theme.
        when {
            name.contains("t-mobile") || name.contains("t-mob") || name.contains("telekom") || name.contains("magenta") -> {
                // T-Mobile Logo: Elegant Magenta Circle with bold white 'T' flanked by dots
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawCircle(color = Color(0xFFE20074)) // T-Mobile Magenta
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "· T ·",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
            name.contains("verizon") || name.contains("vzw") -> {
                // Verizon Logo: Black background with red checkmark next to bold white V or check mark
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawCircle(color = Color(0xFF000000)) // Pure Black
                }
                Box(contentAlignment = Alignment.Center) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "V",
                            color = Color.White,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 16.sp
                        )
                        Text(
                            text = "✓",
                            color = Color(0xFFCD040B), // Verizon Red
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }
                }
            }
            name.contains("at&t") || name.contains("att") -> {
                // AT&T Logo: Globe with white latitudinal stripes
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val r = size.minDimension / 2f
                    drawCircle(color = Color(0xFF00A6FF)) // AT&T Blue

                    // Draw 3D globe latitude stripes
                    val stripeColor = Color.White.copy(alpha = 0.85f)
                    val strokeW = 2.dp.toPx()

                    drawArc(
                        color = stripeColor,
                        startAngle = 180f,
                        sweepAngle = 180f,
                        useCenter = false,
                        topLeft = Offset(r * 0.2f, r * 0.4f),
                        size = size * 0.8f,
                        style = Stroke(width = strokeW)
                    )

                    drawArc(
                        color = stripeColor,
                        startAngle = 0f,
                        sweepAngle = 180f,
                        useCenter = false,
                        topLeft = Offset(r * 0.2f, r * 0.2f),
                        size = size * 0.8f,
                        style = Stroke(width = strokeW)
                    )

                    drawLine(
                        color = stripeColor,
                        start = Offset(r * 0.1f, r),
                        end = Offset(r * 1.9f, r),
                        strokeWidth = strokeW + 1f
                    )
                }
            }
            name.contains("vodafone") -> {
                // Vodafone Logo: Red Circle with White Speechmark
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawCircle(color = Color(0xFFE60000)) // Vodafone Red

                    // Draw speechmark icon
                    val r = size.minDimension / 2f
                    drawCircle(
                        color = Color.White,
                        radius = r * 0.35f,
                        center = Offset(r, r * 0.9f)
                    )
                }
                // Overlaid white comma/speechmark detail
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "’",
                        color = Color(0xFFE60000),
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 28.sp,
                        modifier = Modifier.offset(y = (-4).dp)
                    )
                }
            }
            name.contains("orange") -> {
                // Orange Logo: Square Orange with White "O"
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawRect(color = Color(0xFFFF6600)) // Orange
                }
                Text(
                    text = "O",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            }
            else -> {
                // Generic beautiful carrier chip logo
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(Color(0xFF34D399), Color(0xFF059669)),
                            center = Offset(size.width / 2f, size.height / 2f)
                        )
                    )
                }
                Icon(
                    imageVector = Icons.Default.CellTower,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InteractiveSignalTrendWidget(
    cell: CellModel,
    rsrpHistory: List<Int>,
    logs: List<CellLog>
) {
    val tl = TlTheme.colors
    var selectedMetric by remember { mutableStateOf("RSRP") } // "RSRP" or "SINR"
    var selectedSource by remember { mutableStateOf("LIVE") } // "LIVE" or "HISTORY"

    // Automatically coerce source to "HISTORY" if metric is "SINR" since live only has RSRP
    LaunchedEffect(selectedMetric) {
        if (selectedMetric == "SINR") {
            selectedSource = "HISTORY"
        }
    }

    // Filter and prepare chart points
    val points: List<ChartPoint> = remember(selectedSource, selectedMetric, rsrpHistory, logs, cell.cellId, cell.bandName, cell.tech) {
        val rawPoints = if (selectedSource == "LIVE") {
            rsrpHistory.mapIndexed { idx, rsrpVal ->
                ChartPoint(
                    value = rsrpVal.toFloat(),
                    timestamp = System.currentTimeMillis() - (rsrpHistory.size - 1 - idx) * 1000L,
                    label = "Live RSRP",
                    band = cell.bandName,
                    tech = cell.tech
                )
            }
        } else {
            val cellSpecific = logs.filter { it.cellId == cell.cellId }
            val sourceLogs = if (cellSpecific.isNotEmpty()) cellSpecific else logs
            sourceLogs.map { log ->
                ChartPoint(
                    value = if (selectedMetric == "RSRP") log.rsrp.toFloat() else log.sinr.toFloat(),
                    timestamp = log.timestamp,
                    label = if (selectedMetric == "RSRP") "RSRP History" else "SINR History",
                    band = log.band,
                    tech = log.tech
                )
            }.sortedBy { it.timestamp }
        }
        rawPoints
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = tl.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Timeline,
                        contentDescription = null,
                        tint = if (selectedMetric == "RSRP") tl.emerald else tl.sky,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Historical Signal Trends",
                        style = MaterialTheme.typography.titleMedium,
                        color = tl.textPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Small badge displaying active carriers size
                if (cell.activeCarriers.size > 1 || cell.caIndicated) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(tl.emerald.copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = if (cell.activeCarriers.size > 1) "${cell.activeCarriers.size}CC Aggregated" else "CA Active",
                            color = tl.emerald,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Control Chips Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Metric Selector (RSRP / SINR)
                FilterChip(
                    selected = selectedMetric == "RSRP",
                    onClick = { selectedMetric = "RSRP" },
                    label = { Text("RSRP (Power)", fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = tl.emerald.copy(alpha = 0.15f),
                        selectedLabelColor = tl.emerald,
                        selectedLeadingIconColor = tl.emerald,
                        containerColor = tl.surfaceVariant,
                        labelColor = tl.textSecondary
                    ),
                    border = null,
                    modifier = Modifier.height(32.dp)
                )

                FilterChip(
                    selected = selectedMetric == "SINR",
                    onClick = { selectedMetric = "SINR" },
                    label = { Text("SINR (Quality)", fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = tl.sky.copy(alpha = 0.15f),
                        selectedLabelColor = tl.sky,
                        selectedLeadingIconColor = tl.sky,
                        containerColor = tl.surfaceVariant,
                        labelColor = tl.textSecondary
                    ),
                    border = null,
                    modifier = Modifier.height(32.dp)
                )

                Spacer(modifier = Modifier.weight(1f))

                // Source Selector (Live / History)
                FilterChip(
                    selected = selectedSource == "LIVE",
                    enabled = selectedMetric == "RSRP", // Live only records RSRP
                    onClick = { selectedSource = "LIVE" },
                    label = { Text("Live", fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = tl.textPrimary.copy(alpha = 0.15f),
                        selectedLabelColor = tl.textPrimary,
                        containerColor = tl.surfaceVariant,
                        labelColor = tl.textMuted
                    ),
                    border = null,
                    modifier = Modifier.height(32.dp)
                )

                FilterChip(
                    selected = selectedSource == "HISTORY",
                    onClick = { selectedSource = "HISTORY" },
                    label = { Text("Database", fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = tl.textPrimary.copy(alpha = 0.15f),
                        selectedLabelColor = tl.textPrimary,
                        containerColor = tl.surfaceVariant,
                        labelColor = tl.textMuted
                    ),
                    border = null,
                    modifier = Modifier.height(32.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Chart Container & Canvas
            var canvasWidth by remember { mutableStateOf(0f) }
            var hoverX by remember { mutableStateOf<Float?>(null) }
            var hoveredIndex by remember { mutableStateOf<Int?>(null) }

            val textMeasurer = rememberTextMeasurer()

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .background(tl.background.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                    .padding(4.dp)
            ) {
                if (points.size < 2) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (selectedSource == "LIVE") "Collecting live telemetry..." else "No historical logs saved for this cell tower",
                            color = tl.textMuted,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    val lineColor = if (selectedMetric == "RSRP") tl.emerald else tl.sky

                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .onSizeChanged { size ->
                                canvasWidth = size.width.toFloat()
                            }
                            .pointerInput(points) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull()
                                        if (change != null && change.pressed) {
                                            val pos = change.position
                                            val lX = 8.dp.toPx()
                                            val rX = size.width - 44.dp.toPx()
                                            if (pos.x in lX..rX) {
                                                hoverX = pos.x
                                                // Find closest index
                                                val drawWidth = rX - lX
                                                val pct = ((pos.x - lX) / drawWidth).coerceIn(0f, 1f)
                                                val idx = (pct * (points.size - 1)).roundToInt()
                                                hoveredIndex = idx.coerceIn(0, points.size - 1)
                                            }
                                        } else {
                                            hoverX = null
                                            hoveredIndex = null
                                        }
                                    }
                                }
                            }
                    ) {
                        val w = size.width
                        val h = size.height

                        val lX = 8.dp.toPx()
                        val rX = w - 44.dp.toPx()
                        val tY = 12.dp.toPx()
                        val bY = h - 18.dp.toPx()

                        val drawWidth = rX - lX
                        val drawHeight = bY - tY

                        val maxVal = if (selectedMetric == "RSRP") -50f else 30f
                        val minVal = if (selectedMetric == "RSRP") -120f else -10f
                        val range = maxVal - minVal

                        // 1. Draw dashed grid lines and Y axis reference labels
                        val references = if (selectedMetric == "RSRP") {
                            listOf(
                                -70 to "Excel.",
                                -90 to "Good",
                                -105 to "Fair",
                                -120 to "Poor"
                            )
                        } else {
                            listOf(
                                20 to "Excel.",
                                10 to "Good",
                                2 to "Fair",
                                -5 to "Poor"
                            )
                        }

                        references.forEach { (refVal, refLabel) ->
                            val ratio = (refVal.toFloat() - minVal) / range
                            val y = bY - ratio * drawHeight

                            // Draw reference grid line
                            drawLine(
                                color = tl.outline.copy(alpha = 0.25f),
                                start = Offset(lX, y),
                                end = Offset(rX, y),
                                strokeWidth = 1.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                            )

                            // Draw reference text label
                            val textLayout = textMeasurer.measure(
                                text = "$refVal",
                                style = TextStyle(color = tl.textMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            )
                            drawText(
                                textLayoutResult = textLayout,
                                topLeft = Offset(rX + 6.dp.toPx(), y - textLayout.size.height / 2f)
                            )
                        }

                        // Map points to layout coords
                        val coords = points.mapIndexed { idx, pt ->
                            val x = lX + (idx.toFloat() / (points.size - 1)) * drawWidth
                            val ratio = (pt.value - minVal) / range
                            val y = bY - ratio * drawHeight
                            Offset(x, y)
                        }

                        // 2. Draw curve line using cubic curves
                        val path = Path()
                        if (coords.isNotEmpty()) {
                            path.moveTo(coords[0].x, coords[0].y)
                            for (i in 1 until coords.size) {
                                val prev = coords[i - 1]
                                val curr = coords[i]
                                val cp1X = prev.x + (curr.x - prev.x) / 2f
                                val cp1Y = prev.y
                                val cp2X = prev.x + (curr.x - prev.x) / 2f
                                val cp2Y = curr.y
                                path.cubicTo(cp1X, cp1Y, cp2X, cp2Y, curr.x, curr.y)
                            }
                        }

                        // Outer glowing line path
                        drawPath(
                            path = path,
                            color = lineColor.copy(alpha = 0.25f),
                            style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                        )

                        // Main sharp line path
                        drawPath(
                            path = path,
                            color = lineColor,
                            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                        )

                        // 3. Draw gradient background fill
                        if (coords.isNotEmpty()) {
                            val fillPath = Path().apply {
                                addPath(path)
                                lineTo(coords.last().x, bY)
                                lineTo(coords.first().x, bY)
                                close()
                            }
                            drawPath(
                                path = fillPath,
                                brush = Brush.verticalGradient(
                                    colors = listOf(
                                        lineColor.copy(alpha = 0.22f),
                                        Color.Transparent
                                    ),
                                    startY = tY,
                                    endY = bY
                                )
                            )
                        }

                        // 4. Draw interactive scrubber vertical line and hovered point
                        val activeIdx = hoveredIndex
                        if (activeIdx != null && activeIdx < coords.size) {
                            val activePt = coords[activeIdx]

                            // Draw vertical line
                            drawLine(
                                color = tl.textSecondary.copy(alpha = 0.4f),
                                start = Offset(activePt.x, tY),
                                end = Offset(activePt.x, bY),
                                strokeWidth = 1.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
                            )

                            // Draw glowing dot
                            drawCircle(
                                color = lineColor.copy(alpha = 0.3f),
                                radius = 8.dp.toPx(),
                                center = activePt
                            )
                            drawCircle(
                                color = lineColor,
                                radius = 4.dp.toPx(),
                                center = activePt
                            )
                            drawCircle(
                                color = tl.surface,
                                radius = 2.dp.toPx(),
                                center = activePt
                            )
                        } else {
                            // Pulsing dot on the latest point
                            val latestPt = coords.lastOrNull()
                            if (latestPt != null) {
                                drawCircle(
                                    color = lineColor.copy(alpha = 0.35f),
                                    radius = 6.dp.toPx(),
                                    center = latestPt
                                )
                                drawCircle(
                                    color = lineColor,
                                    radius = 3.5.dp.toPx(),
                                    center = latestPt
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 5. Telemetry details panel
            val activeIdx = hoveredIndex
            if (activeIdx != null && activeIdx < points.size) {
                val pt = points[activeIdx]
                val sdf = remember { SimpleDateFormat("hh:mm:ss a", Locale.US) }
                val formattedTime = remember(pt.timestamp) { sdf.format(Date(pt.timestamp)) }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(tl.surfaceVariant.copy(alpha = 0.5f))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (selectedMetric == "RSRP") signalColorForRsrp(pt.value.toInt()) else tl.sky)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (selectedMetric == "RSRP") "${pt.value.toInt()} dBm" else "${String.format(Locale.US, "%.1f", pt.value)} dB",
                                style = MaterialTheme.typography.titleMedium,
                                color = tl.textPrimary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = "Time: $formattedTime",
                            style = MaterialTheme.typography.labelSmall,
                            color = tl.textMuted
                        )
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = if (pt.band.isBlank()) "Unknown Band" else pt.band,
                            style = MaterialTheme.typography.bodySmall,
                            color = tl.textPrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (pt.tech.contains("5G")) tl.emerald.copy(alpha = 0.15f) else tl.sky.copy(alpha = 0.15f))
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = pt.tech,
                                color = if (pt.tech.contains("5G")) tl.emerald else tl.sky,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                fontSize = 9.sp
                            )
                        }
                    }
                }
            } else {
                // Default Stats view: Min/Max/Average of the displayed series
                val values = remember(points) { points.map { it.value } }
                val peak = remember(values) { values.maxOrNull() }
                val trough = remember(values) { values.minOrNull() }
                val average = remember(values) { if (values.isNotEmpty()) values.average() else null }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Touch and drag to scrub timeline",
                        style = MaterialTheme.typography.bodySmall,
                        color = tl.textSecondary
                    )

                    if (peak != null && trough != null && average != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            StatMiniLabel(
                                label = "PEAK",
                                value = if (selectedMetric == "RSRP") "${peak.toInt()}dBm" else "${String.format(Locale.US, "%.0f", peak)}dB",
                                color = if (selectedMetric == "RSRP") tl.emerald else tl.sky
                            )
                            StatMiniLabel(
                                label = "TROUGH",
                                value = if (selectedMetric == "RSRP") "${trough.toInt()}dBm" else "${String.format(Locale.US, "%.0f", trough)}dB",
                                color = tl.red
                            )
                            StatMiniLabel(
                                label = "AVG",
                                value = if (selectedMetric == "RSRP") "${average.toInt()}dBm" else "${String.format(Locale.US, "%.0f", average)}dB",
                                color = tl.textPrimary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StatMiniLabel(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TlTheme.colors.textMuted,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = color,
            fontWeight = FontWeight.Bold
        )
    }
}

data class ChartPoint(
    val value: Float,
    val timestamp: Long,
    val label: String,
    val band: String,
    val tech: String
)

@Composable
fun AiAuditorCard(
    cell: CellModel,
    distance: Double?,
    address: String,
    confidenceMeters: Int,
    isSuspect: Boolean
) {
    val tl = TlTheme.colors
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("TowerLockPrefs", Context.MODE_PRIVATE) }
    
    // Key state variables
    var auditResult by remember { mutableStateOf<String?>(null) }
    var isAuditing by remember { mutableStateOf(false) }
    var auditError by remember { mutableStateOf<String?>(null) }
    var isExpanded by remember { mutableStateOf(true) }
    val coroutineScope = rememberCoroutineScope()

    // Read the configured Gemini API key (entered in settings or from build config)
    val savedGeminiKey = remember(prefs) { prefs.getString("gemini_api_key", "") ?: "" }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = tl.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Bolt,
                        contentDescription = "AI Auditor",
                        tint = tl.amber,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "AI Security & Signal Auditor",
                            style = MaterialTheme.typography.titleMedium,
                            color = tl.textPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Powered by Gemini 3.1 Flash-Lite",
                            style = MaterialTheme.typography.labelSmall,
                            color = tl.textSecondary
                        )
                    }
                }
                IconButton(onClick = { isExpanded = !isExpanded }) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (isExpanded) "Collapse" else "Expand",
                        tint = tl.textMuted
                    )
                }
            }

            if (isExpanded) {
                Spacer(modifier = Modifier.height(12.dp))

                if (isAuditing) {
                    // Pulsing/Loading state
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(
                            color = tl.emerald,
                            modifier = Modifier.size(36.dp),
                            strokeWidth = 3.dp
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Analyzing cellular tower telemetry...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = tl.emerald,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Performing risk assessments and RF path audit",
                            style = MaterialTheme.typography.labelSmall,
                            color = tl.textMuted
                        )
                    }
                } else if (auditResult != null) {
                    // Render the result
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(tl.background)
                                .padding(12.dp)
                        ) {
                            MarkdownText(text = auditResult!!)
                        }

                        // Re-run button
                        Button(
                            onClick = {
                                isAuditing = true
                                auditError = null
                                coroutineScope.launch {
                                    val result = com.example.gemini.GeminiService.generateSignalAudit(
                                        cell = cell,
                                        distanceMeters = distance,
                                        address = address,
                                        confidenceMeters = confidenceMeters,
                                        isSuspect = isSuspect,
                                        customApiKeyOverride = savedGeminiKey
                                    )
                                    isAuditing = false
                                    if (result == "API_KEY_MISSING") {
                                        auditError = "API key not found. Please add GEMINI_API_KEY to your Secrets panel or set it in the Settings screen."
                                        auditResult = null
                                    } else if (result.startsWith("API_ERROR:") || result.startsWith("CONNECTION_ERROR:")) {
                                        auditError = result
                                    } else {
                                        auditResult = result
                                    }
                                }
                            },
                            modifier = Modifier.align(Alignment.End),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = tl.emerald.copy(alpha = 0.2f),
                                contentColor = tl.emerald
                            ),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Refresh, contentDescription = "Retry", modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Recalculate Audit", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                } else {
                    // Default state / error state
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (auditError != null) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(tl.red.copy(alpha = 0.12f))
                                    .border(1.dp, tl.red.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                    .padding(12.dp)
                            ) {
                                Row(verticalAlignment = Alignment.Top) {
                                    Icon(
                                        imageVector = Icons.Default.Warning,
                                        contentDescription = "Error",
                                        tint = tl.red,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            text = "Auditor Setup Required",
                                            style = MaterialTheme.typography.titleSmall,
                                            color = tl.red,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = auditError!!,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = tl.textPrimary
                                        )
                                    }
                                }
                            }
                        } else {
                            Text(
                                text = "Run a live security and signal quality audit of your serving base station parameters. Detect IMSI catchers, RF anomalies, and optimize cell channel bonding.",
                                style = MaterialTheme.typography.bodySmall,
                                color = tl.textSecondary
                            )
                        }

                        Button(
                            onClick = {
                                isAuditing = true
                                auditError = null
                                coroutineScope.launch {
                                    val result = com.example.gemini.GeminiService.generateSignalAudit(
                                        cell = cell,
                                        distanceMeters = distance,
                                        address = address,
                                        confidenceMeters = confidenceMeters,
                                        isSuspect = isSuspect,
                                        customApiKeyOverride = savedGeminiKey
                                    )
                                    isAuditing = false
                                    if (result == "API_KEY_MISSING") {
                                        auditError = "Gemini API key is not configured. Please add GEMINI_API_KEY to your Secrets panel or set it in the Settings screen to enable real-time AI signal auditing."
                                        auditResult = null
                                    } else if (result.startsWith("API_ERROR:") || result.startsWith("CONNECTION_ERROR:")) {
                                        auditError = result
                                    } else {
                                        auditResult = result
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = tl.emerald,
                                contentColor = tl.onAccent
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Bolt, contentDescription = "Run Audit")
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Generate Smart Security Audit")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val tl = TlTheme.colors
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val lines = text.split("\n")
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            when {
                trimmed.startsWith("###") -> {
                    Text(
                        text = trimmed.removePrefix("###").trim(),
                        style = MaterialTheme.typography.titleSmall,
                        color = tl.emerald,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                    )
                }
                trimmed.startsWith("##") -> {
                    Text(
                        text = trimmed.removePrefix("##").trim(),
                        style = MaterialTheme.typography.titleMedium,
                        color = tl.sky,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                    )
                }
                trimmed.startsWith("**") && trimmed.endsWith("**") -> {
                    Text(
                        text = trimmed.removeSurrounding("**").trim(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = tl.textPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                    val bulletContent = trimmed.substring(2).trim()
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 8.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = "•",
                            color = tl.emerald,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(
                            text = parseBoldText(bulletContent),
                            style = MaterialTheme.typography.bodySmall,
                            color = tl.textSecondary
                        )
                    }
                }
                else -> {
                    Text(
                        text = parseBoldText(trimmed),
                        style = MaterialTheme.typography.bodySmall,
                        color = tl.textSecondary
                    )
                }
            }
        }
    }
}

@Composable
fun parseBoldText(text: String): androidx.compose.ui.text.AnnotatedString {
    val parts = text.split("**")
    return androidx.compose.ui.text.buildAnnotatedString {
        parts.forEachIndexed { index, part ->
            if (index % 2 == 1) {
                pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold, color = TlTheme.colors.textPrimary))
                append(part)
                pop()
            } else {
                append(part)
            }
        }
    }
}
