package com.example.ui.screens

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLocation
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.data.CellLog
import com.example.data.TowerDbEntry
import com.example.location.LocationTracker
import com.example.telephony.CellModel
import com.example.ui.theme.TlTheme
import org.osmdroid.config.Configuration
import org.osmdroid.events.DelayedMapListener
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import java.util.Locale
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.Info
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    cell: CellModel,
    logs: List<CellLog> = emptyList(),
    userLat: Double?,
    userLon: Double?,
    towerLat: Double?,
    towerLon: Double?,
    towerAddress: String,
    confidenceMeters: Int,
    towerCount: Int = 0,
    loadTowersInBounds: suspend (minLat: Double, maxLat: Double, minLon: Double, maxLon: Double) -> List<TowerDbEntry> = { _, _, _, _ -> emptyList() },
    focusLat: Double? = null,
    focusLon: Double? = null,
    onFocusConsumed: () -> Unit = {},
    onSaveTower: (lat: Double, lon: Double, address: String) -> Unit
) {
    val tl = TlTheme.colors
    val isDarkTheme = isSystemInDarkTheme()
    val context = LocalContext.current
    var selectedTower by remember { mutableStateOf<TowerDbEntry?>(null) }
    var isBottomSheetOpen by remember { mutableStateOf(false) }

    var isPlacingTower by remember { mutableStateOf(false) }
    var showHeatmap by remember { mutableStateOf(false) }
    var heatmapBand by remember { mutableStateOf<String?>(null) }
    val isPlacingTowerState = rememberUpdatedState(isPlacingTower)

    // Towers are paged in by map viewport instead of loading the whole table:
    // the imported Florida extract can hold tens of thousands of rows.
    var visibleTowers by remember { mutableStateOf<List<TowerDbEntry>>(emptyList()) }
    var viewportBounds by remember { mutableStateOf<BoundingBox?>(null) }
    var currentZoom by remember { mutableStateOf(15.5) }

    LaunchedEffect(viewportBounds) {
        viewportBounds?.let { bb ->
            visibleTowers = loadTowersInBounds(bb.latSouth, bb.latNorth, bb.lonWest, bb.lonEast)
        }
    }
    var pendingTowerPoint by remember { mutableStateOf<GeoPoint?>(null) }
    var pendingAddressInput by remember { mutableStateOf("") }
    var isResolvingPendingAddress by remember { mutableStateOf(false) }

    var mapViewRef by remember { mutableStateOf<MapView?>(null) }

    // Accent colors for map overlays, resolved from the active theme.
    val skyArgb = tl.sky.toArgb()
    val emeraldArgb = tl.emerald.toArgb()
    val pinkArgb = tl.pink.toArgb()
    val pinkFillArgb = tl.pink.copy(alpha = 0.125f).toArgb()

    data class PlotTower(
        val radio: String,
        val mcc: String,
        val mnc: String,
        val area: Int,
        val cid: Long,
        val lat: Double,
        val lon: Double,
        val range: Int,
        val address: String?,
        val isServing: Boolean,
        val distance: Float? = null
    )

    // Compute the list of plotable towers with their calculated distances from
    // the user; the carousel caps at the nearest 25 so a dense imported area
    // doesn't lay out hundreds of cards.
    val plotTowers = remember(cell, userLat, userLon, towerLat, towerLon, towerAddress, confidenceMeters, visibleTowers) {
        val list = mutableListOf<PlotTower>()

        // 1. Add serving tower (estimated or active)
        if (towerLat != null && towerLon != null) {
            val dist = if (userLat != null && userLon != null) {
                LocationTracker.calculateDistance(userLat, userLon, towerLat, towerLon)
            } else null
            list.add(
                PlotTower(
                    radio = if (cell.tech.contains("5G")) "NR" else "LTE",
                    mcc = cell.mcc ?: "310",
                    mnc = cell.mnc ?: "260",
                    area = cell.tac,
                    cid = cell.cellId,
                    lat = towerLat,
                    lon = towerLon,
                    range = confidenceMeters,
                    address = towerAddress,
                    isServing = true,
                    distance = dist
                )
            )
        }

        // 2. Add known database towers currently in the viewport
        visibleTowers.forEach { tower ->
            val exists = list.any { it.cid == tower.cid && it.mcc == tower.mcc && it.mnc == tower.mnc }
            if (!exists) {
                val dist = if (userLat != null && userLon != null) {
                    LocationTracker.calculateDistance(userLat, userLon, tower.lat, tower.lon)
                } else null
                list.add(
                    PlotTower(
                        radio = tower.radio,
                        mcc = tower.mcc,
                        mnc = tower.mnc,
                        area = tower.area,
                        cid = tower.cid,
                        lat = tower.lat,
                        lon = tower.lon,
                        range = tower.range,
                        address = tower.address,
                        isServing = false,
                        distance = dist
                    )
                )
            }
        }

        // Sort by distance if GPS location is available, so the closest is first
        if (userLat != null && userLon != null) {
            list.sortBy { it.distance ?: Float.MAX_VALUE }
        }
        list.take(25)
    }

    // Find the closest connection point
    val closestTower = remember(plotTowers) {
        if (userLat != null && userLon != null) plotTowers.firstOrNull() else null
    }

    LaunchedEffect(Unit) {
        Configuration.getInstance().load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
    }

    // Deep-link focus (e.g. tapping a 5G-drop alert): center the map on the point
    // once the MapView exists, then consume the request so it doesn't replay.
    LaunchedEffect(focusLat, focusLon, mapViewRef) {
        val map = mapViewRef
        if (focusLat != null && focusLon != null && map != null) {
            map.controller.setZoom(16.0)
            map.controller.animateTo(GeoPoint(focusLat, focusLon))
            onFocusConsumed()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(tl.background)
    ) {
        AndroidView(
            factory = { ctx ->
                MapView(ctx).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true)
                    zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)

                    // Default Zoom and position (SF or user location)
                    controller.setZoom(15.5)
                    val startPoint = if (userLat != null && userLon != null) {
                        GeoPoint(userLat, userLon)
                    } else {
                        GeoPoint(37.7749, -122.4194)
                    }
                    controller.setCenter(startPoint)

                    // Page towers in for the visible region, debounced so a fling
                    // doesn't fire a query per frame.
                    addMapListener(DelayedMapListener(object : MapListener {
                        override fun onScroll(event: ScrollEvent?): Boolean {
                            viewportBounds = boundingBox
                            currentZoom = zoomLevelDouble
                            return true
                        }

                        override fun onZoom(event: ZoomEvent?): Boolean {
                            viewportBounds = boundingBox
                            currentZoom = zoomLevelDouble
                            return true
                        }
                    }, 250))
                    addOnFirstLayoutListener { _, _, _, _, _ ->
                        viewportBounds = boundingBox
                        currentZoom = zoomLevelDouble
                    }
                    mapViewRef = this
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { mapView ->
                // Invert map tiles only when the app renders in dark mode; light mode
                // shows the standard OSM cartography.
                if (isDarkTheme) {
                    val darkMatrix = floatArrayOf(
                        -0.8f, 0f, 0f, 0f, 255f, // R
                        0f, -0.8f, 0f, 0f, 255f, // G
                        0f, 0f, -0.8f, 0f, 255f, // B
                        0f, 0f, 0f, 1f, 0f        // A
                    )
                    mapView.overlayManager.tilesOverlay.setColorFilter(ColorMatrixColorFilter(darkMatrix))
                } else {
                    mapView.overlayManager.tilesOverlay.setColorFilter(null)
                }

                mapView.overlays.clear()

                // 0. Tap-to-place overlay for manually recording a tower location.
                // Always registered; only acts while isPlacingTower is on, so it never
                // steals taps meant for markers otherwise.
                val eventsOverlay = MapEventsOverlay(object : MapEventsReceiver {
                    override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                        if (!isPlacingTowerState.value) return false
                        pendingTowerPoint = p
                        isPlacingTower = false
                        return true
                    }

                    override fun longPressHelper(p: GeoPoint): Boolean = false
                })
                mapView.overlays.add(eventsOverlay)

                // 1. Add User Location Marker
                if (userLat != null && userLon != null) {
                    val userPoint = GeoPoint(userLat, userLon)
                    val userMarker = Marker(mapView).apply {
                        position = userPoint
                        title = "Your Position"
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        // Standard red marker or similar
                    }
                    mapView.overlays.add(userMarker)
                }

                // 2. Add Estimated Tower Position Marker
                if (towerLat != null && towerLon != null) {
                    val towerPoint = GeoPoint(towerLat, towerLon)
                    val towerMarker = Marker(mapView).apply {
                        position = towerPoint
                        title = "Estimated Tower"
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        setOnMarkerClickListener { _, _ ->
                            selectedTower = TowerDbEntry(
                                radio = if (cell.tech.contains("5G")) "NR" else "LTE",
                                mcc = cell.mcc ?: "310",
                                mnc = cell.mnc ?: "260",
                                area = cell.tac,
                                cid = cell.cellId,
                                lat = towerLat,
                                lon = towerLon,
                                range = confidenceMeters,
                                address = towerAddress
                            )
                            isBottomSheetOpen = true
                            true
                        }
                    }
                    mapView.overlays.add(towerMarker)

                    // 3. Draw Dotted Polyline user <-> tower
                    if (userLat != null && userLon != null) {
                        val polyline = Polyline().apply {
                            addPoint(GeoPoint(userLat, userLon))
                            addPoint(towerPoint)
                            outlinePaint.color = skyArgb
                            outlinePaint.strokeWidth = 4f
                            outlinePaint.strokeCap = Paint.Cap.ROUND
                            outlinePaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 10f), 0f)
                        }
                        mapView.overlays.add(polyline)
                    }

                    // 3.5. Highlight Closest Connection Point with a distinctive green line
                    if (userLat != null && userLon != null && closestTower != null && !closestTower.isServing) {
                        val closestPoint = GeoPoint(closestTower.lat, closestTower.lon)
                        val polylineClosest = Polyline().apply {
                            addPoint(GeoPoint(userLat, userLon))
                            addPoint(closestPoint)
                            outlinePaint.color = emeraldArgb
                            outlinePaint.strokeWidth = 5f
                            outlinePaint.strokeCap = Paint.Cap.ROUND
                            outlinePaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(15f, 15f), 0f)
                        }
                        mapView.overlays.add(polylineClosest)
                    }

                    // 4. Draw TA Concentric Distance Ring Overlay around Tower
                    if (cell.distanceEstimateMeters > 0) {
                        val circlePoints = ArrayList<GeoPoint>()
                        val radiusMeters = cell.distanceEstimateMeters
                        for (i in 0 until 360 step 5) {
                            val radialPoint = GeoPoint(towerLat, towerLon).destinationPoint(radiusMeters, i.toDouble())
                            circlePoints.add(radialPoint)
                        }
                        val taRing = Polygon().apply {
                            points = circlePoints
                            fillPaint.color = pinkFillArgb
                            outlinePaint.color = pinkArgb
                            outlinePaint.strokeWidth = 2f
                        }
                        mapView.overlays.add(taRing)
                    }
                }

                // 4.5. Signal heatmap: recent logged sample points colored by RSRP,
                // optionally restricted to a single band via the filter chips.
                // Newest logs first (query is timestamp DESC); capped to keep the
                // overlay pass cheap on big histories.
                if (showHeatmap) {
                    val selectedBand = heatmapBand
                    logs.asSequence()
                        .filter { it.lat != 0.0 || it.lon != 0.0 }
                        .filter { selectedBand == null || it.band.startsWith(selectedBand) }
                        .take(400)
                        .forEach { log ->
                            val fill = when {
                                log.rsrp >= -80 -> 0x664CAF50
                                log.rsrp >= -95 -> 0x668BC34A
                                log.rsrp >= -110 -> 0x66FFB74D
                                else -> 0x66E57373
                            }
                            val dot = Polygon().apply {
                                points = Polygon.pointsAsCircle(GeoPoint(log.lat, log.lon), 30.0)
                                fillPaint.color = fill
                                outlinePaint.color = 0x00000000
                                outlinePaint.strokeWidth = 0f
                            }
                            mapView.overlays.add(dot)
                        }
                }

                // 5. Render viewport towers, clustering by a zoom-scaled grid when
                // the imported database would otherwise flood the map with markers.
                fun addTowerMarker(tower: TowerDbEntry) {
                    // Exclude serving cell if already drawn
                    if (tower.lat == towerLat && tower.lon == towerLon) return
                    val loggedTowerMarker = Marker(mapView).apply {
                        position = GeoPoint(tower.lat, tower.lon)
                        title = "${tower.radio} Tower (${tower.cid})"
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        setOnMarkerClickListener { _, _ ->
                            selectedTower = tower
                            isBottomSheetOpen = true
                            true
                        }
                    }
                    mapView.overlays.add(loggedTowerMarker)
                }

                val clusterGridDeg = when {
                    currentZoom >= 13.5 -> 0.0
                    currentZoom >= 12.0 -> 0.02
                    currentZoom >= 10.0 -> 0.08
                    else -> 0.3
                }
                if (clusterGridDeg == 0.0 || visibleTowers.size <= 60) {
                    visibleTowers.forEach { addTowerMarker(it) }
                } else {
                    visibleTowers
                        .groupBy {
                            Pair(
                                (it.lat / clusterGridDeg).toInt(),
                                (it.lon / clusterGridDeg).toInt()
                            )
                        }
                        .forEach { (_, group) ->
                            if (group.size == 1) {
                                addTowerMarker(group.first())
                            } else {
                                val cLat = group.sumOf { it.lat } / group.size
                                val cLon = group.sumOf { it.lon } / group.size
                                val clusterMarker = Marker(mapView).apply {
                                    position = GeoPoint(cLat, cLon)
                                    title = "${group.size} towers — tap to zoom"
                                    icon = clusterIcon(mapView.context, group.size, skyArgb)
                                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                                    setOnMarkerClickListener { marker, mv ->
                                        mv.controller.animateTo(marker.position)
                                        mv.controller.setZoom(mv.zoomLevelDouble + 2.0)
                                        true
                                    }
                                }
                                mapView.overlays.add(clusterMarker)
                            }
                        }
                }

                mapView.invalidate()
            }
        )

        // Floating layer toggle or information banner
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 16.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(tl.surface.copy(alpha = 0.9f))
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                text = when {
                    isPlacingTower -> "Tap the map where the tower actually is"
                    towerCount > 0 -> "${visibleTowers.size} of $towerCount towers in view"
                    towerLat != null -> "Viewing serving tower"
                    else -> "Awaiting cell tower lock..."
                },
                color = tl.textPrimary,
                style = MaterialTheme.typography.labelMedium
            )
        }

        // Band filter chips for the heatmap overlay
        if (showHeatmap) {
            val heatmapBands = remember(logs) {
                logs.map { it.band.substringBefore(" ") }
                    .filter { it.isNotBlank() && it != "Unknown" }
                    .distinct()
                    .sorted()
            }
            LazyRow(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 60.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(horizontal = 16.dp)
            ) {
                item {
                    FilterChip(
                        selected = heatmapBand == null,
                        onClick = { heatmapBand = null },
                        label = { Text("All bands") }
                    )
                }
                items(heatmapBands) { band ->
                    FilterChip(
                        selected = heatmapBand == band,
                        onClick = { heatmapBand = if (heatmapBand == band) null else band },
                        label = { Text(band) }
                    )
                }
            }
        }

        // FAB: toggle the drive-log signal heatmap overlay.
        FloatingActionButton(
            onClick = { showHeatmap = !showHeatmap },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 275.dp, end = 16.dp),
            containerColor = if (showHeatmap) tl.sky else tl.surface,
            contentColor = if (showHeatmap) tl.onAccent else tl.textPrimary
        ) {
            Icon(
                imageVector = Icons.Default.Grain,
                contentDescription = if (showHeatmap) "Hide signal heatmap" else "Show signal heatmap"
            )
        }

        // FAB: toggle manual tower-placement mode. Lets a user record a real tower
        // location (e.g. found on a tower-location site or standing at the site)
        // even when it's not in OpenCelliD or no API key is configured.
        FloatingActionButton(
            onClick = { isPlacingTower = !isPlacingTower },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 205.dp, end = 16.dp),
            containerColor = if (isPlacingTower) tl.red else tl.emerald,
            contentColor = tl.onAccent
        ) {
            Icon(
                imageVector = if (isPlacingTower) Icons.Default.Close else Icons.Default.AddLocation,
                contentDescription = if (isPlacingTower) "Cancel marking tower" else "Mark tower location"
            )
        }

        // Closest Connection & Nearby Cellular Towers Carousel Panel
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = 16.dp)
                .fillMaxWidth()
        ) {
            Text(
                text = if (userLat != null && userLon != null) "NEAREST CELL CONNECTION POINTS" else "KNOWN CELLULAR TOWERS",
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp),
                color = tl.textSecondary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 20.dp, bottom = 8.dp)
            )

            if (plotTowers.isEmpty()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = tl.surface.copy(alpha = 0.95f))
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = tl.textMuted,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "No towers mapped yet. Tap a spot or discover cells to map.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = tl.textSecondary
                        )
                    }
                }
            } else {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(plotTowers) { plotTower ->
                        val isClosest = closestTower != null && plotTower.cid == closestTower.cid && plotTower.mcc == closestTower.mcc && plotTower.mnc == closestTower.mnc

                        Card(
                            modifier = Modifier
                                .width(280.dp)
                                .clickable {
                                    mapViewRef?.let { map ->
                                        map.controller.animateTo(GeoPoint(plotTower.lat, plotTower.lon))
                                        map.controller.setZoom(16.5)
                                    }
                                    selectedTower = TowerDbEntry(
                                        radio = plotTower.radio,
                                        mcc = plotTower.mcc,
                                        mnc = plotTower.mnc,
                                        area = plotTower.area,
                                        cid = plotTower.cid,
                                        lat = plotTower.lat,
                                        lon = plotTower.lon,
                                        range = plotTower.range,
                                        address = plotTower.address
                                    )
                                    isBottomSheetOpen = true
                                },
                            colors = CardDefaults.cardColors(
                                containerColor = when {
                                    plotTower.isServing -> tl.servingContainer.copy(alpha = 0.95f)
                                    isClosest -> tl.nearestContainer.copy(alpha = 0.95f)
                                    else -> tl.surface.copy(alpha = 0.95f)
                                }
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        ProviderLogo(
                                            operatorName = if (plotTower.isServing) cell.operatorName else "Carrier",
                                            modifier = Modifier.size(28.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "${plotTower.radio} Cell ${plotTower.cid}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = tl.textPrimary,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1
                                        )
                                    }

                                    // Distance representation
                                    if (plotTower.distance != null) {
                                        val feet = plotTower.distance * 3.28084
                                        val distString = if (feet < 1000) {
                                            String.format(Locale.US, "%.0f ft", feet)
                                        } else {
                                            String.format(Locale.US, "%.2f mi", feet / 5280.0)
                                        }
                                        Text(
                                            text = distString,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = if (isClosest) tl.emerald else tl.sky,
                                            fontWeight = FontWeight.ExtraBold,
                                            modifier = Modifier.padding(start = 6.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "MCC-MNC: ${plotTower.mcc}-${plotTower.mnc}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = tl.textSecondary
                                        )
                                        Text(
                                            text = plotTower.address ?: "Saved tower location",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = tl.textMuted,
                                            maxLines = 1
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(6.dp))

                                    // Status pill badges
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        if (plotTower.isServing) {
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(tl.sky)
                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = "SERVING",
                                                    color = tl.onAccent,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                        if (isClosest) {
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(tl.emerald)
                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = "NEAREST",
                                                    color = tl.onAccent,
                                                    style = MaterialTheme.typography.labelSmall,
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
            }
        }

        // Confirm dialog once a point has been tapped in placement mode
        pendingTowerPoint?.let { point ->
            LaunchedEffect(point) {
                isResolvingPendingAddress = true
                pendingAddressInput = LocationTracker.reverseGeocode(context, point.latitude, point.longitude)
                isResolvingPendingAddress = false
            }

            AlertDialog(
                onDismissRequest = { pendingTowerPoint = null },
                containerColor = tl.surface,
                titleContentColor = tl.textPrimary,
                textContentColor = tl.textSecondary,
                title = { Text("Save Tower Location") },
                text = {
                    Column {
                        Text(
                            text = String.format(Locale.US, "%.5f, %.5f", point.latitude, point.longitude),
                            style = MaterialTheme.typography.bodyMedium,
                            color = tl.textPrimary
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = pendingAddressInput,
                            onValueChange = { pendingAddressInput = it },
                            label = { Text("Address") },
                            placeholder = { if (isResolvingPendingAddress) Text("Resolving address...") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Saved for cell ${cell.cellId} (${cell.mcc ?: "310"}-${cell.mnc ?: "260"}), so it resolves instantly next time.",
                            style = MaterialTheme.typography.bodySmall,
                            color = tl.textSecondary
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        onSaveTower(point.latitude, point.longitude, pendingAddressInput)
                        pendingTowerPoint = null
                    }) {
                        Text("Save Tower", color = tl.emerald)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingTowerPoint = null }) {
                        Text("Cancel", color = tl.textSecondary)
                    }
                }
            )
        }

        // Bottom Sheet for Marker Details
        if (isBottomSheetOpen && selectedTower != null) {
            ModalBottomSheet(
                onDismissRequest = { isBottomSheetOpen = false },
                containerColor = tl.surface,
                dragHandle = { BottomSheetDefaults.DragHandle(color = tl.outline) }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp)
                ) {
                    Text(
                        text = "${selectedTower!!.radio} cell tower coordinates",
                        style = MaterialTheme.typography.titleMedium,
                        color = tl.textPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = selectedTower!!.address ?: "No geocoded address resolved",
                        style = MaterialTheme.typography.bodyLarge,
                        color = tl.textSecondary,
                        modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
                    )

                    HorizontalDivider(color = tl.surfaceVariant, modifier = Modifier.padding(bottom = 16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        DetailItem(label = "Lat/Lon", value = String.format(Locale.US, "%.5f, %.5f", selectedTower!!.lat, selectedTower!!.lon))
                        DetailItem(label = "Range Accuracy", value = "±${(selectedTower!!.range * 3.28084).toInt()} ft")
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        DetailItem(label = "Cell ID (CID/gNB)", value = "${selectedTower!!.cid}")
                        DetailItem(label = "MCC-MNC", value = "${selectedTower!!.mcc}-${selectedTower!!.mnc}")
                    }

                    // Per-tower history from this device's own observations
                    TowerObservationSection(tower = selectedTower!!, logs = logs)

                    Spacer(modifier = Modifier.height(24.dp))

                    // Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("coords", "${selectedTower!!.lat}, ${selectedTower!!.lon}")
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "Coordinates copied to clipboard!", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = tl.surfaceVariant,
                                contentColor = tl.textPrimary
                            )
                        ) {
                            Icon(imageVector = Icons.Default.ContentCopy, contentDescription = "Copy")
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Copy Coords")
                        }

                        Button(
                            onClick = {
                                // Package visibility rules make resolveActivity unreliable on
                                // API 30+; attempt Google Maps first, then any browser/maps app.
                                val lat = selectedTower!!.lat
                                val lon = selectedTower!!.lon
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$lat,$lon")).apply {
                                        setPackage("com.google.android.apps.maps")
                                    }
                                    context.startActivity(intent)
                                } catch (e: ActivityNotFoundException) {
                                    try {
                                        val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=$lat,$lon"))
                                        context.startActivity(webIntent)
                                    } catch (e2: ActivityNotFoundException) {
                                        Toast.makeText(context, "No app available to open navigation", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = tl.emerald,
                                contentColor = tl.onAccent
                            )
                        ) {
                            Icon(imageVector = Icons.Default.Navigation, contentDescription = "Navigate")
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Navigate")
                        }
                    }
                }
            }
        }
    }
}

/** Circle-with-count bitmap used for cluster markers. */
private fun clusterIcon(
    context: Context,
    count: Int,
    colorArgb: Int
): android.graphics.drawable.BitmapDrawable {
    val size = 96
    val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val circlePaint = Paint().apply {
        color = colorArgb
        isAntiAlias = true
    }
    canvas.drawCircle(size / 2f, size / 2f, size / 2f - 4f, circlePaint)
    val textPaint = Paint().apply {
        color = android.graphics.Color.WHITE
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        textSize = if (count >= 100) 34f else 42f
    }
    val y = size / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
    canvas.drawText(count.toString(), size / 2f, y, textPaint)
    return android.graphics.drawable.BitmapDrawable(context.resources, bitmap)
}

@Composable
fun DetailItem(label: String, value: String) {
    val tl = TlTheme.colors
    Column {
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = tl.textMuted)
        Text(text = value, style = MaterialTheme.typography.bodyMedium, color = tl.textPrimary, fontWeight = FontWeight.Bold)
    }
}
