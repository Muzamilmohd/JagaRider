package com.jagarider

import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Data hujan dari Open-Meteo (percuma, tiada API key). Hanya koordinat titik semakan dihantar —
 * tiada data lain tentang pengguna.
 */
object Weather {

    /** Tahap kelebatan hujan (mm/jam). */
    enum class Level(val color: Int, val min: Double) {
        NONE(Color.parseColor("#43A047"), 0.0),
        DRIZZLE(Color.parseColor("#4FC3F7"), 0.1),
        LIGHT(Color.parseColor("#039BE5"), 1.0),
        MODERATE(Color.parseColor("#3949AB"), 4.0),
        HEAVY(Color.parseColor("#FB8C00"), 10.0),
        VERY_HEAVY(Color.parseColor("#E53935"), 30.0);

        val label: String
            get() = when (this) {
                NONE -> t("Tiada hujan", "No rain", "无雨", "மழை இல்லை")
                DRIZZLE -> t("Renyai", "Drizzle", "毛毛雨", "தூறல்")
                LIGHT -> t("Ringan", "Light", "小雨", "லேசான")
                MODERATE -> t("Sederhana", "Moderate", "中雨", "மிதமான")
                HEAVY -> t("Lebat", "Heavy", "大雨", "கனமழை")
                VERY_HEAVY -> t("Sangat lebat", "Very heavy", "暴雨", "மிகக் கனமழை")
            }

        companion object {
            fun of(mmh: Double): Level = entries.last { mmh >= it.min }
        }
    }

    /** Ramalan satu jam: masa "HH:mm", kod cuaca, suhu, peluang hujan (%), hujan (mm/jam). */
    data class Hour(val time: String, val code: Int, val tempC: Double, val prob: Int, val mmh: Double)

    /** Saiz grid titik semakan (GRID × GRID) untuk peta taburan hujan. */
    const val GRID = 7

    /** Titik semakan: arah (darjah dari utara), jarak dari rider, dan kedudukan dalam grid (baris 0 = utara). */
    data class Point(
        val lat: Double,
        val lon: Double,
        val bearing: Double,
        val distKm: Double,
        val row: Int = GRID / 2,
        val col: Int = GRID / 2,
    )

    data class Sample(
        val point: Point,
        val mmh: Double,              // keamatan sekarang (mm/jam)
        val code: Int,                // kod cuaca WMO
        val tempC: Double,
        val nextMmh: List<Double>,    // setiap 15 minit, 2 jam akan datang
        val nextTimes: List<String>,  // "HH:mm"
        val nextCodes: List<Int>,     // kod cuaca setiap 15 minit
        val hours: List<Hour>,        // ramalan setiap jam, 6 jam akan datang
    ) {
        val level get() = Level.of(mmh)
        val thunder get() = code >= 95
    }

    /**
     * Grid GRID × GRID meliputi segi empat ±jarak dari rider (49 titik).
     * Titik tengah (lokasi rider) diletak pertama dalam senarai.
     */
    fun points(lat: Double, lon: Double, rangeKm: Double): List<Point> {
        val half = GRID / 2
        val step = rangeKm / half
        val kmPerDegLat = 111.32
        val kmPerDegLon = 111.32 * cos(Math.toRadians(lat))
        val list = mutableListOf(Point(lat, lon, 0.0, 0.0, half, half))
        for (r in 0 until GRID) for (c in 0 until GRID) {
            if (r == half && c == half) continue
            val east = (c - half) * step
            val north = (half - r) * step
            list += Point(
                lat = lat + north / kmPerDegLat,
                lon = lon + east / kmPerDegLon,
                bearing = (Math.toDegrees(atan2(east, north)) + 360) % 360,
                distKm = hypot(east, north),
                row = r,
                col = c,
            )
        }
        return list
    }

