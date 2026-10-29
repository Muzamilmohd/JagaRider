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

    fun near(lat: Double, lon: Double): List<Spot> {
        for (radius in listOf(3000, 8000)) {
            val found = query(lat, lon, radius)
            if (found.size >= 3 || radius == 8000) return found
        }
        return emptyList()
    }

    private fun query(lat: Double, lon: Double, r: Int): List<Spot> {
        val a = String.format(Locale.US, "around:%d,%.5f,%.5f", r, lat, lon)
        val q = "[out:json][timeout:20];(" +
            "way($a)[bridge=yes][highway~\"^(motorway|trunk|primary|secondary)$\"];" +
            "node($a)[amenity=fuel];way($a)[amenity=fuel];" +
            "node($a)[highway=bus_stop][shelter=yes];" +
            "node($a)[amenity=shelter];way($a)[amenity=shelter];" +
            ");out center 80;"
        val o = JSONObject(Route.get("https://overpass-api.de/api/interpreter?data=" + URLEncoder.encode(q, "UTF-8")))
        val els = o.getJSONArray("elements")
        val spots = (0 until els.length()).mapNotNull { i ->
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
            val d = Route.km(lat to lon, la to lo)
            val north = (la - lat) * 111.32
            val east = (lo - lon) * 111.32 * kotlin.math.cos(Math.toRadians(lat))
            Spot(kind, name, la, lo, d, (Math.toDegrees(kotlin.math.atan2(east, north)) + 360) % 360)
        }
        // Jejantas: banyak segmen jalan untuk satu jejantas — buang yang terlalu dekat antara satu sama lain
        val out = mutableListOf<Spot>()
        for (s in spots.sortedBy { it.distKm }) {
            if (out.none { it.kind == s.kind && Route.km(it.lat to it.lon, s.lat to s.lon) < 0.25 }) out += s
        }
        return out.take(12)
    }
}
