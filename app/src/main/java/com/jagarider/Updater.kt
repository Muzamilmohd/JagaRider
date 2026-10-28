package com.jagarider

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Kemas kini terus dari GitHub Releases (repo dalam BuildConfig.UPDATE_REPO).
 * Hanya minta maklumat release dari api.github.com — TIADA data pengguna dihantar.
 * Release: tag "v1.2" (sama dengan versionName) + satu fail .apk ditandatangan kunci yang sama.
 */
object Updater {

    data class Release(val version: String, val notes: String, val apkUrl: String, val size: Long)

    private val main = Handler(Looper.getMainLooper())

    val enabled: Boolean get() = BuildConfig.UPDATE_REPO.contains('/')
    val currentVersion: String get() = BuildConfig.VERSION_NAME

    /** Semak di thread latar. Hasil: release lebih baru, null (dah terkini), atau ralat. */
    fun check(onResult: (Release?, Exception?) -> Unit) {
        Thread {
            try {
                val rel = fetchLatest()
                val newer = rel?.takeIf { isNewer(it.version, currentVersion) }
                main.post { onResult(newer, null) }
            } catch (e: Exception) {
                main.post { onResult(null, e) }
            }
        }.start()
    }

    private fun fetchLatest(): Release? {
        val conn = URL("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
            .openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "JagaRider")
        try {
            if (conn.responseCode == 404) return null // belum ada release
            if (conn.responseCode != 200) throw IllegalStateException("GitHub HTTP ${conn.responseCode}")
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val assets = json.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                    return Release(
                        version = json.optString("tag_name").removePrefix("v").removePrefix("V"),
                        notes = json.optString("body").trim(),
                        apkUrl = a.getString("browser_download_url"),
                        size = a.optLong("size", -1),
                    )
                }
            }
            return null
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Nota release untuk dialog: hanya bahagian "apa yang baru" (berhenti di "Cara pasang"),
     * tanpa simbol markdown supaya senang dibaca.
     */
    fun shortNotes(notes: String): String =
        notes.lineSequence()
            .takeWhile { !Regex("(?i)^#+.*(cara pasang|how to install|安装)").containsMatchIn(it.trim()) }
            .map { line ->
                line.trim()
                    .replace(Regex("^#+\\s*"), "")
                    .replace(Regex("^>\\s*"), "")
                    .replace(Regex("^[-*]\\s+"), "• ")
                    .replace("**", "")
                    .replace(Regex("\\[([^]]+)]\\([^)]+\\)"), "$1")
            }
            .joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()

    /** "1.10" > "1.9" — banding nombor satu-satu. */
    fun isNewer(remote: String, local: String): Boolean {
        fun parts(v: String) = v.split('.', '-', ' ').map { p -> p.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
        val r = parts(remote)
        val l = parts(local)
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    /** Boleh pasang APK? Kalau belum, pengguna perlu benarkan "Pasang app tidak diketahui" untuk JagaRider. */
    fun canInstall(ctx: Context): Boolean = ctx.packageManager.canRequestPackageInstalls()

    /** Muat turun APK terus ke PackageInstaller — Android akan tanya pengesahan pengguna. */
    fun install(ctx: Context, rel: Release, onProgress: (Int) -> Unit, onError: (Exception) -> Unit) {
        val app = ctx.applicationContext
        Thread {
            try {
                val pi = app.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                params.setAppPackageName(app.packageName)
                if (rel.size > 0) params.setSize(rel.size)
                val id = pi.createSession(params)
                pi.openSession(id).use { session ->
                    val conn = URL(rel.apkUrl).openConnection() as HttpURLConnection
                    conn.connectTimeout = 15_000
                    conn.readTimeout = 30_000
                    conn.setRequestProperty("User-Agent", "JagaRider")
                    try {
                        val total = if (rel.size > 0) rel.size else conn.contentLengthLong
                        conn.inputStream.use { input ->
                            session.openWrite("jagarider.apk", 0, total).use { out ->
                                val buf = ByteArray(64 * 1024)
                                var done = 0L
                                var lastPct = -1
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    done += n
                                    if (total > 0) {
                                        val pct = (done * 100 / total).toInt()
                                        if (pct != lastPct) {
                                            lastPct = pct
                                            main.post { onProgress(pct) }
                                        }
                                    }
                                }
                                session.fsync(out)
                            }
                        }
                    } finally {
                        conn.disconnect()
                    }
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                        (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    val pending = PendingIntent.getBroadcast(app, id, Intent(app, InstallResultReceiver::class.java), flags)
                    session.commit(pending.intentSender)
                }
            } catch (e: Exception) {
                main.post { onError(e) }
            }
        }.start()
    }

    // ------------------------------------------------------------ semakan harian

    private const val JOB_ID = 4242
    private const val CH = "jagarider_update"

    /** Jadualkan / batalkan semakan automatik sekali sehari (perlu internet). */
    fun schedule(ctx: Context) {
        // Semakan update tak boleh sekali-kali buat app crash.
        try {
            val js = ctx.getSystemService(JobScheduler::class.java)
            if (!enabled || !Prefs.autoUpdate(ctx)) {
                js.cancel(JOB_ID)
                return
            }
            if (js.getPendingJob(JOB_ID) != null) return
            js.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(ctx, UpdateJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(24 * 60 * 60_000L)
                    .setPersisted(true)
                    .build()
            )
        } catch (_: Exception) {
        }
    }

    @SuppressLint("MissingPermission")
    fun notifyNew(ctx: Context, rel: Release) {
        if (Prefs.notifiedVersion(ctx) == rel.version) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH, "Kemas kini JagaRider", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val pi = PendingIntent.getActivity(
            ctx, 1,
            Intent(ctx, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_CHECK_UPDATE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(ctx, CH)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Versi baru JagaRider ${rel.version}")
            .setContentText("Tekan untuk kemas kini")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(JOB_ID, n)
            Prefs.setNotifiedVersion(ctx, rel.version)
        } catch (_: SecurityException) {
        }
    }
}

/** Semakan latar sekali sehari — keluarkan notifikasi kalau ada versi baru. */
class UpdateJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Updater.check { rel, _ ->
            if (rel != null) Updater.notifyNew(this, rel)
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}

/** Terima status daripada PackageInstaller — buka skrin pengesahan Android bila perlu. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirm?.let { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // app akan dimulakan semula oleh sistem
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                val text = "Kemas kini gagal" + " ($status) $msg"
                Toast.makeText(ctx, text, Toast.LENGTH_LONG).show()
            }
        }
    }
}
