package com.jagarider

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.LruCache
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.tan

/**
 * Radar hujan SEBENAR dari RainViewer (percuma, tiada API key) — untuk "hujan sekarang".
 * Ramalan (2 jam / 6 jam) kekal dari Open-Meteo.
 *
 * Tile radar: zum maksimum 7 (~1.2 km sepiksel di Malaysia), skema warna "Universal Blue" sahaja.
 * Warna piksel → dBZ ikut jadual warna rasmi RainViewer → mm/jam (Marshall-Palmer Z = 200·R^1.6).
 */
object Radar {

    const val ZOOM = 7
    /** Gema di bawah ini dikira tiada hujan (awan / gangguan). 12 dBZ ≈ 0.2 mm/jam = renyai. */
    private const val RAIN_DBZ = 12

    data class Frame(val host: String, val path: String, val time: Long) {
        /** smooth = untuk paparan (lebih licin); tanpa smooth = warna tepat untuk dibaca. */
        fun tileUrl(z: Int, x: Int, y: Int, smooth: Boolean) =
            "$host$path/256/$z/$x/$y/2/${if (smooth) "1_1" else "0_0"}.png"
    }

    /** Hujan radar dekat satu titik: jarak & arah dari rider. */
    data class Hit(val distKm: Double, val bearing: Double, val mmh: Double) {
        val level get() = Weather.Level.of(mmh)
    }

    /** Hasil imbasan radar dalam kawasan. */
    data class Scan(
        val frame: Frame,
        val centerMmh: Double,   // di lokasi rider (maks dalam ~1.5 km)
        val nearest: Hit?,       // hujan terdekat dalam kawasan
        val heaviest: Hit?,      // paling lebat dalam kawasan
        val heaviestNear: Hit?,  // paling lebat dalam 50 km (untuk keputusan ride)
    )

    @Volatile private var frame: Frame? = null
    @Volatile private var frameAt = 0L
    private val tiles = LruCache<String, Bitmap>(24)

    /** Frame radar terbaru (disimpan 5 minit). Di thread latar. */
    fun latest(): Frame? {
        frame?.takeIf { System.currentTimeMillis() - frameAt < 5 * 60_000L }?.let { return it }
        return try {
            val json = JSONObject(get("https://api.rainviewer.com/public/weather-maps.json").decodeToString())
            val past = json.getJSONObject("radar").getJSONArray("past")
            if (past.length() == 0) return frame
            val last = past.getJSONObject(past.length() - 1)
            Frame(json.getString("host"), last.getString("path"), last.getLong("time") * 1000).also {
                frame = it
                frameAt = System.currentTimeMillis()
            }
        } catch (_: Exception) {
            frame
        }
    }

    /** Frame terakhir yang diketahui (untuk peta, tanpa rangkaian). */
    fun current(): Frame? = frame

    // ------------------------------------------------------------ warna → keamatan

    /** Warna "Universal Blue" untuk dBZ -10..75 (RRGGBBAA), dari rainviewer_api_colors_table.csv. */
    private const val MIN_DBZ = -10
    private val PALETTE: IntArray = (
        "6361591466635a1969665c1e6c685d246f6b5f29726e612e75706234787364397c75653e7f786744827b6949857d6a4e" +
        "88806c548b826d598e856f5e928871649e93756eaa9e7978b6a97e82c2b4828ccec08796d2c48ba0d6c88faadacc93b4" +
        "ded097be88ddeeff6cd1ebff51c5e8ff36bae5ff1baee2ff00a3e0ff009ad5ff0091caff0088bfff007fb4ff0077aaff" +
        "0070a3ff00699cff006295ff005b8eff005588ff005180ff004e78ff004a70ff004768ffffee00ffffe000ffffd200ff" +
        "ffc500ffffb700ffffaa00ffff9f00ffff9500ffff8b00ffff8100ffff4400fff23600ffe62800ffd91b00ffcd0d00ff" +
        "c10000ffa80000ff8f0000ff760000ff5d0000ffffaaffffff9fffffff95ffffff8bffffff81ffffff77ffffff6cffff" +
        "ff62ffffff58ffffff4effffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff" +
        "ffffffff00ff00ff"
        ).chunked(8).map { h ->
            val v = h.toLong(16)
            Color.argb((v and 0xFF).toInt(), (v shr 24 and 0xFF).toInt(), (v shr 16 and 0xFF).toInt(), (v shr 8 and 0xFF).toInt())
        }.toIntArray()

