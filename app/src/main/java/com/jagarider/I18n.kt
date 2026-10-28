package com.jagarider


/** Bahasa yang disokong. Nama dipapar dalam bahasa itu sendiri. */
enum class Lang(val code: String, val nativeName: String) {
    MS("ms", "Bahasa Melayu"),
    EN("en", "English"),
    ZH("zh", "中文"),
    TA("ta", "தமிழ்");

    companion object {
        fun of(code: String?): Lang = entries.firstOrNull { it.code == code } ?: MS
    }
}

object I18n {
    @Volatile
    var lang: Lang = Lang.MS
}

/**
 * Teks ikut bahasa semasa. Semua teks yang pengguna nampak mesti lalu sini — sentiasa isi keempat-empat.
 * Dalam template Kotlin sebelah tulisan Cina/Tamil guna `${name}`, bukan `$name`.
 */
fun t(ms: String, en: String, zh: String, ta: String): String = when (I18n.lang) {
    Lang.MS -> ms
    Lang.EN -> en
    Lang.ZH -> zh
    Lang.TA -> ta
}

/** Format jam 12 / 24 jam (Tetapan → Paparan; lalai ikut telefon). */
object Clock {
    @Volatile
    var h24: Boolean = true

    /** "14:15" (dari API) → "14:15" atau "2:15 PM". */
    fun hm(s: String): String {
        if (h24) return s
        val h = s.substringBefore(':').toIntOrNull() ?: return s
        val m = s.substringAfter(':', "00").take(2)
        return "${if (h % 12 == 0) 12 else h % 12}:$m ${if (h < 12) "AM" else "PM"}"
    }

    /** Jam penuh untuk jalur ramalan: "14:00" atau "2 PM". */
    fun hour(s: String): String {
        if (h24) return s
        val h = s.substringBefore(':').toIntOrNull() ?: return s
        return "${if (h % 12 == 0) 12 else h % 12} ${if (h < 12) "AM" else "PM"}"
    }

    /** Masa dari milisaat. */
    fun time(millis: Long): String =
        hm(java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(millis)))
}
