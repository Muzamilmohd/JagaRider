package com.jagarider

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Amaran hujan di latar: kira-kira setiap 15 minit semak hujan di sekitar lokasi rider — lokasi live kalau
 * dibenarkan "sepanjang masa", kalau tak lokasi terakhir semasa app dibuka — dan keluarkan notifikasi berbunyi:
 * hujan biasa = bunyi hujan, hujan lebat / ribut petir = bunyi hujan + guruh.
 */
object RainAlert {

    private const val JOB_ID = 4343
    private const val NOTIF_ID = 4344
    // Bunyi saluran tak boleh diubah selepas dicipta — tukar ID kalau bunyi ditukar.
    private const val CH_RAIN = "rain_alert_v2"
    private const val CH_STORM = "storm_alert_v2"

    const val NONE = 0
    const val RAIN = 1
    const val STORM = 2

    /** Jangan ulang amaran tahap sama dalam tempoh ini. */
    private const val REPEAT_MS = 2 * 60 * 60_000L

    fun schedule(ctx: Context) {
        try {
            val js = ctx.getSystemService(JobScheduler::class.java)
            if (!Prefs.rainAlert(ctx)) {
                js.cancel(JOB_ID)
                return
            }
            if (js.getPendingJob(JOB_ID) != null) return
            js.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(ctx, RainAlertJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(15 * 60_000L)
                    .setPersisted(true)
                    .build()
            )
        } catch (_: Exception) {
        }
    }

    /** Di thread latar. Pulangkan true kalau semakan berjaya (ada lokasi + data). */
    fun check(ctx: Context): Boolean {
        I18n.lang = Prefs.lang(ctx)
        // Lokasi live kalau dibenarkan "sepanjang masa", kalau tak lokasi terakhir semasa app dibuka
        liveLocation(ctx)?.let { Prefs.setLastLocation(ctx, it.first, it.second) }
        val (lat, lon) = Prefs.lastLocation(ctx) ?: return false
        val km = Prefs.alertKm(ctx)
        // Grid kecil 5×5 — jimat had Open-Meteo kerana berjalan setiap 15 minit
        val area = Weather.Area(lat, lon, km.toDouble(), 5, aroundRider = true)
        val samples = runCatching { Weather.fetch(Weather.points(lat, lon, area)) }.getOrNull() ?: return false
        val me = samples.first()

        // Hujan sekarang dalam kawasan, atau dijangka di lokasi rider dalam 30 minit
        val nowRain = samples.filter { it.mmh >= 0.1 && it.point.distKm <= km * 1.01 }
        val soonMmh = me.nextMmh.take(2).maxOrNull() ?: 0.0
        val worst = maxOf(nowRain.maxOfOrNull { it.mmh } ?: 0.0, soonMmh)
        val thunder = samples.any { it.thunder && it.point.distKm <= km * 1.01 }
        val level = when {
            thunder || worst >= Weather.Level.HEAVY.min -> STORM
            nowRain.isNotEmpty() || soonMmh >= 0.1 -> RAIN
            else -> NONE
        }

        val last = Prefs.alertState(ctx)
        val since = System.currentTimeMillis() - Prefs.alertTime(ctx)
        if (level == NONE) {
            Prefs.setAlertState(ctx, NONE, Prefs.alertTime(ctx))
            return true
        }
        // Amaran bila hujan baru bermula, bertambah lebat, atau masih berterusan selepas 2 jam
        if (level > last || since > REPEAT_MS) {
            val msg = message(me, nowRain, soonMmh, thunder, km)
            notify(ctx, level, msg)
            Prefs.setAlertState(ctx, level, System.currentTimeMillis())
        } else {
            Prefs.setAlertState(ctx, level, Prefs.alertTime(ctx))
        }
        return true
    }

