package com.example.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.CellLog
import com.example.data.TowerDbEntry
import com.example.location.LocationTracker
import com.example.ui.theme.TlTheme
import java.util.Locale

/**
 * Browsable tower database: nearby towers by default, full-database search by
 * cell ID or address, filters by radio and by "observed by this device".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TowersScreen(
    logs: List<CellLog>,
    userLat: Double?,
    userLon: Double?,
    towerCount: Int,
    searchTowers: suspend (String) -> List<TowerDbEntry>,
    loadNearby: suspend (Double, Double) -> List<TowerDbEntry>,
    onShowOnMap: (Double, Double) -> Unit
) {
    val tl = TlTheme.colors
    val context = LocalContext.current

    var query by remember { mutableStateOf("") }
    var radioFilter by remember { mutableStateOf("All") } // All / NR / LTE / Observed
    var results by remember { mutableStateOf<List<TowerDbEntry>>(emptyList()) }
    var selectedTower by remember { mutableStateOf<TowerDbEntry?>(null) }

    val gnbBits = remember {
        context.getSharedPreferences("TowerLockPrefs", Context.MODE_PRIVATE).getInt("gnb_bits", 24)
    }
    val observedNodebs = remember(logs) {
        logs.asSequence().map { it.nodebId }.filter { it > 0 }.toSet()
    }

    fun towerNodeb(tower: TowerDbEntry): Long =
        if (tower.radio == "NR") tower.cid shr (36 - gnbBits) else tower.cid shr 8

    // Reload when the query changes (or GPS becomes available for the nearby view)
    LaunchedEffect(query, userLat != null) {
        results = if (query.isBlank()) {
            if (userLat != null && userLon != null) {
                loadNearby(userLat, userLon)
            } else {
                searchTowers("")
            }
        } else {
            searchTowers(query.trim())
        }
    }

    val displayed = remember(results, radioFilter, userLat, userLon, observedNodebs) {
        val filtered = results.filter { tower ->
            when (radioFilter) {
                "NR" -> tower.radio == "NR"
                "LTE" -> tower.radio == "LTE"
                "Observed" -> observedNodebs.contains(towerNodeb(tower))
                else -> true
            }
        }
        if (userLat != null && userLon != null) {
            filtered.sortedBy { LocationTracker.calculateDistance(userLat, userLon, it.lat, it.lon) }
        } else {
            filtered
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(tl.background)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Tower Database",
                style = MaterialTheme.typography.titleMedium,
                color = tl.textPrimary,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "$towerCount known",
                style = MaterialTheme.typography.labelMedium,
                color = tl.textSecondary
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        TextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search cell ID or address") },
            leadingIcon = { Icon(imageVector = Icons.Default.Search, contentDescription = null, tint = tl.textMuted) },
            modifier = Modifier.fillMaxWidth(),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = tl.surface,
                unfocusedContainerColor = tl.surface,
                focusedTextColor = tl.textPrimary,
                unfocusedTextColor = tl.textPrimary,
                focusedIndicatorColor = tl.emerald,
                unfocusedIndicatorColor = tl.outline,
                cursorColor = tl.emerald
            ),
            singleLine = true,
            shape = RoundedCornerShape(8.dp)
        )

        Spacer(modifier = Modifier.height(10.dp))

        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(listOf("All", "NR", "LTE", "Observed")) { filter ->
                FilterChip(
                    selected = radioFilter == filter,
                    onClick = { radioFilter = filter },
                    label = { Text(filter) }
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (displayed.isEmpty()) {
            Text(
                text = if (towerCount == 0) {
                    "No towers yet. Import an OpenCelliD extract in Settings, or drive around with monitoring on to discover them."
                } else {
                    "No towers match this search/filter."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = tl.textSecondary,
                modifier = Modifier.padding(top = 16.dp)
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(displayed) { tower ->
                    val distance = if (userLat != null && userLon != null) {
                        LocationTracker.calculateDistance(userLat, userLon, tower.lat, tower.lon)
                    } else null
                    val isObserved = observedNodebs.contains(towerNodeb(tower))

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedTower = tower },
                        colors = CardDefaults.cardColors(containerColor = tl.surface)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (tower.radio == "NR") tl.emerald.copy(alpha = 0.15f) else tl.sky.copy(alpha = 0.15f))
                                    .padding(horizontal = 6.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = tower.radio,
                                    color = if (tower.radio == "NR") tl.emerald else tl.sky,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Cell ${tower.cid}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = tl.textPrimary,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1
                                )
                                Text(
                                    text = tower.address ?: String.format(Locale.US, "%.5f, %.5f", tower.lat, tower.lon),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = tl.textMuted,
                                    maxLines = 1
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(horizontalAlignment = Alignment.End) {
                                if (distance != null) {
                                    val feet = distance * 3.28084
                                    Text(
                                        text = if (feet < 1000) {
                                            String.format(Locale.US, "%.0f ft", feet)
                                        } else {
                                            String.format(Locale.US, "%.2f mi", feet / 5280.0)
                                        },
                                        style = MaterialTheme.typography.labelMedium,
                                        color = tl.sky,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                if (isObserved) {
                                    Text(
                                        text = "OBSERVED",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = tl.emerald,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Detail sheet with the shared observation section and a map deep link
    selectedTower?.let { tower ->
        ModalBottomSheet(
            onDismissRequest = { selectedTower = null },
            containerColor = tl.surface,
            dragHandle = { BottomSheetDefaults.DragHandle(color = tl.outline) }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                Text(
                    text = "${tower.radio} cell tower",
                    style = MaterialTheme.typography.titleMedium,
                    color = tl.textPrimary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = tower.address ?: "No geocoded address resolved",
                    style = MaterialTheme.typography.bodyLarge,
                    color = tl.textSecondary,
                    modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    DetailItem(label = "Lat/Lon", value = String.format(Locale.US, "%.5f, %.5f", tower.lat, tower.lon))
                    DetailItem(label = "Range Accuracy", value = "±${(tower.range * 3.28084).toInt()} ft")
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    DetailItem(label = "Cell ID (CID/gNB)", value = "${tower.cid}")
                    DetailItem(label = "MCC-MNC", value = "${tower.mcc}-${tower.mnc}")
                }

                TowerObservationSection(tower = tower, logs = logs)

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = {
                        selectedTower = null
                        onShowOnMap(tower.lat, tower.lon)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = tl.emerald,
                        contentColor = tl.onAccent
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(imageVector = Icons.Default.Map, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Show on Map")
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}
