package com.example.analysis

import com.example.data.CellLog
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Aggregated view of everything the user's own logs say about one physical tower. */
data class TowerSummary(
    val nodebId: Long,
    val observationCount: Int,
    val firstSeen: Long,
    val lastSeen: Long,
    val techs: List<String>,
    val bands: List<Pair<String, Int>>, // band label -> samples, most-seen first
    val sectors: List<Pair<Int, Int>>,  // sectorId -> samples, most-seen first
    val avgRsrp: Int,
    val bestRsrp: Int
)

/** Estimated antenna azimuth of one sector, derived from where it served the user. */
data class SectorBearing(
    val sectorId: Int,
    val bearingDegrees: Int,
    val compass: String,
    val samples: Int
)

object TowerObservations {

    data class Observation(
        val lat: Double,
        val lon: Double,
        val rsrp: Int,
        val sectorId: Int,
        val distanceMeters: Double? = null
    )

    fun summarize(logs: List<CellLog>): TowerSummary? {
        val valid = logs.filter { it.nodebId > 0 }
        if (valid.isEmpty()) return null
        val rsrps = valid.map { it.rsrp }.filter { it > -139 }
        return TowerSummary(
            nodebId = valid.first().nodebId,
            observationCount = valid.size,
            firstSeen = valid.minOf { it.timestamp },
            lastSeen = valid.maxOf { it.timestamp },
            techs = valid.map { it.tech }.distinct(),
            bands = valid.groupingBy { it.band }.eachCount()
                .entries.sortedByDescending { it.value }.map { it.key to it.value },
            sectors = valid.filter { it.sectorId > 0 }.groupingBy { it.sectorId }.eachCount()
                .entries.sortedByDescending { it.value }.map { it.key to it.value },
            avgRsrp = if (rsrps.isEmpty()) -140 else rsrps.average().roundToInt(),
            bestRsrp = rsrps.maxOrNull() ?: -140
        )
    }

    /**
     * Refines a tower position from observation points. Starts from an
     * RSRP-weighted centroid (stronger signal pulls harder), then, when
     * timing-advance distance estimates exist for 3+ points, runs Gauss-Newton
     * circular trilateration on a local flat-earth projection.
     */
    fun refinePosition(
        points: List<Observation>,
        initialLat: Double? = null,
        initialLon: Double? = null
    ): Pair<Double, Double>? {
        val usable = points.filter { it.lat in -90.0..90.0 && it.lon in -180.0..180.0 && (it.lat != 0.0 || it.lon != 0.0) }
        if (usable.isEmpty()) return null

        var weightSum = 0.0
        var latSum = 0.0
        var lonSum = 0.0
        usable.forEach {
            val weight = ((it.rsrp + 125).toDouble() / 60.0).coerceIn(0.05, 1.0)
            weightSum += weight
            latSum += it.lat * weight
            lonSum += it.lon * weight
        }
        var lat = initialLat ?: (latSum / weightSum)
        var lon = initialLon ?: (lonSum / weightSum)

        val ranged = usable.filter { (it.distanceMeters ?: 0.0) > 0.0 }
        if (ranged.size >= 3) {
            val lat0 = lat
            val lon0 = lon
            val mPerDegLat = 110_574.0
            val mPerDegLon = 111_320.0 * cos(Math.toRadians(lat0))
            if (mPerDegLon > 1.0) {
                var x = (lon - lon0) * mPerDegLon
                var y = (lat - lat0) * mPerDegLat
                for (iteration in 0 until 20) {
                    var jtj00 = 0.0
                    var jtj01 = 0.0
                    var jtj11 = 0.0
                    var jtr0 = 0.0
                    var jtr1 = 0.0
                    ranged.forEach { o ->
                        val ox = (o.lon - lon0) * mPerDegLon
                        val oy = (o.lat - lat0) * mPerDegLat
                        val dx = x - ox
                        val dy = y - oy
                        val dist = sqrt(dx * dx + dy * dy).coerceAtLeast(1.0)
                        val residual = dist - (o.distanceMeters ?: 0.0)
                        val jx = dx / dist
                        val jy = dy / dist
                        jtj00 += jx * jx
                        jtj01 += jx * jy
                        jtj11 += jy * jy
                        jtr0 += jx * residual
                        jtr1 += jy * residual
                    }
                    val det = jtj00 * jtj11 - jtj01 * jtj01
                    if (abs(det) < 1e-9) break
                    x -= (jtj11 * jtr0 - jtj01 * jtr1) / det
                    y -= (jtj00 * jtr1 - jtj01 * jtr0) / det
                }
                lat = lat0 + y / mPerDegLat
                lon = lon0 + x / mPerDegLon
            }
        }
        return lat to lon
    }

    /**
     * Per-sector antenna azimuth estimate: the circular mean of bearings from the
     * tower to the points where that sector was serving. A sector's antenna faces
     * the users it serves, so with spread-out observations this converges on the
     * true azimuth.
     */
    fun sectorBearings(
        towerLat: Double,
        towerLon: Double,
        points: List<Observation>
    ): List<SectorBearing> {
        return points
            .filter { it.sectorId > 0 && (it.lat != 0.0 || it.lon != 0.0) }
            .groupBy { it.sectorId }
            .map { (sector, obs) ->
                var sinSum = 0.0
                var cosSum = 0.0
                obs.forEach {
                    val b = Math.toRadians(bearing(towerLat, towerLon, it.lat, it.lon))
                    sinSum += sin(b)
                    cosSum += cos(b)
                }
                val mean = (Math.toDegrees(atan2(sinSum, cosSum)) + 360.0) % 360.0
                SectorBearing(
                    sectorId = sector,
                    bearingDegrees = mean.roundToInt() % 360,
                    compass = compassLabel(mean),
                    samples = obs.size
                )
            }
            .sortedBy { it.sectorId }
    }

    /** Initial great-circle bearing in degrees from point 1 to point 2. */
    fun bearing(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Double {
        val lat1 = Math.toRadians(fromLat)
        val lat2 = Math.toRadians(toLat)
        val dLon = Math.toRadians(toLon - fromLon)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** Haversine distance in meters. */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }

    fun compassLabel(degrees: Double): String {
        val dirs = listOf(
            "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
            "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"
        )
        return dirs[(((degrees + 11.25) / 22.5).toInt()) % 16]
    }
}
