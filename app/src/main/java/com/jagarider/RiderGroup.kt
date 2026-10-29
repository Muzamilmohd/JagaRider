package com.jagarider

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Kumpulan rider: kongsi lokasi live sesama ahli.
 *
 * Kod jemputan (cth. JR-7KQ2-M9XA-…) ialah rahsia kumpulan. Daripadanya diterbitkan:
 *  - gid  = SHA-256("gid:"+kod) → laluan dalam Firebase (pelayan hanya nampak ini)
 *  - kunci = SHA-256("key:"+kod) → AES-GCM; nama & lokasi disulitkan, pelayan tak boleh baca.
 * Sesiapa yang ada kod boleh sertai — kongsi kod hanya dengan rider yang dipercayai.
 */
object RiderGroup {

    data class Group(val code: String, val gid: String, val key: ByteArray, val name: String, val icon: String = DEFAULT_ICON)

    const val DEFAULT_ICON = "🏍️"

    /** Pilihan ikon kumpulan. */
    val ICONS = listOf(
        "🏍️", "🛵", "🏁", "🔥", "⚡", "🌧️", "🦅", "🐯", "🦁", "🐺", "🚀", "⭐",
        "🛣️", "🏔️", "🌊", "🌴", "🎯", "💪", "❤️", "🇲🇾", "☕", "🍜", "🎓", "👥",
    )

    data class Rider(
        val uid: String,
        val name: String,
        val lat: Double,
        val lon: Double,
        val speedKmh: Double,
        val time: Long,      // masa pelayan (ms) kemas kini terakhir
        val bearing: Double, // arah perjalanan (darjah dari utara), NaN kalau tiada
        val me: Boolean,
    )

    // 32 aksara tanpa 0/O/1/I supaya senang dibaca & ditaip
    private const val ALPHA = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private val random = SecureRandom()

    /** Kod baru: 20 aksara rawak (100 bit) → "JR-XXXX-XXXX-XXXX-XXXX-XXXX". */
    fun newCode(): String = "JR-" + (1..20).map { ALPHA[random.nextInt(ALPHA.length)] }.chunked(4).joinToString("-") { it.joinToString("") }

