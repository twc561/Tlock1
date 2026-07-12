package com.example.service

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import java.util.Locale
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.data.AppDatabase
import com.example.data.CellLog
import com.example.data.CellRepository
import com.example.location.LocationTracker
import com.example.telephony.CellModel
import com.example.telephony.ITelephonyTracker
import com.example.telephony.TelephonyTracker
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest

class TowerMonitoringService : Service() {

    private val binder = LocalBinder()

    private lateinit var telephonyTracker: ITelephonyTracker
    private lateinit var locationTracker: LocationTracker
    private lateinit var repository: CellRepository

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val _currentCell = MutableStateFlow(CellModel())
    val currentCell: StateFlow<CellModel> = _currentCell.asStateFlow()

    private val _towerLocation = MutableStateFlow<Pair<Double, Double>?>(null)
    val towerLocation: StateFlow<Pair<Double, Double>?> = _towerLocation.asStateFlow()

    private val _resolvedAddress = MutableStateFlow("Locating serving tower...")
    val resolvedAddress: StateFlow<String> = _resolvedAddress.asStateFlow()

    private val _towerSource = MutableStateFlow("Unmapped")
    val towerSource: StateFlow<String> = _towerSource.asStateFlow()

    private val _confidenceRange = MutableStateFlow(0)
    val confidenceRange: StateFlow<Int> = _confidenceRange.asStateFlow()

    val userLocation get() = locationTracker.userLocation
    val deviceHeading get() = locationTracker.deviceHeading

    private var iconStyle = "dBm" // "dBm", "band", "tech", "bars"
    private var alertRsearchBelow = -110 // Alert threshold for RSRP
    private var isLoggingPaused = false
    private var monitoringStartedAt = System.currentTimeMillis()
    private var lastAlertedUnmappedCellId: Long = 0
    // Towers already checked for "new tower" discovery this session, so the
    // per-poll DB count query only ever runs once per eNB/gNB.
    private val discoveryCheckedNodebs = java.util.Collections.synchronizedSet(mutableSetOf<Long>())
    // Monotonic ids keep alert notifications distinct without the collision risk
    // of truncating System.currentTimeMillis() to Int.
    private val alertNotificationId = java.util.concurrent.atomic.AtomicInteger(2000)

    companion object {
        private const val ALERT_GROUP = "com.example.TOWERLOCK_ALERTS"
        private const val ALERT_SUMMARY_ID = 1999

        private val _isRunning = MutableStateFlow(false)
        /** True while the service is alive; the UI's LIVE indicator observes this. */
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()
    }

