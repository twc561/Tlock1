package com.example.telephony

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.*
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executor
import kotlin.random.Random

class TelephonyTracker(
    private val context: Context
) : ITelephonyTracker {

    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

    private val _cellState = MutableStateFlow(CellModel())
    override val cellState: StateFlow<CellModel> = _cellState.asStateFlow()

    private var scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var job: Job? = null

    private var pollIntervalSeconds = 3
    private var gnbBitLength = 24 // default 24-bit for T-Mobile 310-260

    // Callback objects for API 31+. The physical-channel listener is registered
    // separately from the others: it needs READ_PRECISE_PHONE_STATE (privileged),
    // and registerTelephonyCallback throws for the whole bundle if any listener
    // interface is not permitted.
    private var telephonyCallback: Any? = null
    private var physicalChannelCallback: Any? = null
    @Volatile
    private var physicalChannelConfigs: List<PhysicalChannelConfig> = emptyList()

    // Last network-reported state from TelephonyDisplayInfo, kept in fields so the
    // periodic poll rebuild doesn't erase it.
    @Volatile
    private var overrideTech: String? = null
    @Volatile
    private var networkIndicatedCa = false

    // Simulation fields
    private var isSimulationMode = true
    private var simMcc = "310"
    private var simMnc = "260"
    private var simOperator = "T-Mobile"
    private var simSectorIndex = 1
    private var simRsfphistory = mutableListOf<Int>()

    init {
        // Automatically detect if running in emulator or has no active SIM card
        isSimulationMode = checkIfSimulationRequired()
        if (isSimulationMode) {
            setupMockCellState()
        } else {
            _cellState.value = CellModel()
        }
    }

    private fun checkIfSimulationRequired(): Boolean {
        val prefs = context.getSharedPreferences("TowerLockPrefs", Context.MODE_PRIVATE)
        val simulationEnabled = prefs.getBoolean("enable_simulation", false)
        if (!simulationEnabled) {
            return false
        }
        if (Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.startsWith("unknown") ||
            Build.MODEL.contains("google_sdk") ||
            Build.MODEL.contains("Emulator") ||
            Build.MODEL.contains("Android SDK built for x86") ||
            Build.MANUFACTURER.contains("Genymotion") ||
            (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic"))
        ) {
            return true
        }
        // Also check if telephonyManager has no SIM card or service
        return telephonyManager.simState != TelephonyManager.SIM_STATE_READY
    }

    override fun setPollIntervalSeconds(seconds: Int) {
        pollIntervalSeconds = seconds.coerceIn(1, 10)
        Log.d("TelephonyTracker", "Poll interval updated to $pollIntervalSeconds s")
    }

    override fun setGnbBitLength(bits: Int) {
        gnbBitLength = bits.coerceIn(20, 28)
        Log.d("TelephonyTracker", "gNB bit length updated to $gnbBitLength bits")
    }

    override fun updateSimulatedCell(mockCell: CellModel) {
        _cellState.value = mockCell
    }

    override fun startMonitoring() {
        stopMonitoring()
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        isSimulationMode = checkIfSimulationRequired()

        // 1. Setup real listening if permissions are granted and not in absolute simulation mode
        if (!isSimulationMode && hasPermissions()) {
            registerRealListeners()
        }

        // 2. Start polling loop
        job = scope.launch {
            while (isActive) {
                val currentSimMode = checkIfSimulationRequired()
                if (currentSimMode != isSimulationMode) {
                    isSimulationMode = currentSimMode
                    if (isSimulationMode) {
                        unregisterRealListeners()
                    } else if (hasPermissions()) {
                        registerRealListeners()
                    }
                }

                if (isSimulationMode) {
                    runSimulationStep()
                } else {
                    pollRealTelephony()
                }
                delay(pollIntervalSeconds * 1000L)
            }
        }
    }

    override fun stopMonitoring() {
        job?.cancel()
        job = null
        unregisterRealListeners()
        scope.cancel()
    }

    private fun hasPermissions(): Boolean {
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val phone = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
        return coarse == PackageManager.PERMISSION_GRANTED &&
                fine == PackageManager.PERMISSION_GRANTED &&
                phone == PackageManager.PERMISSION_GRANTED
    }

    private fun registerRealListeners() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val callback = object : TelephonyCallback(),
                    TelephonyCallback.CellInfoListener,
                    TelephonyCallback.DisplayInfoListener,
                    TelephonyCallback.SignalStrengthsListener {

                    override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>) {
                        parseCellInfoList(cellInfo)
                    }

                    override fun onDisplayInfoChanged(telephonyDisplayInfo: TelephonyDisplayInfo) {
                        updateDisplayInfo(telephonyDisplayInfo)
                    }

                    override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                        updateSignalStrength(signalStrength)
                    }
                }
                telephonyManager.registerTelephonyCallback(context.mainExecutor, callback)
                telephonyCallback = callback

                // PhysicalChannelConfigListener requires READ_PRECISE_PHONE_STATE, which
                // is only grantable to privileged/carrier apps. Register it on its own so
                // the expected SecurityException doesn't take down the listeners above.
                try {
                    val pccCallback = object : TelephonyCallback(),
                        TelephonyCallback.PhysicalChannelConfigListener {
                        override fun onPhysicalChannelConfigChanged(configs: List<PhysicalChannelConfig>) {
                            updatePhysicalChannelConfigs(configs)
                        }
                    }
                    telephonyManager.registerTelephonyCallback(context.mainExecutor, pccCallback)
                    physicalChannelCallback = pccCallback
                } catch (e: SecurityException) {
                    Log.i(
                        "TelephonyTracker",
                        "PhysicalChannelConfig unavailable (READ_PRECISE_PHONE_STATE not held); " +
                                "CA will be detected via DisplayInfo/ServiceState fallbacks"
                    )
                }
            }
        } catch (e: SecurityException) {
            Log.e("TelephonyTracker", "Permission denied for telephony callback", e)
        } catch (e: Exception) {
            Log.e("TelephonyTracker", "Error registering telephony callback", e)
        }
    }

    private fun updatePhysicalChannelConfigs(configs: List<PhysicalChannelConfig>) {
        physicalChannelConfigs = configs
        Log.d("TelephonyTracker", "Physical Channel Configs changed: ${configs.size} items")
        // Trigger parsing with latest available configs
        pollRealTelephony()
    }

    private fun unregisterRealListeners() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(telephonyCallback, physicalChannelCallback).forEach { cb ->
                cb?.let {
                    try {
                        telephonyManager.unregisterTelephonyCallback(it as TelephonyCallback)
                    } catch (e: Exception) {
                        Log.e("TelephonyTracker", "Error unregistering callback", e)
                    }
                }
            }
            telephonyCallback = null
            physicalChannelCallback = null
        }
    }

    private fun pollRealTelephony() {
        if (!hasPermissions()) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                telephonyManager.requestCellInfoUpdate(context.mainExecutor, object : TelephonyManager.CellInfoCallback() {
                    override fun onCellInfo(cellInfoList: List<CellInfo>) {
                        if (!cellInfoList.isNullOrEmpty()) {
                            parseCellInfoList(cellInfoList)
                        } else {
                            if (checkIfSimulationRequired()) {
                                isSimulationMode = true
                            }
                        }
                    }
                })
            } else {
                @Suppress("DEPRECATION")
                val cellInfoList = telephonyManager.allCellInfo
                if (cellInfoList.isNullOrEmpty()) {
                    if (checkIfSimulationRequired()) {
                        isSimulationMode = true
                    }
                    return
                }
                parseCellInfoList(cellInfoList)
            }
        } catch (e: SecurityException) {
            Log.e("TelephonyTracker", "Permission error polling cell info", e)
        } catch (e: Exception) {
            Log.e("TelephonyTracker", "Error polling cell info", e)
        }
    }

    private fun parseCellInfoList(cellInfoList: List<CellInfo>) {
        if (cellInfoList.isEmpty()) return
        
        // Find registered/serving cell
        val servingCell = cellInfoList.find { it.isRegistered } ?: cellInfoList.first()
        val neighborsList = cellInfoList.filter { !it.isRegistered }

        val builder = CellModelBuilder()
        builder.operatorName = telephonyManager.networkOperatorName.ifBlank { "Carrier" }

        // Decode technology and identity
        when (servingCell) {
            is CellInfoLte -> {
                val id = servingCell.cellIdentity
                val sig = servingCell.cellSignalStrength

                builder.tech = "4G LTE"
                builder.mcc = id.mccString ?: "310"
                builder.mnc = id.mncString ?: "260"
                builder.tac = sanitizeInt(id.tac)
                builder.cellId = sanitizeLong(id.ci.toLong())
                builder.pci = sanitizeInt(id.pci)
                builder.arfcn = sanitizeInt(id.earfcn)

                // Decode eNB and sector for LTE (28-bit Cell Identity)
                if (builder.cellId > 0 && builder.cellId != 2147483647L) {
                    builder.nodebId = builder.cellId shr 8
                    builder.sectorId = (builder.cellId and 0xFF).toInt()
                }

                builder.rsrp = sanitizeSignal(sig.rsrp)
                builder.rsrq = sanitizeSignal(sig.rsrq)
                builder.sinr = sanitizeSignal(sig.rssnr) // for LTE, rssnr serves as SINR
                builder.timingAdvance = sanitizeInt(sig.timingAdvance)

                val bandData = BandFrequencyMapper.decodeLteEarfcn(builder.arfcn)
                builder.bandName = bandData.first
                builder.frequencyMhz = bandData.second
                builder.bandwidthKhz = sanitizeInt(id.bandwidth)
            }
            is CellInfoNr -> {
                val id = servingCell.cellIdentity as CellIdentityNr
                val sig = servingCell.cellSignalStrength as CellSignalStrengthNr

                builder.tech = "5G SA"
                builder.mcc = id.mccString ?: "310"
                builder.mnc = id.mncString ?: "260"
                builder.tac = sanitizeInt(id.tac)
                builder.cellId = sanitizeLong(id.nci)
                builder.pci = sanitizeInt(id.pci)
                builder.arfcn = sanitizeInt(id.nrarfcn)

                // Decode gNB and sector for 5G NR (36-bit Cell Identity)
                if (builder.cellId > 0 && builder.cellId != 9223372036854775807L) {
                    val shiftBits = 36 - gnbBitLength
                    builder.nodebId = builder.cellId shr shiftBits
                    builder.sectorId = (builder.cellId and ((1L shl shiftBits) - 1)).toInt()
                }

                // Parse 5G NR Signal Strengths
                builder.ssRsrp = sanitizeSignal(sig.ssRsrp)
                builder.ssRsrq = sanitizeSignal(sig.ssRsrq)
                builder.ssSinr = sanitizeSignal(sig.ssSinr)
                builder.csiRsrp = sanitizeSignal(sig.csiRsrp)
                builder.csiRsrq = sanitizeSignal(sig.csiRsrq)
                builder.csiSinr = sanitizeSignal(sig.csiSinr)

                // Use SS-RSRP as primary metrics
                builder.rsrp = builder.ssRsrp
                builder.rsrq = builder.ssRsrq
                builder.sinr = builder.ssSinr

                // 5G Timing Advance if available (added in SDK 31)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // For Nr we can query timing advance if available, else 0
                    builder.timingAdvance = 0
                }

                val bandData = BandFrequencyMapper.decodeNrArfcn(builder.arfcn)
                builder.bandName = bandData.first
                builder.frequencyMhz = bandData.second
            }
        }

        // Map neighbor cell table
        val neighbors = neighborsList.mapNotNull { cell ->
            when (cell) {
                is CellInfoLte -> {
                    val id = cell.cellIdentity
                    val sig = cell.cellSignalStrength
                    val bandData = BandFrequencyMapper.decodeLteEarfcn(id.earfcn)
                    NeighborCell(
                        pci = sanitizeInt(id.pci),
                        band = bandData.first,
                        arfcn = sanitizeInt(id.earfcn),
                        rsrp = sanitizeSignal(sig.rsrp),
                        tech = "LTE"
                    )
                }
                is CellInfoNr -> {
                    val id = cell.cellIdentity as CellIdentityNr
                    val sig = cell.cellSignalStrength as CellSignalStrengthNr
                    val bandData = BandFrequencyMapper.decodeNrArfcn(id.nrarfcn)
                    NeighborCell(
                        pci = sanitizeInt(id.pci),
                        band = bandData.first,
                        arfcn = sanitizeInt(id.nrarfcn),
                        rsrp = sanitizeSignal(sig.ssRsrp),
                        tech = "NR"
                    )
                }
                else -> null
            }
        }
        builder.neighbors = neighbors

        // Extract active carriers for Carrier Aggregation
        val activeCarriersList = mutableListOf<CarrierInfo>()
        val servingBandData = when (servingCell) {
            is CellInfoLte -> BandFrequencyMapper.decodeLteEarfcn(servingCell.cellIdentity.earfcn)
            is CellInfoNr -> BandFrequencyMapper.decodeNrArfcn((servingCell.cellIdentity as CellIdentityNr).nrarfcn)
            else -> Pair("Unknown", 0.0)
        }
        val servingArfcn = when (servingCell) {
            is CellInfoLte -> servingCell.cellIdentity.earfcn
            is CellInfoNr -> (servingCell.cellIdentity as CellIdentityNr).nrarfcn
            else -> 0
        }
        val servingRsrp = when (servingCell) {
            is CellInfoLte -> servingCell.cellSignalStrength.rsrp
            is CellInfoNr -> (servingCell.cellSignalStrength as CellSignalStrengthNr).ssRsrp
            else -> -140
        }
        activeCarriersList.add(
            CarrierInfo(
                band = servingBandData.first,
                arfcn = sanitizeInt(servingArfcn),
                rsrp = sanitizeSignal(servingRsrp),
                type = "PCC"
            )
        )

        // Capture secondary registered cells as SCCs if reported
        cellInfoList.filter { it.isRegistered && it != servingCell }.forEach { cell ->
            val bandData = when (cell) {
                is CellInfoLte -> BandFrequencyMapper.decodeLteEarfcn(cell.cellIdentity.earfcn)
                is CellInfoNr -> BandFrequencyMapper.decodeNrArfcn((cell.cellIdentity as CellIdentityNr).nrarfcn)
                else -> null
            }
            if (bandData != null) {
                val arfcn = when (cell) {
                    is CellInfoLte -> cell.cellIdentity.earfcn
                    is CellInfoNr -> (cell.cellIdentity as CellIdentityNr).nrarfcn
                    else -> 0
                }
                val rsrp = when (cell) {
                    is CellInfoLte -> cell.cellSignalStrength.rsrp
                    is CellInfoNr -> (cell.cellSignalStrength as CellSignalStrengthNr).ssRsrp
                    else -> -140
                }
                activeCarriersList.add(
                    CarrierInfo(
                        band = bandData.first,
                        arfcn = sanitizeInt(arfcn),
                        rsrp = sanitizeSignal(rsrp),
                        type = "SCC"
                    )
                )
            }
        }

        // 1. If we have PhysicalChannelConfigs on Android S+, use that first as it is the most precise!
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && physicalChannelConfigs.isNotEmpty()) {
            val carriers = physicalChannelConfigs.mapNotNull { config ->
                try {
                    val arfcn = config.downlinkChannelNumber
                    val isNr = (config.networkType == TelephonyManager.NETWORK_TYPE_NR)

                    val typeStr = if (config.connectionStatus == PhysicalChannelConfig.CONNECTION_PRIMARY_SERVING) {
                        "PCC"
                    } else {
                        "SCC"
                    }

                    // Prefer the band number the modem reports over the ARFCN heuristic
                    val decoded = if (isNr) {
                        BandFrequencyMapper.decodeNrArfcn(arfcn)
                    } else {
                        BandFrequencyMapper.decodeLteEarfcn(arfcn)
                    }
                    val bandStr = if (config.band > 0) {
                        val prefix = if (isNr) "n" else "B"
                        if (decoded.second > 0) {
                            "$prefix${config.band} (${decoded.second.toInt()} MHz)"
                        } else {
                            "$prefix${config.band}"
                        }
                    } else {
                        decoded.first
                    }

                    val matchedRsrp = if (typeStr == "PCC") {
                        servingRsrp
                    } else {
                        neighbors.find { it.arfcn == arfcn }?.rsrp ?: (servingRsrp - 8)
                    }
                    
                    CarrierInfo(
                        band = bandStr,
                        arfcn = sanitizeInt(arfcn),
                        rsrp = sanitizeSignal(matchedRsrp),
                        type = typeStr
                    )
                } catch (e: Exception) {
                    Log.e("TelephonyTracker", "Error parsing PhysicalChannelConfig", e)
                    null
                }
            }
            if (carriers.isNotEmpty()) {
                val sortedCarriers = carriers.sortedByDescending { it.type == "PCC" }
                activeCarriersList.clear()
                activeCarriersList.addAll(sortedCarriers)
            }
        }

        // 2. Fallback: PhysicalChannelConfig needs a privileged permission, so on most
        // devices CA has to be inferred from ServiceState.cellBandwidths, which holds one
        // entry per aggregated component carrier. Band/ARFCN/RSRP of the SCCs are not
        // exposed there, so report the real bandwidth and mark the rest as unreported
        // rather than inventing values.
        if (activeCarriersList.size == 1) {
            val bandwidths = getCellBandwidthsFromServiceState()
                ?.filter { it > 0 }
                .orEmpty()
            if (bandwidths.size > 1) {
                bandwidths.drop(1).forEach { bwKhz ->
                    activeCarriersList.add(
                        CarrierInfo(
                            band = "SCC ${bwKhz / 1000} MHz (band not reported)",
                            arfcn = 0,
                            rsrp = sanitizeSignal(servingRsrp),
                            type = "SCC"
                        )
                    )
                }
            }
        }

        builder.activeCarriers = activeCarriersList

        // Re-apply the last display-info override so the periodic poll rebuild doesn't
        // erase the network-reported NSA/CA state between events.
        overrideTech?.let { if (builder.tech == "4G LTE") builder.tech = it }
        builder.caIndicated = networkIndicatedCa

        // Update current state
        val updatedCell = builder.build()
        _cellState.value = updatedCell
    }

    private fun updateDisplayInfo(displayInfo: TelephonyDisplayInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val override = displayInfo.overrideNetworkType
            overrideTech = when (override) {
                TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA -> "5G NSA"
                TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED -> "5G NSA (Adv)"
                else -> null
            }
            // LTE_CA is the network's explicit "carrier aggregation active" flag
            // (deprecated on S+ in favor of PhysicalChannelConfig, but that needs a
            // privileged permission and LTE_CA is still delivered on most devices).
            // NR_ADVANCED likewise implies aggregated NR carriers.
            @Suppress("DEPRECATION")
            networkIndicatedCa = override == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA ||
                    override == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED

            val current = _cellState.value
            val newTech = overrideTech ?: current.tech
            if (newTech != current.tech || networkIndicatedCa != current.caIndicated) {
                _cellState.value = current.copy(tech = newTech, caIndicated = networkIndicatedCa)
            }
        }
    }

    private fun updateSignalStrength(signalStrength: SignalStrength) {
        // Fallback or supplementary signal metrics
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cellSignalStrengths = signalStrength.cellSignalStrengths
            if (cellSignalStrengths.isNotEmpty()) {
                val current = _cellState.value
                val primarySig = cellSignalStrengths.first()
                val dbm = primarySig.dbm
                if (dbm != CellInfo.UNAVAILABLE && dbm != current.rsrp) {
                    _cellState.value = current.copy(rsrp = dbm)
                }
            }
        }
    }

    private fun getCellBandwidthsFromServiceState(): IntArray? {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val serviceState = telephonyManager.serviceState
                if (serviceState != null) {
                    return serviceState.cellBandwidths
                }
            }
        } catch (e: SecurityException) {
            Log.e("TelephonyTracker", "Permission error getting ServiceState", e)
        } catch (e: Exception) {
            Log.e("TelephonyTracker", "Error getting ServiceState cellBandwidths", e)
        }
        return null
    }

    // Helper functions to handle OEM sentinels
    private fun sanitizeInt(value: Int): Int {
        return if (value == CellInfo.UNAVAILABLE || value == 2147483647) 0 else value
    }

    private fun sanitizeLong(value: Long): Long {
        return if (value == CellInfo.UNAVAILABLE_LONG || value == Long.MAX_VALUE) 0L else value
    }

    private fun sanitizeSignal(value: Int): Int {
        return if (value == CellInfo.UNAVAILABLE || value == 2147483647 || value == -2147483648) -140 else value
    }

    // --- SIMULATED CELLS ENGINE ---

    private fun setupMockCellState() {
        val defaultModel = CellModel(
            tech = "5G SA",
            mcc = simMcc,
            mnc = simMnc,
            operatorName = simOperator,
            cellId = 1234567,
            nodebId = 88888,
            sectorId = 1,
            pci = 412,
            tac = 1024,
            arfcn = 126800,
            bandName = "n71 (600 MHz)",
            frequencyMhz = 622.0,
            bandwidthKhz = 20000,
            rsrp = -82,
            rsrq = -11,
            sinr = 18,
            ssRsrp = -82,
            ssRsrq = -11,
            ssSinr = 18,
            timingAdvance = 4,
            distanceEstimateMeters = 36.96, // 4 * 9.24
            signalGrade = "Excellent",
            signalGradeColorHex = 0xFF4CAF50,
            description = "High speed primary cell connection with superb signal quality."
        )
        _cellState.value = defaultModel
    }

    private fun runSimulationStep() {
        val current = _cellState.value
        val random = Random(System.currentTimeMillis())

        // Periodically hand over sector or band
        var tech = current.tech
        var bandName = current.bandName
        var arfcn = current.arfcn
        var cellId = current.cellId
        var nodebId = current.nodebId
        var sectorId = current.sectorId
        var pci = current.pci
        var tac = current.tac
        var timingAdvance = current.timingAdvance

        // 1 in 10 chance of sector handover or technology change
        if (random.nextInt(10) == 0) {
            simSectorIndex = (simSectorIndex % 3) + 1
            sectorId = simSectorIndex
            pci = (pci + 1) % 500
            cellId = (nodebId shl (36 - gnbBitLength)) + sectorId
            
            // Randomly switch bands and technologies to simulate real T-Mobile deployments
            if (random.nextBoolean()) {
                val techSelect = random.nextInt(3)
                if (techSelect == 0) {
                    tech = "5G SA"
                    bandName = "n41 (2.5 GHz Mid-Band)"
                    arfcn = 518000
                    tac = 1024
                    timingAdvance = random.nextInt(2, 6)
                } else if (techSelect == 1) {
                    tech = "5G NSA"
                    bandName = "B66 (1700/2100 MHz AWS-3)"
                    arfcn = 66436
                    tac = 24500
                    timingAdvance = random.nextInt(3, 8)
                } else {
                    tech = "4G LTE"
                    bandName = "B66 (1700/2100 MHz AWS-3)"
                    arfcn = 66436
                    tac = 24500
                    timingAdvance = random.nextInt(4, 12)
                }
            } else {
                tech = "5G SA"
                bandName = "n71 (600 MHz)"
                arfcn = 126800
                tac = 1024
                timingAdvance = random.nextInt(1, 4)
            }
        }

        // Fluctuate signal values realistically
        val deltaRsrp = random.nextInt(-3, 4)
        val rsrp = (current.rsrp + deltaRsrp).coerceIn(-120, -50)
        
        val deltaRsrq = random.nextInt(-2, 3)
        val rsrq = (current.rsrq + deltaRsrq).coerceIn(-20, -6)

        val deltaSinr = random.nextInt(-3, 4)
        val sinr = (current.sinr + deltaSinr).coerceIn(-5, 30)

        // Calculate distance based on timing advance
        // 5G steps are ~9.24 meters, LTE is ~78.12 meters
        val distEst = if (tech.contains("5G")) {
            timingAdvance * 9.24
        } else {
            timingAdvance * 78.12
        }

        // Neighbors simulation
        val neighbors = listOf(
            NeighborCell(pci = (pci + 5) % 500, band = bandName, arfcn = arfcn, rsrp = rsrp - random.nextInt(6, 12), tech = if (tech.contains("5G")) "NR" else "LTE"),
            NeighborCell(pci = (pci + 12) % 500, band = bandName, arfcn = arfcn, rsrp = rsrp - random.nextInt(10, 18), tech = if (tech.contains("5G")) "NR" else "LTE"),
            NeighborCell(pci = (pci + 24) % 500, band = "n251 (mmWave)", arfcn = 2055000, rsrp = -115, tech = "NR")
        )

        // Active carriers (Carrier Aggregation)
        val activeCarriers = when {
            tech == "5G SA" -> {
                if (bandName.contains("n41")) {
                    // Simulate T-Mobile 4CC Ultra Capacity Carrier Aggregation: n41 (100MHz) + n41 (100MHz) + n25 (20MHz) + n71 (15MHz)
                    listOf(
                        CarrierInfo(band = "n41 (2.5 GHz Mid-Band - PCC)", arfcn = 518000, rsrp = rsrp, type = "PCC"),
                        CarrierInfo(band = "n41 (2.5 GHz Mid-Band - SCC)", arfcn = 522000, rsrp = rsrp - 4, type = "SCC"),
                        CarrierInfo(band = "n25 (1900 MHz PCS)", arfcn = 390000, rsrp = rsrp - 9, type = "SCC"),
                        CarrierInfo(band = "n71 (600 MHz)", arfcn = 126800, rsrp = rsrp - 5, type = "SCC")
                    )
                } else {
                    // Simulate T-Mobile 3CC Extended Range Carrier Aggregation: n71 (15MHz) + n41 (100MHz) + n25 (20MHz)
                    listOf(
                        CarrierInfo(band = "n71 (600 MHz - PCC)", arfcn = 126800, rsrp = rsrp, type = "PCC"),
                        CarrierInfo(band = "n41 (2.5 GHz Mid-Band)", arfcn = 518000, rsrp = rsrp - 7, type = "SCC"),
                        CarrierInfo(band = "n25 (1900 MHz PCS)", arfcn = 390000, rsrp = rsrp - 10, type = "SCC")
                    )
                }
            }
            tech == "5G NSA" -> {
                // Simulate ENDC Dual Connectivity: B66 (PCC LTE) + B2 (SCC LTE) + n41 (SCC 5G NR)
                listOf(
                    CarrierInfo(band = "B66 (1700/2100 MHz - PCC LTE)", arfcn = 66436, rsrp = rsrp, type = "PCC"),
                    CarrierInfo(band = "B2 (1900 MHz - SCC LTE)", arfcn = 900, rsrp = rsrp - 4, type = "SCC"),
                    CarrierInfo(band = "n41 (2.5 GHz - SCC 5G NR)", arfcn = 518000, rsrp = rsrp - 6, type = "SCC")
                )
            }
            else -> { // 4G LTE
                // Simulate T-Mobile LTE 4CC CA: B66 (20MHz) + B2 (20MHz) + B12 (5MHz) + B71 (10MHz)
                listOf(
                    CarrierInfo(band = "B66 (1700/2100 MHz - PCC)", arfcn = 66436, rsrp = rsrp, type = "PCC"),
                    CarrierInfo(band = "B2 (1900 MHz)", arfcn = 900, rsrp = rsrp - 5, type = "SCC"),
                    CarrierInfo(band = "B12 (700 MHz)", arfcn = 5010, rsrp = rsrp - 11, type = "SCC"),
                    CarrierInfo(band = "B71 (600 MHz)", arfcn = 68735, rsrp = rsrp - 8, type = "SCC")
                )
            }
        }

        // Signal grade and metrics description
        val (grade, color, desc) = evaluateSignalQuality(tech, rsrp, rsrq, sinr)

        _cellState.value = CellModel(
            tech = tech,
            mcc = simMcc,
            mnc = simMnc,
            operatorName = simOperator,
            cellId = cellId,
            nodebId = nodebId,
            sectorId = sectorId,
            pci = pci,
            tac = tac,
            arfcn = arfcn,
            bandName = bandName,
            frequencyMhz = if (arfcn == 518000) 2590.0 else if (arfcn == 66436) 2110.0 else 622.0,
            bandwidthKhz = 20000,
            rsrp = rsrp,
            rsrq = rsrq,
            sinr = sinr,
            ssRsrp = rsrp,
            ssRsrq = rsrq,
            ssSinr = sinr,
            timingAdvance = timingAdvance,
            distanceEstimateMeters = distEst,
            signalGrade = grade,
            signalGradeColorHex = color,
            description = desc,
            neighbors = neighbors,
            activeCarriers = activeCarriers
        )
    }

    private fun evaluateSignalQuality(tech: String, rsrp: Int, rsrq: Int, sinr: Int): Triple<String, Long, String> {
        return if (rsrp >= -80 && sinr >= 15) {
            Triple(
                "Excellent",
                0xFF4CAF50,
                "Strong link with negligible packet loss. Ideal for high-definition streaming, cloud gaming, and large downloads."
            )
        } else if (rsrp >= -95 && sinr >= 8) {
            Triple(
                "Good",
                0xFF8BC34A,
                "Stable connection with minimal latency. More than adequate for web browsing, audio calls, and regular usage."
            )
        } else if (rsrp >= -110 && sinr >= 2) {
            Triple(
                "Fair",
                0xFFFFB74D,
                "Acceptable quality, but may suffer from periodic speed drops or high latency under load. Recommended to move closer to a window."
            )
        } else {
            Triple(
                "Poor",
                0xFFE57373,
                "Highly degraded link. High risk of call drops, stalling, and significant packet corruption. Check for obstructions."
            )
        }
    }
}

