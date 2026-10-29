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
 * Kongsi lokasi live dengan kumpulan rider yang dipilih (RiderGroup.sharing). Berjalan sebagai servis
 * latar depan (notifikasi tetap) hanya selepas rider tekan "Mula kongsi" — berhenti bila tiada lagi
 * kumpulan dikongsi, atau dengan butang Henti dalam notifikasi. Lokasi dihantar ~setiap 10 saat
 * (disulitkan dengan kunci setiap kumpulan, lihat RiderGroup).
 */
class LocationShareService : Service(), LocationListener {

    private lateinit var worker: Handler
    private var last: Location? = null
    private var lastSent = 0L

    /** Setiap 30 saat: tamatkan kongsi bagi kumpulan yang dah habis tempoh. */
    private val expiryTick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            var changed = false
            for (g in RiderGroup.groups(this@LocationShareService)) {
                val until = RiderGroup.shareUntil(this@LocationShareService, g.code)
                if (until in 1..now && g.code in RiderGroup.sharingRaw(this@LocationShareService)) {
                    RiderGroup.setSharing(this@LocationShareService, g.code, false)
                    runCatching { RiderGroup.removeMe(this@LocationShareService, g) }
                    changed = true
                }
            }
            if (changed) {
                val left = sharedGroups(this@LocationShareService)
                if (left.isEmpty()) {
                    running = false
                    stopSelf()
                    return
                }
                getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(left))
            }
            worker.postDelayed(this, 30_000L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        worker = Handler(HandlerThread("share").apply { start() }.looper)
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        I18n.lang = Prefs.lang(this)
        if (intent?.action == ACTION_STOP) {
            stopAll(this)
            return START_NOT_STICKY
        }
        val groups = sharedGroups(this)
        if (groups.isEmpty() || !Firebase.enabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        val n = notification(groups)
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        else startForeground(NOTIF_ID, n)
        running = true
        lastSent = 0L // kumpulan baru ditambah — hantar segera pada lokasi seterusnya
        worker.removeCallbacks(expiryTick)
        worker.postDelayed(expiryTick, 30_000L)
        try {
            val lm = getSystemService(LocationManager::class.java)
            lm.removeUpdates(this)
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (p in lm.getProviders(true)) lm.requestLocationUpdates(p, 3_000L, 5f, this, worker.looper)
            }
        } catch (_: SecurityException) {
            stopAll(this)
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
        val speed = if (loc.hasSpeed()) loc.speed * 3.6 else 0.0
        val bearing = if (loc.hasBearing() && speed >= 3) loc.bearing.toDouble() else Double.NaN
        for (g in sharedGroups(this)) {
            runCatching { RiderGroup.publish(this, g, loc.latitude, loc.longitude, speed, bearing) }
        }
        Prefs.setLastLocation(this, loc.latitude, loc.longitude)
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

    private fun notification(list: List<RiderGroup.Group>): Notification {
        val groups = list.joinToString(", ") { it.icon + " " + it.name }
        // tempoh tamat paling awal (0 = sehingga ditekan Henti)
        val end = list.map { RiderGroup.shareUntil(this, it.code) }.filter { it > 0 }.minOrNull()
        val text = if (end != null) {
            val at = Clock.time(end)
            t("Sehingga $at. Ahli kumpulan nampak lokasi anda.", "Until $at. Group members can see where you are.", "直到${at}。群组成员可以看到您的位置。", "$at வரை. குழு உறுப்பினர்கள் உங்கள் இருப்பிடத்தைப் பார்க்கலாம்.")
        } else {
            t("Ahli kumpulan nampak lokasi anda.", "Group members can see where you are.", "群组成员可以看到您的位置。", "குழு உறுப்பினர்கள் உங்கள் இருப்பிடத்தைப் பார்க்கலாம்.")
        }
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
            .setContentTitle("📍 " + t("Berkongsi lokasi", "Sharing location", "正在共享位置", "இருப்பிடம் பகிரப்படுகிறது") + " · $groups")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, t("Henti semua", "Stop all", "全部停止", "அனைத்தையும் நிறுத்து"), stop).build())
            .build()
    }

    companion object {
        private const val NOTIF_ID = 4345
        private const val CH = "rider_share"
        private const val ACTION_STOP = "com.jagarider.STOP_SHARE"

        @Volatile
        var running = false
            private set

        private fun sharedGroups(c: Context): List<RiderGroup.Group> {
            val codes = RiderGroup.sharing(c)
            return RiderGroup.groups(c).filter { it.code in codes }
        }

        /**
         * Mula / sambung kongsi dengan satu kumpulan (kumpulan lain yang dikongsi kekal).
         * until = masa tamat (ms), 0 = sehingga ditekan Henti; null = kekalkan tempoh sedia ada.
         */
        fun startGroup(c: Context, g: RiderGroup.Group, until: Long? = null) {
            RiderGroup.setSharing(c, g.code, true, until ?: RiderGroup.shareUntil(c, g.code))
            c.startForegroundService(Intent(c, LocationShareService::class.java))
        }

        /** Henti kongsi dengan satu kumpulan; servis berhenti bila tiada kumpulan lagi. */
        fun stopGroup(c: Context, g: RiderGroup.Group) {
            RiderGroup.setSharing(c, g.code, false)
            val app = c.applicationContext
            Thread { runCatching { RiderGroup.removeMe(app, g) } }.start()
            if (RiderGroup.sharing(c).isEmpty()) {
                c.stopService(Intent(c, LocationShareService::class.java))
                running = false
            } else {
                c.startForegroundService(Intent(c, LocationShareService::class.java)) // kemas kini notifikasi
            }
        }

        /** Henti semua dan buang lokasi saya dari semua kumpulan. */
        fun stopAll(c: Context) {
            val groups = sharedGroups(c)
            RiderGroup.clearSharing(c)
            c.stopService(Intent(c, LocationShareService::class.java))
            running = false
            val app = c.applicationContext
            Thread { for (g in groups) runCatching { RiderGroup.removeMe(app, g) } }.start()
        }
    }
}