    inner class LocalBinder : Binder() {
        fun getService(): TowerMonitoringService = this@TowerMonitoringService
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        _isRunning.value = true
        monitoringStartedAt = System.currentTimeMillis()
        val database = AppDatabase.getDatabase(this, serviceScope)
        repository = CellRepository(database.cellDao(), this)
        telephonyTracker = TelephonyTracker(this)
        locationTracker = LocationTracker(this)

        val prefs = getSharedPreferences("TowerLockPrefs", MODE_PRIVATE)
        telephonyTracker.setPollIntervalSeconds(prefs.getInt("poll_interval", 3))
        telephonyTracker.setGnbBitLength(prefs.getInt("gnb_bits", 24))

        createNotificationChannels()
        startForegroundService()

        // Start listening to cell updates using custom task scheduler & power optimizer
        serviceScope.launch {
            // A small delay ensures the service's foreground state is fully registered by AppOps
            delay(1000L)

            var previousCell: CellModel? = null

            while (isActive) {
                val prefs = getSharedPreferences("TowerLockPrefs", MODE_PRIVATE)
                // Drive mode needs continuous sampling to catch short-lived
                // handovers between towers, so it overrides batching.
                val batchingEnabled = prefs.getBoolean("batching_enabled", true) &&
                        !prefs.getBoolean("drive_mode", false)
                val intervalSeconds = prefs.getInt("batch_interval", 60)
                val pollInterval = prefs.getInt("poll_interval", 3)

                if (batchingEnabled) {
                    Log.d("TowerMonitoringService", "Scheduler: Starting batched polling active window")
                    // 1. Acquire WakeLock for the active duration (3 polls spaced by pollInterval, plus buffer)
                    val activeWindowMs = (3 * pollInterval * 1000L) + 5000L
                    acquireWakeLock(activeWindowMs)

                    // 2. Wake up sensors and telephony tracking
                    locationTracker.startLocationTracking()
                    telephonyTracker.startMonitoring()

                    // 3. Perform a batch of 3 polls to reduce continuous wake time
                    val batchLogs = mutableListOf<CellLog>()

                    for (i in 0 until 3) {
                        if (!isActive) break
                        delay(pollInterval * 1000L)

                        val cell = telephonyTracker.cellState.value
                        _currentCell.value = cell

                        if (cell.cellId > 0) {
                            val lookup = repository.findTowerLocation(
                                tech = cell.tech,
                                mcc = cell.mcc ?: "310",
                                mnc = cell.mnc ?: "260",
                                area = cell.tac,
                                cid = cell.cellId,
                                openCellIdApiKey = prefs.getString("opencellid_key", null),
                                userLocation = locationTracker.userLocation.value,
                                distanceEstimateMeters = cell.distanceEstimateMeters
                            )

                            val userLoc = locationTracker.userLocation.value
                            val resolvedAddress = lookup.address?.takeIf { it.isNotBlank() }
                                ?: "Unmapped cell tower location"

                            _towerLocation.value = if (lookup.lat != null && lookup.lon != null) {
                                Pair(lookup.lat, lookup.lon)
                            } else {
                                null
                            }
                            _resolvedAddress.value = resolvedAddress
                            _confidenceRange.value = lookup.range
                            _towerSource.value = lookup.source

                            maybeNotifyNewTower(cell, lookup.lat, lookup.lon)

                            if (userLoc != null && !isLoggingPaused) {
                                val logEntry = CellLog(
                                    tech = cell.tech,
                                    mcc = cell.mcc ?: "310",
                                    mnc = cell.mnc ?: "260",
                                    operatorName = cell.operatorName ?: "Carrier",
                                    cellId = cell.cellId,
                                    nodebId = cell.nodebId,
                                    sectorId = cell.sectorId,
                                    pci = cell.pci,
                                    tac = cell.tac,
                                    arfcn = cell.arfcn,
                                    band = cell.bandName,
                                    rsrp = cell.rsrp,
                                    rsrq = cell.rsrq,
                                    sinr = cell.sinr,
                                    lat = userLoc.latitude,
                                    lon = userLoc.longitude,
                                    towerLat = lookup.lat,
                                    towerLon = lookup.lon,
                                    address = resolvedAddress,
                                    source = lookup.source,
                                    timingAdvanceMeters = cell.distanceEstimateMeters,
                                    caCarrierCount = cell.activeCarriers.size.coerceAtLeast(1),
                                    aggregateBandwidthKhz = cell.activeCarriers.sumOf { it.bandwidthKhz }
                                )
                                batchLogs.add(logEntry)
                            }

                            // Update widget
                            com.example.widget.TowerLockWidget.sendWidgetUpdate(this@TowerMonitoringService, cell, resolvedAddress)

                            // Alert rules engine
                            previousCell?.let { prev ->
                                triggerAlertsEngine(prev, cell, lookup.source)
                            }
                            previousCell = cell

                            updateNotification(cell)
                        }
                    }

                    // 4. Batch insert all recorded logs in a single SQLite transaction
                    if (batchLogs.isNotEmpty() && !isLoggingPaused) {
                        Log.d("TowerMonitoringService", "Scheduler: Saving batch of ${batchLogs.size} logs to database")
                        repository.insertLogs(batchLogs)
                    }

                    // 5. Completely sleep sensors and telephony tracking to conserve battery
                    Log.d("TowerMonitoringService", "Scheduler: Suspending trackers for background sleep")
                    locationTracker.stopLocationTracking()
                    telephonyTracker.stopMonitoring()

                    // 6. Release active WakeLock
                    releaseWakeLock()

                    // 7. Standby sleep for the remaining batch interval
                    val sleepSeconds = (intervalSeconds - (3 * pollInterval)).coerceAtLeast(10)
                    Log.d("TowerMonitoringService", "Scheduler: Power-save sleep for $sleepSeconds seconds")
                    delay(sleepSeconds * 1000L)

                } else {
                    // Continuous Real-Time Tracking mode
                    Log.d("TowerMonitoringService", "Scheduler: Running in real-time continuous mode")
                    locationTracker.startLocationTracking()
                    telephonyTracker.startMonitoring()

                    val collectJob = launch {
                        telephonyTracker.cellState.collect { cell ->
                            _currentCell.value = cell

                            if (!isLoggingPaused && cell.cellId > 0) {
                                val lookup = repository.findTowerLocation(
                                    tech = cell.tech,
                                    mcc = cell.mcc ?: "310",
                                    mnc = cell.mnc ?: "260",
                                    area = cell.tac,
                                    cid = cell.cellId,
                                    openCellIdApiKey = prefs.getString("opencellid_key", null),
                                    userLocation = locationTracker.userLocation.value,
                                    distanceEstimateMeters = cell.distanceEstimateMeters
                                )

                                val userLoc = locationTracker.userLocation.value
                                val resolvedAddress = lookup.address?.takeIf { it.isNotBlank() }
                                    ?: "Unmapped cell tower location"

                                _towerLocation.value = if (lookup.lat != null && lookup.lon != null) {
                                    Pair(lookup.lat, lookup.lon)
                                } else {
                                    null
                                }
                                _resolvedAddress.value = resolvedAddress
                                _confidenceRange.value = lookup.range
                                _towerSource.value = lookup.source

                                maybeNotifyNewTower(cell, lookup.lat, lookup.lon)

                                if (userLoc != null) {
                                    val logEntry = CellLog(
                                        tech = cell.tech,
                                        mcc = cell.mcc ?: "310",
                                        mnc = cell.mnc ?: "260",
                                        operatorName = cell.operatorName ?: "Carrier",
                                        cellId = cell.cellId,
                                        nodebId = cell.nodebId,
                                        sectorId = cell.sectorId,
                                        pci = cell.pci,
                                        tac = cell.tac,
                                        arfcn = cell.arfcn,
                                        band = cell.bandName,
                                        rsrp = cell.rsrp,
                                        rsrq = cell.rsrq,
                                        sinr = cell.sinr,
                                        lat = userLoc.latitude,
                                        lon = userLoc.longitude,
                                        towerLat = lookup.lat,
                                        towerLon = lookup.lon,
                                        address = resolvedAddress,
                                        source = lookup.source,
                                        timingAdvanceMeters = cell.distanceEstimateMeters,
                                        caCarrierCount = cell.activeCarriers.size.coerceAtLeast(1),
                                        aggregateBandwidthKhz = cell.activeCarriers.sumOf { it.bandwidthKhz }
                                    )
                                    repository.insertLog(logEntry)
                                }

                                com.example.widget.TowerLockWidget.sendWidgetUpdate(this@TowerMonitoringService, cell, resolvedAddress)

                                previousCell?.let { prev ->
                                    triggerAlertsEngine(prev, cell, lookup.source)
                                }
                                previousCell = cell
                            }

                            updateNotification(cell)
                        }
                    }

                    // Dynamically detect configuration shifts back to batching
                    while (isActive) {
                        val p = getSharedPreferences("TowerLockPrefs", MODE_PRIVATE)
                        val currentBatchingEnabled = p.getBoolean("batching_enabled", true) &&
                                !p.getBoolean("drive_mode", false)
                        if (currentBatchingEnabled) {
                            Log.d("TowerMonitoringService", "Scheduler: Transitioning back to batched mode")
                            break
                        }
                        delay(2000L)
                    }
                    collectJob.cancel()
                }
            }
        }
    }

