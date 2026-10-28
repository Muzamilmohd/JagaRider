package com.jagarider

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Skrin utama: status hujan live di lokasi rider + peta taburan hujan dalam jarak dipilih + ramalan. */
class MainActivity : BaseActivity() {

    private val main = Handler(Looper.getMainLooper())

    private lateinit var statusText: TextView
    private lateinit var levelText: TextView
    private lateinit var detailText: TextView
    private lateinit var levelBar: LevelBarView
    private lateinit var verdictText: TextView
    private lateinit var map: RainMapView
    private lateinit var nearestText: TextView
    private lateinit var rangeText: TextView
    private lateinit var chart: RainChartView
    private lateinit var forecastText: TextView
    private lateinit var hourRow: LinearLayout
    private lateinit var hourText: TextView
    private lateinit var locationBtn: Button

    private var lastLocation: Location? = null
    private var lastRefresh = 0L
    private var loading = false
    private var askedFromButton = false

    private val autoRefresh = object : Runnable {
        override fun run() {
            refresh()
            main.postDelayed(this, refreshMs())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        val scroll = ScrollView(this).apply { addView(col) }
        setContentView(scroll)
        setupWindow(scroll)

        // Tajuk + butang Tetapan
        col.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(this@MainActivity).apply { setImageResource(R.drawable.ic_launcher) }, LinearLayout.LayoutParams(dp(52), dp(52)))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
                addView(label("JagaRide", 26f, bold = true, color = pal.orange).apply { setPadding(0, 0, 0, 0) })
                addView(label(t("Semak hujan sebelum ride", "Check the rain before you ride", "出发前查看降雨", "பயணத்திற்கு முன் மழையைச் சரிபார்க்கவும்"), 14f, color = pal.sub).apply { setPadding(0, 0, 0, 0) })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(label("⚙️", 24f).apply {
                gravity = Gravity.CENTER
                contentDescription = t("Tetapan", "Settings", "设置", "அமைப்புகள்")
                background = GradientDrawable().apply {
                    setColor(pal.card)
                    setStroke(dp(1), pal.border)
                    shape = GradientDrawable.OVAL
                }
                setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
        })

        // Kad status live
        val card = card()
        col.addView(card, lp(top = 14))
        card.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            val live = label("● LIVE", 13f, bold = true, color = Color.parseColor("#E53935"))
            ObjectAnimator.ofFloat(live, "alpha", 1f, 0.25f).apply {
                duration = 800
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                start()
            }
            addView(live)
            statusText = label("", 13f, color = pal.sub).apply { setPadding(dp(8), 0, 0, 0) }
            addView(statusText)
        })
        levelText = label("—", 30f, bold = true)
        card.addView(levelText)
        detailText = label(t("Sedang cari lokasi…", "Finding your location…", "正在获取位置…", "இருப்பிடத்தைத் தேடுகிறது…"), 15f, color = pal.sub)
        card.addView(detailText)
        levelBar = LevelBarView(this)
        card.addView(levelBar, lp(top = 10))
        verdictText = label("", 17f, bold = true, color = Color.WHITE).apply {
            setPadding(dp(14), dp(10), dp(14), dp(10))
            visibility = View.GONE
        }
        card.addView(verdictText, lp(top = 12))
        locationBtn = pill("", primary = true) { onLocationButton() }.apply { visibility = View.GONE }
        card.addView(locationBtn, lp(top = 8))

        // Jarak + peta hujan
        val mapCard = card()
        col.addView(mapCard, lp(top = 14))
        val saved = Prefs.rangeKm(this)
        val start = RANGES.indexOf(saved).takeIf { it >= 0 } ?: RANGES.indexOfFirst { it >= saved }.coerceAtLeast(0)
        mapCard.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(label(t("Jarak semakan", "Check radius", "检查范围", "சரிபார்ப்பு தூரம்"), 17f, bold = true),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            rangeText = label(rangeName(RANGES[start]), 17f, bold = true, color = pal.orange)
            addView(rangeText)
        })
        mapCard.addView(SeekBar(this).apply {
            max = RANGES.size - 1
            progress = start
            val tint = ColorStateList.valueOf(pal.orange)
            progressTintList = tint
            thumbTintList = tint
            tickMark = null
            setPadding(dp(4), dp(10), dp(4), dp(10))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    rangeText.text = rangeName(RANGES[p])
                    map.preview(RANGES[p])
                }

                override fun onStartTrackingTouch(sb: SeekBar) {}

                // Ambil data hanya bila jari dilepas — jimat permintaan
                override fun onStopTrackingTouch(sb: SeekBar) = selectRange(RANGES[sb.progress])
            })
        }, lp(top = 4))
        mapCard.addView(LinearLayout(this).apply {
            addView(label("${RANGES.first()} km", 12f, color = pal.sub), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(label("🇲🇾 " + t("Semenanjung", "Peninsula", "半岛", "தீபகற்பம்"), 12f, color = pal.sub))
        })
        map = RainMapView(this).apply { preview(RANGES[start]) }
        mapCard.addView(map, lp(top = 6))
        nearestText = label("", 15f)
        mapCard.addView(nearestText, lp(top = 6))

        // Ramalan 2 jam
        col.addView(header(t("2 jam akan datang (lokasi anda)", "Next 2 hours (your location)", "未来2小时（您的位置）", "அடுத்த 2 மணி நேரம் (உங்கள் இடம்)")))
        val fcCard = card()
        col.addView(fcCard, lp(top = 8))
        chart = RainChartView(this)
        fcCard.addView(chart)
        fcCard.addView(label(t("mm/jam setiap 15 minit", "mm/h every 15 minutes", "每15分钟（毫米/小时）", "ஒவ்வொரு 15 நிமிடமும் மிமீ/மணி"), 12f, color = pal.sub))
        forecastText = label("", 15f)
        fcCard.addView(forecastText)

        // Ramalan 6 jam
        col.addView(header(t("Ramalan 6 jam", "6-hour forecast", "6小时预报", "6 மணி நேர முன்னறிவிப்பு")))
        val hourCard = card()
        col.addView(hourCard, lp(top = 8))
        hourRow = LinearLayout(this)
        hourCard.addView(hourRow)
        hourText = label("", 15f)
        hourCard.addView(hourText, lp(top = 6))

        col.addView(pill("🔄 " + t("Muat semula", "Refresh", "刷新", "புதுப்பி")) { refresh(force = true) }, lp(top = 16))
        col.addView(label(
            t(
                "Dikemas kini automatik setiap 5 minit semasa app dibuka.",
                "Updates automatically every 5 minutes while the app is open.",
                "应用打开时每5分钟自动更新。",
                "ஆப் திறந்திருக்கும்போது ஒவ்வொரு 5 நிமிடமும் தானாகப் புதுப்பிக்கப்படும்.",
            ),
            13f, color = pal.sub,
        ).apply { gravity = Gravity.CENTER })
        col.addView(label("JagaRide v${BuildConfig.VERSION_NAME} · " + t("Dibangunkan oleh", "Developed by", "开发者：", "உருவாக்கியவர்") + " Mohd Muzamil", 12f, color = pal.sub).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, 0)
        })

        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        val missing = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQ_PERMS)

        if (Updater.enabled) {
            Updater.schedule(this)
            val fromNotification = intent.getBooleanExtra(EXTRA_CHECK_UPDATE, false)
            val stale = System.currentTimeMillis() - Prefs.lastUpdateCheck(this) > 12 * 60 * 60_000L
            if (fromNotification || (Prefs.autoUpdate(this) && stale)) UpdateUi.check(this, quiet = !fromNotification) {}
        }
    }

    override fun onResume() {
        super.onResume()
        val wait = (refreshMs() - (System.currentTimeMillis() - lastRefresh)).coerceAtLeast(0)
        main.postDelayed(autoRefresh, if (lastRefresh == 0L) 0 else wait)
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(autoRefresh)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_CHECK_UPDATE, false)) UpdateUi.check(this, quiet = false) {}
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode != REQ_PERMS) return
        if (hasLocationPermission()) {
            refresh(force = true)
        } else if (askedFromButton && !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
            // "Jangan tanya lagi" — Android tak tunjuk dialog, jadi buka tetapan app
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        } else {
            refresh(force = true)
        }
        askedFromButton = false
    }

    // ------------------------------------------------------------ lokasi

    private fun hasLocationPermission() =
        checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun checking() = t("Sedang semak…", "Checking…", "正在检查…", "சரிபார்க்கிறது…")

    private fun refresh(force: Boolean = false) {
        if (loading) return
        if (!force && System.currentTimeMillis() - lastRefresh < 60_000L) return
        if (!hasLocationPermission()) {
            showLocationProblem(
                t(
                    "JagaRide perlu kebenaran lokasi untuk semak hujan di sekeliling anda.",
                    "JagaRide needs location permission to check the rain around you.",
                    "JagaRide 需要位置权限来查看您周围的降雨。",
                    "உங்களைச் சுற்றியுள்ள மழையைச் சரிபார்க்க JagaRide-க்கு இருப்பிட அனுமதி தேவை.",
                ),
                t("Beri kebenaran lokasi", "Allow location", "允许定位", "இருப்பிட அனுமதி வழங்கவும்"),
            )
            return
        }
        val lm = getSystemService(LocationManager::class.java)
        if (Build.VERSION.SDK_INT >= 28 && !lm.isLocationEnabled) {
            showLocationProblem(
                t("Lokasi (GPS) telefon dimatikan.", "Phone location (GPS) is off.", "手机定位（GPS）已关闭。", "தொலைபேசி இருப்பிடம் (GPS) அணைக்கப்பட்டுள்ளது."),
                t("Hidupkan lokasi", "Turn on location", "开启定位", "இருப்பிடத்தை இயக்கவும்"),
            )
            return
        }
        locationBtn.visibility = View.GONE
        loading = true
        statusText.text = checking()
        currentLocation(lm) { loc ->
            if (loc == null) {
                loading = false
                showLocationProblem(
                    t(
                        "Tak dapat lokasi. Cuba di kawasan terbuka atau hidupkan GPS.",
                        "Couldn't get your location. Try an open area or turn on GPS.",
                        "无法获取位置。请到空旷处或开启GPS。",
                        "இருப்பிடம் கிடைக்கவில்லை. திறந்த இடத்தில் முயற்சிக்கவும் அல்லது GPS-ஐ இயக்கவும்.",
                    ),
                    t("Tetapan lokasi", "Location settings", "定位设置", "இருப்பிட அமைப்புகள்"),
                )
            } else {
                lastLocation = loc
                Prefs.setLastLocation(this, loc.latitude, loc.longitude)
                RainAlert.schedule(this)
                fetchWeather(loc)
            }
        }
    }

    private fun showLocationProblem(msg: String, button: String) {
        statusText.text = ""
        detailText.text = msg
        locationBtn.text = button
        locationBtn.visibility = View.VISIBLE
    }

    private fun onLocationButton() {
        if (!hasLocationPermission()) {
            askedFromButton = true
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQ_PERMS)
        } else {
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        }
    }

    /** Lokasi terkini: guna yang baru (<5 minit) kalau ada, kalau tak minta sekali. */
    @SuppressLint("MissingPermission")
    private fun currentLocation(lm: LocationManager, cb: (Location?) -> Unit) {
        val providers = lm.getProviders(true)
        val last = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        if (last != null && System.currentTimeMillis() - last.time < 5 * 60_000L) {
            cb(last)
            return
        }
        val provider = when {
            LocationManager.NETWORK_PROVIDER in providers -> LocationManager.NETWORK_PROVIDER
            LocationManager.GPS_PROVIDER in providers -> LocationManager.GPS_PROVIDER
            else -> null
        }
        if (provider == null) {
            cb(last ?: lastLocation)
            return
        }
        var done = false
        val finish = { loc: Location? ->
            if (!done) {
                done = true
                cb(loc ?: last ?: lastLocation)
            }
        }
        if (Build.VERSION.SDK_INT >= 30) {
            lm.getCurrentLocation(provider, null, mainExecutor) { finish(it) }
        } else {
            val listener = LocationListener { finish(it) }
            @Suppress("DEPRECATION")
            lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            main.postDelayed({ lm.removeUpdates(listener) }, 20_000)
        }
        main.postDelayed({ finish(null) }, 20_000)
    }

    // ------------------------------------------------------------ cuaca

    private fun fetchWeather(loc: Location) {
        loading = true
        statusText.text = checking()
        val range = Prefs.rangeKm(this)
        val points = Weather.points(loc.latitude, loc.longitude, Weather.area(loc.latitude, loc.longitude, range))
        Thread {
            val result = runCatching { Weather.fetch(points) }
            main.post {
                loading = false
                if (isDestroyed) return@post
                result.onSuccess {
                    lastRefresh = System.currentTimeMillis()
                    render(it, range)
                }.onFailure {
                    statusText.text = t("Gagal (internet?): ", "Failed (internet?): ", "失败（网络？）：", "தோல்வி (இணையம்?): ") + it.message
                }
            }
        }.start()
    }

    private fun render(samples: List<Weather.Sample>, range: Int) {
        val center = samples.first()
        val around = samples.drop(1)
        val level = center.level
        val anyThunder = samples.any { it.thunder }
        val thunderNear = samples.any { it.thunder && it.point.distKm <= 50.0 }

        statusText.text = t("Dikemas kini ", "Updated ", "更新于 ", "புதுப்பிக்கப்பட்டது ") + SimpleDateFormat("HH:mm", Locale.US).format(Date())
        levelText.text = emoji(center) + " " + level.label
        levelText.setTextColor(if (level == Weather.Level.NONE) pal.text else level.color)
        val temp = if (center.tempC.isNaN()) "" else String.format(Locale.US, " · %.0f°C", center.tempC)
        detailText.text = String.format(Locale.US, "%.1f ", center.mmh) + Weather.unit + " · " + Weather.describe(center.code) + temp
        levelBar.level = level

        // Kawasan sekeliling
        map.set(samples, range)
        val peninsula = range == Weather.PENINSULA
        val rainy = around.filter { it.mmh >= 0.1 && (peninsula || it.point.distKm <= range * 1.01) }
        val nearest = rainy.minWithOrNull(compareBy<Weather.Sample> { it.point.distKm }.thenByDescending { it.mmh })
        val heaviest = rainy.maxByOrNull { it.mmh }
        val km = "$range km"
        nearestText.text = buildString {
            when {
                nearest == null && center.mmh < 0.1 ->
                    append(
                        if (peninsula) t("✅ Tiada hujan di Semenanjung Malaysia.", "✅ No rain across Peninsular Malaysia.", "✅ 马来西亚半岛无雨。", "✅ தீபகற்ப மலேசியாவில் மழை இல்லை.")
                        else t("✅ Tiada hujan dalam $km.", "✅ No rain within $km.", "✅ ${km}内无雨。", "✅ ${km}-க்குள் மழை இல்லை.")
                    )
                nearest == null ->
                    append(
                        t(
                            "Hujan hanya di lokasi anda — sekeliling kering.",
                            "Rain only at your location — surroundings are dry.",
                            "仅您所在位置下雨，周围无雨。",
                            "உங்கள் இடத்தில் மட்டும் மழை — சுற்றிலும் வறண்டுள்ளது.",
                        )
                    )
                else -> append("🌧️ " + rainAt(t("Hujan terdekat", "Nearest rain", "最近的降雨", "அருகிலுள்ள மழை"), nearest))
            }
            if (heaviest != null && heaviest != nearest && heaviest.level > (nearest?.level ?: Weather.Level.NONE)) {
                append("\n" + rainAt(t("Paling lebat", "Heaviest", "最大降雨", "அதிக மழை"), heaviest))
            }
            if (anyThunder) append(
                "\n" + t(
                    "⚡ Ribut petir dikesan dalam kawasan ini!",
                    "⚡ Thunderstorm detected in this area!",
                    "⚡ 此区域检测到雷暴！",
                    "⚡ இந்தப் பகுதியில் இடியுடன் கூடிய மழை!",
                )
            )
        }

        // Ramalan 2 jam
        chart.set(center.nextMmh, center.nextTimes, center.nextCodes)
        renderHours(center.hours)
        val firstWet = center.nextMmh.indexOfFirst { it >= 0.1 }
        val firstDry = center.nextMmh.indexOfFirst { it < 0.1 }
        forecastText.text = when {
            center.mmh < 0.1 && firstWet < 0 ->
                Weather.icon(center.nextCodes.firstOrNull() ?: center.code, center.nextTimes.firstOrNull().orEmpty()) + " " +
                    t(
                        "Tiada hujan dijangka dalam 2 jam.",
                        "No rain expected in the next 2 hours.",
                        "未来2小时预计无雨。",
                        "அடுத்த 2 மணி நேரத்தில் மழை எதிர்பார்க்கப்படவில்லை.",
                    )
            center.mmh < 0.1 -> {
                val at = center.nextTimes[firstWet]
                val l = Weather.Level.of(center.nextMmh[firstWet]).label
                "🌧️ " + t("Hujan dijangka mula ~$at ($l).", "Rain expected from ~$at ($l).", "预计约${at}开始下雨（${l}）。", "~$at முதல் மழை எதிர்பார்க்கப்படுகிறது ($l).")
            }
            firstDry >= 0 -> {
                val at = center.nextTimes[firstDry]
                "🌤️ " + t("Hujan dijangka reda ~$at.", "Rain expected to ease ~$at.", "预计约${at}雨停。", "~$at அளவில் மழை குறையும்.")
            }
            else -> "🌧️ " + t(
                "Hujan dijangka berterusan 2 jam lagi.",
                "Rain expected to continue for 2 more hours.",
                "预计未来2小时持续下雨。",
                "அடுத்த 2 மணி நேரம் மழை தொடரும்.",
            )
        }

        // Keputusan ride
        val maxNext = center.nextMmh.maxOrNull() ?: 0.0
        // Keputusan ride hanya ambil kira hujan berdekatan (≤ 50 km), bukan yang jauh di peta
        val worstAround = rainy.filter { it.point.distKm <= 50.0 }.maxOfOrNull { it.mmh } ?: 0.0
        val rainNear = rainy.any { it.point.distKm <= 50.0 }
        val (msg, color) = when {
            level >= Weather.Level.MODERATE || center.thunder -> t(
                "🔴 Tangguh dulu — hujan lebat / ribut di lokasi anda",
                "🔴 Hold off — heavy rain / storm at your location",
                "🔴 暂缓出行——您所在位置有大雨/雷暴",
                "🔴 பயணத்தை ஒத்திவைக்கவும் — உங்கள் இடத்தில் கனமழை / புயல்",
            ) to "#C62828"
            center.mmh >= 0.1 -> t(
                "🟠 Sedang hujan — pakai baju hujan & bawa perlahan",
                "🟠 Raining now — wear a raincoat & ride slowly",
                "🟠 正在下雨——穿雨衣，慢慢骑",
                "🟠 மழை பெய்கிறது — மழைக்கோட் அணிந்து மெதுவாகச் செல்லவும்",
            ) to "#EF6C00"
            thunderNear || worstAround >= 4.0 || maxNext >= 4.0 -> t(
                "🟠 Hati-hati — hujan lebat berdekatan / akan datang",
                "🟠 Careful — heavy rain nearby / coming",
                "🟠 小心——附近有大雨/即将下大雨",
                "🟠 கவனம் — அருகில் / விரைவில் கனமழை",
            ) to "#EF6C00"
            rainNear || maxNext >= 0.1 -> t(
                "🟡 Ada hujan berdekatan — bawa baju hujan",
                "🟡 Rain nearby — bring a raincoat",
                "🟡 附近有雨——带上雨衣",
                "🟡 அருகில் மழை — மழைக்கோட் எடுத்துச் செல்லவும்",
            ) to "#F9A825"
            else -> t(
                "🟢 Cuaca baik untuk ride",
                "🟢 Good weather for a ride",
                "🟢 天气适合骑行",
                "🟢 பயணத்திற்கு நல்ல வானிலை",
            ) to "#2E7D32"
        }
        verdictText.text = msg
        verdictText.background = GradientDrawable().apply {
            setColor(Color.parseColor(color))
            cornerRadius = dp(10).toFloat()
        }
        verdictText.visibility = View.VISIBLE
    }

    /** "Hujan terdekat: ~12.5 km arah Timur Laut (Renyai)" dalam bahasa semasa. */
    private fun rainAt(what: String, s: Weather.Sample): String {
        val d = Weather.formatKm(s.point.distKm)
        val dir = Weather.toward(s.point.bearing)
        val l = s.level.label
        return t("$what: ~$d $dir ($l)", "$what: ~$d $dir ($l)", "${what}：${dir}约${d}（${l}）", "$what: $dir ~$d ($l)")
    }

    /** Jalur ramalan setiap jam: masa, ikon, suhu, peluang hujan. Jam berpeluang hujan tinggi diserlahkan. */
    private fun renderHours(hours: List<Weather.Hour>) {
        hourRow.removeAllViews()
        for (h in hours) {
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(8), 0, dp(8))
                val tint = when {
                    h.code >= 95 -> pal.stormTint
                    h.prob >= 50 -> pal.rainTint
                    else -> null
                }
                if (tint != null) background = GradientDrawable().apply {
                    setColor(tint)
                    cornerRadius = dp(12).toFloat()
                }
            }
            fun line(s: String, size: Float, color: Int, bold: Boolean = false) = label(s, size, bold, color).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(1), 0, dp(1))
            }
            cell.addView(line(h.time, 12f, pal.sub))
            cell.addView(line(Weather.icon(h.code, h.time), 26f, pal.text))
            cell.addView(line(if (h.tempC.isNaN()) "—" else String.format(Locale.US, "%.0f°", h.tempC), 15f, pal.text, bold = true))
            val probColor = when {
                h.prob >= 70 -> pal.probHigh
                h.prob >= 40 -> pal.probMid
                else -> pal.probLow
            }
            cell.addView(line("💧${h.prob}%", 12f, probColor, bold = h.prob >= 50))
            hourRow.addView(cell, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(2), 0, dp(2), 0)
            })
        }
        val storm = hours.firstOrNull { it.code >= 95 }
        val wet = hours.firstOrNull { it.prob >= 50 }
        val maxProb = hours.maxOfOrNull { it.prob } ?: 0
        hourText.text = when {
            hours.isEmpty() -> ""
            storm != null -> {
                val at = storm.time
                "⛈️ " + t(
                    "Ribut petir dijangka ~$at — elak ride waktu tu.",
                    "Thunderstorm expected ~$at — avoid riding then.",
                    "预计约${at}有雷暴——避免在此时骑行。",
                    "~$at அளவில் இடிமழை — அந்நேரம் பயணத்தைத் தவிர்க்கவும்.",
                )
            }
            wet != null -> {
                val at = wet.time
                val p = wet.prob
                "☔ " + t(
                    "Peluang hujan tinggi mulai ~$at ($p%) — gerak awal atau bawa baju hujan.",
                    "High chance of rain from ~$at ($p%) — leave early or bring a raincoat.",
                    "约${at}起降雨概率高（${p}%）——提早出发或带雨衣。",
                    "~$at முதல் மழை வாய்ப்பு அதிகம் ($p%) — முன்னதாகப் புறப்படவும் அல்லது மழைக்கோட் எடுக்கவும்.",
                )
            }
            maxProb >= 30 -> "🌥️ " + t(
                "Peluang hujan sederhana (sehingga $maxProb%).",
                "Moderate chance of rain (up to $maxProb%).",
                "中等降雨概率（最高${maxProb}%）。",
                "மிதமான மழை வாய்ப்பு ($maxProb% வரை).",
            )
            else -> "👍 " + t(
                "Peluang hujan rendah untuk 6 jam ini.",
                "Low chance of rain for the next 6 hours.",
                "未来6小时降雨概率低。",
                "அடுத்த 6 மணி நேரம் மழை வாய்ப்பு குறைவு.",
            )
        }
    }

    private fun emoji(s: Weather.Sample): String = when {
        s.thunder -> "⛈️"
        s.level == Weather.Level.NONE -> when {
            s.code <= 1 -> "☀️"
            s.code <= 3 -> "⛅"
            else -> "☁️"
        }
        s.level == Weather.Level.DRIZZLE -> "🌦️"
        else -> "🌧️"
    }

    private fun selectRange(km: Int) {
        if (km == Prefs.rangeKm(this) && lastRefresh != 0L) return
        Prefs.setRangeKm(this, km)
        map.preview(km)
        val loc = lastLocation
        if (loc != null && !loading) fetchWeather(loc) else refresh(force = true)
    }

    private fun rangeName(km: Int) =
        if (km == Weather.PENINSULA) "🇲🇾 " + t("Semenanjung", "Peninsula", "半岛", "தீபகற்பம்") else "$km km"

    /** Kawasan luas = lebih banyak titik, jadi muat semula kurang kerap (had percuma Open-Meteo). */
    private fun refreshMs(): Long = when (Prefs.rangeKm(this)) {
        in 0..25 -> 5 * 60_000L
        in 26..100 -> 10 * 60_000L
        else -> 15 * 60_000L
    }

    companion object {
        const val EXTRA_CHECK_UPDATE = "check_update"
        private const val REQ_PERMS = 1
        private val RANGES = listOf(5, 10, 25, 50, 100, 200, 300, Weather.PENINSULA)
    }
}
