package com.jagarider

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Jubin peta OpenStreetMap: cache memori + cache fail 7 hari, maksimum 2 sambungan serentak
 * (ikut polisi penggunaan jubin OSM). Hanya jubin yang kelihatan dimuat turun.
 */
object MapTiles {

    private const val MAX_AGE = 7 * 24 * 60 * 60_000L
    private const val RETRY_MS = 60_000L

    private val mem = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val pending = Collections.synchronizedSet(HashSet<String>())
    private val failed = ConcurrentHashMap<String, Long>()
    private val pool = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    /** Bitmap jubin kalau sudah ada; kalau belum, mula muat turun dan panggil onReady bila siap. */
    fun get(ctx: Context, z: Int, x: Int, y: Int, onReady: () -> Unit): Bitmap? {
        val key = "$z/$x/$y"
        mem.get(key)?.let { return it }
        if (System.currentTimeMillis() - (failed[key] ?: 0L) < RETRY_MS) return null
        if (!pending.add(key)) return null
        val dir = File(ctx.applicationContext.cacheDir, "tiles")
        pool.execute {
            var bmp: Bitmap? = null
            try {
                val f = File(dir, "$z-$x-$y.png")
                if (f.exists() && System.currentTimeMillis() - f.lastModified() < MAX_AGE) bmp = BitmapFactory.decodeFile(f.path)
                if (bmp == null) bmp = download(key, f)
                // tiada internet: guna jubin lama pun tak apa
                if (bmp == null && f.exists()) bmp = BitmapFactory.decodeFile(f.path)
            } catch (_: Exception) {
            } finally {
                if (bmp != null) mem.put(key, bmp) else failed[key] = System.currentTimeMillis()
                pending.remove(key)
                main.post(onReady)
            }
        }
        return null
    }

    private fun download(key: String, f: File): Bitmap? {
        val conn = URL("https://tile.openstreetmap.org/$key.png").openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("User-Agent", "JagaRider/${BuildConfig.VERSION_NAME} (+https://github.com/Muzamilmohd/JagaRider)")
        try {
            if (conn.responseCode != 200) return null
            val bytes = conn.inputStream.use { it.readBytes() }
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            f.parentFile?.mkdirs()
            f.writeBytes(bytes)
            return bmp
        } finally {
            conn.disconnect()
        }
    }
}