    /** Piksel → dBZ (warna terdekat dalam jadual), null kalau lutsinar. */
    fun dbz(argb: Int): Int? {
        val a = Color.alpha(argb)
        if (a < 16) return null
        val r = Color.red(argb)
        val g = Color.green(argb)
        val b = Color.blue(argb)
        var best = 0
        var bestD = Int.MAX_VALUE
        for ((i, c) in PALETTE.withIndex()) {
            val d = sq(r - Color.red(c)) + sq(g - Color.green(c)) + sq(b - Color.blue(c)) + sq(a - Color.alpha(c))
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        return best + MIN_DBZ
    }

    private fun sq(v: Int) = v * v

    /** dBZ → mm/jam (Marshall-Palmer). */
    fun mmh(dbz: Int): Double = if (dbz < RAIN_DBZ) 0.0 else (10.0.pow(dbz / 10.0) / 200.0).pow(1 / 1.6)

    // ------------------------------------------------------------ imbasan kawasan

    private fun mercX(lon: Double) = (lon + 180.0) / 360.0
    private fun mercY(lat: Double): Double {
        val r = Math.toRadians(lat)
        return (1 - ln(tan(r) + 1 / cos(r)) / PI) / 2
    }
    private fun lonOf(x: Double) = x * 360.0 - 180.0
    private fun latOf(y: Double) = Math.toDegrees(kotlin.math.atan(kotlin.math.sinh(PI * (1 - 2 * y))))

    /**
     * Imbas setiap piksel radar dalam kawasan (bulatan sekeliling rider, atau segi empat untuk Semenanjung).
     * Di thread latar. Null kalau radar tak dapat dicapai.
     */
    fun scan(riderLat: Double, riderLon: Double, area: Weather.Area): Scan? {
        val f = latest() ?: return null
        val world = 256.0 * (1 shl ZOOM)
        val kmPerDegLat = 111.32
        val kmPerDegLon = 111.32 * cos(Math.toRadians(area.lat))
        val nLat = area.lat + area.halfKm / kmPerDegLat
        val sLat = area.lat - area.halfKm / kmPerDegLat
        val wLon = area.lon - area.halfKm / kmPerDegLon
        val eLon = area.lon + area.halfKm / kmPerDegLon
        val px0 = floor(mercX(wLon) * world).toInt()
        val px1 = floor(mercX(eLon) * world).toInt()
        val py0 = floor(mercY(nLat) * world).toInt()
        val py1 = floor(mercY(sLat) * world).toInt()
        val rx = mercX(riderLon) * world
        val ry = mercY(riderLat) * world

        var center = 0.0
        var nearest: Hit? = null
        var heaviest: Hit? = null
        var heaviestNear: Hit? = null
        var anyTile = false
        for (ty in floor(py0 / 256.0).toInt()..floor(py1 / 256.0).toInt()) {
            for (tx in floor(px0 / 256.0).toInt()..floor(px1 / 256.0).toInt()) {
                val bmp = tile(f, tx, ty) ?: continue
                anyTile = true
                val xs = maxOf(px0, tx * 256)
                val xe = minOf(px1, tx * 256 + 255)
                val ys = maxOf(py0, ty * 256)
                val ye = minOf(py1, ty * 256 + 255)
                for (py in ys..ye) for (px in xs..xe) {
                    val d = dbz(bmp.getPixel(px - tx * 256, py - ty * 256)) ?: continue
                    val v = mmh(d)
                    if (v <= 0.0) continue
                    val lat = latOf((py + 0.5) / world)
                    val lon = lonOf((px + 0.5) / world)
                    val north = (lat - riderLat) * kmPerDegLat
                    val east = (lon - riderLon) * 111.32 * cos(Math.toRadians((lat + riderLat) / 2))
                    val dist = hypot(east, north)
                    if (area.aroundRider && dist > area.halfKm) continue
                    // lokasi rider = piksel dalam ~1.5 km
                    if (hypot(px + 0.5 - rx, py + 0.5 - ry) <= 1.5) center = maxOf(center, v)
                    val hit = Hit(dist, (Math.toDegrees(atan2(east, north)) + 360) % 360, v)
                    if (nearest == null || dist < nearest.distKm) nearest = hit
                    if (heaviest == null || v > heaviest.mmh) heaviest = hit
                    if (dist <= 50.0 && (heaviestNear == null || v > heaviestNear.mmh)) heaviestNear = hit
                }
            }
        }
        if (!anyTile) return null
        return Scan(f, center, nearest, heaviest, heaviestNear)
    }

    private fun tile(f: Frame, x: Int, y: Int): Bitmap? {
        val n = 1 shl ZOOM
        if (y < 0 || y >= n) return null
        val key = "${f.path}/$x/$y"
        tiles.get(key)?.let { return it }
        return try {
            val bytes = get(f.tileUrl(ZOOM, Math.floorMod(x, n), y, smooth = false))
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inPremultiplied = false })
                ?.also { tiles.put(key, it) }
        } catch (_: Exception) {
            null
        }
    }

    private fun get(url: String): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("User-Agent", "JagaRide/${BuildConfig.VERSION_NAME} (+https://github.com/Muzamilmohd/JagaRider)")
        try {
            if (conn.responseCode != 200) throw IllegalStateException("HTTP ${conn.responseCode}")
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }
}
