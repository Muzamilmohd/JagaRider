package com.jagarider

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import java.util.Calendar
import java.util.Locale

/**
 * Tab "Ride Saya": ringkasan minggu ini (km setiap hari), auto rekod ride, ride solo, dan rekod ride.
 * Semua data ride disimpan dalam phone sahaja (RideLog).
 */
class MyRidePage(private val act: MainActivity) {

    val root = ScrollView(act)
    private val col = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(act.dp(16), act.dp(16), act.dp(16), act.dp(24))
    }
    private val main = Handler(Looper.getMainLooper())
    private var visible = false
    /** Bilangan ride dipapar dalam senarai (tekan "Lagi" untuk tambah). */
    private var shown = 10

    /** Semasa ride direkod: kemas kini statistik ride semasa setiap 5 saat. */
    private val tick = object : Runnable {
        override fun run() {
            if (!visible) return
            if (RideLog.current() != null) build()
            main.postDelayed(this, 5_000L)
        }
    }

    init {
        root.addView(col)
    }

    fun onShow() {
        visible = true
        // Rider pernah mula ride solo tapi servis terhenti (phone restart / app dikemas kini) — sambung semula
        if (RideLog.solo(act) && !LocationShareService.running && hasLocation()) LocationShareService.startSolo(act)
        build()
        main.removeCallbacks(tick)
        main.postDelayed(tick, 5_000L)
    }

    fun onHide() {
        visible = false
        main.removeCallbacks(tick)
    }

    // ------------------------------------------------------------ UI

    private fun build() {
        val y = root.scrollY
        col.removeAllViews()
        col.addView(LinearLayout(act).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(act.label("🏍️", 34f).apply { setPadding(0, 0, act.dp(10), 0) })
            addView(LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                addView(act.label(t("Ride Saya", "My Ride", "我的骑行", "என் பயணம்"), 26f, bold = true, color = act.pal.orange).apply { setPadding(0, 0, 0, 0) })
                addView(act.label(t("Rekod & ringkasan ride anda", "Your ride records & summary", "您的骑行记录与统计", "உங்கள் பயணப் பதிவுகள் & சுருக்கம்"), 14f, color = act.pal.sub).apply { setPadding(0, 0, 0, 0) })
            })
        })
        val rides = RideLog.rides(act)
        buildWeek(rides)
        buildRecord()
        buildLog(rides)
        root.post { root.scrollTo(0, y) }
    }

    /** Ringkasan minggu ini (Isnin–Ahad): jumlah km, bilangan ride, masa, carta km setiap hari. */
    private fun buildWeek(rides: List<RideLog.Ride>) {
        val c = act.card()
        col.addView(c, act.lp(top = 14))
        c.addView(act.label("🗓️ " + t("Minggu ini", "This week", "本周", "இந்த வாரம்"), 18f, bold = true))

        val weekStart = weekStart(System.currentTimeMillis())
        val week = rides.filter { it.start >= weekStart }
        val lastWeek = rides.filter { it.start in (weekStart - 7 * DAY) until weekStart }
        val km = week.sumOf { it.km }
        val lastKm = lastWeek.sumOf { it.km }

        c.addView(LinearLayout(act).apply {
            gravity = Gravity.BOTTOM
            addView(act.label(String.format(Locale.US, "%.1f", km), 40f, bold = true, color = act.pal.orange).apply { setPadding(0, 0, act.dp(6), 0) })
            addView(act.label(t("km", "km", "公里", "கி.மீ."), 18f, bold = true, color = act.pal.sub).apply { setPadding(0, 0, 0, act.dp(8)) })
        })
        // Banding dengan minggu lepas
        if (lastKm > 0.05) {
            val diff = km - lastKm
            val pct = (diff / lastKm * 100).toInt()
            val txt = if (diff >= 0) {
                "📈 " + t("+$pct% berbanding minggu lepas", "+$pct% vs last week", "比上周 +$pct%", "கடந்த வாரத்தை விட +$pct%")
            } else {
                "📉 " + t("$pct% berbanding minggu lepas", "$pct% vs last week", "比上周 $pct%", "கடந்த வாரத்தை விட $pct%")
            }
            c.addView(act.label(txt + String.format(Locale.US, " (%.0f km)", lastKm), 13f, color = act.pal.sub).apply { setPadding(0, 0, 0, act.dp(4)) })
        }

        // Statistik ringkas
        val movingMs = week.sumOf { it.movingMs }
        val max = week.maxOfOrNull { it.maxKmh } ?: 0.0
        val longest = week.maxOfOrNull { it.km } ?: 0.0
        c.addView(LinearLayout(act).apply {
            addView(stat("🏁", "${week.size}", t("ride", "rides", "次骑行", "பயணம்")), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(stat("⏱️", durText(movingMs), t("bergerak", "moving", "骑行时间", "இயக்கம்")), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(stat("📏", String.format(Locale.US, "%.0f", longest), t("km terjauh", "km longest", "最长公里", "கி.மீ. அதிகம்")), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(stat("⚡", String.format(Locale.US, "%.0f", max), Weather.maxWord + " " + Weather.kmh), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }, act.lp(top = 8))

        // Carta km setiap hari
        val perDay = DoubleArray(7)
        for (r in week) perDay[((r.start - weekStart) / DAY).toInt().coerceIn(0, 6)] += r.km
        val today = ((System.currentTimeMillis() - weekStart) / DAY).toInt().coerceIn(0, 6)
        c.addView(WeekChart(act, perDay, today), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, act.dp(150)).apply { topMargin = act.dp(12) })

        // Bulan ini
        val cal = Calendar.getInstance()
        val month = rides.filter {
            val k = Calendar.getInstance().apply { timeInMillis = it.start }
            k.get(Calendar.MONTH) == cal.get(Calendar.MONTH) && k.get(Calendar.YEAR) == cal.get(Calendar.YEAR)
        }
        if (month.isNotEmpty()) {
            val n = month.size
            val mk = month.sumOf { it.km }
            c.addView(act.label("📅 " + t(
                String.format(Locale.US, "Bulan ini: %d ride · %.0f km", n, mk),
                String.format(Locale.US, "This month: %d rides · %.0f km", n, mk),
                String.format(Locale.US, "本月：%d 次骑行 · %.0f 公里", n, mk),
                String.format(Locale.US, "இந்த மாதம்: %d பயணம் · %.0f கி.மீ.", n, mk),
            ), 14f, color = act.pal.sub), act.lp(top = 6))
        }
    }

    private fun stat(icon: String, value: String, unit: String) = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(act.label(icon, 18f).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, 0) })
        addView(act.label(value, 17f, bold = true).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, 0) })
        addView(act.label(unit, 11f, color = act.pal.sub).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, 0) })
    }

    /** Auto rekod + ride solo + ride semasa. */
    private fun buildRecord() {
        val c = act.card()
        col.addView(c, act.lp(top = 12))
        c.addView(act.label("⏺️ " + t("Rekod ride", "Recording", "记录骑行", "பயணப் பதிவு"), 18f, bold = true))

        // Ride semasa
        RideLog.current()?.let { (km, _, max) ->
            val dur = durText(System.currentTimeMillis() - RideLog.currentStart())
            val auto = RideLog.autoSession(act)
            c.addView(act.label((if (auto) "🤖 " else "🏍️ ") + t("Sedang direkod", "Recording now", "正在记录", "இப்போது பதிவாகிறது") +
                String.format(Locale.US, ": %.1f km · %s · %s %.0f %s", km, dur, Weather.maxWord, max, Weather.kmh), 15f, bold = true, color = act.pal.orange).apply {
                setPadding(act.dp(12), act.dp(8), act.dp(12), act.dp(8))
                background = GradientDrawable().apply {
                    setColor(act.pal.stormTint)
                    cornerRadius = act.dp(10).toFloat()
                }
            }, act.lp(top = 6))
        }

        // Auto rekod
        val ready = AutoRide.ready(act)
        val on = AutoRide.enabled(act)
        c.addView(Switch(act).apply {
            text = "🤖 " + t("Auto rekod ride", "Auto-record rides", "自动记录骑行", "பயணத்தைத் தானாகப் பதிவு செய்")
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(act.pal.text)
            isChecked = on
            setOnClickListener {
                if (isChecked) enableAuto() else AutoRide.setEnabled(act, false)
                build()
            }
        }, act.lp(top = 10))
        if (on && !ready) {
            c.addView(act.label("⚠️ " + t(
                "Perlu kebenaran \"Aktiviti fizikal\" dan lokasi \"sepanjang masa\". Tekan di sini untuk beri kebenaran.",
                "Needs \"Physical activity\" and location \"all the time\" permissions. Tap here to grant them.",
                "需要“身体活动”权限和“始终允许”定位。点击此处授予权限。",
                "\"உடல் செயல்பாடு\" மற்றும் \"எப்போதும்\" இருப்பிட அனுமதி தேவை. அனுமதி வழங்க இங்கே தட்டவும்.",
            ), 13f, bold = true, color = act.pal.orange).apply { setOnClickListener { enableAuto() } })
        }
        c.addView(act.label(t(
            "Sensor gerakan phone kesan bila anda naik motor — rekod bermula bila laju 20 km/j dan tamat bila berhenti 5 minit. Berhenti isi minyak / makan (bawah 15 minit) dikira satu ride. Sensor tak boleh beza motor dengan kereta — tekan \"Bukan ride? Buang\" dalam notifikasi.",
            "The phone's motion sensor notices when you get on the bike — recording starts at 20 km/h and ends after a 5-minute stop. Fuel / food stops (under 15 min) count as one ride. The sensor can't tell a bike from a car — tap \"Not a ride? Delete\" in the notification.",
            "手机的运动传感器会察觉您上车——时速达到20公里开始记录，停下5分钟后结束。加油/用餐停留（15分钟内）算作同一次骑行。传感器无法区分摩托车和汽车——可在通知中点“不是骑行？删除”。",
            "நீங்கள் பைக்கில் ஏறியதை போனின் இயக்க சென்சார் கண்டறியும் — 20 கி.மீ./ம வேகத்தில் பதிவு தொடங்கி 5 நிமிடம் நின்றால் முடியும். எரிபொருள் / உணவு நிறுத்தம் (15 நிமி. குறைவு) ஒரே பயணம். சென்சாரால் பைக்கையும் காரையும் வேறுபடுத்த முடியாது — அறிவிப்பில் \"பயணம் இல்லையா? நீக்கு\" தட்டவும்.",
        ), 13f, color = act.pal.sub))

        // Ride solo (manual)
        val solo = RideLog.solo(act) && LocationShareService.running
        c.addView(act.pill(
            if (solo) "⏹ " + t("Tamat ride solo", "End solo ride", "结束单人骑行", "தனிப் பயணத்தை முடி")
            else "▶ " + t("Mula ride solo sekarang", "Start a solo ride now", "立即开始单人骑行", "இப்போது தனிப் பயணம் தொடங்கு"),
            primary = !solo,
        ) {
            if (solo) {
                LocationShareService.stopSolo(act)
            } else if (!hasLocation()) {
                act.requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOC)
                return@pill
            } else {
                LocationShareService.startSolo(act)
            }
            main.postDelayed({ build() }, 800)
        }, act.lp(top = 10))
        c.addView(act.label(t(
            "Ride juga direkod bila anda kongsi lokasi dalam Ride Kumpulan. Disimpan dalam phone sahaja.",
            "Rides are also recorded while you share location in Group Ride. Stored on this phone only.",
            "在群组骑行中共享位置时也会记录。仅保存在此手机。",
            "குழுப் பயணத்தில் இருப்பிடம் பகிரும்போதும் பதிவாகும். இந்த தொலைபேசியில் மட்டும்.",
        ), 12f, color = act.pal.sub))
    }

    /** Senarai ride terkini — tekan lama untuk buang. */
    private fun buildLog(rides: List<RideLog.Ride>) {
        val c = act.card()
        col.addView(c, act.lp(top = 12))
        c.addView(act.label("📊 " + t("Rekod ride", "Ride log", "骑行记录", "பயணப் பதிவு"), 18f, bold = true))
        if (rides.isEmpty()) {
            c.addView(act.label(t(
                "Belum ada ride. Hidupkan auto rekod, mula ride solo, atau kongsi lokasi dengan kumpulan.",
                "No rides yet. Turn on auto-record, start a solo ride, or share location with a group.",
                "还没有骑行记录。开启自动记录、开始单人骑行或与群组共享位置。",
                "இன்னும் பயணம் இல்லை. தானியங்கிப் பதிவை இயக்கவும், தனிப் பயணம் தொடங்கவும் அல்லது குழுவுடன் இருப்பிடம் பகிரவும்.",
            ), 14f, color = act.pal.sub))
            return
        }
        c.addView(act.label(t("Tekan lama satu ride untuk buang.", "Long-press a ride to delete it.", "长按某次骑行可删除。", "நீக்க ஒரு பயணத்தை நீண்ட நேரம் அழுத்தவும்."), 12f, color = act.pal.sub))
        val fmt = java.text.SimpleDateFormat("EEE d MMM", Locale(I18n.lang.code))
        for (r in rides.take(shown)) {
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(act.dp(12), act.dp(8), act.dp(12), act.dp(8))
                background = GradientDrawable().apply {
                    setColor(act.pal.bg)
                    cornerRadius = act.dp(10).toFloat()
                }
            }
            val top = (if (r.auto) "🤖 " else "🏍️ ") + fmt.format(java.util.Date(r.start)) + " · " + Clock.time(r.start) +
                (if (r.label.isNotBlank() && !r.auto) " · " + r.label else "")
            row.addView(act.label(top, 13f, color = act.pal.sub).apply { setPadding(0, 0, 0, 0) })
            val line = String.format(Locale.US, "%.1f km · %s", r.km, durText(r.end - r.start)) +
                (if (r.avgKmh > 0) String.format(Locale.US, " · ~%.0f %s", r.avgKmh, Weather.kmh) else "") +
                String.format(Locale.US, " · %s %.0f", Weather.maxWord, r.maxKmh)
            row.addView(act.label(line, 15f, bold = true).apply { setPadding(0, act.dp(2), 0, 0) })
            row.setOnLongClickListener {
                AlertDialog.Builder(act)
                    .setTitle("🗑️ " + t("Buang ride ini?", "Delete this ride?", "删除此次骑行？", "இந்தப் பயணத்தை நீக்கவா?"))
                    .setMessage("$top\n$line")
                    .setPositiveButton(t("Buang", "Delete", "删除", "நீக்கு")) { _, _ ->
                        RideLog.remove(act, r.start)
                        build()
                    }
                    .setNegativeButton(t("Batal", "Cancel", "取消", "ரத்து"), null)
                    .show()
                true
            }
            c.addView(row, act.lp(top = 6))
        }
        if (rides.size > shown) {
            c.addView(act.pill(t("Lagi", "More", "更多", "மேலும்") + " (${rides.size - shown})") {
                shown += 10
                build()
            }, act.lp(top = 8))
        }
    }

    // ------------------------------------------------------------ kebenaran

    /** Hidupkan auto rekod: minta kebenaran satu-satu (aktiviti fizikal → lokasi sepanjang masa). */
    private fun enableAuto() {
        if (!AutoRide.hasActivityPermission(act)) {
            act.requestPermissions(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION), REQ_ACT)
            return
        }
        AutoRide.setEnabled(act, true)
        if (!RainAlert.hasBackgroundLocation(act)) askBackgroundLocation()
        build()
    }

    private fun askBackgroundLocation() {
        val fg = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (!hasLocation()) {
            act.requestPermissions(fg, REQ_LOC)
            return
        }
        if (Build.VERSION.SDK_INT < 29) return
        AlertDialog.Builder(act)
            .setTitle("📍 " + t("Lokasi sepanjang masa", "Location all the time", "始终允许定位", "எப்போதும் இருப்பிடம்"))
            .setMessage(t(
                "Supaya ride boleh direkod sendiri walaupun app ditutup, pilih \"Benarkan sepanjang masa\" di skrin seterusnya.\n\nLokasi ride disimpan dalam phone sahaja.",
                "So rides can be recorded by themselves even when the app is closed, choose \"Allow all the time\" on the next screen.\n\nRide locations stay on this phone only.",
                "为了在应用关闭时也能自动记录骑行，请在下一个页面选择“始终允许”。\n\n骑行位置仅保存在此手机。",
                "ஆப் மூடப்பட்டிருந்தாலும் பயணம் தானாகப் பதிவாக, அடுத்த திரையில் \"எப்போதும் அனுமதி\" என்பதைத் தேர்ந்தெடுக்கவும்.\n\nபயண இருப்பிடம் இந்த தொலைபேசியில் மட்டும்.",
            ))
            .setPositiveButton(t("Teruskan", "Continue", "继续", "தொடரவும்")) { _, _ ->
                act.requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), REQ_BG)
            }
            .setNegativeButton(t("Nanti", "Later", "稍后", "பின்னர்"), null)
            .show()
    }

    /** Dari MainActivity.onRequestPermissionsResult. True kalau kod ini milik halaman ini. */
    fun onPermissionResult(requestCode: Int, granted: Boolean): Boolean {
        when (requestCode) {
            REQ_ACT -> {
                if (granted) enableAuto()
                else if (!act.shouldShowRequestPermissionRationale(Manifest.permission.ACTIVITY_RECOGNITION)) openAppSettings()
            }
            REQ_LOC -> if (granted && AutoRide.enabled(act) && !RainAlert.hasBackgroundLocation(act)) askBackgroundLocation()
            REQ_BG -> if (!granted && !act.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) openAppSettings()
            else -> return false
        }
        build()
        return true
    }

    private fun openAppSettings() {
        act.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${act.packageName}")))
    }

    private fun hasLocation() =
        act.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            act.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun durText(ms: Long): String {
        val m = ms / 60_000L
        return if (m >= 60) String.format(Locale.US, "%d:%02d", m / 60, m % 60) else "$m min"
    }

    companion object {
        private const val DAY = 24 * 60 * 60_000L
        const val REQ_ACT = 31
        const val REQ_BG = 32
        const val REQ_LOC = 33

        /** Isnin 00:00 (waktu tempatan) bagi minggu yang mengandungi masa t. */
        fun weekStart(t: Long): Long = Calendar.getInstance().apply {
            timeInMillis = t
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            val dow = get(Calendar.DAY_OF_WEEK) // Ahad = 1
            add(Calendar.DAY_OF_MONTH, -((dow + 5) % 7))
        }.timeInMillis
    }
}

