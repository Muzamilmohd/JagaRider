package com.jagarider

import android.content.Context
import android.location.Location
import org.json.JSONArray
import org.json.JSONObject

/**
 * Rekod ride: jarak, masa bergerak, purata & laju maksimum — dikira dalam phone sahaja semasa
 * LocationShareService berjalan (kongsi dengan kumpulan atau ride solo). 30 ride terakhir disimpan.
 */
object RideLog {

    data class Ride(val start: Long, val end: Long, val km: Double, val movingMs: Long, val maxKmh: Double, val label: String) {
        val avgKmh: Double get() = if (movingMs > 60_000) km / (movingMs / 3_600_000.0) else 0.0
    }

    private fun sp(c: Context) = c.getSharedPreferences("rides", Context.MODE_PRIVATE)

    /** Ride solo: rekod tanpa kongsi dengan kumpulan. */
    fun solo(c: Context): Boolean = sp(c).getBoolean("solo", false)
    fun setSolo(c: Context, on: Boolean) {
        sp(c).edit().putBoolean("solo", on).apply()
    }

    // sesi semasa (servis)
    @Volatile private var start = 0L
    private var prev: Location? = null
    private var km = 0.0
    private var movingMs = 0L
    private var maxKmh = 0.0
    @Volatile var label = ""

    fun begin(c: Context) {
        if (start != 0L) return
        start = System.currentTimeMillis()
        prev = null
        km = 0.0
        movingMs = 0L
        maxKmh = 0.0
    }

    /** Tambah satu lokasi GPS. Abaikan bacaan kurang tepat dan lompatan mustahil. */
    @Synchronized
    fun add(loc: Location) {
        if (start == 0L || (loc.hasAccuracy() && loc.accuracy > 35f)) return
        val p = prev
        if (p == null) {
            prev = loc
            return
        }
        val d = p.distanceTo(loc) / 1000.0
        val dt = loc.time - p.time
        if (dt <= 0) return
        val kmh = d / (dt / 3_600_000.0)
        if (kmh > 250) return // lompatan GPS
        if (d < 0.01) return  // < 10 m — tunggu gerakan sebenar
        km += d
        if (kmh >= 5) movingMs += dt
        val sp = if (loc.hasSpeed()) loc.speed * 3.6 else kmh
        if (sp in 0.0..250.0) maxKmh = maxOf(maxKmh, sp)
        prev = loc
    }

    /** Tamat sesi; simpan kalau ride sebenar (> 0.5 km). */
    @Synchronized
    fun finish(c: Context) {
        if (start == 0L) return
        val r = Ride(start, System.currentTimeMillis(), km, movingMs, maxKmh, label)
        start = 0L
        if (r.km < 0.5) return
        val arr = JSONArray(sp(c).getString("list", "[]"))
        arr.put(JSONObject().put("s", r.start).put("e", r.end).put("k", r.km).put("m", r.movingMs).put("x", r.maxKmh).put("l", r.label))
        val keep = JSONArray()
        for (i in maxOf(0, arr.length() - 30) until arr.length()) keep.put(arr.get(i))
        sp(c).edit().putString("list", keep.toString()).apply()
    }

    /** Ride terkini dahulu. */
    fun rides(c: Context): List<Ride> {
        val arr = JSONArray(sp(c).getString("list", "[]"))
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map {
            Ride(it.getLong("s"), it.getLong("e"), it.getDouble("k"), it.getLong("m"), it.getDouble("x"), it.optString("l"))
        }.reversed()
    }

    /** Sesi semasa (untuk dipapar semasa ride). */
    fun current(): Triple<Double, Long, Double>? = if (start == 0L) null else Triple(km, movingMs, maxKmh)
    fun currentStart(): Long = start
}