    /** Cari kod dalam teks (cth. mesej WhatsApp ditampal penuh). */
    fun parse(text: String): String? {
        val m = Regex("JR-?((?:[A-Z2-9]{4}-?){5})", RegexOption.IGNORE_CASE).find(text) ?: return null
        val raw = m.groupValues[1].replace("-", "").uppercase()
        if (raw.any { it !in ALPHA }) return null
        return "JR-" + raw.chunked(4).joinToString("-")
    }

    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())

    private fun derive(code: String, name: String, icon: String = DEFAULT_ICON) = Group(
        code = code,
        gid = sha("gid:$code").joinToString("") { "%02x".format(it) }.take(32),
        key = sha("key:$code").copyOf(16),
        name = name,
        icon = icon,
    )

    private fun encrypt(key: ByteArray, plain: String): String {
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return Base64.encodeToString(iv + c.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }

    private fun decrypt(key: ByteArray, data: String): String {
        val bytes = Base64.decode(data, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, bytes, 0, 12))
        return String(c.doFinal(bytes, 12, bytes.size - 12))
    }

    // ------------------------------------------------------------ senarai kumpulan (boleh lebih dari satu)

    private fun sp(c: Context) = c.getSharedPreferences("rider_group", Context.MODE_PRIVATE)

    /** Semua kumpulan yang saya cipta / sertai, ikut susunan ditambah. */
    fun groups(c: Context): List<Group> {
        migrate(c)
        val arr = JSONArray(sp(c).getString("groups", "[]"))
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .map { derive(it.getString("code"), it.optString("name"), it.optString("icon", DEFAULT_ICON)) }
    }

    fun find(c: Context, code: String): Group? = groups(c).firstOrNull { it.code == code }

    private fun saveGroups(c: Context, list: List<Group>) {
        val arr = JSONArray()
        for (g in list) arr.put(JSONObject().put("code", g.code).put("name", g.name).put("icon", g.icon))
        sp(c).edit().putString("groups", arr.toString()).apply()
    }

    private fun add(c: Context, g: Group) = saveGroups(c, groups(c).filter { it.code != g.code } + g)

    /** Ganti maklumat kumpulan (nama / ikon) dalam senarai, kekalkan susunan. */
    private fun replace(c: Context, g: Group) = saveGroups(c, groups(c).map { if (it.code == g.code) g else it })

    /** v2.2–v2.4 simpan satu kumpulan sahaja ("code"/"name") — pindah ke senarai. */
    private fun migrate(c: Context) {
        val p = sp(c)
        val old = p.getString("code", null) ?: return
        val arr = JSONArray(p.getString("groups", "[]"))
        arr.put(JSONObject().put("code", old).put("name", p.getString("name", "").orEmpty()))
        p.edit().putString("groups", arr.toString()).remove("code").remove("name").apply()
    }

    fun riderName(c: Context): String = sp(c).getString("rider", "").orEmpty()
    fun setRiderName(c: Context, name: String) {
        sp(c).edit().putString("rider", name.trim().take(24)).apply()
    }

    // ------------------------------------------------------------ kongsi lokasi (dengan tempoh)

    /** Tamat kongsi bagi setiap kumpulan: kod → masa (ms), 0 = sehingga ditekan Henti. */
    private fun untilMap(c: Context): JSONObject = JSONObject(sp(c).getString("share_until", "{}") ?: "{}")

    /** Kumpulan yang sedang menerima lokasi saya (kod) — yang dah tamat tempoh tak dikira. */
    fun sharing(c: Context): Set<String> {
        val m = untilMap(c)
        val now = System.currentTimeMillis()
        val legacy = sp(c).getStringSet("sharing", emptySet())!! // v2.5: tanpa tempoh
        return (m.keys().asSequence().filter { m.optLong(it) == 0L || m.optLong(it) > now } + legacy.asSequence()).toSet()
    }

    /** Semua kod dalam senarai kongsi, termasuk yang dah tamat tempoh (untuk dibersihkan). */
    fun sharingRaw(c: Context): Set<String> = untilMap(c).keys().asSequence().toSet() + sp(c).getStringSet("sharing", emptySet())!!

    /** Masa tamat kongsi (ms), 0 = sehingga ditekan Henti. */
    fun shareUntil(c: Context, code: String): Long = untilMap(c).optLong(code, 0L)

    fun setSharing(c: Context, code: String, on: Boolean, until: Long = 0L) {
        val m = untilMap(c)
        if (on) m.put(code, until) else m.remove(code)
        val legacy = sp(c).getStringSet("sharing", emptySet())!!.toMutableSet().apply { remove(code) }
        sp(c).edit().putString("share_until", m.toString()).putStringSet("sharing", legacy).apply()
    }

    fun clearSharing(c: Context) {
        sp(c).edit().remove("sharing").remove("share_until").apply()
    }

    // ------------------------------------------------------------ cipta / sertai / ubah

    private fun metaJson(g: Group) = JSONObject()
        .put("n", encrypt(g.key, g.name))
        .put("i", encrypt(g.key, g.icon))
        .put("t", serverTime())
        .toString()

    /** Cipta kumpulan baru (rangkaian). */
    fun create(c: Context, name: String, icon: String = DEFAULT_ICON): Group {
        val g = derive(newCode(), name.trim().take(30), icon)
        Firebase.put(c, "groups/${g.gid}/meta", metaJson(g))
        add(c, g)
        return g
    }

    /** Sertai dengan kod (rangkaian). Null kalau kumpulan tak wujud. */
    fun join(c: Context, code: String): Group? {
        val g = readMeta(c, derive(code, "", DEFAULT_ICON)) ?: return null
        add(c, g)
        return g
    }

    /** Baca nama & ikon terkini dari pelayan (null kalau kumpulan tak wujud / rosak). */
    private fun readMeta(c: Context, probe: Group): Group? {
        val meta = Firebase.get(c, "groups/${probe.gid}/meta")
        if (meta == "null") return null
        val o = JSONObject(meta)
        val name = runCatching { decrypt(probe.key, o.getString("n")) }.getOrNull() ?: return null
        val icon = runCatching { decrypt(probe.key, o.getString("i")) }.getOrNull() ?: DEFAULT_ICON
        return probe.copy(name = name, icon = icon)
    }

    /** Kemas kini nama / ikon dari pelayan (ahli lain mungkin dah tukar). Pulang kumpulan terkini. */
    fun refreshMeta(c: Context, g: Group): Group {
        val fresh = runCatching { readMeta(c, g) }.getOrNull() ?: return g
        if (fresh.name != g.name || fresh.icon != g.icon) replace(c, fresh)
        return fresh
    }

    /**
     * Tukar nama / ikon kumpulan untuk semua ahli. Disimpan dalam phone dulu; pulang false kalau pelayan
     * menolak (peraturan Firebase lama — v2.2 hanya benarkan meta ditulis sekali).
     */
    fun edit(c: Context, g: Group, name: String, icon: String): Boolean {
        val n = g.copy(name = name.trim().take(30).ifBlank { g.name }, icon = icon)
        replace(c, n)
        return runCatching { Firebase.put(c, "groups/${n.gid}/meta", metaJson(n)) }.isSuccess
    }

    /** Keluar kumpulan: buang lokasi saya dari pelayan dan dari senarai. */
    fun leave(c: Context, g: Group) {
        runCatching { removeMe(c, g) }
        setSharing(c, g.code, false)
        saveGroups(c, groups(c).filter { it.code != g.code })
    }

    // ------------------------------------------------------------ lokasi

    private fun serverTime() = JSONObject().put(".sv", "timestamp")

    fun publish(c: Context, g: Group, lat: Double, lon: Double, speedKmh: Double, bearing: Double) {
        val uid = Firebase.uid(c) ?: Firebase.token(c).let { Firebase.uid(c)!! }
        val plain = JSONObject()
            .put("n", riderName(c).ifBlank { "Rider" })
            .put("a", lat)
            .put("o", lon)
            .put("s", speedKmh)
        if (!bearing.isNaN()) plain.put("b", bearing)
        Firebase.put(c, "groups/${g.gid}/m/$uid", JSONObject().put("d", encrypt(g.key, plain.toString())).put("t", serverTime()).toString())
    }

    fun removeMe(c: Context, g: Group) {
        val uid = Firebase.uid(c) ?: return
        Firebase.delete(c, "groups/${g.gid}/m/$uid")
    }

    /** Semua ahli yang pernah kongsi lokasi (yang lama ditapis oleh pemanggil). */
    fun fetch(c: Context, g: Group): List<Rider> {
        val json = Firebase.get(c, "groups/${g.gid}/m")
        if (json == "null") return emptyList()
        val me = Firebase.uid(c)
        val o = JSONObject(json)
        return o.keys().asSequence().mapNotNull { uid ->
            runCatching {
                val e = o.getJSONObject(uid)
                val d = JSONObject(decrypt(g.key, e.getString("d")))
                Rider(uid, d.optString("n", "Rider"), d.getDouble("a"), d.getDouble("o"), d.optDouble("s", 0.0), e.optLong("t"), d.optDouble("b", Double.NaN), uid == me)
            }.getOrNull()
        }.toList()
    }
}
