package com.jagarider

import android.content.Context
import android.util.Base64
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

    data class Group(val code: String, val gid: String, val key: ByteArray, val name: String)

    data class Rider(
        val uid: String,
        val name: String,
        val lat: Double,
        val lon: Double,
        val speedKmh: Double,
        val time: Long,      // masa pelayan (ms) kemas kini terakhir
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

    private fun derive(code: String, name: String) = Group(
        code = code,
        gid = sha("gid:$code").joinToString("") { "%02x".format(it) }.take(32),
        key = sha("key:$code").copyOf(16),
        name = name,
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

    // ------------------------------------------------------------ kumpulan aktif (satu pada satu masa)

    private fun sp(c: Context) = c.getSharedPreferences("rider_group", Context.MODE_PRIVATE)

    fun active(c: Context): Group? {
        val code = sp(c).getString("code", null) ?: return null
        return derive(code, sp(c).getString("name", "").orEmpty())
    }

    fun riderName(c: Context): String = sp(c).getString("rider", "").orEmpty()
    fun setRiderName(c: Context, name: String) {
        sp(c).edit().putString("rider", name.trim().take(24)).apply()
    }

    private fun setActive(c: Context, g: Group?) {
        sp(c).edit().putString("code", g?.code).putString("name", g?.name).apply()
    }

    /** Cipta kumpulan baru (rangkaian). */
    fun create(c: Context, name: String): Group {
        val g = derive(newCode(), name.trim().take(30))
        Firebase.put(c, "groups/${g.gid}/meta", JSONObject().put("n", encrypt(g.key, g.name)).put("t", serverTime()).toString())
        setActive(c, g)
        return g
    }

    /** Sertai dengan kod (rangkaian). Null kalau kumpulan tak wujud. */
    fun join(c: Context, code: String): Group? {
        val probe = derive(code, "")
        val meta = Firebase.get(c, "groups/${probe.gid}/meta")
        if (meta == "null") return null
        val name = runCatching { decrypt(probe.key, JSONObject(meta).getString("n")) }.getOrNull() ?: return null
        val g = probe.copy(name = name)
        setActive(c, g)
        return g
    }

    fun leave(c: Context) {
        active(c)?.let { g -> runCatching { removeMe(c, g) } }
        setActive(c, null)
    }

    // ------------------------------------------------------------ lokasi

    private fun serverTime() = JSONObject().put(".sv", "timestamp")

    fun publish(c: Context, g: Group, lat: Double, lon: Double, speedKmh: Double) {
        val uid = Firebase.uid(c) ?: Firebase.token(c).let { Firebase.uid(c)!! }
        val plain = JSONObject()
            .put("n", riderName(c).ifBlank { "Rider" })
            .put("a", lat)
            .put("o", lon)
            .put("s", speedKmh)
            .toString()
        Firebase.put(c, "groups/${g.gid}/m/$uid", JSONObject().put("d", encrypt(g.key, plain)).put("t", serverTime()).toString())
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
                Rider(uid, d.optString("n", "Rider"), d.getDouble("a"), d.getDouble("o"), d.optDouble("s", 0.0), e.optLong("t"), uid == me)
            }.getOrNull()
        }.toList()
    }
}
