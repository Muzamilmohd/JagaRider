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
                if (left.isEmpty() && !RideLog.solo(this@LocationShareService)) {
                    running = false
                    stopSelf()
                    return
                }
                getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(left))
            }
            worker.postDelayed(this, 30_000L)
        }
    }

    /** Setiap 20 saat semasa berkongsi: mesej pantas baru + amaran rider tertinggal / berhenti lama. */
    private val groupTick = object : Runnable {
        override fun run() {
            if (!autoOnly) RideLog.snapshot(this@LocationShareService)
            runCatching { watchGroups() }
            worker.postDelayed(this, if (Prefs.batterySaver(this@LocationShareService)) 60_000L else 20_000L)
        }
    }
    // ------------------------------------------------------------ auto rekod (AutoRide)

    /** Sesi auto sahaja (tiada kongsi / ride solo). */
    private var autoOnly = false
    private var armedAt = 0L
    private var fastSince = 0L
    private var confirmed = false
    private var lastMove = 0L
    private var anchor: Location? = null
    /** Titik GPS pertama sesi auto + titik sebelumnya (kira laju bila GPS tak beri laju). */
    private var firstFix: Location? = null
    private var prevFix: Location? = null
    /** Laju tertinggi dilihat semasa menunggu pengesahan (untuk mesej "kenapa tak direkod"). */
    private var armMaxKmh = 0.0

    /** Setiap 15 saat dalam sesi auto: batal kalau tak disahkan, tamat bila berhenti lama. */
    private val autoTick = object : Runnable {
        override fun run() {
            if (!autoOnly) return
            val now = System.currentTimeMillis()
            if (!confirmed && now - armedAt > AutoRide.ARM_TIMEOUT_MS) {
                endAuto(save = false, why = "slow")
                return
            }
            if (confirmed && now - lastMove > AutoRide.STOP_MS) {
                endAuto(save = true)
                return
            }
            if (confirmed) RideLog.snapshot(this@LocationShareService)
            worker.postDelayed(this, 15_000L)
        }
    }

    /**
     * Sahkan ride: laju ≥ 20 km/j selama 30 s, ATAU sudah bergerak ≥ 500 m dari titik mula (GPS dalam
     * poket / beg kadang tak beri laju yang stabil). Selepas disahkan, jejak bila kali terakhir bergerak.
     */
    private fun autoCheck(loc: Location) {
        if (loc.provider != LocationManager.GPS_PROVIDER) return
        val now = System.currentTimeMillis()
        val p = prevFix
        prevFix = loc
        val calc = if (p != null && loc.time > p.time) p.distanceTo(loc) / ((loc.time - p.time) / 1000.0) * 3.6 else 0.0
        val kmh = if (loc.hasSpeed() && loc.speed > 0f) loc.speed * 3.6 else calc.coerceAtMost(200.0)
        if (!confirmed) {
            armMaxKmh = maxOf(armMaxKmh, kmh)
            val f = firstFix ?: loc.also { firstFix = it }
            if (kmh >= AutoRide.START_KMH) {
                if (fastSince == 0L) fastSince = now
            } else if (kmh < 10) {
                fastSince = 0L
            }
            val fastEnough = fastSince != 0L && now - fastSince >= AutoRide.CONFIRM_MS
            val farEnough = f.distanceTo(loc) >= AutoRide.CONFIRM_M
            if (fastEnough || farEnough) {
                confirmed = true
                lastMove = now
                anchor = loc
                getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(emptyList()))
            }
            return
        }
        val a = anchor
        if (kmh >= 8 || a == null || a.distanceTo(loc) > 100f) {
            lastMove = now
            anchor = loc
        }
    }

    /** Tamat sesi auto: simpan ride (masa tamat = kali terakhir bergerak) atau batal, kemudian henti servis. */
    private fun endAuto(save: Boolean, why: String = "") {
        val cur = RideLog.current()
        if (save) {
            val r = RideLog.finish(this, lastMove)
            if (r != null) autoDone(r)
            RideLog.setLastAuto(this, if (r != null) "saved" else "short", r?.km ?: cur?.first ?: 0.0, r?.maxKmh ?: cur?.third ?: 0.0)
        } else {
            RideLog.cancel(this)
            RideLog.setLastAuto(this, why, cur?.first ?: 0.0, armMaxKmh)
        }
        RideLog.setAutoSession(this, false)
        autoOnly = false
        running = false
        stopSelf()
    }

    /** Notifikasi ringkasan ride auto + butang "Bukan ride? Buang" (sensor tak boleh beza motor / kereta). */
    @SuppressLint("MissingPermission")
    private fun autoDone(r: RideLog.Ride) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_AUTO, t("Auto rekod ride", "Auto ride recording", "自动骑行记录", "தானியங்கி பயணப் பதிவு"), NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 8, Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_MY_RIDE_TAB, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val discard = PendingIntent.getBroadcast(
            this, 9, Intent(this, AutoRideReceiver::class.java).setAction(AutoRide.ACTION_DISCARD).putExtra(AutoRide.EXTRA_START, r.start),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val min = (r.end - r.start) / 60_000L
        val dur = if (min >= 60) String.format(java.util.Locale.US, "%d:%02d", min / 60, min % 60) else "$min min"
        val text = String.format(java.util.Locale.US, "%.1f km · %s · ", r.km, dur) + Weather.maxWord + String.format(java.util.Locale.US, " %.0f ", r.maxKmh) + Weather.kmh
        val n = Notification.Builder(this, CH_AUTO)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("🏍️ " + t("Ride direkod", "Ride recorded", "骑行已记录", "பயணம் பதிவானது"))
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(Notification.Action.Builder(null, t("Bukan ride? Buang", "Not a ride? Delete", "不是骑行？删除", "பயணம் இல்லையா? நீக்கு"), discard).build())
            .build()
        runCatching { nm.notify(AUTO_DONE_ID, n) }
    }

    private val stillSince = HashMap<String, Pair<Pair<Double, Double>, Long>>()
    private val alerted = HashMap<String, Long>()

    private fun once(key: String): Boolean {
        val now = System.currentTimeMillis()
        if (now - (alerted[key] ?: 0L) < 15 * 60_000L) return false
        alerted[key] = now
        return true
    }

    private fun watchGroups() {
        val now = System.currentTimeMillis()
        val me = last
        val iAmMoving = me != null && me.hasSpeed() && me.speed * 3.6 >= 10
        for (g in sharedGroups(this)) {
            // 1. Mesej pantas dari ahli lain
            val seen = RiderGroup.lastSeenQuick(this, g)
            val msgs = runCatching { RiderGroup.fetchQuick(this, g) }.getOrDefault(emptyList())
            if (seen == 0L) {
                RiderGroup.setLastSeenQuick(this, g, now) // kali pertama: jangan bising dengan mesej lama
            } else {
                val fresh = msgs.filter { !it.me && it.time > seen && now - it.time < 10 * 60_000L }.sortedBy { it.time }
                for (m in fresh) groupAlert(g, "${m.quick.icon} ${m.name}: ${m.quick.text}", "${m.name}: ${m.quick.text}")
                msgs.maxOfOrNull { it.time }?.let { if (it > seen) RiderGroup.setLastSeenQuick(this, g, it) }
            }

            // 2. Rider tertinggal jauh / berhenti lama (hanya bila saya sedang ride)
            if (me == null) continue
            val riders = runCatching { RiderGroup.fetch(this, g) }.getOrDefault(emptyList())
                .filter { !it.me && now - it.time < 3 * 60_000L }
            for (r in riders) {
                val d = Route.km(me.latitude to me.longitude, r.lat to r.lon)
                if (iAmMoving && d > LEFT_BEHIND_KM && once("far:${g.code}:${r.uid}")) {
                    val km = String.format(java.util.Locale.US, "%.1f", d)
                    groupAlert(
                        g,
                        "📏 " + t("${r.name} jauh $km km dari anda", "${r.name} is $km km away from you", "${r.name}离您${km}公里", "${r.name} உங்களிடமிருந்து $km கி.மீ. தொலைவில்"),
                        t("${r.name} jauh $km kilometer", "${r.name} is $km kilometres away", "${r.name}离您${km}公里", "${r.name} $km கிலோமீட்டர் தொலைவில்"),
                    )
                }
                val prev = stillSince[r.uid]
                if (prev == null || Route.km(prev.first, r.lat to r.lon) > 0.15) {
                    stillSince[r.uid] = (r.lat to r.lon) to now
                } else if (iAmMoving && now - prev.second > 5 * 60_000L && once("stop:${g.code}:${r.uid}")) {
                    val min = (now - prev.second) / 60_000L
                    groupAlert(
                        g,
                        "⏸️ " + t("${r.name} berhenti $min minit — mungkin ada masalah?", "${r.name} has stopped for $min min — any trouble?", "${r.name}已停下${min}分钟——是否有问题？", "${r.name} $min நிமி. நின்றுள்ளார் — ஏதேனும் பிரச்சனையா?"),
                        t("${r.name} berhenti $min minit", "${r.name} has stopped for $min minutes", "${r.name}已停下${min}分钟", "${r.name} $min நிமிடம் நின்றுள்ளார்"),
                    )
                }
            }
        }
    }

    /** Notifikasi kumpulan (berbunyi) + suara kalau dihidupkan. Di thread worker. */
    @SuppressLint("MissingPermission")
    private fun groupAlert(g: RiderGroup.Group, text: String, spoken: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_GROUP, t("Mesej kumpulan rider", "Rider group messages", "骑手群组消息", "ரைடர் குழு செய்திகள்"), NotificationManager.IMPORTANCE_HIGH)
        )
        val open = PendingIntent.getActivity(
            this, 5, Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_RIDER_TAB, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, CH_GROUP)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(g.icon + " " + g.name)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify((System.currentTimeMillis() % 100000).toInt() + 10000, n) }
        Speech.speakBlocking(this, spoken)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        worker = Handler(HandlerThread("share").apply { start() }.looper)
        // Proses sebelum ini dibunuh di tengah ride — simpan bahagian yang sudah direkod
        RideLog.recover(this)
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        I18n.lang = Prefs.lang(this)
        if (intent?.action == ACTION_STOP) {
            stopAll(this)
            return START_NOT_STICKY
        }
        // Sensor kata dah turun dari kenderaan sebelum ride disahkan → batal (bukan ride)
        if (intent?.action == ACTION_AUTO_EXIT) {
            if (autoOnly && !confirmed) endAuto(save = false, why = "slow")
            return START_NOT_STICKY
        }
        val groups = sharedGroups(this)
        val solo = RideLog.solo(this)
        val manual = (groups.isNotEmpty() && Firebase.enabled) || solo
        if (!manual && !RideLog.autoSession(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Kongsi / ride solo dimulakan semasa sesi auto → jadi sesi biasa (ride yang sedang direkod diteruskan)
        if (manual && RideLog.autoSession(this)) RideLog.setAutoSession(this, false)
        autoOnly = !manual
        val n = notification(groups)
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        else startForeground(NOTIF_ID, n)
        running = true
        lastSent = 0L // kumpulan baru ditambah — hantar segera pada lokasi seterusnya
        if (autoOnly) {
            if (armedAt == 0L) {
                armedAt = System.currentTimeMillis()
                fastSince = 0L
                firstFix = null
                prevFix = null
                armMaxKmh = 0.0
                confirmed = false
                RideLog.begin(this)
                RideLog.auto = true
                RideLog.label = t("Auto", "Auto", "自动", "தானியங்கி")
            }
            worker.removeCallbacks(autoTick)
            worker.postDelayed(autoTick, 15_000L)
        } else {
            worker.removeCallbacks(autoTick)
            RideLog.begin(this)
            RideLog.auto = false
            RideLog.label = if (groups.isEmpty()) t("Ride solo", "Solo ride", "单人骑行", "தனிப் பயணம்") else groups.joinToString(", ") { it.name }
        }
        worker.removeCallbacks(expiryTick)
        worker.postDelayed(expiryTick, 30_000L)
        worker.removeCallbacks(groupTick)
        worker.postDelayed(groupTick, 5_000L)
        try {
            val lm = getSystemService(LocationManager::class.java)
            lm.removeUpdates(this)
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                // Mod jimat bateri: kemas kini lokasi lebih jarang
                if (p in lm.getProviders(true)) {
                    if (Prefs.batterySaver(this)) lm.requestLocationUpdates(p, 10_000L, 25f, this, worker.looper)
                    else lm.requestLocationUpdates(p, 3_000L, 5f, this, worker.looper)
                }
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
        RideLog.add(loc)
        if (autoOnly) autoCheck(loc)
        val now = System.currentTimeMillis()
        if (now - lastSent < (if (Prefs.batterySaver(this)) 30_000L else 10_000L)) return
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
        // Sesi auto yang belum disahkan bukan ride — jangan simpan
        if (autoOnly && !confirmed) {
            RideLog.setLastAuto(this, "slow", RideLog.current()?.first ?: 0.0, armMaxKmh)
            RideLog.cancel(this)
        } else {
            RideLog.finish(this)?.let { if (autoOnly) RideLog.setLastAuto(this, "saved", it.km, it.maxKmh) }
        }
        if (autoOnly) RideLog.setAutoSession(this, false)
        running = false
        runCatching { getSystemService(LocationManager::class.java).removeUpdates(this) }
        worker.looper.quitSafely()
        super.onDestroy()
    }

    private fun notification(list: List<RiderGroup.Group>): Notification {
        if (autoOnly) return autoNotification()
        val groups = if (list.isEmpty()) "🏍️ " + t("Ride solo", "Solo ride", "单人骑行", "தனிப் பயணம்") else list.joinToString(", ") { it.icon + " " + it.name }
        // tempoh tamat paling awal (0 = sehingga ditekan Henti)
        val end = list.map { RiderGroup.shareUntil(this, it.code) }.filter { it > 0 }.minOrNull()
        val text = if (list.isEmpty()) {
            t("Merekod ride anda (jarak, masa, laju).", "Recording your ride (distance, time, speed).", "正在记录您的骑行（距离、时间、速度）。", "உங்கள் பயணம் பதிவாகிறது (தூரம், நேரம், வேகம்).")
        } else if (end != null) {
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
            this, 3, Intent(this, MainActivity::class.java).putExtra(if (list.isEmpty()) MainActivity.EXTRA_MY_RIDE_TAB else MainActivity.EXTRA_RIDER_TAB, true)
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

    /** Notifikasi tetap sesi auto: menyemak dulu, kemudian "ride direkod". Tiada lokasi dikongsi. */
    private fun autoNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH, t("Kongsi lokasi rider", "Rider location sharing", "骑手位置共享", "ரைடர் இருப்பிடப் பகிர்வு"), NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 3, Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_MY_RIDE_TAB, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 4, Intent(this, LocationShareService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val (title, text) = if (confirmed) {
            ("🏍️ " + t("Ride direkod (auto)", "Recording ride (auto)", "正在记录骑行（自动）", "பயணம் பதிவாகிறது (தானியங்கி)")) to
                t("Tamat sendiri bila anda berhenti 5 minit.", "Ends by itself when you stop for 5 minutes.", "停下5分钟后自动结束。", "5 நிமிடம் நின்றால் தானாக முடியும்.")
        } else {
            ("🤖 " + t("Kesan kenderaan…", "Vehicle detected…", "检测到交通工具…", "வாகனம் கண்டறியப்பட்டது…")) to
                t("Rekod bermula bila laju 20 km/j.", "Recording starts at 20 km/h.", "时速达到20公里时开始记录。", "20 கி.மீ./ம வேகத்தில் பதிவு தொடங்கும்.")
        }
        return Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, t("Henti", "Stop", "停止", "நிறுத்து"), stop).build())
            .build()
    }

    companion object {
        const val AUTO_DONE_ID = 4346
        private const val CH_AUTO = "auto_ride"
        private const val NOTIF_ID = 4345
        private const val CH = "rider_share"
        private const val CH_GROUP = "rider_group_msg"
        /** Amaran bila rider lain lebih jauh dari ini (km) semasa saya sedang ride. */
        private const val LEFT_BEHIND_KM = 3.0
        private const val ACTION_STOP = "com.jagarider.STOP_SHARE"
        private const val ACTION_AUTO_EXIT = "com.jagarider.AUTO_EXIT"

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
            if (RiderGroup.sharing(c).isEmpty() && !RideLog.solo(c)) {
                c.stopService(Intent(c, LocationShareService::class.java))
                running = false
            } else {
                c.startForegroundService(Intent(c, LocationShareService::class.java)) // kemas kini notifikasi
            }
        }

        /** Ride solo: rekod jarak / masa / laju tanpa kongsi dengan kumpulan. */
        fun startSolo(c: Context) {
            RideLog.setSolo(c, true)
            c.startForegroundService(Intent(c, LocationShareService::class.java))
        }

        fun stopSolo(c: Context) {
            RideLog.setSolo(c, false)
            if (RiderGroup.sharing(c).isEmpty()) {
                c.stopService(Intent(c, LocationShareService::class.java))
                running = false
            } else {
                c.startForegroundService(Intent(c, LocationShareService::class.java))
            }
        }

        /** Auto rekod: sensor kesan kenderaan → GPS untuk sahkan ride (dipanggil dari latar, dikecualikan Android). */
        fun startAuto(c: Context) {
            RideLog.setAutoSession(c, true)
            runCatching { c.startForegroundService(Intent(c, LocationShareService::class.java)) }
                .onFailure { RideLog.setAutoSession(c, false) }
        }

        /** Sensor: turun dari kenderaan — batalkan sesi auto yang belum disahkan. */
        fun autoExit(c: Context) {
            if (!running || !RideLog.autoSession(c)) return
            runCatching { c.startService(Intent(c, LocationShareService::class.java).setAction(ACTION_AUTO_EXIT)) }
        }

        /** Auto rekod dimatikan — henti sesi auto (bukan kongsi / ride solo). */
        fun stopAuto(c: Context) {
            if (!RideLog.autoSession(c)) return
            RideLog.setAutoSession(c, false)
            if (RiderGroup.sharing(c).isEmpty() && !RideLog.solo(c)) {
                c.stopService(Intent(c, LocationShareService::class.java))
                running = false
            }
        }

        /** Henti semua dan buang lokasi saya dari semua kumpulan. */
        fun stopAll(c: Context) {
            val groups = sharedGroups(c)
            RiderGroup.clearSharing(c)
            RideLog.setSolo(c, false)
            RideLog.setAutoSession(c, false)
            c.stopService(Intent(c, LocationShareService::class.java))
            running = false
            val app = c.applicationContext
            Thread { for (g in groups) runCatching { RiderGroup.removeMe(app, g) } }.start()
        }
    }
}
