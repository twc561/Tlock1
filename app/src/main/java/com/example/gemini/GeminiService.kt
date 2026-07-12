package com.example.gemini

import android.util.Log
import com.example.BuildConfig
import com.example.telephony.CellModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object GeminiService {
    private const val TAG = "GeminiService"
    private const val MODEL_NAME = "gemini-3.1-flash-lite-preview"
    private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/$MODEL_NAME:generateContent"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun generateSignalAudit(
        cell: CellModel,
        distanceMeters: Double?,
        address: String,
        confidenceMeters: Int,
        isSuspect: Boolean,
        customApiKeyOverride: String? = null
    ): String = withContext(Dispatchers.IO) {
        // Resolve the API Key: Use the override if provided, otherwise fallback to BuildConfig
        val apiKey = if (!customApiKeyOverride.isNullOrBlank()) {
            customApiKeyOverride.trim()
        } else {
            BuildConfig.GEMINI_API_KEY
        }

        if (apiKey.isBlank()) {
            return@withContext "API_KEY_MISSING"
        }

        val prompt = buildPrompt(cell, distanceMeters, address, confidenceMeters, isSuspect)

        try {
            val jsonRequest = JSONObject().apply {
                val contentsArray = JSONArray().apply {
                    val contentObj = JSONObject().apply {
                        val partsArray = JSONArray().apply {
                            val partObj = JSONObject().apply {
                                put("text", prompt)
                            }
                            put(partObj)
                        }
                        put("parts", partsArray)
                    }
                    put(contentObj)
                }
                put("contents", contentsArray)

                // High-performance configuration for ultra-low latency
                val generationConfig = JSONObject().apply {
                    put("temperature", 0.3)
                    put("maxOutputTokens", 450)
                }
                put("generationConfig", generationConfig)

                // System instruction for consistent personality and behavior
                val systemInstruction = JSONObject().apply {
                    val partsArray = JSONArray().apply {
                        val partObj = JSONObject().apply {
                            put("text", "You are an expert RF (Radio Frequency) Engineer and Cellular Security Auditor for 'TowerLock Pro'. Your analysis must be concise, objective, authoritative, and strictly professional. Always format your output in clean Markdown with distinct, styled bullet points. Do not use conversational filler.")
                        }
                        put(partObj)
                    }
                    put("parts", partsArray)
                }
                put("systemInstruction", systemInstruction)
            }

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val requestBody = jsonRequest.toString().toRequestBody(mediaType)

            val request = Request.Builder()
                .url("$BASE_URL?key=$apiKey")
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errBody = response.body?.string() ?: ""
                    Log.e(TAG, "Gemini Request failed: ${response.code} - $errBody")
                    return@withContext "API_ERROR: ${response.code}"
                }

                val responseBodyStr = response.body?.string() ?: return@withContext "ERROR: Empty Response"
                val jsonResponse = JSONObject(responseBodyStr)
                val candidates = jsonResponse.optJSONArray("candidates")
                if (candidates == null || candidates.length() == 0) {
                    return@withContext "ERROR: No analysis candidates returned."
                }

                val candidate = candidates.getJSONObject(0)
                val content = candidate.optJSONObject("content")
                if (content == null) {
                    return@withContext "ERROR: Content missing from response candidate."
                }

                val parts = content.optJSONArray("parts")
                if (parts == null || parts.length() == 0) {
                    return@withContext "ERROR: Content parts missing from response."
                }

                val text = parts.getJSONObject(0).optString("text")
                if (text.isNullOrBlank()) {
                    return@withContext "ERROR: Analysis text is blank."
                }

                return@withContext text
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error generating signal audit", e)
            return@withContext "CONNECTION_ERROR: ${e.localizedMessage ?: "Unknown network failure"}"
        }
    }

    private fun buildPrompt(
        cell: CellModel,
        distanceMeters: Double?,
        address: String,
        confidenceMeters: Int,
        isSuspect: Boolean
    ): String {
        val aggregatedBands = cell.activeCarriers.joinToString(", ") { "${it.band} (${it.type})" }
        val neighborsStr = if (cell.neighbors.isEmpty()) "None detected" else {
            cell.neighbors.joinToString(", ") { "${it.tech} ${it.band} (PCI: ${it.pci}, RSRP: ${it.rsrp}dBm)" }
        }

        return """
            Analyze the following live mobile cellular telemetry state and provide an instant security & signal quality audit:
            
            [CELL TELEMETRY]
            - Operator/Carrier: ${cell.operatorName ?: "Unknown"} (MCC/MNC: ${cell.mcc ?: "---"}/${cell.mnc ?: "---"})
            - Technology: ${cell.tech}
            - Cell ID: ${cell.cellId} (gNodeB/eNodeB: ${cell.nodebId}, Sector: ${cell.sectorId})
            - Physical Cell ID (PCI): ${cell.pci}
            - Tracking Area Code (TAC): ${cell.tac}
            - Primary Carrier Band: ${cell.bandName} (ARFCN: ${cell.arfcn})
            - Signal Quality metrics: RSRP = ${cell.rsrp} dBm, SINR = ${cell.sinr} dB, Grade = ${cell.signalGrade}
            - Timing Advance Range: ${if (cell.timingAdvance > 0) "${cell.timingAdvance} steps (approx. ${(cell.distanceEstimateMeters * 3.28084).toInt()} ft)" else "0 (Immediate)"}
            - Carrier Aggregation (CA): ${if (cell.activeCarriers.size > 1) "Active (${cell.activeCarriers.size} aggregated channels: $aggregatedBands)" else "Standby (Single channel)"}
            - Nearby Neighbors: $neighborsStr
            
            [GEOLOCATION ENVIRONMENT]
            - Triangulated Tower Address: $address
            - Location Confidence Radius: ±${(confidenceMeters * 3.28084).toInt()} ft
            - Distance to Tower: ${if (distanceMeters != null) "${(distanceMeters * 3.28084).toInt()} ft" else "Calculating..."}
            - Anomaly Mismatch Flag (isSuspect): $isSuspect
            
            Based on these parameters, output exactly three brief sections in clean Markdown:
            1. **Signal Performance & Aggregation**: Assess speed/throughput capabilities and band configuration. Mention CA status.
            2. **Security & Stingray Risk**: Evaluate IMSI catcher/fake base station risk based on anomaly flags, PCI, and distance-to-TA alignment.
            3. **Actionable RF Recommendation**: A single clear instruction (e.g. "Move 100ft south", "Secure link, optimal throughput", etc.).
            
            Keep the response brief (under 250 words total) to preserve low-latency responsiveness. Focus purely on technical facts.
        """.trimIndent()
    }
}
