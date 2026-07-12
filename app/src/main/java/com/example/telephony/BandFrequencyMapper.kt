package com.example.telephony

object BandFrequencyMapper {

    /**
     * Decode LTE EARFCN to Band Name and Center Frequency in MHz
     */
    fun decodeLteEarfcn(earfcn: Int): Pair<String, Double> {
        return when (earfcn) {
            in 0..599 -> Pair("B1 (2100 MHz)", 2140.0 + 0.1 * (earfcn - 0))
            in 600..1199 -> Pair("B2 (1900 MHz)", 1930.0 + 0.1 * (earfcn - 600))
            in 1200..1949 -> Pair("B3 (1800 MHz)", 1805.0 + 0.1 * (earfcn - 1200))
            in 1950..2399 -> Pair("B4 (1700/2100 MHz AWS)", 2110.0 + 0.1 * (earfcn - 1950))
            in 2400..2649 -> Pair("B5 (850 MHz)", 869.0 + 0.1 * (earfcn - 2400))
            in 2750..3449 -> Pair("B7 (2600 MHz)", 2620.0 + 0.1 * (earfcn - 2750))
            in 3450..3799 -> Pair("B8 (900 MHz)", 925.0 + 0.1 * (earfcn - 3450))
            in 5010..5179 -> Pair("B12 (700 MHz Low-A/B/C)", 729.0 + 0.1 * (earfcn - 5010))
            in 5180..5279 -> Pair("B13 (700 MHz Upper-C)", 746.0 + 0.1 * (earfcn - 5180))
            in 5280..5379 -> Pair("B14 (700 MHz FirstNet)", 758.0 + 0.1 * (earfcn - 5280))
            in 5730..5849 -> Pair("B17 (700 MHz B/C)", 734.0 + 0.1 * (earfcn - 5730))
            in 8690..9039 -> Pair("B25 (1900 MHz Extended)", 1930.0 + 0.1 * (earfcn - 8690))
            in 9210..9659 -> Pair("B26 (850 MHz Extended)", 859.0 + 0.1 * (earfcn - 9210))
            in 9750..9849 -> Pair("B29 (700 MHz SDL)", 717.0 + 0.1 * (earfcn - 9750))
            in 9850..9949 -> Pair("B30 (2300 MHz WCS)", 2350.0 + 0.1 * (earfcn - 9850))
            in 39650..41589 -> Pair("B41 (2500 MHz TDD)", 2496.0 + 0.1 * (earfcn - 39650))
            in 46790..54539 -> Pair("B46 (5200 MHz LAA)", 5150.0 + 0.1 * (earfcn - 46790))
            in 54540..55239 -> Pair("B48 (3500 MHz CBRS)", 3550.0 + 0.1 * (earfcn - 54540))
            in 66436..67335 -> Pair("B66 (1700/2100 MHz AWS-3)", 2110.0 + 0.1 * (earfcn - 66436))
            in 68586..68935 -> Pair("B71 (600 MHz)", 617.0 + 0.1 * (earfcn - 68586))
            else -> Pair("B${estimateLteBand(earfcn)} (LTE)", estimateLteFrequency(earfcn))
        }
    }

    /**
     * Decode 5G NR-ARFCN to Band Name and Center Frequency in MHz
     */
    fun decodeNrArfcn(arfcn: Int): Pair<String, Double> {
        return when (arfcn) {
            in 123400..130400 -> Pair("n71 (600 MHz)", 617.0 + 0.015 * (arfcn - 123400))
            in 500000..538000 -> Pair("n41 (2.5 GHz Mid-Band)", 2500.0 + 0.015 * (arfcn - 500000))
            in 386000..398000 -> Pair("n25 (1900 MHz)", 1930.0 + 0.015 * (arfcn - 386000))
            in 382000..386000 -> Pair("n2 (1900 MHz)", 1930.0 + 0.015 * (arfcn - 382000))
            in 422000..434000 -> Pair("n66 (AWS-3)", 2110.0 + 0.015 * (arfcn - 422000))
            in 173800..178800 -> Pair("n5 (850 MHz)", 869.0 + 0.015 * (arfcn - 173800))
            in 151600..153600 -> Pair("n12 (700 MHz)", 758.0 + 0.015 * (arfcn - 151600))
            in 154600..155800 -> Pair("n14 (700 MHz FirstNet)", 758.0 + 0.015 * (arfcn - 154600))
            in 158200..159800 -> Pair("n29 (700 MHz SDL)", 717.0 + 0.015 * (arfcn - 158200))
            in 460000..472000 -> Pair("n30 (2300 MHz WCS)", 2350.0 + 0.015 * (arfcn - 460000))
            in 630000..654000 -> Pair("n77 (3.7 GHz C-Band)", 3700.0 + 0.015 * (arfcn - 630000))
            in 620000..630000 -> Pair("n78 (3.5 GHz)", 3500.0 + 0.015 * (arfcn - 620000))
            in 2054000..2104000 -> Pair("n258 (24 GHz mmWave)", 24250.0 + 0.06 * (arfcn - 2054000))
            in 2229250..2254250 -> Pair("n260 (39 GHz mmWave)", 37000.0 + 0.06 * (arfcn - 2229250))
            in 2254160..2294160 -> Pair("n261 (28 GHz mmWave)", 27500.0 + 0.06 * (arfcn - 2254160))
            else -> Pair("n${estimateNrBand(arfcn)} (NR)", estimateNrFrequency(arfcn))
        }
    }

    private fun estimateLteBand(earfcn: Int): Int {
        return when (earfcn) {
            in 9660..9749 -> 27
            in 9850..9949 -> 30
            in 36200..36899 -> 37
            in 37550..38249 -> 38
            in 38250..38649 -> 39
            in 38650..39649 -> 40
            in 41590..43589 -> 42
            in 43590..45589 -> 43
            in 45590..46589 -> 44
            else -> 2 // Default B2 estimate
        }
    }

    private fun estimateLteFrequency(earfcn: Int): Double {
        return if (earfcn > 0) earfcn * 0.1 else 0.0
    }

    private fun estimateNrBand(arfcn: Int): Int {
        return when {
            arfcn in 100000..200000 -> 71
            arfcn in 380000..400000 -> 25
            arfcn in 410000..440000 -> 66
            arfcn in 500000..540000 -> 41
            arfcn in 600000..700000 -> 77
            arfcn >= 2000000 -> 260
            else -> 41
        }
    }

    private fun estimateNrFrequency(arfcn: Int): Double {
        return if (arfcn < 600000) {
            0.005 * arfcn
        } else if (arfcn in 600000..2010000) {
            3000.0 + 0.015 * (arfcn - 600000)
        } else {
            24250.0 + 0.06 * (arfcn - 2010000)
        }
    }
}
