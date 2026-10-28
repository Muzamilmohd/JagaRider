package com.jagarider

import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Data hujan dari Open-Meteo (percuma, tiada API key). Hanya koordinat titik semakan dihantar —
 * tiada data lain tentang pengguna.
 */
object Weather {

    /** Tahap kelebatan hujan (mm/jam). */
    enum class Level(val label: String, val color: Int, val min: Double) {
        NONE("Tiada hujan", Color.parseColor("#43A047"), 0.0),
        DRIZZLE("Renyai", Color.parseColor("#4FC3F7"), 0.1),
        LIGHT("Ringan", Color.parseColor("#039BE5"), 1.0),
        MODERATE("Sederhana", Color.parseColor("#3949AB"), 4.0),
        HEAVY("Lebat", Color.parseColor("#FB8C00"), 10.0),
        VERY_HEAVY("Sangat lebat", Color.parseColor("#E53935"), 30.0);

        companion object {
            fun of(mmh: Double): Level = entries.last { mmh >= it.min }
        }
    }

    /** Titik semakan: arah (darjah dari utara) dan jarak dari rider. */
    data class Point(val lat: Double, val lon: Double, val bearing: Double, val distKm: Double)

    data class Sample(
        val point: Point,
        val mmh: Double,              // keamatan sekarang (mm/jam)
        val code: Int,                // kod cuaca WMO
        val tempC: Double,
        val nextMmh: List<Double>,    // setiap 15 minit, 2 jam akan datang
        val nextTimes: List<String>,  // "HH:mm"
    ) {
        val level get() = Level.of(mmh)
        val thunder get() = code >= 95
    }

    /** Tengah (lokasi rider) + 8 arah pada separuh jarak dan jarak penuh = 17 titik. */
    fun points(lat: Double, lon: Double, rangeKm: Double): List<Point> {
        val list = mutableListOf(Point(lat, lon, 0.0, 0.0))
        for (d in listOf(rangeKm / 2, rangeKm)) for (i in 0 until 8) {
            val b = i * 45.0
            val (la, lo) = destination(lat, lon, b, d)
            list += Point(la, lo, b, d)
        }
        return list
    }

    private fun destination(lat: Double, lon: Double, bearingDeg: Double, distKm: Double): Pair<Double, Double> {
        val d = distKm / 6371.0
        val b = Math.toRadians(bearingDeg)
        val p1 = Math.toRadians(lat)
        val l1 = Math.toRadians(lon)
        val p2 = asin(sin(p1) * cos(d) + cos(p1) * sin(d) * cos(b))
        val l2 = l1 + atan2(sin(b) * sin(d) * cos(p1), cos(d) - sin(p1) * sin(p2))
        return Math.toDegrees(p2) to Math.toDegrees(l2)
    }

    /** Panggil di thread latar. Satu permintaan untuk semua titik. */
    fun fetch(points: List<Point>): List<Sample> {
        fun join(f: (Point) -> Double) = points.joinToString(",") { String.format(Locale.US, "%.4f", f(it)) }
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${join { it.lat }}&longitude=${join { it.lon }}" +
            "&current=temperature_2m,precipitation,weather_code&minutely_15=precipitation" +
            "&forecast_minutely_15=9&timezone=auto"
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("User-Agent", "JagaRider")
        val text = try {
            val ok = conn.responseCode == 200
            val body = (if (ok) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (!ok) throw IllegalStateException(
                runCatching { JSONObject(body).optString("reason") }.getOrNull().orEmpty().ifBlank { "HTTP ${conn.responseCode}" }
            )
            body
        } finally {
            conn.disconnect()
        }
        val arr = if (text.trimStart().startsWith("[")) JSONArray(text) else JSONArray().put(JSONObject(text))
        return points.mapIndexed { i, p -> parse(p, arr.getJSONObject(i)) }
    }

    private fun parse(p: Point, o: JSONObject): Sample {
        val cur = o.getJSONObject("current")
        // "current" dan "minutely_15" ialah jumlah mm dalam 15 minit → x4 = mm/jam
        val perHour = 3600.0 / cur.optInt("interval", 900).coerceAtLeast(1)
        val m = o.getJSONObject("minutely_15")
        val times = m.getJSONArray("time")
        val prec = m.getJSONArray("precipitation")
        val now = cur.optString("time")
        val start = (0 until times.length()).firstOrNull { times.getString(it) == now } ?: 0
        val next = mutableListOf<Double>()
        val nextTimes = mutableListOf<String>()
        for (k in start + 1 until minOf(times.length(), start + 9)) {
            next += prec.optDouble(k, 0.0).let { if (it.isNaN()) 0.0 else it * 4 }
            nextTimes += times.getString(k).substringAfter('T')
        }
        return Sample(
            point = p,
            mmh = cur.optDouble("precipitation", 0.0).let { if (it.isNaN()) 0.0 else it * perHour },
            code = cur.optInt("weather_code", 0),
            tempC = cur.optDouble("temperature_2m", Double.NaN),
            nextMmh = next,
            nextTimes = nextTimes,
        )
    }

    fun describe(code: Int): String = when (code) {
        0 -> "Cerah"
        1, 2 -> "Sedikit berawan"
        3 -> "Mendung"
        45, 48 -> "Berkabus"
        in 51..57 -> "Gerimis"
        in 61..67 -> "Hujan"
        in 80..82 -> "Hujan sekejap-sekejap"
        in 95..99 -> "Ribut petir"
        else -> "—"
    }

    private val DIRS = listOf("Utara", "Timur Laut", "Timur", "Tenggara", "Selatan", "Barat Daya", "Barat", "Barat Laut")
    fun direction(bearing: Double): String = DIRS[(((bearing + 22.5) % 360) / 45).toInt()]

    fun formatKm(km: Double): String = if (km % 1.0 == 0.0) "${km.toInt()} km" else String.format(Locale.US, "%.1f km", km)
}