    /** Panggil di thread latar. Satu permintaan untuk semua titik. */
    fun fetch(points: List<Point>): List<Sample> {
        fun join(f: (Point) -> Double) = points.joinToString(",") { String.format(Locale.US, "%.4f", f(it)) }
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${join { it.lat }}&longitude=${join { it.lon }}" +
            "&current=temperature_2m,precipitation,weather_code&minutely_15=precipitation,weather_code" +
            "&forecast_minutely_15=9&hourly=weather_code,temperature_2m,precipitation_probability,precipitation" +
            "&forecast_hours=7&timezone=auto"
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
        val codes = m.optJSONArray("weather_code")
        val next = mutableListOf<Double>()
        val nextTimes = mutableListOf<String>()
        val nextCodes = mutableListOf<Int>()
        for (k in start + 1 until minOf(times.length(), start + 9)) {
            next += prec.optDouble(k, 0.0).let { if (it.isNaN()) 0.0 else it * 4 }
            nextTimes += times.getString(k).substringAfter('T')
            nextCodes += codes?.optInt(k, 0) ?: 0
        }
        // Jam penuh selepas sekarang (jam semasa dilangkau). Hujan setiap jam = mm/jam terus.
        val hours = mutableListOf<Hour>()
        o.optJSONObject("hourly")?.let { hr ->
            val ht = hr.getJSONArray("time")
            for (k in 0 until ht.length()) {
                if (ht.getString(k) <= now || hours.size >= 6) continue
                hours += Hour(
                    time = ht.getString(k).substringAfter('T'),
                    code = hr.getJSONArray("weather_code").optInt(k, 0),
                    tempC = hr.getJSONArray("temperature_2m").optDouble(k, Double.NaN),
                    prob = hr.getJSONArray("precipitation_probability").optInt(k, 0),
                    mmh = hr.getJSONArray("precipitation").optDouble(k, 0.0).let { if (it.isNaN()) 0.0 else it },
                )
            }
        }
        return Sample(
            point = p,
            mmh = cur.optDouble("precipitation", 0.0).let { if (it.isNaN()) 0.0 else it * perHour },
            code = cur.optInt("weather_code", 0),
            tempC = cur.optDouble("temperature_2m", Double.NaN),
            nextMmh = next,
            nextTimes = nextTimes,
            nextCodes = nextCodes,
            hours = hours,
        )
    }

    /** Ikon emoji untuk kod cuaca; "HH:mm" untuk bezakan siang / malam. */
    fun icon(code: Int, time: String = ""): String {
        val hour = time.substringBefore(':').toIntOrNull() ?: 12
        val day = hour in 7..18
        return when (code) {
            0 -> if (day) "☀️" else "🌙"
            1, 2 -> if (day) "⛅" else "☁️"
            3 -> "☁️"
            45, 48 -> "🌫️"
            in 51..57 -> if (day) "🌦️" else "🌧️"
            in 61..67, 81, 82 -> "🌧️"
            80 -> if (day) "🌦️" else "🌧️"
            in 95..99 -> "⛈️"
            else -> "☁️"
        }
    }

    fun describe(code: Int): String = when (code) {
        0 -> t("Cerah", "Clear", "晴", "தெளிவு")
        1, 2 -> t("Sedikit berawan", "Partly cloudy", "少云", "ஓரளவு மேகமூட்டம்")
        3 -> t("Mendung", "Overcast", "阴", "மேகமூட்டம்")
        45, 48 -> t("Berkabus", "Fog", "雾", "மூடுபனி")
        in 51..57 -> t("Gerimis", "Drizzle", "毛毛雨", "தூறல்")
        in 61..67 -> t("Hujan", "Rain", "雨", "மழை")
        in 80..82 -> t("Hujan sekejap-sekejap", "Rain showers", "阵雨", "விட்டு விட்டு மழை")
        in 95..99 -> t("Ribut petir", "Thunderstorm", "雷暴", "இடியுடன் மழை")
        else -> "—"
    }

    /** Arah dalam ayat, cth. "arah Timur Laut" / "to the north-east". */
    fun toward(bearing: Double): String {
        val i = (((bearing + 22.5) % 360) / 45).toInt()
        return when (I18n.lang) {
            Lang.MS -> "arah " + listOf("Utara", "Timur Laut", "Timur", "Tenggara", "Selatan", "Barat Daya", "Barat", "Barat Laut")[i]
            Lang.EN -> "to the " + listOf("north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west")[i]
            Lang.ZH -> listOf("北", "东北", "东", "东南", "南", "西南", "西", "西北")[i] + "方向"
            Lang.TA -> listOf("வடக்கு", "வடகிழக்கு", "கிழக்கு", "தென்கிழக்கு", "தெற்கு", "தென்மேற்கு", "மேற்கு", "வடமேற்கு")[i] + " திசையில்"
        }
    }

    val unit: String get() = t("mm/jam", "mm/h", "毫米/小时", "மிமீ/மணி")

    fun formatKm(km: Double): String = if (km % 1.0 == 0.0) "${km.toInt()} km" else String.format(Locale.US, "%.1f km", km)
}
