package com.jagarider

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Firebase melalui REST (tiada SDK): log masuk tanpa nama (Anonymous Auth) + Realtime Database.
 * Projek ditetapkan dalam firebase.properties (dbUrl, apiKey) → BuildConfig. Semua panggilan di thread latar.
 * Data lokasi disulitkan sebelum dihantar (lihat RiderGroup) — pelayan tak boleh baca lokasi.
 */
object Firebase {

    val enabled: Boolean get() = BuildConfig.FIREBASE_DB.startsWith("https://") && BuildConfig.FIREBASE_KEY.isNotBlank()

    private fun sp(c: Context) = c.getSharedPreferences("firebase", Context.MODE_PRIVATE)

    /** ID pengguna tanpa nama (tetap untuk phone ini). */
    fun uid(c: Context): String? = sp(c).getString("uid", null)

    /** Token sah (log masuk / perbaharui bila perlu). */
    @Synchronized
    fun token(c: Context): String {
        val p = sp(c)
        val tok = p.getString("id_token", null)
        if (tok != null && System.currentTimeMillis() < p.getLong("expires", 0L) - 60_000L) return tok
        val refresh = p.getString("refresh", null)
        val refreshed = refresh != null && runCatching {
            val json = post(
                "https://securetoken.googleapis.com/v1/token?key=${BuildConfig.FIREBASE_KEY}",
                "grant_type=refresh_token&refresh_token=" + URLEncoder.encode(refresh, "UTF-8"),
                "application/x-www-form-urlencoded",
            )
            save(c, json.getString("id_token"), json.getString("refresh_token"), json.getString("user_id"), json.optString("expires_in", "3600"))
        }.isSuccess
        if (!refreshed) {
            // Kali pertama, atau token lama tak sah lagi — daftar pengguna tanpa nama baru
            val json = post(
                "https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=${BuildConfig.FIREBASE_KEY}",
                """{"returnSecureToken":true}""",
                "application/json",
            )
            save(c, json.getString("idToken"), json.getString("refreshToken"), json.getString("localId"), json.optString("expiresIn", "3600"))
        }
        return p.getString("id_token", null)!!
    }

    private fun save(c: Context, token: String, refresh: String, uid: String, expiresIn: String) {
        sp(c).edit()
            .putString("id_token", token)
            .putString("refresh", refresh)
            .putString("uid", uid)
            .putLong("expires", System.currentTimeMillis() + (expiresIn.toLongOrNull() ?: 3600L) * 1000L)
            .commit()
    }

    // ------------------------------------------------------------ Realtime Database

    private fun url(c: Context, path: String) = "${BuildConfig.FIREBASE_DB}/$path.json?auth=${token(c)}"

    fun put(c: Context, path: String, json: String) {
        request("PUT", url(c, path), json)
    }

    fun get(c: Context, path: String): String = request("GET", url(c, path), null)

    fun delete(c: Context, path: String) {
        request("DELETE", url(c, path), null)
    }

    private fun post(url: String, body: String, type: String): JSONObject = JSONObject(request("POST", url, body, type))

    private fun request(method: String, url: String, body: String?, type: String = "application/json"): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", type)
            conn.outputStream.use { it.write(body.toByteArray()) }
        }
        try {
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("HTTP $code ${text.take(120)}")
            return text
        } finally {
            conn.disconnect()
        }
    }
}
