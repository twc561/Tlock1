package com.example.telephony

/**
 * Rough downlink capacity estimation from the current carrier-aggregation
 * configuration. The bps/Hz factors assume typical US deployments (256QAM,
 * 2x2–4x4 MIMO, TDD downlink duty cycle on TDD bands); treat the result as a
 * theoretical ceiling, not a promise.
 */
object CapacityEstimator {

    data class Estimate(
        val totalMhz: Double,       // summed bandwidth of carriers whose BW is known
        val knownBwCarriers: Int,   // carriers contributing to totalMhz
        val unknownBwCarriers: Int, // carriers whose bandwidth was not reported
        val ceilingMbps: Int        // theoretical DL ceiling from known carriers
    )

    // TDD bands common in the US: NR n41 (2.5 GHz), n77/n78 (C-band), n79, n38, n40;
    // LTE B41 and the other 3.5 GHz/TDD allocations.
    private val nrTddBands = setOf(38, 40, 41, 77, 78, 79)
    private val lteTddBands = setOf(38, 39, 40, 41, 42, 43, 48)

    fun estimate(carriers: List<CarrierInfo>): Estimate {
        var totalKhz = 0L
        var known = 0
        var unknown = 0
        var mbps = 0.0
        carriers.forEach { carrier ->
            val isNr = carrier.band.trimStart().startsWith("n")
            val bandNumber = parseBandNumber(carrier.band)
            if (carrier.bandwidthKhz > 0) {
                known++
                totalKhz += carrier.bandwidthKhz
                mbps += (carrier.bandwidthKhz / 1000.0) * spectralEfficiency(isNr, bandNumber)
            } else {
                unknown++
            }
        }
        return Estimate(
            totalMhz = totalKhz / 1000.0,
            knownBwCarriers = known,
            unknownBwCarriers = unknown,
            ceilingMbps = mbps.toInt()
        )
    }

    /** Effective peak DL bps/Hz for one carrier, including TDD duty cycle. */
    private fun spectralEfficiency(isNr: Boolean, band: Int?): Double = when {
        isNr && band != null && band in nrTddBands -> 10.0 // mid-band TDD, 4x4 256QAM
        isNr -> 8.0                                        // FDD low/mid band
        band != null && band in lteTddBands -> 7.0
        else -> 8.0                                        // LTE FDD
    }

    /** Extracts 41 from "n41 (2.5 GHz Mid-Band)" or 66 from "B66 (...)"; null when absent. */
    fun parseBandNumber(label: String): Int? {
        val trimmed = label.trim()
        val digits = StringBuilder()
        var started = false
        for (ch in trimmed) {
            when {
                ch.isDigit() -> {
                    digits.append(ch)
                    started = true
                }
                started -> return digits.toString().toIntOrNull()
                ch == 'n' || ch == 'N' || ch == 'B' || ch == 'b' -> continue
                else -> return null
            }
        }
        return digits.toString().toIntOrNull()
    }
}