    /** Boleh dapat lokasi di latar? Android 10+ perlu "Benarkan sepanjang masa"; sebelum tu cukup kebenaran biasa. */
    fun hasBackgroundLocation(ctx: Context): Boolean {
        fun granted(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        val foreground = granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)
        return foreground && (Build.VERSION.SDK_INT < 29 || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
    }

    /** Lokasi semasa di thread latar (tunggu maks 20 s). Null kalau tiada kebenaran / tiada lokasi. */
    @SuppressLint("MissingPermission")
    private fun liveLocation(ctx: Context): Pair<Double, Double>? {
        if (!hasBackgroundLocation(ctx)) return null
        return try {
            val lm = ctx.getSystemService(LocationManager::class.java)
            val providers = lm.getProviders(true)
            val last = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
            var loc: Location? = last?.takeIf { System.currentTimeMillis() - it.time < 10 * 60_000L }
            val provider = when {
                LocationManager.NETWORK_PROVIDER in providers -> LocationManager.NETWORK_PROVIDER
                LocationManager.GPS_PROVIDER in providers -> LocationManager.GPS_PROVIDER
                else -> null
            }
            if (loc == null && provider != null && Build.VERSION.SDK_INT >= 30) {
                val latch = CountDownLatch(1)
                lm.getCurrentLocation(provider, null, ctx.mainExecutor) { loc = it; latch.countDown() }
                latch.await(20, TimeUnit.SECONDS)
            }
            (loc ?: last)?.let { it.latitude to it.longitude }
        } catch (_: Exception) {
            null
        }
    }

    private fun message(me: Weather.Sample, nowRain: List<Weather.Sample>, soonMmh: Double, thunder: Boolean, km: Int): String {
        val near = nowRain.filter { it !== me }.minByOrNull { it.point.distKm }
        val heaviest = nowRain.maxByOrNull { it.mmh }
        val parts = mutableListOf<String>()
        when {
            me.mmh >= 0.1 -> {
                val l = me.level.label
                parts += t("Sedang hujan di lokasi anda ($l).", "Raining at your location ($l).", "您所在位置正在下雨（${l}）。", "உங்கள் இடத்தில் மழை பெய்கிறது ($l).")
            }
            near != null -> {
                val d = Weather.formatKm(near.point.distKm)
                val dir = Weather.toward(near.point.bearing)
                val l = near.level.label
                parts += t("Hujan ~$d $dir ($l).", "Rain ~$d $dir ($l).", "${dir}约${d}有雨（${l}）。", "$dir ~$d மழை ($l).")
            }
            soonMmh >= 0.1 -> {
                val at = me.nextTimes.firstOrNull().orEmpty()
                parts += t("Hujan dijangka di lokasi anda ~$at.", "Rain expected at your location ~$at.", "预计约${at}您所在位置下雨。", "~$at உங்கள் இடத்தில் மழை எதிர்பார்க்கப்படுகிறது.")
            }
        }
        if (thunder) parts += t("⚡ Ribut petir dalam $km km!", "⚡ Thunderstorm within $km km!", "⚡ ${km}公里内有雷暴！", "⚡ $km கி.மீ.க்குள் இடிமழை!")
        if (heaviest != null && heaviest.level >= Weather.Level.HEAVY || thunder) {
            parts += t("Tangguh ride jika boleh.", "Hold off riding if you can.", "如可以请暂缓骑行。", "முடிந்தால் பயணத்தை ஒத்திவைக்கவும்.")
        } else {
            parts += t("Bawa baju hujan.", "Bring a raincoat.", "请带雨衣。", "மழைக்கோட் எடுத்துச் செல்லவும்.")
        }
        return parts.joinToString(" ")
    }

    /** Notifikasi ujian dari skrin Tetapan — dengar bunyi tanpa tunggu hujan. */
    fun test(ctx: Context, level: Int) {
        I18n.lang = Prefs.lang(ctx)
        notify(
            ctx, level,
            if (level == STORM) t("Ujian: bunyi hujan lebat + guruh.", "Test: heavy rain + thunder sound.", "测试：大雨 + 雷声。", "சோதனை: கனமழை + இடி ஒலி.")
            else t("Ujian: bunyi hujan.", "Test: rain sound.", "测试：雨声。", "சோதனை: மழை ஒலி."),
        )
    }

    @SuppressLint("MissingPermission")
    private fun notify(ctx: Context, level: Int, text: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        createChannels(ctx, nm)
        val storm = level == STORM
        val pi = PendingIntent.getActivity(
            ctx, 2,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val title = if (storm) {
            "⛈️ " + t("Hujan lebat / ribut berdekatan", "Heavy rain / storm nearby", "附近有大雨/雷暴", "அருகில் கனமழை / புயல்")
        } else {
            "🌧️ " + t("Hujan di kawasan anda", "Rain in your area", "您所在地区有雨", "உங்கள் பகுதியில் மழை")
        }
        val n = Notification.Builder(ctx, if (storm) CH_STORM else CH_RAIN)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setColor(if (storm) 0xFFE53935.toInt() else 0xFF039BE5.toInt())
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(NOTIF_ID, n)
        } catch (_: SecurityException) {
        }
    }

    private fun createChannels(ctx: Context, nm: NotificationManager) {
        // Saluran lama dengan bunyi lama (v1.6–1.7)
        nm.deleteNotificationChannel("rain_alert_v1")
        nm.deleteNotificationChannel("storm_alert_v1")
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        fun sound(name: String) = Uri.parse("android.resource://${ctx.packageName}/raw/$name")
        nm.createNotificationChannel(
            NotificationChannel(CH_RAIN, t("Amaran hujan", "Rain alerts", "降雨提醒", "மழை எச்சரிக்கை"), NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(sound("rain"), attrs)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 300, 200, 300)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_STORM, t("Amaran hujan lebat / ribut", "Heavy rain / storm alerts", "大雨/雷暴提醒", "கனமழை / புயல் எச்சரிக்கை"), NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(sound("storm"), attrs)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500, 200, 400)
            }
        )
    }
}

/** Semakan latar setiap ~15 minit (Android boleh lewatkan bila bateri jimat / Doze). */
class RainAlertJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            RainAlert.check(applicationContext)
            jobFinished(params, false)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}

/**
 * Android buang job yang dijadualkan bila app dikemas kini (OTA) — jadualkan semula amaran hujan
 * dan semakan kemas kini terus selepas update / restart, tanpa tunggu app dibuka.
 */
class RescheduleReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        RainAlert.schedule(ctx)
        Updater.schedule(ctx)
    }
}
