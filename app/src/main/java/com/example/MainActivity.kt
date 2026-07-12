package com.example

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.example.data.AppDatabase
import com.example.data.CellRepository
import com.example.data.TowerDbEntry
import com.example.service.TowerMonitoringService
import com.example.telephony.CellModel
import com.example.ui.TelemetryViewModel
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.LogsScreen
import com.example.ui.screens.MapScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.TlTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class MainActivity : ComponentActivity() {

    companion object {
        /** Deep-link extras used by notification PendingIntents. */
        const val EXTRA_OPEN_TAB = "com.example.OPEN_TAB"
        const val EXTRA_FOCUS_LAT = "com.example.FOCUS_LAT"
        const val EXTRA_FOCUS_LON = "com.example.FOCUS_LON"
    }

    private val telemetryViewModel: TelemetryViewModel by viewModels()
    private var monitoringService: TowerMonitoringService? = null
    private var isBound = false

    // Deep-link requests (tab to open, map point to center) consumed by the UI.
    private val requestedTabState = mutableStateOf<Int?>(null)
    private val requestedFocusState = mutableStateOf<Pair<Double, Double>?>(null)

    private fun handleDeepLinkIntent(intent: Intent?) {
        intent ?: return
        val tab = intent.getIntExtra(EXTRA_OPEN_TAB, -1)
        if (tab in 0..3) requestedTabState.value = tab
        if (intent.hasExtra(EXTRA_FOCUS_LAT) && intent.hasExtra(EXTRA_FOCUS_LON)) {
            requestedFocusState.value =
                intent.getDoubleExtra(EXTRA_FOCUS_LAT, 0.0) to intent.getDoubleExtra(EXTRA_FOCUS_LON, 0.0)
        }
        // Strip the extras so a configuration change doesn't replay the deep link.
        intent.removeExtra(EXTRA_OPEN_TAB)
        intent.removeExtra(EXTRA_FOCUS_LAT)
        intent.removeExtra(EXTRA_FOCUS_LON)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLinkIntent(intent)
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as TowerMonitoringService.LocalBinder
            val boundService = binder.getService()
            monitoringService = boundService
            isBound = true

            // Let the centralized ViewModel manage the subscriptions and aggregate telemetry state
            telemetryViewModel.connectService(boundService)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            monitoringService = null
            isBound = false
            telemetryViewModel.disconnectService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleDeepLinkIntent(intent)

        val db = AppDatabase.getDatabase(this, lifecycleScope)
        val repository = CellRepository(db.cellDao(), this)

        setContent {
            MyApplicationTheme {
                val uiState by telemetryViewModel.uiState.collectAsStateWithLifecycle()

                MainAppScreen(
                    repository = repository,
                    currentCell = uiState.currentCell,
                    rsrpHistory = uiState.rsrpHistory,
                    userLocation = uiState.userLocation,
                    towerLocation = uiState.towerLocation,
                    resolvedAddress = uiState.resolvedAddress,
                    confidenceRange = uiState.confidenceRange,
                    deviceHeading = uiState.deviceHeading,
                    towerSource = uiState.towerSource,
                    requestedTab = requestedTabState.value,
                    onRequestedTabConsumed = { requestedTabState.value = null },
                    focusPoint = requestedFocusState.value,
                    onFocusConsumed = { requestedFocusState.value = null },
                    onStartService = { startAndBindService() },
                    onStopService = { stopAndUnbindService() },
                    onBackupDb = { backupDatabase() },
                    onRestoreDb = { restoreDatabase() }
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Auto-start and bind the monitoring service if permissions are already granted
        if (hasRequiredPermissions() && !isBound) {
            startAndBindService()
        }
    }

    private fun startAndBindService() {
        try {
            val intent = Intent(this, TowerMonitoringService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            // Foreground-service starts can be rejected (background restrictions,
            // battery saver); surface the failure instead of crashing.
            Log.e("MainActivity", "Unable to start monitoring service", e)
            Toast.makeText(this, "Could not start monitoring: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopAndUnbindService() {
        if (isBound) {
            try {
                unbindService(serviceConnection)
            } catch (e: IllegalArgumentException) {
                Log.w("MainActivity", "Service already unbound", e)
            }
            isBound = false
        }
        telemetryViewModel.disconnectService()
        stopService(Intent(this, TowerMonitoringService::class.java))
    }

    private fun hasRequiredPermissions(): Boolean {
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val phone = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
        return coarse == PackageManager.PERMISSION_GRANTED &&
                fine == PackageManager.PERMISSION_GRANTED &&
                phone == PackageManager.PERMISSION_GRANTED
    }

    private fun backupDatabase() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val dbFile = getDatabasePath("towerlock_database")
                val backupFile = File(getExternalFilesDir(null), "towerlock_database.bak")

                if (dbFile.exists()) {
                    // Flush the write-ahead log into the main file first, otherwise the
                    // copy silently misses everything logged since the last checkpoint.
                    AppDatabase.getDatabase(this@MainActivity, lifecycleScope).checkpoint()
                    FileInputStream(dbFile).use { input ->
                        FileOutputStream(backupFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Backup saved to: ${backupFile.name}", Toast.LENGTH_LONG).show()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Database not found", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Backup failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Backup failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun restoreDatabase() {
        val backupFile = File(getExternalFilesDir(null), "towerlock_database.bak")
        if (!backupFile.exists()) {
            Toast.makeText(this, "Backup file not found", Toast.LENGTH_SHORT).show()
            return
        }

        // The service holds its own DB handle, so it must stop before we swap files.
        stopAndUnbindService()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Close the open connection so SQLite isn't holding the file mid-copy,
                // and drop stale WAL/SHM journals so the restored snapshot is authoritative.
                AppDatabase.closeInstance()
                val dbFile = getDatabasePath("towerlock_database")
                File(dbFile.path + "-wal").delete()
                File(dbFile.path + "-shm").delete()
                FileInputStream(backupFile).use { input ->
                    FileOutputStream(dbFile).use { output ->
                        input.copyTo(output)
                    }
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Restore successful! Restarting monitor...", Toast.LENGTH_LONG).show()
                    // Recreate so every screen and the service reconnect to the restored database.
                    recreate()
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Restore failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Restore failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            try {
                unbindService(serviceConnection)
            } catch (e: IllegalArgumentException) {
                Log.w("MainActivity", "Service already unbound", e)
            }
            isBound = false
        }
        telemetryViewModel.disconnectService()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScreen(
    repository: CellRepository,
    currentCell: CellModel,
    rsrpHistory: List<Int>,
    userLocation: Pair<Double, Double>?,
    towerLocation: Pair<Double, Double>?,
    resolvedAddress: String,
    confidenceRange: Int,
    deviceHeading: Float,
    towerSource: String = "Unmapped",
    requestedTab: Int? = null,
    onRequestedTabConsumed: () -> Unit = {},
    focusPoint: Pair<Double, Double>? = null,
    onFocusConsumed: () -> Unit = {},
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onBackupDb: () -> Unit,
    onRestoreDb: () -> Unit
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }
    var hasPermissions by remember { mutableStateOf(hasAllRequiredPermissions(context)) }
    // The LIVE indicator reflects the service's actual state, so stopping from the
    // notification (or the system killing the service) stays in sync with the UI.
    val isMonitoringActive by TowerMonitoringService.isRunning.collectAsState()

    val logs by repository.allLogs.collectAsState(initial = emptyList())
    val towers by repository.allTowers.collectAsState(initial = emptyList())

    // Notification deep links: switch to the requested tab once per request.
    LaunchedEffect(requestedTab) {
        if (requestedTab != null) {
            selectedTab = requestedTab
            onRequestedTabConsumed()
        }
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        // Notifications are nice-to-have; only location + phone state gate monitoring.
        val requiredGranted = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.READ_PHONE_STATE
        ).all { perms[it] == true || ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        hasPermissions = requiredGranted
        if (requiredGranted) {
            onStartService()
        } else {
            Toast.makeText(context, "Location & phone permissions are required for monitoring", Toast.LENGTH_LONG).show()
        }
    }

    Scaffold(
        containerColor = TlTheme.colors.background,
        topBar = {
            if (hasPermissions) {
                TowerLockTopBar(
                    isMonitoring = isMonitoringActive,
                    onToggleMonitoring = {
                        if (isMonitoringActive) {
                            onStopService()
                        } else {
                            onStartService()
                        }
                    }
                )
            }
        },
        bottomBar = {
            if (hasPermissions) {
                val navItemColors = NavigationBarItemDefaults.colors(
                    selectedIconColor = TlTheme.colors.emerald,
                    selectedTextColor = TlTheme.colors.emerald,
                    unselectedIconColor = TlTheme.colors.textMuted,
                    unselectedTextColor = TlTheme.colors.textMuted,
                    indicatorColor = TlTheme.colors.emerald.copy(alpha = 0.15f)
                )
                NavigationBar(
                    containerColor = TlTheme.colors.surface,
                    contentColor = TlTheme.colors.textPrimary
                ) {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        icon = { Icon(imageVector = Icons.Default.Dashboard, contentDescription = "Dashboard") },
                        label = { Text("Status") },
                        colors = navItemColors
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        icon = { Icon(imageVector = Icons.Default.Map, contentDescription = "Map") },
                        label = { Text("Map") },
                        colors = navItemColors
                    )
                    NavigationBarItem(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        icon = { Icon(imageVector = Icons.Default.History, contentDescription = "Logs") },
                        label = { Text("Logs") },
                        colors = navItemColors
                    )
                    NavigationBarItem(
                        selected = selectedTab == 3,
                        onClick = { selectedTab = 3 },
                        icon = { Icon(imageVector = Icons.Default.Settings, contentDescription = "Settings") },
                        label = { Text("Settings") },
                        colors = navItemColors
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (!hasPermissions) {
                PermissionRationaleView(onRequestPermissions = {
                    val permissionsNeeded = mutableListOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.READ_PHONE_STATE
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        permissionsNeeded.add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    launcher.launch(permissionsNeeded.toTypedArray())
                })
            } else {
                Crossfade(targetState = selectedTab, label = "tabTransition") { tab ->
                    when (tab) {
                    0 -> DashboardScreen(
                        cell = currentCell,
                        userLat = userLocation?.first,
                        userLon = userLocation?.second,
                        towerLat = towerLocation?.first,
                        towerLon = towerLocation?.second,
                        resolvedAddress = resolvedAddress,
                        confidenceMeters = confidenceRange,
                        deviceHeading = deviceHeading,
                        rsrpHistory = rsrpHistory,
                        logs = logs,
                        towerSource = towerSource,
                        onSnapshotClick = {
                            val intent = Intent(context, TowerMonitoringService::class.java).apply {
                                action = "ACTION_SNAPSHOT"
                            }
                            context.startService(intent)
                        }
                    )
                    1 -> {
                        val coroutineScope = rememberCoroutineScope()
                        MapScreen(
                            cell = currentCell,
                            userLat = userLocation?.first,
                            userLon = userLocation?.second,
                            towerLat = towerLocation?.first,
                            towerLon = towerLocation?.second,
                            towerAddress = resolvedAddress,
                            confidenceMeters = confidenceRange,
                            allTowers = towers,
                            focusLat = focusPoint?.first,
                            focusLon = focusPoint?.second,
                            onFocusConsumed = onFocusConsumed,
                            onSaveTower = { lat, lon, address ->
                                coroutineScope.launch {
                                    repository.insertCustomTower(
                                        TowerDbEntry(
                                            radio = if (currentCell.tech.contains("5G")) "NR" else "LTE",
                                            mcc = currentCell.mcc ?: "310",
                                            mnc = currentCell.mnc ?: "260",
                                            area = currentCell.tac,
                                            cid = currentCell.cellId,
                                            lat = lat,
                                            lon = lon,
                                            range = 50,
                                            address = address
                                        )
                                    )
                                    Toast.makeText(context, "Tower location saved", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    }
                    2 -> {
                        val coroutineScope = rememberCoroutineScope()
                        LogsScreen(
                            logs = logs,
                            onDeleteLog = { log ->
                                coroutineScope.launch { repository.deleteLog(log) }
                            },
                            onClearAllLogs = {
                                coroutineScope.launch { repository.clearAllLogs() }
                            }
                        )
                    }
                    3 -> {
                        val coroutineScope = rememberCoroutineScope()
                        SettingsScreen(
                            onSaveApiKey = { key ->
                                context.getSharedPreferences("TowerLockPrefs", Context.MODE_PRIVATE)
                                    .edit().putString("opencellid_key", key).apply()
                            },
                            onSavePollInterval = { sec ->
                                val intent = Intent(context, TowerMonitoringService::class.java).apply {
                                    action = "UPDATE_POLL_INTERVAL"
                                    putExtra("POLL_INTERVAL", sec)
                                }
                                context.startService(intent)
                            },
                            onSaveGnbBitLength = { bits ->
                                val intent = Intent(context, TowerMonitoringService::class.java).apply {
                                    action = "UPDATE_GNB_BITS"
                                    putExtra("GNB_BITS", bits)
                                }
                                context.startService(intent)
                            },
                            onSaveIconStyle = { style ->
                                val intent = Intent(context, TowerMonitoringService::class.java).apply {
                                    action = "UPDATE_ICON_STYLE"
                                    putExtra("STYLE", style)
                                }
                                context.startService(intent)
                            },
                            onBackupDb = onBackupDb,
                            onRestoreDb = onRestoreDb,
                            onImportCsv = { uri ->
                                coroutineScope.launch {
                                    val imported = context.contentResolver.openInputStream(uri)?.use { stream ->
                                        repository.importCsv(stream)
                                    } ?: 0
                                    Toast.makeText(context, "Imported $imported towers", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TowerLockTopBar(isMonitoring: Boolean, onToggleMonitoring: () -> Unit) {
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.SignalCellularAlt,
                    contentDescription = null,
                    tint = TlTheme.colors.emerald,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("TowerLock", fontWeight = FontWeight.Bold, color = TlTheme.colors.textPrimary)
            }
        },
        actions = {
            LiveStatusIndicator(isMonitoring = isMonitoring)
            IconButton(onClick = onToggleMonitoring) {
                Icon(
                    imageVector = if (isMonitoring) Icons.Default.PauseCircle else Icons.Default.PlayCircle,
                    contentDescription = if (isMonitoring) "Stop monitoring" else "Start monitoring",
                    tint = if (isMonitoring) TlTheme.colors.textSecondary else TlTheme.colors.emerald
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = TlTheme.colors.surface,
            titleContentColor = TlTheme.colors.textPrimary
        )
    )
}

@Composable
fun LiveStatusIndicator(isMonitoring: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "liveDot")
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Reverse
        ),
        label = "liveDotAlpha"
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(end = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(
                    if (isMonitoring) {
                        TlTheme.colors.emerald.copy(alpha = dotAlpha)
                    } else {
                        TlTheme.colors.textMuted
                    }
                )
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = if (isMonitoring) "LIVE" else "PAUSED",
            color = if (isMonitoring) TlTheme.colors.emerald else TlTheme.colors.textMuted,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun PermissionRationaleView(onRequestPermissions: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TlTheme.colors.background)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Security,
            contentDescription = "Security Permissions",
            tint = TlTheme.colors.emerald,
            modifier = Modifier.size(80.dp)
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Fine Location & Telephony Access",
            style = MaterialTheme.typography.titleLarge,
            color = TlTheme.colors.textPrimary,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "TowerLock Pro requires Fine Location and Read Phone State permissions to identify your active cellular serving base stations, calculate Timing Advance distances, and geocode nearby tower nodes.",
            style = MaterialTheme.typography.bodyMedium,
            color = TlTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
            lineHeight = 22.sp
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(
            onClick = onRequestPermissions,
            colors = ButtonDefaults.buttonColors(
                containerColor = TlTheme.colors.emerald,
                contentColor = TlTheme.colors.onAccent
            ),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Grant Required Permissions", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

private fun hasAllRequiredPermissions(context: Context): Boolean {
    val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
    val phone = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
    return fine == PackageManager.PERMISSION_GRANTED && phone == PackageManager.PERMISSION_GRANTED
}