/** Carta bar km setiap hari (Isnin–Ahad); hari ini ditanda oren. */
private class WeekChart(ctx: Context, private val km: DoubleArray, private val today: Int) : View(ctx) {
    private val pal = Ui.palette(ctx)
    private val density = ctx.resources.displayMetrics.density
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 11 * density
    }
    private val days = when (I18n.lang.code) {
        "ms" -> listOf("Isn", "Sel", "Rab", "Kha", "Jum", "Sab", "Ahd")
        "zh" -> listOf("一", "二", "三", "四", "五", "六", "日")
        "ta" -> listOf("திங்", "செவ்", "புத", "வியா", "வெள்", "சனி", "ஞாயி")
        else -> listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val labelH = 18 * density
        val valueH = 16 * density
        val top = valueH
        val bottom = h - labelH
        val max = (km.maxOrNull() ?: 0.0).coerceAtLeast(1.0)
        val slot = w / 7
        val bw = slot * 0.55f
        for (i in 0 until 7) {
            val cx = slot * i + slot / 2
            val frac = (km[i] / max).toFloat()
            val bh = if (km[i] > 0) ((bottom - top) * frac).coerceAtLeast(4 * density) else 2 * density
            bar.color = when {
                i == today -> pal.orange
                km[i] > 0 -> Color.argb(150, 230, 81, 0)
                else -> pal.border
            }
            canvas.drawRoundRect(RectF(cx - bw / 2, bottom - bh, cx + bw / 2, bottom), 6 * density, 6 * density, bar)
            text.color = if (i == today) pal.orange else pal.sub
            text.typeface = if (i == today) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            canvas.drawText(days[i], cx, h - 4 * density, text)
            if (km[i] >= 0.05) {
                text.color = pal.text
                canvas.drawText(String.format(Locale.US, if (km[i] >= 10) "%.0f" else "%.1f", km[i]), cx, bottom - bh - 4 * density, text)
            }
        }
    }
}
