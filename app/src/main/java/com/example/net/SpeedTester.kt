package com.example.net

import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Minimal single-connection throughput probe against Cloudflare's speed-test
 * endpoint. Downloads for up to [MAX_TEST_MILLIS] and reports the average rate
 * plus a small-request latency estimate. A run can consume 100+ MB of mobile
 * data at gigabit-class speeds, so it only ever runs on an explicit user tap.
 */
object SpeedTester {

    data class SpeedResult(
        val timestamp: Long,
        val downloadMbps: Double,
        val latencyMs: Int,
        val label: String // radio configuration at test time, e.g. "3CC n41+n41+n25"
    )

    private const val DOWNLOAD_URL = "https://speed.cloudflare.com/__down?bytes=200000000"
    private const val PING_URL = "https://speed.cloudflare.com/__down?bytes=0"
    private const val MAX_TEST_MILLIS = 8_000L
    private const val PREFS_KEY = "speed_test_history"
    private const val MAX_HISTORY = 10

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun run(label: String): SpeedResult = withContext(Dispatchers.IO) {
        // Latency: three tiny requests, keep the best. The first includes
        // TLS/connection setup; later ones reuse the pooled connection and
        // approximate steady-state RTT.
        var bestLatency = Int.MAX_VALUE
        repeat(3) {
            val started = System.nanoTime()
            client.newCall(Request.Builder().url(PING_URL).build()).execute().use { response ->
                response.body?.bytes()
            }
            val ms = ((System.nanoTime() - started) / 1_000_000L).toInt()
            if (ms < bestLatency) bestLatency = ms
        }

        var bytes = 0L
        val startNs = System.nanoTime()
        client.newCall(Request.Builder().url(DOWNLOAD_URL).build()).execute().use { response ->
            val stream = response.body?.byteStream() ?: throw IllegalStateException("Empty response")
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                bytes += read
                if ((System.nanoTime() - startNs) / 1_000_000L >= MAX_TEST_MILLIS) break
            }
        }
        val seconds = (System.nanoTime() - startNs) / 1e9
        val mbps = if (seconds > 0) bytes * 8 / 1e6 / seconds else 0.0

        SpeedResult(
            timestamp = System.currentTimeMillis(),
            downloadMbps = mbps,
            latencyMs = if (bestLatency == Int.MAX_VALUE) -1 else bestLatency,
            label = label
        )
    }

    fun saveResult(prefs: SharedPreferences, result: SpeedResult) {
        val history = (listOf(result) + loadResults(prefs)).take(MAX_HISTORY)
        val arr = JSONArray()
        history.forEach { r ->
            arr.put(JSONObject().apply {
                put("ts", r.timestamp)
                put("mbps", r.downloadMbps)
                put("lat", r.latencyMs)
                put("label", r.label)
            })
        }
        prefs.edit().putString(PREFS_KEY, arr.toString()).apply()
    }

    fun loadResults(prefs: SharedPreferences): List<SpeedResult> {
        return try {
            val arr = JSONArray(prefs.getString(PREFS_KEY, "[]") ?: "[]")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                SpeedResult(o.getLong("ts"), o.getDouble("mbps"), o.getInt("lat"), o.getString("label"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
