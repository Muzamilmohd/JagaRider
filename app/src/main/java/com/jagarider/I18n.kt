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