class CellModelBuilder {
    var tech: String = "Unknown"
    var mcc: String = "310"
    var mnc: String = "260"
    var operatorName: String = "Carrier"
    var cellId: Long = 0L
    var nodebId: Long = 0L
    var sectorId: Int = 0
    var pci: Int = 0
    var tac: Int = 0
    var arfcn: Int = 0
    var bandName: String = "Unknown"
    var frequencyMhz: Double = 0.0
    var bandwidthKhz: Int = 0
    var rsrp: Int = -140
    var rsrq: Int = -20
    var sinr: Int = -10
    var ssRsrp: Int = -140
    var ssRsrq: Int = -20
    var ssSinr: Int = -10
    var csiRsrp: Int = -140
    var csiRsrq: Int = -20
    var csiSinr: Int = -10
    var timingAdvance: Int = 0
    var distanceEstimateMeters: Double = 0.0
    var neighbors: List<NeighborCell> = emptyList()
    var activeCarriers: List<CarrierInfo> = emptyList()
    var caIndicated: Boolean = false

    fun build(): CellModel {
        val (grade, color, desc) = when {
            rsrp >= -80 && sinr >= 15 -> Triple("Excellent", 0xFF4CAF50, "Strong link with negligible packet loss.")
            rsrp >= -95 && sinr >= 8 -> Triple("Good", 0xFF8BC34A, "Stable connection with minimal latency.")
            rsrp >= -110 && sinr >= 2 -> Triple("Fair", 0xFFFFB74D, "Acceptable quality, but may suffer from periodic speed drops.")
            else -> Triple("Poor", 0xFFE57373, "Highly degraded link. High risk of call drops.")
        }

        // Compute distance based on timing advance
        val distEst = if (tech.contains("5G")) {
            timingAdvance * 9.24
        } else {
            timingAdvance * 78.12
        }

        return CellModel(
            tech = tech,
            mcc = mcc,
            mnc = mnc,
            operatorName = operatorName,
            cellId = cellId,
            nodebId = nodebId,
            sectorId = sectorId,
            pci = pci,
            tac = tac,
            arfcn = arfcn,
            bandName = bandName,
            frequencyMhz = frequencyMhz,
            bandwidthKhz = bandwidthKhz,
            rsrp = rsrp,
            rsrq = rsrq,
            sinr = sinr,
            ssRsrp = ssRsrp,
            ssRsrq = ssRsrq,
            ssSinr = ssSinr,
            csiRsrp = csiRsrp,
            csiRsrq = csiRsrq,
            csiSinr = csiSinr,
            timingAdvance = timingAdvance,
            distanceEstimateMeters = distEst,
            signalGrade = grade,
            signalGradeColorHex = color,
            description = desc,
            neighbors = neighbors,
            activeCarriers = activeCarriers.ifEmpty { listOf(CarrierInfo(band = bandName, arfcn = arfcn, rsrp = rsrp, type = "PCC")) },
            caIndicated = caIndicated
        )
    }
}
