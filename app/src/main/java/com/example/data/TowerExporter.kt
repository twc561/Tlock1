package com.example.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Plain-string builders for tower-database and speed-test exports. KML opens
 * directly in Google Earth; CSV in any spreadsheet.
 */
object TowerExporter {

    fun towersKml(towers: List<TowerDbEntry>): String {
        val kml = StringBuilder(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                    "<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n<Document>\n" +
                    "<name>TowerLock Tower Database</name>\n"
        )
        towers.forEach { tower ->
            kml.append("<Placemark>\n")
            kml.append("  <name>${xmlEscape("${tower.radio} ${tower.cid}")}</name>\n")
            kml.append(
                "  <description>${
                    xmlEscape(
                        "MCC-MNC: ${tower.mcc}-${tower.mnc}\n" +
                                "Area (TAC/LAC): ${tower.area}\n" +
                                "Range: ±${tower.range} m\n" +
                                "Address: ${tower.address ?: "n/a"}"
                    )
                }</description>\n"
            )
            kml.append("  <Point>\n    <coordinates>${tower.lon},${tower.lat},0</coordinates>\n  </Point>\n")
            kml.append("</Placemark>\n")
        }
        kml.append("</Document>\n</kml>")
        return kml.toString()
    }

    fun towersCsv(towers: List<TowerDbEntry>): String {
        val csv = StringBuilder("Radio,MCC,MNC,Area,CID,Lat,Lon,RangeMeters,Address\n")
        towers.forEach { t ->
            csv.append(
                listOf(
                    csvField(t.radio), csvField(t.mcc), csvField(t.mnc),
                    "${t.area}", "${t.cid}", "${t.lat}", "${t.lon}", "${t.range}",
                    csvField(t.address ?: "")
                ).joinToString(",")
            ).append("\n")
        }
        return csv.toString()
    }

    fun speedTestsCsv(tests: List<SpeedTestEntity>): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val csv = StringBuilder("Timestamp,DownloadMbps,LatencyMs,RadioConfig\n")
        tests.forEach { t ->
            csv.append(
                listOf(
                    csvField(fmt.format(Date(t.timestamp))),
                    String.format(Locale.US, "%.1f", t.downloadMbps),
                    "${t.latencyMs}",
                    csvField(t.label)
                ).joinToString(",")
            ).append("\n")
        }
        return csv.toString()
    }

    private fun csvField(value: String): String {
        return if (value.contains(',') || value.contains('"') || value.contains('\n')) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }
    }

    private fun xmlEscape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