    /**
     * Drive-mode tower discovery: fires a notification the first time the device
     * ever connects to a physical tower (eNB/gNB) that has no prior log history.
     */
    private suspend fun maybeNotifyNewTower(cell: CellModel, towerLat: Double?, towerLon: Double?) {
        if (!getSharedPreferences("TowerLockPrefs", MODE_PRIVATE).getBoolean("drive_mode", false)) return
        if (cell.nodebId <= 0) return
        if (!discoveryCheckedNodebs.add(cell.nodebId)) return
        if (repository.countLogsForNodeb(cell.nodebId) > 0) return

        val gnbLabel = if (cell.tech.contains("5G")) "gNB" else "eNB"
        val focusIntent = if (towerLat != null && towerLon != null) {
            tabPendingIntent(alertNotificationId.incrementAndGet(), 1) {
                putExtra(MainActivity.EXTRA_FOCUS_LAT, towerLat)
                putExtra(MainActivity.EXTRA_FOCUS_LON, towerLon)
            }
        } else null
        triggerAlert(
            "New Tower Discovered",
            "First contact with $gnbLabel ${cell.nodebId} on ${cell.bandName}.",
            R.drawable.ic_cell_tower, 0xFF10B981.toInt(), focusIntent
        )
    }

    private fun triggerAlertsEngine(prev: CellModel, current: CellModel, source: String) {
        // 1. Notify on band change
        if (prev.bandName != current.bandName && getSharedPreferences("TowerLockPrefs", MODE_PRIVATE).getBoolean("alert_band", false)) {
            triggerAlert(
                "Band Changed", "Moved from ${prev.bandName} to ${current.bandName}",
                R.drawable.ic_swap, 0xFFF59E0B.toInt()
            )
        }
        // 2. SA <-> NSA transition
        if (prev.tech != current.tech && getSharedPreferences("TowerLockPrefs", MODE_PRIVATE).getBoolean("alert_tech", false)) {
            triggerAlert(
                "Technology Shift", "Connection shifted from ${prev.tech} to ${current.tech}",
                R.drawable.ic_swap, 0xFF0EA5E9.toInt()
            )
        }
        // 3. RSRP below threshold
        if (current.rsrp < alertRsearchBelow && prev.rsrp >= alertRsearchBelow && getSharedPreferences("TowerLockPrefs", MODE_PRIVATE).getBoolean("alert_rsrp", false)) {
            triggerAlert(
                "Weak Signal Alert", "RSRP dropped below threshold to ${current.rsrp} dBm",
                R.drawable.ic_warning, 0xFFF59E0B.toInt()
            )
        }
        // 4. Connecting to an unmapped tower (only alert once per unique unmapped cell)
        if (source == "Unmapped cell") {
            if (current.cellId != lastAlertedUnmappedCellId && getSharedPreferences("TowerLockPrefs", MODE_PRIVATE).getBoolean("alert_unmapped", false)) {
                lastAlertedUnmappedCellId = current.cellId
                triggerAlert(
                    "Unmapped Tower Detected", "Connected to cell ${current.cellId} which is not mapped.",
                    R.drawable.ic_cell_tower, 0xFFEF4444.toInt()
                )
            }
        } else {
            // Reset so we can alert if we connect back to the same unmapped cell later
            if (lastAlertedUnmappedCellId == current.cellId) {
                lastAlertedUnmappedCellId = 0
            }
        }
        // 5. 5G SA to LTE Drop
        val isPrev5gSa = prev.tech == "5G SA"
        val isCurrentLte = current.tech == "4G LTE" || current.tech == "LTE" || current.tech.contains("LTE")
        if (isPrev5gSa && isCurrentLte) {
            val prefs = getSharedPreferences("TowerLockPrefs", MODE_PRIVATE)
            if (prefs.getBoolean("alert_5g_drop", true)) {
                val userLoc = locationTracker.userLocation.value
                val locationLabel = if (userLoc != null) {
                    String.format(
                        java.util.Locale.US,
                        "(%.5f, %.5f)",
                        userLoc.latitude,
                        userLoc.longitude
                    )
                } else {
                    "location unavailable"
                }
                // Deep link to the Map tab, centered on where the drop happened.
                val dropPendingIntent = tabPendingIntent(4, 1) {
                    if (userLoc != null) {
                        putExtra(MainActivity.EXTRA_FOCUS_LAT, userLoc.latitude)
                        putExtra(MainActivity.EXTRA_FOCUS_LON, userLoc.longitude)
                    }
                }
                triggerAlert(
                    "5G Connection Dropped",
                    "Device switched from 5G SA to legacy LTE at: $locationLabel",
                    R.drawable.ic_trending_down, 0xFFEF4444.toInt(), dropPendingIntent
                )

                if (prefs.getBoolean("log_drop_coords", true) && userLoc != null) {
                    serviceScope.launch {
                        val dropLog = CellLog(
                            tech = "5G SA to LTE Drop",
                            mcc = current.mcc ?: "310",
                            mnc = current.mnc ?: "260",
                            operatorName = current.operatorName ?: "Carrier",
                            cellId = current.cellId,
                            nodebId = current.nodebId,
                            sectorId = current.sectorId,
                            pci = current.pci,
                            tac = current.tac,
                            arfcn = current.arfcn,
                            band = current.bandName,
                            rsrp = current.rsrp,
                            rsrq = current.rsrq,
                            sinr = current.sinr,
                            lat = userLoc.latitude,
                            lon = userLoc.longitude,
                            towerLat = null,
                            towerLon = null,
                            address = "Recorded 5G SA to LTE drop coordinates",
                            source = "5G Drop Monitor",
                            timingAdvanceMeters = current.distanceEstimateMeters,
                            caCarrierCount = current.activeCarriers.size.coerceAtLeast(1),
                            aggregateBandwidthKhz = current.activeCarriers.sumOf { it.bandwidthKhz }
                        )
                        repository.insertLog(dropLog)
                    }
                }
            }
        }
    }

