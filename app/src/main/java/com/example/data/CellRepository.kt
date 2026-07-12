package com.example.data

import android.content.Context
import android.util.Log
import com.example.location.LocationTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

class CellRepository(
    private val cellDao: CellDao,
    private val context: Context
) {
    val allLogs: Flow<List<CellLog>> = cellDao.getAllLogs()
    val allTowers: Flow<List<TowerDbEntry>> = cellDao.getAllTowers()

    // Bounded timeouts so a stalled OpenCelliD response can never wedge the
    // monitoring loop that calls findTowerLocation on every poll.
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun insertLog(log: CellLog) = withContext(Dispatchers.IO) {
        cellDao.insertLog(log)
    }

    suspend fun insertLogs(logs: List<CellLog>) = withContext(Dispatchers.IO) {
        cellDao.insertLogs(logs)
    }

    suspend fun deleteLog(log: CellLog) = withContext(Dispatchers.IO) {
        cellDao.deleteLog(log)
    }

    suspend fun deleteLogById(id: Long) = withContext(Dispatchers.IO) {
        cellDao.deleteLogById(id)
    }

    suspend fun clearAllLogs() = withContext(Dispatchers.IO) {
        cellDao.clearAllLogs()
    }

    // Lookup Chain with Source Attribution Badge
    // Returns: Triple(lat, lon, range/accuracy) and Source String
    suspend fun findTowerLocation(
        tech: String,
        mcc: String,
        mnc: String,
        area: Int,
        cid: Long,
        openCellIdApiKey: String?,
        userLocation: android.location.Location? = null,
        distanceEstimateMeters: Double? = null
    ): TowerLocationResult = withContext(Dispatchers.IO) {
        // 1. Search local database
        val localTower = cellDao.findTower(mcc, mnc, area, cid)
        if (localTower != null) {
            // Backfill the address once, so we don't re-geocode this same tower on every poll
            val address = if (localTower.address.isNullOrBlank()) {
                val resolved = LocationTracker.reverseGeocode(context, localTower.lat, localTower.lon)
                cellDao.updateTowerAddress(mcc, mnc, area, cid, resolved)
                resolved
            } else {
                localTower.address
            }
            return@withContext TowerLocationResult(
                lat = localTower.lat,
                lon = localTower.lon,
                range = localTower.range,
                address = address,
                source = "Local Database"
            )
        }

        // 1b. Check if another sector of the same physical tower is already identified in local database
        val gnbBitLength = context.getSharedPreferences("TowerLockPrefs", Context.MODE_PRIVATE).getInt("gnb_bits", 24)
        val targetNodebId = if (tech.contains("5G")) {
            cid shr (36 - gnbBitLength)
        } else {
            cid shr 8
        }

        if (targetNodebId > 0) {
            val towersInArea = cellDao.findTowersInArea(mcc, mnc, area)
            val matchingTower = towersInArea.firstOrNull { entry ->
                val entryNodebId = if (entry.radio == "NR") {
                    entry.cid shr (36 - gnbBitLength)
                } else {
                    entry.cid shr 8
                }
                entryNodebId == targetNodebId
            }
            if (matchingTower != null) {
                // Backfill address for this specific sector if needed or use the matching tower's address
                val address = if (matchingTower.address.isNullOrBlank()) {
                    val resolved = LocationTracker.reverseGeocode(context, matchingTower.lat, matchingTower.lon)
                    cellDao.updateTowerAddress(matchingTower.mcc, matchingTower.mnc, matchingTower.area, matchingTower.cid, resolved)
                    resolved
                } else {
                    matchingTower.address
                }
                return@withContext TowerLocationResult(
                    lat = matchingTower.lat,
                    lon = matchingTower.lon,
                    range = matchingTower.range,
                    address = address,
                    source = "Local Database" // Return as Local Database so it's treated as identified/mapped
                )
            }
        }

        // 2. Search OpenCelliD API if key is available
        if (!openCellIdApiKey.isNullOrBlank()) {
            try {
                // OpenCelliD API uses LAC for area, cellid for cid. Built with
                // HttpUrl so the user-supplied key is always query-encoded.
                val url = HttpUrl.Builder()
                    .scheme("https")
                    .host("opencellid.org")
                    .addPathSegments("cell/get")
                    .addQueryParameter("key", openCellIdApiKey)
                    .addQueryParameter("mcc", mcc)
                    .addQueryParameter("mnc", mnc)
                    .addQueryParameter("lac", area.toString())
                    .addQueryParameter("cellid", cid.toString())
                    .addQueryParameter("format", "json")
                    .build()
                val request = Request.Builder().url(url).build()
                okHttpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (body != null) {
                            val json = JSONObject(body)
                            if (json.has("lat") && json.has("lon")) {
                                val lat = json.getDouble("lat")
                                val lon = json.getDouble("lon")
                                val range = json.optInt("range", 1000)
                                if (lat !in -90.0..90.0 || lon !in -180.0..180.0) {
                                    Log.w("CellRepository", "Discarding OpenCelliD result with invalid coordinates: $lat, $lon")
                                    return@use
                                }
                                val address = LocationTracker.reverseGeocode(context, lat, lon)

                                // Cache it in our local database, address included, so we
                                // never need to hit OpenCelliD or the geocoder for this cell again
                                val cachedTower = TowerDbEntry(
                                    radio = if (tech.contains("5G")) "NR" else "LTE",
                                    mcc = mcc,
                                    mnc = mnc,
                                    area = area,
                                    cid = cid,
                                    lat = lat,
                                    lon = lon,
                                    range = range,
                                    address = address
                                )
                                cellDao.insertTower(cachedTower)

                                return@withContext TowerLocationResult(
                                    lat = lat,
                                    lon = lon,
                                    range = range,
                                    address = address,
                                    source = "OpenCelliD API"
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("CellRepository", "OpenCelliD lookup failed", e)
            }
        }

        // 3. Smart Fallback: Estimate tower location near the user if userLocation is available
        if (userLocation != null) {
            // Generate a stable, deterministic bearing based on CID so the tower doesn't dance around
            val deterministicBearing = (cid % 360).toFloat()
            // Use provided distance estimate or default to 350 meters
            val distance = if (distanceEstimateMeters != null && distanceEstimateMeters > 0.0) {
                distanceEstimateMeters
            } else {
                350.0
            }
            
            // Calculate coordinates at distance and bearing from the user
            val earthRadius = 6371000.0 // meters
            val latRad = Math.toRadians(userLocation.latitude)
            val lonRad = Math.toRadians(userLocation.longitude)
            val bearingRad = Math.toRadians(deterministicBearing.toDouble())
            val angularDistance = distance / earthRadius

            val destLatRad = Math.asin(
                Math.sin(latRad) * Math.cos(angularDistance) +
                Math.cos(latRad) * Math.sin(angularDistance) * Math.cos(bearingRad)
            )
            val destLonRad = lonRad + Math.atan2(
                Math.sin(bearingRad) * Math.sin(angularDistance) * Math.cos(latRad),
                Math.cos(angularDistance) - Math.sin(latRad) * Math.sin(destLatRad)
            )

            val lat = Math.toDegrees(destLatRad)
            val lon = Math.toDegrees(destLonRad)
            
            val address = LocationTracker.reverseGeocode(context, lat, lon)
            
            return@withContext TowerLocationResult(
                lat = lat,
                lon = lon,
                range = distance.toInt(),
                address = address,
                source = "Estimated Fallback (TA/Signal)"
            )
        }

        // 4. Unmapped cell state
        return@withContext TowerLocationResult(
            lat = null,
            lon = null,
            range = 0,
            address = null,
            source = "Unmapped cell"
        )
    }

    suspend fun insertCustomTower(entry: TowerDbEntry) = withContext(Dispatchers.IO) {
        cellDao.insertTower(entry)
    }

    private data class CsvMapping(
        val radioIdx: Int = 0,
        val mccIdx: Int = 1,
        val mncIdx: Int = 2,
        val areaIdx: Int = 3,
        val cidIdx: Int = 4,
        val latIdx: Int = 5,
        val lonIdx: Int = 6,
        val rangeIdx: Int = 7,
        val addressIdx: Int = 8
    )

    /**
     * Splits a CSV line honoring double-quoted fields (RFC 4180), so addresses
     * containing commas don't shift every following column.
     */
    private fun splitCsvLine(line: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> {
                    current.append('"')
                    i++
                }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> {
                    fields.add(current.toString())
                    current.setLength(0)
                }
                else -> current.append(c)
            }
            i++
        }
        fields.add(current.toString())
        return fields
    }

    private fun detectMapping(firstLine: String, isHeader: Boolean): CsvMapping {
        val parts = splitCsvLine(firstLine)
        if (isHeader) {
            var radioIdx = 0
            var mccIdx = 1
            var mncIdx = 2
            var areaIdx = 3
            var cidIdx = 4
            var latIdx = 5
            var lonIdx = 6
            var rangeIdx = 7
            var addressIdx = 8

            parts.forEachIndexed { index, rawHeader ->
                val header = rawHeader.trim().lowercase()
                when {
                    header == "radio" || header == "tech" -> radioIdx = index
                    header == "mcc" -> mccIdx = index
                    header == "mnc" || header == "net" -> mncIdx = index
                    header == "area" || header == "lac" || header == "tac" -> areaIdx = index
                    header == "cell" || header == "cid" || header == "cellid" -> cidIdx = index
                    header == "lat" || header == "latitude" -> latIdx = index
                    header == "lon" || header == "lng" || header == "longitude" -> lonIdx = index
                    header == "range" -> rangeIdx = index
                    header == "address" -> addressIdx = index
                }
            }
            return CsvMapping(radioIdx, mccIdx, mncIdx, areaIdx, cidIdx, latIdx, lonIdx, rangeIdx, addressIdx)
        } else {
            if (parts.size >= 9) {
                val p5AsDouble = parts.getOrNull(5)?.trim()?.toDoubleOrNull()
                val p6AsDouble = parts.getOrNull(6)?.trim()?.toDoubleOrNull()
                val p7AsDouble = parts.getOrNull(7)?.trim()?.toDoubleOrNull()
                
                val looksLikeOpenCellId = p6AsDouble != null && p7AsDouble != null && 
                        p6AsDouble >= -180.0 && p6AsDouble <= 180.0 &&
                        p7AsDouble >= -90.0 && p7AsDouble <= 90.0 &&
                        (p5AsDouble == null || p5AsDouble.toInt().toDouble() == p5AsDouble)
                
                if (looksLikeOpenCellId) {
                    return CsvMapping(
                        radioIdx = 0,
                        mccIdx = 1,
                        mncIdx = 2,
                        areaIdx = 3,
                        cidIdx = 4,
                        latIdx = 7,
                        lonIdx = 6,
                        rangeIdx = 8,
                        addressIdx = -1
                    )
                }
            }
            return CsvMapping()
        }
    }

    // CSV Import: mcc, mnc, area, cid, lat, lon, range, address
    suspend fun importCsv(inputStream: InputStream): Int = withContext(Dispatchers.IO) {
        var importedCount = 0
        val entries = mutableListOf<TowerDbEntry>()
        try {
            BufferedReader(InputStreamReader(inputStream)).use { reader ->
                val firstLine = reader.readLine() ?: return@use
                val isHeader = firstLine.contains("mcc", ignoreCase = true) || 
                               firstLine.contains("radio", ignoreCase = true) || 
                               firstLine.contains("lat", ignoreCase = true)
                
                val mapping = detectMapping(firstLine, isHeader)
                
                if (!isHeader) {
                    parseCsvLine(firstLine, mapping)?.let { entries.add(it) }
                }
                
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val currentLine = line ?: continue
                    if (currentLine.isBlank()) continue
                    parseCsvLine(currentLine, mapping)?.let { entries.add(it) }
                    if (entries.size >= 100) {
                        cellDao.insertTowers(entries)
                        importedCount += entries.size
                        entries.clear()
                    }
                }
                if (entries.isNotEmpty()) {
                    cellDao.insertTowers(entries)
                    importedCount += entries.size
                }
            }
        } catch (e: Exception) {
            Log.e("CellRepository", "CSV Import failed", e)
        }
        importedCount
    }

    private fun parseCsvLine(line: String, mapping: CsvMapping): TowerDbEntry? {
        val parts = splitCsvLine(line)
        return try {
            val radio = parts.getOrNull(mapping.radioIdx)?.trim()?.takeIf { it.isNotBlank() } ?: return null
            val mcc = parts.getOrNull(mapping.mccIdx)?.trim()?.takeIf { it.isNotBlank() } ?: return null
            val mnc = parts.getOrNull(mapping.mncIdx)?.trim()?.takeIf { it.isNotBlank() } ?: return null
            val area = parts.getOrNull(mapping.areaIdx)?.trim()?.toIntOrNull() ?: return null
            val cid = parts.getOrNull(mapping.cidIdx)?.trim()?.toLongOrNull() ?: return null
            val lat = parts.getOrNull(mapping.latIdx)?.trim()?.toDoubleOrNull() ?: return null
            val lon = parts.getOrNull(mapping.lonIdx)?.trim()?.toDoubleOrNull() ?: return null
            // Reject rows whose coordinates can't be real; a single bad row
            // shouldn't plant a phantom tower on the map.
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
            val range = if (mapping.rangeIdx >= 0) {
                parts.getOrNull(mapping.rangeIdx)?.trim()?.toIntOrNull()?.coerceAtLeast(0) ?: 1000
            } else {
                1000
            }
            val address = if (mapping.addressIdx >= 0) {
                parts.getOrNull(mapping.addressIdx)?.trim()?.takeIf { it.isNotBlank() }
            } else {
                null
            }
            TowerDbEntry(
                radio = radio,
                mcc = mcc,
                mnc = mnc,
                area = area,
                cid = cid,
                lat = lat,
                lon = lon,
                range = range,
                address = address
            )
        } catch (e: Exception) {
            null
        }
    }
}

data class TowerLocationResult(
    val lat: Double?,
    val lon: Double?,
    val range: Int,
    val address: String?,
    val source: String
)
