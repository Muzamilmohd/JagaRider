package com.jagarider

import android.content.Context

/** Tetapan ringkas. */
object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("jagarider", Context.MODE_PRIVATE)

    /** Jarak semakan hujan sekeliling rider (km). */
    fun rangeKm(c: Context): Int = sp(c).getInt("range_km", 10)
    fun setRangeKm(c: Context, km: Int) {
        sp(c).edit().putInt("range_km", km).apply()
    }

    fun autoUpdate(c: Context): Boolean = sp(c).getBoolean("auto_update", true)
    fun setAutoUpdate(c: Context, on: Boolean) {
        sp(c).edit().putBoolean("auto_update", on).apply()
    }

    fun lastUpdateCheck(c: Context): Long = sp(c).getLong("last_update_check", 0L)
    fun setLastUpdateCheck(c: Context, t: Long) {
        sp(c).edit().putLong("last_update_check", t).apply()
    }

    fun notifiedVersion(c: Context): String = sp(c).getString("notified_version", "").orEmpty()
    fun setNotifiedVersion(c: Context, v: String) {
        sp(c).edit().putString("notified_version", v).apply()
    }
}
