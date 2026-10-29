package com.jagarider

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Hujan di sepanjang laluan: cari destinasi (Nominatim / OpenStreetMap), dapatkan laluan (OSRM), kemudian
 * semak ramalan hujan di setiap titik laluan PADA MASA rider dijangka sampai di situ (Open-Meteo), dan
 * radar sebenar bagi bahagian yang akan dilalui dalam 30 minit. Semua percuma, tiada API key.
 * Semua fungsi di thread latar.
 */
object Route {

    private const val UA = "JagaRide (+https://github.com/Muzamilmohd/JagaRider)"

    data class Place(val name: String, val detail: String, val lat: Double, val lon: Double)

    /** Titik semakan di laluan: jarak dari mula, minit perjalanan, hujan dijangka (mm/jam). */
    data class Sample(val lat: Double, val lon: Double, val km: Double, val minutes: Double, val mmh: Double, val code: Int) {
        val level get() = Weather.Level.of(mmh)
    }

    /** Bahagian laluan yang hujan (untuk ringkasan). */
    data class Wet(val fromKm: Double, val toKm: Double, val atMinutes: Double, val level: Weather.Level, val thunder: Boolean, var place: String = "")

    data class Plan(
        val dest: Place,
        val line: List<Pair<Double, Double>>,  // laluan penuh (lat, lon)
        val distKm: Double,
        val minutes: Double,
        val start: Long,                       // masa bertolak (ms)
        val samples: List<Sample>,
        val wet: List<Wet>,
        val bestDelayMin: Int,                 // cadangan tangguh (minit), 0 = gerak sekarang
        val bestDelayRain: Double,             // "jumlah hujan" jika tangguh (untuk banding)
        val nowRain: Double,
    )

    // ------------------------------------------------------------ cari tempat

    fun search(q: String, nearLat: Double, nearLon: Double): List<Place> {
        val vb = String.format(Locale.US, "%.3f,%.3f,%.3f,%.3f", nearLon - 1.5, nearLat + 1.5, nearLon + 1.5, nearLat - 1.5)
        val url = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=6&countrycodes=my&viewbox=$vb&q=" +
            URLEncoder.encode(q, "UTF-8")
        val arr = JSONArray(get(url))
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map { o ->
            val display = o.optString("display_name")
            Place(
                name = o.optString("name").ifBlank { display.substringBefore(",") },
                detail = display.split(",").drop(1).take(2).joinToString(",").trim(),
                lat = o.getString("lat").toDouble(),
                lon = o.getString("lon").toDouble(),
            )
        }
    }

    /** Nama bandar / kawasan untuk satu titik (Nominatim reverse). */
    private fun placeName(lat: Double, lon: Double): String = runCatching {
        val o = JSONObject(get(String.format(Locale.US, "https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=12&lat=%.5f&lon=%.5f", lat, lon)))
        val a = o.optJSONObject("address")
        listOf("town", "city", "suburb", "village", "county", "state_district").firstNotNullOfOrNull { k -> a?.optString(k)?.takeIf { it.isNotBlank() } }
            ?: o.optString("name")
    }.getOrDefault("")

    // ------------------------------------------------------------ laluan + hujan

