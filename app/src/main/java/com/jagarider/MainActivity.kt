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
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
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
        weatherPage = ScrollView(this).apply { addView(col) }
        riderPage = RiderPage(this)
        // Dua halaman (Cuaca / Rider) + bar tab di bawah
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(FrameLayout(this@MainActivity).apply {
                addView(weatherPage)
                addView(riderPage.root)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(tabBar())
        }
        setContentView(root)
        setupWindow(root)

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
        map = RainMapView(this).apply {
            preview(RANGES[start])
            interactive = true
            // ketik dua kali = kembali ke kawasan slider
            onResetView = { preview(Prefs.rangeKm(this@MainActivity)) }
        }
        // Peta + butang ⛶ skrin penuh di penjuru kanan atas
        mapCard.addView(FrameLayout(this).apply {
            addView(map)
            addView(label("⛶", 22f, bold = true).apply {
                gravity = Gravity.CENTER
                contentDescription = t("Skrin penuh", "Full screen", "全屏", "முழுத் திரை")
                background = GradientDrawable().apply {
                    setColor(pal.card)
                    cornerRadius = dp(12).toFloat()
                }
                elevation = dp(3).toFloat()
                setOnClickListener { openFullMap() }
            }, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(8), dp(8), 0) })
        }, lp(top = 6))
        mapCard.addView(label("🤏 " + t(
            "Dua jari: cubit untuk zum / seret untuk gerak · ketik dua kali: kembali",
            "Two fingers: pinch to zoom / drag to move · double-tap: reset",
            "双指：缩放 / 拖动 · 双击：复原",
            "இரு விரல்: பெரிதாக்க / நகர்த்த · இருமுறை தட்டு: மீட்டமை",
        ), 12f, color = pal.sub).apply { gravity = Gravity.CENTER })
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
        col.addView(label("JagaRide v${BuildConfig.VERSION_NAME} · " + t("Dibangunkan oleh", "Developed by", "开发者：", "உருவாக்கியவர்") + " Muzamil Mohd", 12f, color = pal.sub).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, 0)
        })

        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        val missing = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQ_PERMS)

        RainAlert.schedule(this)
        if (Updater.enabled) {
            Updater.schedule(this)
            val fromNotification = intent.getBooleanExtra(EXTRA_CHECK_UPDATE, false)
            val stale = System.currentTimeMillis() - Prefs.lastUpdateCheck(this) > 12 * 60 * 60_000L
            if (fromNotification || (Prefs.autoUpdate(this) && stale)) UpdateUi.check(this, quiet = !fromNotification) {}
        }
        showTab(if (intent.getBooleanExtra(EXTRA_RIDER_TAB, false)) 1 else (savedInstanceState?.getInt("tab") ?: 0))
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putInt("tab", tab)
    }

    override fun onResume() {
        super.onResume()
        val wait = (refreshMs() - (System.currentTimeMillis() - lastRefresh)).coerceAtLeast(0)
        main.postDelayed(autoRefresh, if (lastRefresh == 0L) 0 else wait)
        if (tab == 1) riderPage.onShow()
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(autoRefresh)
        riderPage.onHide()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_CHECK_UPDATE, false)) UpdateUi.check(this, quiet = false) {}
        if (intent.getBooleanExtra(EXTRA_RIDER_TAB, false)) showTab(1)
    }

    // ------------------------------------------------------------ peta skrin penuh

    /** Peta skrin penuh yang sedang dibuka (dikemas kini bila data baru masuk). */
    private var fullMap: RainMapView? = null

    /** Buka peta cuaca skrin penuh: satu jari seret, dua jari zum, ketik dua kali kembali, ✕ tutup. */
    private fun openFullMap() {
        val dlg = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val fm = RainMapView(this).apply {
            copyFrom(map)
            interactive = true
            oneFingerPan = true
            fill = true
            onResetView = { preview(Prefs.rangeKm(this@MainActivity)) }
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(pal.mapBg)
            addView(fm, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(label("✕", 22f, bold = true).apply {
                gravity = Gravity.CENTER
                contentDescription = t("Tutup", "Close", "关闭", "மூடு")
                background = GradientDrawable().apply {
                    setColor(pal.card)
                    shape = GradientDrawable.OVAL
                }
                elevation = dp(4).toFloat()
                setOnClickListener { dlg.dismiss() }
            }, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(16), dp(16), 0) })
        }
        dlg.setContentView(root)
        dlg.setOnDismissListener { fullMap = null }
        dlg.show()
        dlg.window?.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
        fullMap = fm
        android.widget.Toast.makeText(
            this,
            t(
                "☝️ Seret untuk gerak · 🤏 cubit untuk zum · ketik dua kali untuk kembali",
                "☝️ Drag to move · 🤏 pinch to zoom · double-tap to reset",
                "☝️ 拖动移动 · 🤏 双指缩放 · 双击复原",
                "☝️ இழுத்து நகர்த்து · 🤏 பெரிதாக்க சுருக்கு · இருமுறை தட்டி மீட்டமை",
            ),
            android.widget.Toast.LENGTH_LONG,
        ).show()
    }

    // ------------------------------------------------------------ tab bawah

    private lateinit var weatherPage: ScrollView
    private lateinit var riderPage: RiderPage
    private var tab = 0
    private val tabViews = mutableListOf<LinearLayout>()

    private fun tabBar() = LinearLayout(this).apply {
        setBackgroundColor(pal.card)
        elevation = dp(8).toFloat()
        setPadding(0, dp(4), 0, dp(4))
        val tabs = listOf(
            "🌧️" to t("Cuaca", "Weather", "天气", "வானிலை"),
            "🏍️" to t("Rider", "Riders", "骑手", "ரைடர்கள்"),
        )
        for ((i, pair) in tabs.withIndex()) {
            val v = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, dp(4))
                addView(label(pair.first, 22f).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, 0) })
                addView(label(pair.second, 12f, bold = true).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, 0) })
                setOnClickListener { showTab(i) }
            }
            tabViews += v
            addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    // Butang Kembali: dalam halaman kumpulan → kembali ke senarai kumpulan (bukan keluar app).
    // targetSdk 36: Android 16 tak panggil onBackPressed lagi — guna OnBackInvokedCallback (API 33+).
    private var backCallback: OnBackInvokedCallback? = null

    fun setRiderBackHandler(on: Boolean) {
        if (Build.VERSION.SDK_INT < 33) return
        val cb = backCallback ?: OnBackInvokedCallback { riderPage.onBack() }.also { backCallback = it }
        onBackInvokedDispatcher.unregisterOnBackInvokedCallback(cb)
        if (on && tab == 1) onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (tab == 1 && riderPage.onBack()) return
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    private fun showTab(i: Int) {
        tab = i
        weatherPage.visibility = if (i == 0) View.VISIBLE else View.GONE
        riderPage.root.visibility = if (i == 1) View.VISIBLE else View.GONE
        if (i == 1) riderPage.onShow() else riderPage.onHide()
        setRiderBackHandler(i == 1 && riderPage.inDetail)
        for ((k, v) in tabViews.withIndex()) {
            val on = k == i
            v.alpha = if (on) 1f else 0.5f
            (v.getChildAt(1) as TextView).setTextColor(if (on) pal.orange else pal.sub)
            v.background = if (on) GradientDrawable().apply {
                setColor(Color.argb(30, 230, 81, 0))
                cornerRadius = dp(14).toFloat()
            } else null
        }
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
        val area = Weather.area(loc.latitude, loc.longitude, range)
        val points = Weather.points(loc.latitude, loc.longitude, area)
        map.setCenter(loc.latitude, loc.longitude, range) // peta keluar terus, sebelum data cuaca siap
        Thread {
            // cuba semula sekali — rangkaian mobile kadang-kadang perlahan
            val result = runCatching { Weather.fetch(points) }.recoverCatching { Weather.fetch(points) }
            // Radar sebenar untuk "sekarang" — kalau gagal, guna ramalan model sahaja
            val scan = runCatching { Radar.scan(loc.latitude, loc.longitude, area) }.getOrNull()
            main.post {
                loading = false
                if (isDestroyed) return@post
                result.onSuccess {
                    lastRefresh = System.currentTimeMillis()
                    render(it, range, scan)
                }.onFailure {
                    statusText.text = t("Gagal (internet?): ", "Failed (internet?): ", "失败（网络？）：", "தோல்வி (இணையம்?): ") + it.message
                    map.radarFrame = scan?.frame ?: Radar.current() // radar tetap keluar walaupun ramalan gagal
                }
            }
        }.start()
    }

    private fun render(samples: List<Weather.Sample>, range: Int, scan: Radar.Scan?) {
        val model = samples.first()
        // Hujan sekarang di lokasi rider: radar sebenar kalau ada, kalau tak ramalan model.
        // Kod cuaca diselaraskan supaya ikon / teks tak bercanggah dengan radar.
        val center = if (scan == null) model else {
            val r = scan.centerMmh
            val code = when {
                r >= 0.1 && model.code < 51 -> if (r < 1.0) 51 else 61
                r < 0.1 && model.code >= 51 -> 3
                model.code >= 95 && r < 1.0 -> 61
                else -> model.code
            }
            model.copy(mmh = r, code = code)
        }
        val around = samples.drop(1)
        val level = center.level
        // Ribut petir (model) hanya dipercayai bila radar pun nampak hujan lebat
        val anyThunder = samples.any { it.thunder } && (scan == null || (scan.heaviest?.mmh ?: 0.0) >= 4.0)
        val thunderNear = samples.any { it.thunder && it.point.distKm <= 50.0 } && (scan == null || (scan.heaviestNear?.mmh ?: 0.0) >= 4.0)

        statusText.text = t("Dikemas kini ", "Updated ", "更新于 ", "புதுப்பிக்கப்பட்டது ") + Clock.time(System.currentTimeMillis()) +
            (scan?.let { " · 📡 radar " + Clock.time(it.frame.time) } ?: "")
        map.radarFrame = scan?.frame ?: Radar.current()
        levelText.text = emoji(center) + " " + level.label
        levelText.setTextColor(if (level == Weather.Level.NONE) pal.text else level.color)
        val temp = if (center.tempC.isNaN()) "" else String.format(Locale.US, " · %.0f°C", center.tempC)
        detailText.text = String.format(Locale.US, "%.1f ", center.mmh) + Weather.unit + " · " + Weather.describe(center.code) + temp
        levelBar.level = level

        // Kawasan sekeliling
        map.set(samples, range)
        fullMap?.let { it.radarFrame = map.radarFrame; it.set(samples, range) } // skrin penuh ikut dikemas kini
        val peninsula = range == Weather.PENINSULA
        val rainy = around.filter { it.mmh >= 0.1 && (peninsula || it.point.distKm <= range * 1.01) }
        // Hujan terdekat / paling lebat: ikut sumber yang sama dengan peta (radar bila zum dekat, model bila jauh)
        val mapRadar = scan != null && Radar.shownFor(range)
        fun hit(s: Weather.Sample) = Radar.Hit(s.point.distKm, s.point.bearing, s.mmh)
        val nearest = if (mapRadar) scan!!.nearest else
            rainy.minWithOrNull(compareBy<Weather.Sample> { it.point.distKm }.thenByDescending { it.mmh })?.let(::hit)
        val heaviest = if (mapRadar) scan!!.heaviest else rainy.maxByOrNull { it.mmh }?.let(::hit)
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
                val at = Clock.hm(center.nextTimes[firstWet])
                val l = Weather.Level.of(center.nextMmh[firstWet]).label
                "🌧️ " + t("Hujan dijangka mula ~$at ($l).", "Rain expected from ~$at ($l).", "预计约${at}开始下雨（${l}）。", "~$at முதல் மழை எதிர்பார்க்கப்படுகிறது ($l).")
            }
            firstDry >= 0 -> {
                val at = Clock.hm(center.nextTimes[firstDry])
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
        val worstAround = if (scan != null) scan.heaviestNear?.mmh ?: 0.0
            else rainy.filter { it.point.distKm <= 50.0 }.maxOfOrNull { it.mmh } ?: 0.0
        val rainNear = worstAround >= 0.1
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
    private fun rainAt(what: String, s: Radar.Hit): String {
        val d = Weather.formatKm(s.distKm)
        val dir = Weather.toward(s.bearing)
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
            cell.addView(line(Clock.hour(h.time), 12f, pal.sub))
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
                val at = Clock.hm(storm.time)
                "⛈️ " + t(
                    "Ribut petir dijangka ~$at — elak ride waktu tu.",
                    "Thunderstorm expected ~$at — avoid riding then.",
                    "预计约${at}有雷暴——避免在此时骑行。",
                    "~$at அளவில் இடிமழை — அந்நேரம் பயணத்தைத் தவிர்க்கவும்.",
                )
            }
            wet != null -> {
                val at = Clock.hm(wet.time)
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
        const val EXTRA_RIDER_TAB = "rider_tab"
        private const val REQ_PERMS = 1
        private val RANGES = listOf(5, 10, 25, 50, 100, 200, 300, Weather.PENINSULA)
    }
}
