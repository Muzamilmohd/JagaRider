package com.jagarider

import android.content.Context

/** Tetapan ringkas. */
object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("jagarider", Context.MODE_PRIVATE)

    const val THEME_SYSTEM = "system"
    const val THEME_LIGHT = "light"
    const val THEME_DARK = "dark"

    /** Lalai Bahasa Melayu (bukan bahasa telefon) — pengguna tukar di Tetapan. */
    fun lang(c: Context): Lang = Lang.of(sp(c).getString("lang", null))
    fun setLang(c: Context, lang: Lang) {
        sp(c).edit().putString("lang", lang.code).apply()
    }

    /** Mod paparan: ikut sistem / cerah / gelap. */
    fun theme(c: Context): String = sp(c).getString("theme", THEME_SYSTEM) ?: THEME_SYSTEM
    fun setTheme(c: Context, theme: String) {
        sp(c).edit().putString("theme", theme).apply()
    }

    /** Format jam 24 jam? Lalai ikut tetapan telefon. */
    fun clock24(c: Context): Boolean =
        if (sp(c).contains("clock24")) sp(c).getBoolean("clock24", true) else android.text.format.DateFormat.is24HourFormat(c)
    fun setClock24(c: Context, on: Boolean) {
        sp(c).edit().putBoolean("clock24", on).apply()
    }

    /** Jarak semakan hujan sekeliling rider (km). */
    fun rangeKm(c: Context): Int = sp(c).getInt("range_km", 10)
    fun setRangeKm(c: Context, km: Int) {
        sp(c).edit().putInt("range_km", km).apply()
    }

    /** Amaran hujan (notifikasi berbunyi) di latar. */
    fun rainAlert(c: Context): Boolean = sp(c).getBoolean("rain_alert", true)
    fun setRainAlert(c: Context, on: Boolean) {
        sp(c).edit().putBoolean("rain_alert", on).apply()
    }

    /** Jarak kawasan amaran hujan (km). */
    fun alertKm(c: Context): Int = sp(c).getInt("alert_km", 10)
    fun setAlertKm(c: Context, km: Int) {
        sp(c).edit().putInt("alert_km", km).apply()
    }

    /** Lokasi terakhir rider (semasa app dibuka) — digunakan semakan amaran di latar. */
    fun lastLocation(c: Context): Pair<Double, Double>? {
        val p = sp(c)
        if (!p.contains("last_lat")) return null
        return p.getFloat("last_lat", 0f).toDouble() to p.getFloat("last_lon", 0f).toDouble()
    }
    fun setLastLocation(c: Context, lat: Double, lon: Double) {
        sp(c).edit().putFloat("last_lat", lat.toFloat()).putFloat("last_lon", lon.toFloat()).apply()
    }

    /** Tahap amaran terakhir (RainAlert.NONE/RAIN/STORM) dan masa notifikasi terakhir. */
    fun alertState(c: Context): Int = sp(c).getInt("alert_state", 0)
    fun alertTime(c: Context): Long = sp(c).getLong("alert_time", 0L)
    fun setAlertState(c: Context, state: Int, time: Long) {
        sp(c).edit().putInt("alert_state", state).putLong("alert_time", time).apply()
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
