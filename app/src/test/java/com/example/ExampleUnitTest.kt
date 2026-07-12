package com.example

import com.example.telephony.BandFrequencyMapper
import org.junit.Assert.assertEquals
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun testLteEciDecoding() {
        // ECI is a 28-bit cell identity for LTE
        val ci = 987654L // 0x0F1206
        val eNB = ci shr 8
        val sector = ci and 0xFF

        assertEquals(3858L, eNB)
        assertEquals(6L, sector)
    }

    @Test
    fun testNrNciDecoding_24bit_gNB() {
        // NCI is a 36-bit cell identity for 5G NR
        val nci = 1234567L // 0x12D687
        val gnbBits = 24
        val shiftBits = 36 - gnbBits // 12
        val gNB = nci shr shiftBits
        val sector = nci and ((1L shl shiftBits) - 1)

        assertEquals(301L, gNB)
        assertEquals(1671L, sector)
    }

    @Test
    fun testNrNciDecoding_22bit_gNB() {
        val nci = 1234567L
        val gnbBits = 22
        val shiftBits = 36 - gnbBits // 14
        val gNB = nci shr shiftBits
        val sector = nci and ((1L shl shiftBits) - 1)

        assertEquals(75L, gNB)
        assertEquals(5767L, sector)
    }

    @Test
    fun testLteEarfcnToBandDerivation() {
        val earfcn = 68700 // Band 71 (600 MHz)
        val bandData = BandFrequencyMapper.decodeLteEarfcn(earfcn)
        assertEquals("B71 (600 MHz)", bandData.first)
    }

    @Test
    fun testNrArfcnToBandDerivation() {
        val arfcn = 518000 // Band n41
        val bandData = BandFrequencyMapper.decodeNrArfcn(arfcn)
        assertEquals("n41 (2.5 GHz Mid-Band)", bandData.first)
    }

    @Test
    fun test5gToLteDropDetection() {
        val prevTech = "5G SA"
        val currentTech = "4G LTE"
        val isPrev5gSa = prevTech == "5G SA"
        val isCurrentLte = currentTech == "4G LTE" || currentTech == "LTE" || currentTech.contains("LTE")
        
        val isDrop = isPrev5gSa && isCurrentLte
        assertEquals(true, isDrop)
    }
}
