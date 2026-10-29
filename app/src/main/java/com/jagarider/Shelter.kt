package com.jagarider

import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

/**
 * Tempat berteduh terdekat bila hujan: jejantas / bawah jalan bertingkat, stesen minyak, perhentian bas
 * berbumbung dan wakaf / pondok — dari OpenStreetMap (Overpass API, percuma). Di thread latar.
 */
object Shelter {

    enum class Kind(val icon: String) { BRIDGE("🌉"), FUEL("⛽"), BUS("🚏"), SHELTER("🛖") }

    data class Spot(val kind: Kind, val name: String, val lat: Double, val lon: Double, val distKm: Double, val bearing: Double) {
        val label: String
            get() = name.ifBlank {
                when (kind) {
                    Kind.BRIDGE -> t("Bawah jejantas", "Under a flyover", "高架桥下", "மேம்பாலத்தின் கீழ்")
                    Kind.FUEL -> t("Stesen minyak", "Petrol station", "加油站", "பெட்ரோல் நிலையம்")
                    Kind.BUS -> t("Perhentian bas", "Bus stop", "巴士站", "பேருந்து நிறுத்தம்")
                    Kind.SHELTER -> t("Wakaf / pondok", "Shelter", "避雨亭", "தங்குமிடம்")
                }
            }
    }

    /**
     * Overpass dulu (satu-satunya sumber jejantas) — tapi pelayan awamnya kerap penuh, jadi tunggu
     * maks 12 s; kalau gagal, sandaran Nominatim (stesen minyak, perhentian bas, wakaf).
     */
    fun near(lat: Double, lon: Double): List<Spot> {
        val over = runCatching {
            val near = query(lat, lon, 3000)
            if (near.size >= 3) near else query(lat, lon, 8000)
        }.getOrNull()
        if (!over.isNullOrEmpty()) return over
        return nominatim(lat, lon, 0.03).takeIf { it.size >= 3 } ?: nominatim(lat, lon, 0.07)
    }

    /** Sandaran: carian kategori Nominatim dalam kotak sekeliling (maks 1 permintaan sesaat). */
    private fun nominatim(lat: Double, lon: Double, d: Double): List<Spot> {
        val vb = String.format(Locale.US, "%.4f,%.4f,%.4f,%.4f", lon - d, lat + d, lon + d, lat - d)
        val out = mutableListOf<Spot>()
        for ((i, pair) in listOf("petrol station" to Kind.FUEL, "bus stop" to Kind.BUS, "shelter" to Kind.SHELTER).withIndex()) {
            if (i > 0) Thread.sleep(1100)
            val arr = runCatching {
                org.json.JSONArray(Route.get(
                    "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=10&bounded=1&viewbox=$vb&q=" +
                        URLEncoder.encode(pair.first, "UTF-8"), 15_000,
                ))
            }.getOrNull() ?: continue
            for (k in 0 until arr.length()) {
                val o = arr.getJSONObject(k)
                val la = o.getString("lat").toDouble()
                val lo = o.getString("lon").toDouble()
                out += spot(pair.second, o.optString("name").takeIf { it != pair.first }.orEmpty(), lat, lon, la, lo)
            }
        }
        return dedupe(out)
    }

    private fun spot(kind: Kind, name: String, lat: Double, lon: Double, la: Double, lo: Double): Spot {
        val north = (la - lat) * 111.32
        val east = (lo - lon) * 111.32 * kotlin.math.cos(Math.toRadians(lat))
        return Spot(kind, name, la, lo, Route.km(lat to lon, la to lo), (Math.toDegrees(kotlin.math.atan2(east, north)) + 360) % 360)
    }

    /** Buang titik terlalu dekat antara satu sama lain (cth. banyak segmen untuk satu jejantas). */
    private fun dedupe(spots: List<Spot>): List<Spot> {
        val out = mutableListOf<Spot>()
        for (s in spots.sortedBy { it.distKm }) {
            if (out.none { it.kind == s.kind && Route.km(it.lat to it.lon, s.lat to s.lon) < 0.25 }) out += s
        }
        return out.take(12)
    }

    private fun query(lat: Double, lon: Double, r: Int): List<Spot> {
        val a = String.format(Locale.US, "around:%d,%.5f,%.5f", r, lat, lon)
        val q = "[out:json][timeout:20];(" +
            "way($a)[bridge=yes][highway~\"^(motorway|trunk|primary|secondary)$\"];" +
            "node($a)[amenity=fuel];way($a)[amenity=fuel];" +
            "node($a)[highway=bus_stop][shelter=yes];" +
            "node($a)[amenity=shelter];way($a)[amenity=shelter];" +
            ");out center 80;"
        val o = JSONObject(Route.get("https://overpass-api.de/api/interpreter?data=" + URLEncoder.encode(q, "UTF-8"), 12_000))
        val els = o.getJSONArray("elements")
        return dedupe((0 until els.length()).mapNotNull { i ->
            val e = els.getJSONObject(i)
            val tags = e.optJSONObject("tags") ?: return@mapNotNull null
            val (la, lo) = if (e.has("lat")) e.getDouble("lat") to e.getDouble("lon")
            else e.optJSONObject("center")?.let { it.getDouble("lat") to it.getDouble("lon") } ?: return@mapNotNull null
            val kind = when {
                tags.optString("bridge") == "yes" -> Kind.BRIDGE
                tags.optString("amenity") == "fuel" -> Kind.FUEL
                tags.optString("highway") == "bus_stop" -> Kind.BUS
                else -> Kind.SHELTER
            }
            val name = if (kind == Kind.BRIDGE) "" else tags.optString("name").ifBlank { tags.optString("brand") }
            spot(kind, name, lat, lon, la, lo)
        })
    }
}