    /**
     * POST_NOTIFICATIONS is a runtime permission on Android 13+; posting without it
     * is silently dropped (or raises AppOps warnings), so check before notifying.
     */
    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

    private fun triggerAlert(
        title: String,
        body: String,
        smallIcon: Int = R.drawable.ic_warning,
        accentColor: Int = 0xFFF59E0B.toInt(),
        contentIntent: PendingIntent? = null
    ) {
        if (!canPostNotifications()) return
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val defaultIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val defaultPendingIntent = PendingIntent.getActivity(this, 0, defaultIntent, PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(this, "Alerts")
            .setSmallIcon(smallIcon)
            .setColor(accentColor)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(contentIntent ?: defaultPendingIntent)
            .setGroup(ALERT_GROUP)
            .setAutoCancel(true)

        if (getSharedPreferences("TowerLockPrefs", MODE_PRIVATE).getBoolean("alert_vibrate", true)) {
            builder.setVibrate(longArrayOf(0, 300, 100, 300))
        }

        try {
            notificationManager.notify(alertNotificationId.incrementAndGet(), builder.build())
            // Group summary keeps the shade collapsed to one entry when alerts stack up.
            val summary = NotificationCompat.Builder(this, "Alerts")
                .setSmallIcon(R.drawable.ic_cell_tower)
                .setColor(0xFF10B981.toInt())
                .setContentTitle("TowerLock alerts")
                .setGroup(ALERT_GROUP)
                .setGroupSummary(true)
                .setAutoCancel(true)
                .build()
            notificationManager.notify(ALERT_SUMMARY_ID, summary)
        } catch (e: SecurityException) {
            Log.w("TowerMonitoringService", "Alert notification rejected", e)
        }
    }

    private fun startForegroundService() {
        try {
            val notification = createNotification(CellModel())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(1001, notification)
            }
        } catch (e: Exception) {
            // FGS starts can be rejected (e.g. missing while-in-use location permission
            // on newer Android); stop cleanly instead of crashing the process.
            Log.e("TowerMonitoringService", "Unable to enter foreground state", e)
            stopSelf()
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val monitoringChannel = NotificationChannel(
                "Monitoring",
                "Tower Monitoring Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Persistent display of current cellular serving cell stats"
                enableLights(false)
                enableVibration(false)
            }

            val alertsChannel = NotificationChannel(
                "Alerts",
                "Status Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Fires on cell tower handovers, weak signals, and unmapped cells"
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(monitoringChannel)
            manager.createNotificationChannel(alertsChannel)
        }
    }

    private fun getBandSummary(cell: CellModel): String {
        if (cell.activeCarriers.isEmpty()) {
            return cell.bandName.ifBlank { "Unknown" }
        }
        val bands = cell.activeCarriers.map { it.band.trim() }.filter { it.isNotBlank() }
        val joinedBands = if (bands.isNotEmpty()) {
            bands.joinToString("+")
        } else {
            cell.bandName
        }
        val caText = when {
            cell.activeCarriers.size > 1 -> " (${cell.activeCarriers.size}CC CA)"
            cell.caIndicated -> " (CA)"
            else -> ""
        }
        return "$joinedBands$caText"
    }

    /** Signal-grade color used for notification accents and the RSRP readout. */
    private fun signalColorInt(rsrp: Int): Int = when {
        rsrp >= -80 -> 0xFF4CAF50.toInt()
        rsrp >= -95 -> 0xFF8BC34A.toInt()
        rsrp >= -110 -> 0xFFFFB74D.toInt()
        else -> 0xFFE57373.toInt()
    }

    /** Deep link into MainActivity on a given tab (0=Status, 1=Map, 2=Logs, 3=Settings). */
    private fun tabPendingIntent(requestCode: Int, tab: Int, extras: Intent.() -> Unit = {}): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_OPEN_TAB, tab)
            extras()
        }
        return PendingIntent.getActivity(
            this, requestCode, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /** Fills the fields shared by the collapsed and expanded notification layouts. */
    private fun populateHeaderRow(views: RemoteViews, cell: CellModel, hasSignal: Boolean) {
        val carrier = if (hasSignal) (cell.operatorName ?: "Carrier") else "TowerLock"
        views.setTextViewText(R.id.notif_carrier, carrier)
        if (hasSignal) {
            views.setViewVisibility(R.id.notif_tech, View.VISIBLE)
            views.setTextViewText(R.id.notif_tech, cell.tech)
            views.setInt(
                R.id.notif_tech, "setBackgroundResource",
                if (cell.tech.contains("5G")) R.drawable.bg_chip_5g else R.drawable.bg_chip_lte
            )
            views.setTextViewText(R.id.notif_rsrp, "${cell.rsrp} dBm")
            views.setTextColor(R.id.notif_rsrp, signalColorInt(cell.rsrp))

            // Carrier Aggregation badge in header
            if (cell.activeCarriers.size > 1 || cell.caIndicated) {
                views.setViewVisibility(R.id.notif_ca_badge, View.VISIBLE)
                views.setTextViewText(
                    R.id.notif_ca_badge,
                    if (cell.activeCarriers.size > 1) "${cell.activeCarriers.size}CC CA" else "CA"
                )
                views.setInt(
                    R.id.notif_ca_badge, "setBackgroundResource",
                    if (cell.tech.contains("5G")) R.drawable.bg_chip_5g else R.drawable.bg_chip_lte
                )
            } else {
                views.setViewVisibility(R.id.notif_ca_badge, View.GONE)
            }
        } else {
            views.setViewVisibility(R.id.notif_tech, View.GONE)
            views.setViewVisibility(R.id.notif_ca_badge, View.GONE)
            views.setTextViewText(R.id.notif_rsrp, "--")
            views.setTextColor(R.id.notif_rsrp, 0xFF94A3B8.toInt())
        }
    }

    private fun createNotification(cell: CellModel): Notification {
        val hasSignal = cell.cellId > 0
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )

        // Actions
        val pauseIntent = Intent(this, TowerMonitoringService::class.java).apply {
            action = "ACTION_PAUSE"
        }
        val pausePendingIntent = PendingIntent.getService(this, 1, pauseIntent, PendingIntent.FLAG_IMMUTABLE)

        val snapshotIntent = Intent(this, TowerMonitoringService::class.java).apply {
            action = "ACTION_SNAPSHOT"
        }
        val snapshotPendingIntent = PendingIntent.getService(this, 2, snapshotIntent, PendingIntent.FLAG_IMMUTABLE)

        val stopIntent = Intent(this, TowerMonitoringService::class.java).apply {
            action = "ACTION_STOP"
        }
        val stopPendingIntent = PendingIntent.getService(this, 3, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        val collapsed = RemoteViews(packageName, R.layout.notification_collapsed)
        populateHeaderRow(collapsed, cell, hasSignal)

        val expanded = RemoteViews(packageName, R.layout.notification_expanded)
        populateHeaderRow(expanded, cell, hasSignal)
        expanded.setTextViewText(
            R.id.notif_address,
            if (hasSignal) _resolvedAddress.value.ifBlank { "Resolving location..." }
            else "Acquiring signal — waiting for the modem to report a serving cell."
        )
        // Tapping the address jumps straight to the Map tab.
        expanded.setOnClickPendingIntent(R.id.notif_address, tabPendingIntent(5, 1))

        // One pre-tinted bar per grade; RemoteViews can't retint a single bar below API 31.
        val allBars = listOf(
            R.id.notif_bar_excellent, R.id.notif_bar_good, R.id.notif_bar_fair, R.id.notif_bar_poor
        )
        allBars.forEach { expanded.setViewVisibility(it, View.GONE) }
        if (hasSignal) {
            val activeBar = when {
                cell.rsrp >= -80 -> R.id.notif_bar_excellent
                cell.rsrp >= -95 -> R.id.notif_bar_good
                cell.rsrp >= -110 -> R.id.notif_bar_fair
                else -> R.id.notif_bar_poor
            }
            expanded.setViewVisibility(activeBar, View.VISIBLE)
            val percent = ((cell.rsrp + 140) * 100 / 90).coerceIn(0, 100)
            expanded.setProgressBar(activeBar, 100, percent, false)
        } else {
            expanded.setViewVisibility(R.id.notif_bar_poor, View.VISIBLE)
            expanded.setProgressBar(R.id.notif_bar_poor, 100, 0, false)
        }

        val gnbLabel = if (cell.tech.contains("5G")) "gNB" else "eNB"
        expanded.setTextViewText(R.id.notif_band, if (hasSignal) getBandSummary(cell) else "—")
        expanded.setTextViewText(
            R.id.notif_cellid,
            if (hasSignal) "$gnbLabel ${cell.nodebId} • ${cell.cellId}" else "—"
        )
        expanded.setTextViewText(
            R.id.notif_pcitac,
            if (hasSignal) "${cell.pci} / ${cell.tac}" else "—"
        )
        expanded.setTextViewText(
            R.id.notif_ca,
            when {
                !hasSignal -> "—"
                cell.activeCarriers.size > 1 -> {
                    val bandNames = cell.activeCarriers.joinToString("+") { it.band.substringBefore(" ") }
                    "Active (${cell.activeCarriers.size}CC: $bandNames)"
                }
                cell.caIndicated -> "Active (CA)"
                else -> "Standby"
            }
        )
        expanded.setTextViewText(
            R.id.notif_quality,
            if (hasSignal && cell.sinr > -20) "${cell.sinr} dB" else "—"
        )
        expanded.setTextViewText(
            R.id.notif_ta,
            if (hasSignal && cell.timingAdvance > 0) {
                val feet = cell.distanceEstimateMeters * 3.28084
                if (feet < 1000) {
                    "${cell.timingAdvance} (approx. ${Math.round(feet)} ft)"
                } else {
                    val miles = feet / 5280.0
                    "${cell.timingAdvance} (approx. ${String.format(Locale.US, "%.2f", miles)} mi)"
                }
            } else if (hasSignal && cell.timingAdvance == 0) {
                "0 (Immediate Range)"
            } else {
                "—"
            }
        )

        val iconText = when (iconStyle) {
            "dBm" -> "${cell.rsrp}"
            "band" -> cell.bandName.substringBefore(" ").replace("n", "").replace("B", "")
            "tech" -> if (cell.tech.contains("5G")) "5G" else "4G"
            else -> "bars"
        }

        return NotificationCompat.Builder(this, "Monitoring")
            .setSmallIcon(createDynamicIcon(iconText, cell.rsrp))
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(collapsed)
            .setCustomBigContentView(expanded)
            .setSubText(if (hasSignal) getBandSummary(cell) else "Acquiring signal")
            .setColor(if (hasSignal) signalColorInt(cell.rsrp) else 0xFF10B981.toInt())
            // Silent, stable presence in the shade: never re-alert or re-sort on updates.
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(contentIntent)
            .setWhen(monitoringStartedAt)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .addAction(
                if (isLoggingPaused) R.drawable.ic_play else R.drawable.ic_pause,
                if (isLoggingPaused) "Resume" else "Pause",
                pausePendingIntent
            )
            .addAction(R.drawable.ic_camera, "Snapshot", snapshotPendingIntent)
            .addAction(R.drawable.ic_stop, "Stop", stopPendingIntent)
            .build()
    }

    private fun updateNotification(cell: CellModel) {
        if (!canPostNotifications()) return
        try {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(1001, createNotification(cell))
        } catch (e: SecurityException) {
            Log.w("TowerMonitoringService", "Status notification rejected", e)
        }
    }

    /**
     * Renders the status-bar icon. Status-bar icons are treated as alpha masks by
     * the system, so this draws white-on-transparent (the old filled circle showed
     * up as a solid blob on most devices). The signal color lives in the
     * notification accent instead.
     */
    private fun createDynamicIcon(text: String, rsrp: Int): androidx.core.graphics.drawable.IconCompat {
        val size = 96
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        if (text == "bars") {
            // Classic signal-bars glyph; filled count follows the grade.
            val filled = when {
                rsrp >= -80 -> 4
                rsrp >= -95 -> 3
                rsrp >= -110 -> 2
                else -> 1
            }
            val barWidth = 16f
            val gap = 8f
            for (i in 0 until 4) {
                val barHeight = 28f + i * 20f
                val left = 2f + i * (barWidth + gap)
                val paint = Paint().apply {
                    color = Color.WHITE
                    isAntiAlias = true
                    alpha = if (i < filled) 255 else 70
                }
                canvas.drawRoundRect(left, size - barHeight, left + barWidth, size.toFloat(), 4f, 4f, paint)
            }
        } else {
            val paint = Paint().apply {
                color = Color.WHITE
                isAntiAlias = true
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textSize = when {
                    text.length <= 2 -> 60f
                    text.length == 3 -> 48f
                    text.length == 4 -> 40f
                    else -> 32f
                }
            }
            val xPos = size / 2f
            val yPos = (size / 2f) - ((paint.descent() + paint.ascent()) / 2f)
            canvas.drawText(text, xPos, yPos, paint)
        }

        return androidx.core.graphics.drawable.IconCompat.createWithBitmap(bitmap)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "ACTION_STOP" -> {
                // User asked to stop from the notification: leave foreground state,
                // remove the notification, and don't let START_STICKY revive us.
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            "ACTION_PAUSE" -> {
                isLoggingPaused = !isLoggingPaused
                updateNotification(_currentCell.value)
            }
            "ACTION_SNAPSHOT" -> {
                serviceScope.launch {
                    val current = _currentCell.value
                    val userLoc = locationTracker.userLocation.value
                    if (current.cellId > 0 && userLoc != null) {
                        val snapshotLog = CellLog(
                            tech = "${current.tech} (SNAPSHOT)",
                            mcc = current.mcc ?: "310",
                            mnc = current.mnc ?: "260",
                            operatorName = current.operatorName ?: "Carrier",
                            cellId = current.cellId,
                            nodebId = current.nodebId,
                            sectorId = current.sectorId,
                            pci = current.pci,
                            tac = current.tac,
                            arfcn = current.arfcn,
                            band = current.bandName,
                            rsrp = current.rsrp,
                            rsrq = current.rsrq,
                            sinr = current.sinr,
                            lat = userLoc.latitude,
                            lon = userLoc.longitude,
                            towerLat = _towerLocation.value?.first,
                            towerLon = _towerLocation.value?.second,
                            address = "Manual snapshot saved by user",
                            source = "Manual Snapshot",
                            timingAdvanceMeters = current.distanceEstimateMeters,
                            caCarrierCount = current.activeCarriers.size.coerceAtLeast(1),
                            aggregateBandwidthKhz = current.activeCarriers.sumOf { it.bandwidthKhz }
                        )
                        repository.insertLog(snapshotLog)
                        triggerAlert(
                            "Snapshot Saved", "Serving cell state recorded in log history.",
                            R.drawable.ic_camera, 0xFF10B981.toInt()
                        )
                    } else if (current.cellId > 0) {
                        triggerAlert(
                            "Snapshot Failed", "GPS location not yet available; try again shortly.",
                            R.drawable.ic_camera, 0xFFEF4444.toInt()
                        )
                    }
                }
            }
            "UPDATE_ICON_STYLE" -> {
                iconStyle = intent.getStringExtra("STYLE") ?: "dBm"
                updateNotification(_currentCell.value)
            }
            "UPDATE_ALERT_THRESHOLDS" -> {
                alertRsearchBelow = intent.getIntExtra("RSRP_THRESHOLD", -110)
            }
            "UPDATE_POLL_INTERVAL" -> {
                telephonyTracker.setPollIntervalSeconds(intent.getIntExtra("POLL_INTERVAL", 3))
            }
            "UPDATE_GNB_BITS" -> {
                telephonyTracker.setGnbBitLength(intent.getIntExtra("GNB_BITS", 24))
            }
        }
        return START_STICKY
    }

    private var wakeLock: android.os.PowerManager.WakeLock? = null

    private fun acquireWakeLock(timeoutMs: Long) {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (wakeLock == null) {
                wakeLock = powerManager.newWakeLock(
                    android.os.PowerManager.PARTIAL_WAKE_LOCK,
                    "TowerLockPro:MonitoringWakeLock"
                )
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(timeoutMs)
                Log.d("TowerMonitoringService", "WakeLock acquired for $timeoutMs ms")
            }
        } catch (e: Exception) {
            Log.e("TowerMonitoringService", "Failed to acquire WakeLock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d("TowerMonitoringService", "WakeLock released")
            }
        } catch (e: Exception) {
            Log.e("TowerMonitoringService", "Failed to release WakeLock", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        _isRunning.value = false
        locationTracker.stopLocationTracking()
        telephonyTracker.stopMonitoring()
        releaseWakeLock()
        serviceScope.cancel()
        // Flip the home-screen widget to its OFFLINE state instead of showing stale data.
        com.example.widget.TowerLockWidget.clearWidgetState(this)
    }
}