    fun plan(fromLat: Double, fromLon: Double, dest: Place): Plan {
        val r = JSONObject(get(String.format(
            Locale.US, "https://router.project-osrm.org/route/v1/driving/%.6f,%.6f;%.6f,%.6f?overview=full&geometries=geojson",
            fromLon, fromLat, dest.lon, dest.lat,
        ))).getJSONArray("routes").getJSONObject(0)
        val coords = r.getJSONObject("geometry").getJSONArray("coordinates")
        val line = (0 until coords.length()).map { coords.getJSONArray(it).let { c -> c.getDouble(1) to c.getDouble(0) } }
        val distKm = r.getDouble("distance") / 1000
        val minutes = r.getDouble("duration") / 60

        // Titik semakan setiap ~3–10 km (maks 40) sepanjang laluan
        val cum = DoubleArray(line.size)
        for (i in 1 until line.size) cum[i] = cum[i - 1] + km(line[i - 1], line[i])
        val step = max(3.0, distKm / 40)
        val picks = mutableListOf<Pair<Pair<Double, Double>, Double>>()
        var next = 0.0
        for (i in line.indices) if (cum[i] >= next) {
            picks += line[i] to cum[i]
            next += step
        }
        if (picks.last().second < cum.last() - 0.5) picks += line.last() to cum.last()

        // Ramalan setiap 15 minit untuk 8 jam (UTC) di semua titik — satu permintaan
        val start = System.currentTimeMillis()
        val forecast = forecast(picks.map { it.first })
        // Radar sebenar untuk bahagian yang akan dilalui dalam 30 minit
        val radar = picks.map { (p, k) -> if (minutes * k / distKm <= 30) runCatching { Radar.mmhAt(p.first, p.second) }.getOrNull() else null }

        fun samplesFor(delayMin: Int) = picks.mapIndexed { i, (p, k) ->
            val m = minutes * (k / distKm)
            val at = start + ((delayMin + m) * 60_000).toLong()
            val (mmh, code) = forecast[i].at(at)
            val live = if (delayMin == 0) radar[i] else null
            Sample(p.first, p.second, k, m, max(mmh, live ?: 0.0), code)
        }

        val samples = samplesFor(0)
        fun exposure(s: List<Sample>) = s.sumOf { it.mmh }
        val nowRain = exposure(samples)
        // Bandingkan tangguh 30 / 60 / 90 / 120 minit (dalam julat ramalan 8 jam)
        var best = 0
        var bestRain = nowRain
        for (d in listOf(30, 60, 90, 120)) {
            if (d + minutes > 8 * 60 - 15) break
            val e = exposure(samplesFor(d))
            if (e < bestRain * 0.5 && nowRain - e > 1.0) {
                best = d
                bestRain = e
                break // tangguh paling singkat yang cukup baik
            }
        }

        // Kumpul bahagian yang hujan
        val wet = mutableListOf<Wet>()
        var cur: Wet? = null
        for (s in samples) {
            if (s.mmh >= 0.1) {
                cur = cur?.copy(toKm = s.km, level = maxOf(cur.level, s.level), thunder = cur.thunder || s.code >= 95)
                    ?: Wet(s.km, s.km, s.minutes, s.level, s.code >= 95)
            } else if (cur != null) {
                wet += cur
                cur = null
            }
        }
        cur?.let { wet += it }
        // Nama kawasan untuk 3 bahagian pertama (Nominatim: maks 1 permintaan sesaat)
        for ((i, w) in wet.take(3).withIndex()) {
            if (i > 0) Thread.sleep(1100)
            val mid = samples.minByOrNull { kotlin.math.abs(it.km - (w.fromKm + w.toKm) / 2) } ?: continue
            w.place = placeName(mid.lat, mid.lon)
        }

        return Plan(dest, line, distKm, minutes, start, samples, wet, best, bestRain, nowRain)
    }

    /** Ramalan 15 minit bagi satu titik: masa (ms, UTC) → (mm/jam, kod cuaca). */
    private class Series(val times: LongArray, val mmh: DoubleArray, val codes: IntArray) {
        fun at(t: Long): Pair<Double, Int> {
            if (times.isEmpty()) return 0.0 to 0
            var i = times.indexOfLast { it <= t }
            if (i < 0) i = 0
            return mmh[i] to codes[i]
        }
    }

    private fun forecast(points: List<Pair<Double, Double>>): List<Series> {
        fun join(f: (Pair<Double, Double>) -> Double) = points.joinToString(",") { String.format(Locale.US, "%.4f", f(it)) }
        val text = get(
            "https://api.open-meteo.com/v1/forecast?latitude=${join { it.first }}&longitude=${join { it.second }}" +
                "&minutely_15=precipitation,weather_code&forecast_minutely_15=32&past_minutely_15=1&timezone=GMT"
        )
        val arr = if (text.trimStart().startsWith("[")) JSONArray(text) else JSONArray().put(JSONObject(text))
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }
        return (0 until arr.length()).map { i ->
            val m = arr.getJSONObject(i).getJSONObject("minutely_15")
            val t = m.getJSONArray("time")
            val p = m.getJSONArray("precipitation")
            val c = m.optJSONArray("weather_code")
            Series(
                LongArray(t.length()) { fmt.parse(t.getString(it))!!.time },
                DoubleArray(t.length()) { p.optDouble(it, 0.0).let { v -> if (v.isNaN()) 0.0 else v * 4 } },
                IntArray(t.length()) { c?.optInt(it, 0) ?: 0 },
            )
        }
    }

    fun km(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val dLat = Math.toRadians(b.first - a.first)
        val dLon = Math.toRadians(b.second - a.second)
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(a.first)) * cos(Math.toRadians(b.first)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * 6371.0 * asin(sqrt(h))
    }

    fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 25_000
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Accept-Language", I18n.lang.code)
        try {
            if (conn.responseCode != 200) throw IllegalStateException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
