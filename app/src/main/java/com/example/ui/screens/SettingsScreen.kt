package com.example.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.TlTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onSaveApiKey: (String) -> Unit,
    onSavePollInterval: (Int) -> Unit,
    onSaveGnbBitLength: (Int) -> Unit,
    onSaveIconStyle: (String) -> Unit,
    onBackupDb: () -> Unit,
    onRestoreDb: () -> Unit,
    onImportCsv: (Uri, Boolean) -> Unit,
    importStatus: String? = null,
    onExport: (String) -> Unit = {}
) {
    val tl = TlTheme.colors
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("TowerLockPrefs", Context.MODE_PRIVATE) }
    var importFloridaOnly by remember { mutableStateOf(prefs.getBoolean("import_filter_fl", true)) }
    val csvImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { onImportCsv(it, importFloridaOnly) } }

    var openCellIdKey by remember { mutableStateOf(prefs.getString("opencellid_key", "") ?: "") }
    var isKeyVisible by remember { mutableStateOf(false) }
    var geminiApiKey by remember { mutableStateOf(prefs.getString("gemini_api_key", "") ?: "") }
    var isGeminiKeyVisible by remember { mutableStateOf(false) }
    var pollInterval by remember { mutableStateOf(prefs.getInt("poll_interval", 3)) }
    var gnbBits by remember { mutableStateOf(prefs.getInt("gnb_bits", 24)) }
    var selectedIconStyle by remember { mutableStateOf(prefs.getString("icon_style", "dBm") ?: "dBm") }
    var batchingEnabled by remember { mutableStateOf(prefs.getBoolean("batching_enabled", true)) }
    var batchInterval by remember { mutableStateOf(prefs.getInt("batch_interval", 60)) }

    // Alert triggers states
    var alertOnBand by remember { mutableStateOf(prefs.getBoolean("alert_band", false)) }
    var alertOnTech by remember { mutableStateOf(prefs.getBoolean("alert_tech", false)) }
    var alertOnRsrp by remember { mutableStateOf(prefs.getBoolean("alert_rsrp", false)) }
    var alertOnUnmapped by remember { mutableStateOf(prefs.getBoolean("alert_unmapped", false)) }
    var alertOn5gDrop by remember { mutableStateOf(prefs.getBoolean("alert_5g_drop", true)) }
    var logDropCoords by remember { mutableStateOf(prefs.getBoolean("log_drop_coords", true)) }
    var alertVibrate by remember { mutableStateOf(prefs.getBoolean("alert_vibrate", true)) }

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(tl.background)
            .padding(16.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Monitoring Settings",
            style = MaterialTheme.typography.titleMedium,
            color = tl.textPrimary,
            fontWeight = FontWeight.Bold
        )

        // API Key Section
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = tl.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "OpenCelliD Integration",
                        style = MaterialTheme.typography.titleSmall,
                        color = tl.textPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    val isConfigured = openCellIdKey.isNotBlank()
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (isConfigured) tl.emerald.copy(alpha = 0.18f)
                                else tl.red.copy(alpha = 0.18f)
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (isConfigured) "CONFIGURED" else "NOT SET",
                            color = if (isConfigured) tl.emerald else tl.red,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Text(
                    text = "Real-world cell towers resolve to an address only when this key is set — " +
                            "without it, almost every tower you connect to will show as \"Unmapped.\"",
                    style = MaterialTheme.typography.bodySmall,
                    color = tl.textSecondary
                )
                TextField(
                    value = openCellIdKey,
                    onValueChange = {
                        openCellIdKey = it
                        prefs.edit().putString("opencellid_key", it).apply()
                        onSaveApiKey(it)
                    },
                    placeholder = { Text("Enter OpenCelliD API Key") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = tl.background,
                        unfocusedContainerColor = tl.background,
                        focusedTextColor = tl.textPrimary,
                        unfocusedTextColor = tl.textPrimary,
                        focusedIndicatorColor = tl.emerald,
                        unfocusedIndicatorColor = tl.outline,
                        cursorColor = tl.emerald
                    ),
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                    // The key is a secret; mask it unless the user opts to reveal it.
                    visualTransformation = if (isKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { isKeyVisible = !isKeyVisible }) {
                            Icon(
                                imageVector = if (isKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (isKeyVisible) "Hide API key" else "Show API key",
                                tint = tl.textMuted
                            )
                        }
                    }
                )
                Row(
                    modifier = Modifier
                        .clickable {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://my.opencellid.org/register"))
                                context.startActivity(intent)
                            } catch (e: ActivityNotFoundException) {
                                Toast.makeText(context, "No browser available to open the link", Toast.LENGTH_SHORT).show()
                            }
                        }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Get a free API key at opencellid.org",
                        style = MaterialTheme.typography.bodySmall,
                        color = tl.sky,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.OpenInNew,
                        contentDescription = "Open OpenCelliD sign-up page",
                        tint = tl.sky,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }

        // Gemini AI Integration Section
        Card(
            modifier = Modifier.fillMaxWidth().testTag("gemini_integration_card"),
            colors = CardDefaults.cardColors(containerColor = tl.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Gemini AI Signal Auditor",
                        style = MaterialTheme.typography.titleSmall,
                        color = tl.textPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    val isGeminiConfigured = geminiApiKey.isNotBlank()
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (isGeminiConfigured) tl.emerald.copy(alpha = 0.18f)
                                else tl.textMuted.copy(alpha = 0.18f)
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (isGeminiConfigured) "CONFIGURED" else "OPTIONAL",
                            color = if (isGeminiConfigured) tl.emerald else tl.textSecondary,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Text(
                    text = "Provide low-latency cellular signal audits and security anomaly warnings powered by Gemini 3.1 Flash-Lite.",
                    style = MaterialTheme.typography.bodySmall,
                    color = tl.textSecondary
                )
                TextField(
                    value = geminiApiKey,
                    onValueChange = {
                        geminiApiKey = it
                        prefs.edit().putString("gemini_api_key", it).apply()
                    },
                    placeholder = { Text("Enter Gemini API Key (Optional)") },
                    modifier = Modifier.fillMaxWidth().testTag("gemini_api_key_input"),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = tl.background,
                        unfocusedContainerColor = tl.background,
                        focusedTextColor = tl.textPrimary,
                        unfocusedTextColor = tl.textPrimary,
                        focusedIndicatorColor = tl.emerald,
                        unfocusedIndicatorColor = tl.outline,
                        cursorColor = tl.emerald
                    ),
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                    visualTransformation = if (isGeminiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { isGeminiKeyVisible = !isGeminiKeyVisible }) {
                            Icon(
                                imageVector = if (isGeminiKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (isGeminiKeyVisible) "Hide Gemini API key" else "Show Gemini API key",
                                tint = tl.textMuted
                            )
                        }
                    }
                )
                Row(
                    modifier = Modifier
                        .clickable {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/"))
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                Toast.makeText(context, "No browser available to open the link", Toast.LENGTH_SHORT).show()
                            }
                        }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Get a free Gemini API key from Google AI Studio",
                        style = MaterialTheme.typography.bodySmall,
                        color = tl.sky,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.OpenInNew,
                        contentDescription = "Open Google AI Studio website",
                        tint = tl.sky,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }

        // Telemetry Data Source Section
        Card(
            modifier = Modifier.fillMaxWidth().testTag("telemetry_data_source_card"),
            colors = CardDefaults.cardColors(containerColor = tl.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Telemetry Data Source",
                    style = MaterialTheme.typography.titleSmall,
                    color = tl.textPrimary,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "Switch between real hardware sensor telemetry and virtual simulated cell tower sweeps.",
                    style = MaterialTheme.typography.bodySmall,
                    color = tl.textSecondary
                )

                var simulationEnabled by remember { mutableStateOf(prefs.getBoolean("enable_simulation", false)) }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Enable Simulation Mode",
                        style = MaterialTheme.typography.bodyMedium,
                        color = tl.textPrimary
                    )
                    Switch(
                        checked = simulationEnabled,
                        onCheckedChange = {
                            simulationEnabled = it
                            prefs.edit().putBoolean("enable_simulation", it).apply()
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = tl.onAccent,
                            checkedTrackColor = tl.emerald
                        ),
                        modifier = Modifier.testTag("enable_simulation_switch")
                    )
                }
            }
        }

        // Drive Mode Section
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = tl.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Drive Mode — Tower Discovery",
                    style = MaterialTheme.typography.titleSmall,
                    color = tl.textPrimary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Samples continuously while you drive, logs every serving cell with GPS, and " +
                            "notifies you the first time you connect to a tower you've never seen before. " +
                            "Overrides batched polling while enabled, so expect higher battery use.",
                    style = MaterialTheme.typography.bodySmall,
                    color = tl.textSecondary
                )

                var driveMode by remember { mutableStateOf(prefs.getBoolean("drive_mode", false)) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Enable Drive Mode",
                        style = MaterialTheme.typography.bodyMedium,
                        color = tl.textPrimary
                    )
                    Switch(
                        checked = driveMode,
                        onCheckedChange = {
                            driveMode = it
                            prefs.edit().putBoolean("drive_mode", it).apply()
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = tl.onAccent,
                            checkedTrackColor = tl.emerald
                        )
                    )
                }
            }
        }

        // Config Params Section
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = tl.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Hardware Resolution",
                    style = MaterialTheme.typography.titleSmall,
                    color = tl.textPrimary,
                    fontWeight = FontWeight.Bold
                )

                // Polling Interval
                Column {
                    Text(
                        text = "Polling Interval: $pollInterval seconds",
                        style = MaterialTheme.typography.bodyMedium,
                        color = tl.textPrimary
                    )
                    Slider(
                        value = pollInterval.toFloat(),
                        onValueChange = {
                            pollInterval = it.toInt()
                            prefs.edit().putInt("poll_interval", pollInterval).apply()
                            onSavePollInterval(pollInterval)
                        },
                        valueRange = 1f..10f,
                        colors = SliderDefaults.colors(
                            thumbColor = tl.emerald,
                            activeTrackColor = tl.emerald,
                            inactiveTrackColor = tl.surfaceVariant
                        )
                    )
                }

                HorizontalDivider(color = tl.surfaceVariant)

                // gNB Bits
                Column {
                    Text(
                        text = "5G gNodeB Decode Mask: $gnbBits bits",
                        style = MaterialTheme.typography.bodyMedium,
                        color = tl.textPrimary
                    )
                    Text(
                        text = "Standard US Carriers (T-Mobile/Verizon) typically use 24 bits. AT&T can fluctuate.",
                        style = MaterialTheme.typography.bodySmall,
                        color = tl.textSecondary
                    )
                    Slider(
                        value = gnbBits.toFloat(),
                        onValueChange = {
                            gnbBits = it.toInt()
                            prefs.edit().putInt("gnb_bits", gnbBits).apply()
                            onSaveGnbBitLength(gnbBits)
                        },
                        valueRange = 20f..28f,
                        colors = SliderDefaults.colors(
                            thumbColor = tl.sky,
                            activeTrackColor = tl.sky,
                            inactiveTrackColor = tl.surfaceVariant
                        )
                    )
                }
            }
        }

        // Battery Optimization Section
        Card(
            modifier = Modifier.fillMaxWidth().testTag("battery_optimization_card"),
            colors = CardDefaults.cardColors(containerColor = tl.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Power & Battery Optimization",
                    style = MaterialTheme.typography.titleSmall,
                    color = tl.textPrimary,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "Batch polling mode wakes up sensors periodically to take a burst of cell tower measurements, reducing continuous CPU and GPS battery drain.",
                    style = MaterialTheme.typography.bodySmall,
                    color = tl.textSecondary
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Enable Polling Batching",
                        style = MaterialTheme.typography.bodyMedium,
                        color = tl.textPrimary
                    )
                    Switch(
                        checked = batchingEnabled,
                        onCheckedChange = {
                            batchingEnabled = it
                            prefs.edit().putBoolean("batching_enabled", it).apply()
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = tl.onAccent,
                            checkedTrackColor = tl.emerald
                        ),
                        modifier = Modifier.testTag("batching_enabled_switch")
                    )
                }

                if (batchingEnabled) {
                    HorizontalDivider(color = tl.surfaceVariant)

                    Column {
                        Text(
                            text = "Batch Wake-up Interval: $batchInterval seconds",
                            style = MaterialTheme.typography.bodyMedium,
                            color = tl.textPrimary
                        )
                        Slider(
                            value = batchInterval.toFloat(),
                            onValueChange = {
                                batchInterval = it.toInt()
                                prefs.edit().putInt("batch_interval", batchInterval).apply()
                            },
                            valueRange = 30f..300f,
                            colors = SliderDefaults.colors(
                                thumbColor = tl.emerald,
                                activeTrackColor = tl.emerald,
                                inactiveTrackColor = tl.surfaceVariant
                            ),
                            modifier = Modifier.testTag("batch_interval_slider")
                        )
                    }
                }
            }
        }

        // Notification Icon Section
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = tl.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Status-Bar Icon Style",
                    style = MaterialTheme.typography.titleSmall,
                    color = tl.textPrimary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Choose what is drawn in real-time onto the small foreground notification icon.",
                    style = MaterialTheme.typography.bodySmall,
                    color = tl.textSecondary
                )

                val styles = listOf("dBm", "band", "tech")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    styles.forEach { style ->
                        val selected = selectedIconStyle == style
                        Button(
                            onClick = {
                                selectedIconStyle = style
                                prefs.edit().putString("icon_style", style).apply()
                                onSaveIconStyle(style)
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (selected) tl.emerald else tl.surfaceVariant,
                                contentColor = if (selected) tl.onAccent else tl.textPrimary
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(style.uppercase(), fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // Alerts Rules Engine
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = tl.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Notification Alert Rules",
                    style = MaterialTheme.typography.titleSmall,
                    color = tl.textPrimary,
                    fontWeight = FontWeight.Bold
                )

                AlertSwitchRow(
                    label = "Alert on Band Handover",
                    state = alertOnBand,
                    testTag = "alert_on_band_switch"
                ) {
                    alertOnBand = it
                    prefs.edit().putBoolean("alert_band", it).apply()
                }
                AlertSwitchRow(
                    label = "Alert on Network Shift (SA/NSA)",
                    state = alertOnTech,
                    testTag = "alert_on_tech_switch"
                ) {
                    alertOnTech = it
                    prefs.edit().putBoolean("alert_tech", it).apply()
                }
                AlertSwitchRow(
                    label = "Alert on Critical Signal (< -110 dBm)",
                    state = alertOnRsrp,
                    testTag = "alert_on_rsrp_switch"
                ) {
                    alertOnRsrp = it
                    prefs.edit().putBoolean("alert_rsrp", it).apply()
                }
                AlertSwitchRow(
                    label = "Alert on Unmapped Tower Connect",
                    state = alertOnUnmapped,
                    testTag = "alert_on_unmapped_switch"
                ) {
                    alertOnUnmapped = it
                    prefs.edit().putBoolean("alert_unmapped", it).apply()
                }
                AlertSwitchRow(
                    label = "Alert on 5G SA to LTE Drop",
                    state = alertOn5gDrop,
                    testTag = "alert_on_5g_drop_switch"
                ) {
                    alertOn5gDrop = it
                    prefs.edit().putBoolean("alert_5g_drop", it).apply()
                }
                AlertSwitchRow(
                    label = "Log 5G Drop Coordinates",
                    state = logDropCoords,
                    testTag = "log_drop_coords_switch"
                ) {
                    logDropCoords = it
                    prefs.edit().putBoolean("log_drop_coords", it).apply()
                }
                AlertSwitchRow(
                    label = "Enable Haptic Vibration",
                    state = alertVibrate,
                    testTag = "alert_vibrate_switch"
                ) {
                    alertVibrate = it
                    prefs.edit().putBoolean("alert_vibrate", it).apply()
                }
            }
        }

        // Database Backup & Restore
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = tl.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Data Management",
                    style = MaterialTheme.typography.titleSmall,
                    color = tl.textPrimary,
                    fontWeight = FontWeight.Bold
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = onBackupDb,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = tl.surfaceVariant,
                            contentColor = tl.textPrimary
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Save, contentDescription = "Backup")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Backup DB")
                    }

                    Button(
                        onClick = onRestoreDb,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = tl.outline,
                            contentColor = tl.textPrimary
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Restore, contentDescription = "Restore")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Restore DB")
                    }
                }

                HorizontalDivider(color = tl.surfaceVariant)

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Bulk Tower Import",
                        style = MaterialTheme.typography.bodyMedium,
                        color = tl.textPrimary
                    )
                    Text(
                        text = "Import a tower CSV — including full OpenCelliD regional extracts " +
                                "(.csv or .csv.gz) — so towers resolve locally without an API lookup. " +
                                "Download the MCC 310 extract from opencellid.org (free account required).",
                        style = MaterialTheme.typography.bodySmall,
                        color = tl.textSecondary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Keep only T-Mobile (310-260) in Florida",
                            style = MaterialTheme.typography.bodyMedium,
                            color = tl.textPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = importFloridaOnly,
                            onCheckedChange = {
                                importFloridaOnly = it
                                prefs.edit().putBoolean("import_filter_fl", it).apply()
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = tl.onAccent,
                                checkedTrackColor = tl.emerald
                            )
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Button(
                        onClick = { csvImportLauncher.launch("*/*") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = tl.emerald,
                            contentColor = tl.onAccent
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(imageVector = Icons.Default.FileUpload, contentDescription = "Import CSV")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Import Tower CSV")
                    }
                    importStatus?.let { status ->
                        Text(
                            text = status,
                            style = MaterialTheme.typography.bodySmall,
                            color = tl.sky,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                HorizontalDivider(color = tl.surfaceVariant)

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Export & Share",
                        style = MaterialTheme.typography.bodyMedium,
                        color = tl.textPrimary
                    )
                    Text(
                        text = "Tower database as KML (opens in Google Earth) or CSV, and speed-test " +
                                "history as CSV. Log exports live on the Logs tab.",
                        style = MaterialTheme.typography.bodySmall,
                        color = tl.textSecondary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            "Towers KML" to "towers_kml",
                            "Towers CSV" to "towers_csv",
                            "Speed CSV" to "speed_csv"
                        ).forEach { (label, kind) ->
                            Button(
                                onClick = { onExport(kind) },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = tl.surfaceVariant,
                                    contentColor = tl.textPrimary
                                ),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(label, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AlertSwitchRow(
    label: String,
    state: Boolean,
    testTag: String? = null,
    onCheckedChange: (Boolean) -> Unit
) {
    val tl = TlTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, color = tl.textPrimary, style = MaterialTheme.typography.bodyMedium)
        Switch(
            checked = state,
            onCheckedChange = onCheckedChange,
            modifier = if (testTag != null) Modifier.testTag(testTag) else Modifier,
            colors = SwitchDefaults.colors(
                checkedThumbColor = tl.onAccent,
                checkedTrackColor = tl.emerald
            )
        )
    }
}
