package com.jagarider

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder

/**
 * Kongsi lokasi live dengan kumpulan rider. Berjalan sebagai servis latar depan (notifikasi tetap)
 * hanya selepas rider tekan "Mula kongsi" — berhenti dengan butang Henti dalam app atau notifikasi.
 * Lokasi dihantar ~setiap 10 saat (disulitkan, lihat RiderGroup).
 */
class LocationShareService : Service(), LocationListener {

    private lateinit var worker: Handler
    private var last: Location? = null
    private var lastSent = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        worker = Handler(HandlerThread("share").apply { start() }.looper)
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        I18n.lang = Prefs.lang(this)
        if (intent?.action == ACTION_STOP) {
            stop(this)
            return START_NOT_STICKY
        }
        val g = RiderGroup.active(this)
        if (g == null || !Firebase.enabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        val n = notification(g.name)
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        else startForeground(NOTIF_ID, n)
        running = true
        try {
            val lm = getSystemService(LocationManager::class.java)
            lm.removeUpdates(this)
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (p in lm.getProviders(true)) lm.requestLocationUpdates(p, 5_000L, 10f, this, worker.looper)
            }
        } catch (_: SecurityException) {
            stop(this)
        }
        return START_STICKY
    }

    override fun onLocationChanged(loc: Location) {
        // GPS lebih tepat — abaikan lokasi rangkaian kalau ada GPS baru-baru ini
        val prev = last
        if (prev != null && loc.provider == LocationManager.NETWORK_PROVIDER && prev.provider == LocationManager.GPS_PROVIDER &&
            loc.time - prev.time < 30_000L
        ) return
        last = loc
        val now = System.currentTimeMillis()
        if (now - lastSent < 10_000L) return
        lastSent = now
        val g = RiderGroup.active(this) ?: return
        runCatching {
            RiderGroup.publish(this, g, loc.latitude, loc.longitude, if (loc.hasSpeed()) loc.speed * 3.6 else 0.0)
            Prefs.setLastLocation(this, loc.latitude, loc.longitude)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    override fun onDestroy() {
        running = false
        runCatching { getSystemService(LocationManager::class.java).removeUpdates(this) }
        worker.looper.quitSafely()
        super.onDestroy()
    }

    private fun notification(group: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH, t("Kongsi lokasi rider", "Rider location sharing", "骑手位置共享", "ரைடர் இருப்பிடப் பகிர்வு"), NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 3, Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_RIDER_TAB, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 4, Intent(this, LocationShareService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("📍 " + t("Berkongsi lokasi", "Sharing location", "正在共享位置", "இருப்பிடம் பகிரப்படுகிறது") + " · $group")
            .setContentText(t("Ahli kumpulan nampak lokasi anda.", "Group members can see where you are.", "群组成员可以看到您的位置。", "குழு உறுப்பினர்கள் உங்கள் இருப்பிடத்தைப் பார்க்கலாம்."))
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, t("Henti", "Stop", "停止", "நிறுத்து"), stop).build())
            .build()
    }

    companion object {
        private const val NOTIF_ID = 4345
        private const val CH = "rider_share"
        private const val ACTION_STOP = "com.jagarider.STOP_SHARE"

        @Volatile
        var running = false
            private set

        fun start(c: Context) {
            c.startForegroundService(Intent(c, LocationShareService::class.java))
        }

        /** Henti kongsi dan buang lokasi saya dari kumpulan. */
        fun stop(c: Context) {
            c.stopService(Intent(c, LocationShareService::class.java))
            running = false
            val app = c.applicationContext
            Thread { RiderGroup.active(app)?.let { g -> runCatching { RiderGroup.removeMe(app, g) } } }.start()
        }
    }
}
