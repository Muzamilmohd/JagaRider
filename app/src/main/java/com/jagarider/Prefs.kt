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
