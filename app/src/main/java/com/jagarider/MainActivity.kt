package com.jagarider

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
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
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Skrin utama: status hujan live di lokasi rider + peta taburan hujan dalam jarak dipilih + ramalan 2 jam. */
class MainActivity : Activity() {

    private val grey = Color.parseColor("#607D8B")
    private val dark = Color.parseColor("#263238")
    private val orange = Color.parseColor("#E65100")
    private val main = Handler(Looper.getMainLooper())

    private lateinit var statusText: TextView
    private lateinit var levelText: TextView
    private lateinit var detailText: TextView
    private lateinit var levelBar: LevelBarView
    private lateinit var verdictText: TextView
    private lateinit var map: RainMapView
    private lateinit var nearestText: TextView
    private lateinit var chart: RainChartView
    private lateinit var forecastText: TextView
    private lateinit var hourRow: LinearLayout
    private lateinit var hourText: TextView
    private lateinit var locationBtn: Button
    private val rangeButtons = mutableMapOf<Int, Button>()
    private var updateText: TextView? = null

    private var lastLocation: Location? = null
    private var lastRefresh = 0L
    private var loading = false
    private var askedFromButton = false

    private val autoRefresh = object : Runnable {
        override fun run() {
            refresh()
            main.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#F5F7F9"))
            addView(col)
        }
        // targetSdk 36 = edge-to-edge: elak kandungan di bawah status/nav bar
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            v.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            insets
        }
        setContentView(scroll)
        // Ikon status bar gelap (latar app cerah)
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.setSystemBarsAppearance(
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            )
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }

        // Tajuk
        col.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(this@MainActivity).apply { setImageResource(R.drawable.ic_launcher) }, LinearLayout.LayoutParams(dp(52), dp(52)))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
                addView(label("JagaRider", 26f, bold = true, color = orange).apply { setPadding(0, 0, 0, 0) })
                addView(label("Semak hujan sebelum ride", 14f, color = grey).apply { setPadding(0, 0, 0, 0) })
            })
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
            statusText = label("", 13f, color = grey).apply { setPadding(dp(8), 0, 0, 0) }
            addView(statusText)
        })
        levelText = label("—", 30f, bold = true, color = dark)
        card.addView(levelText)
        detailText = label("Sedang cari lokasi…", 15f, color = grey)
        card.addView(detailText)
        levelBar = LevelBarView(this)
        card.addView(levelBar, lp(top = 10))
        verdictText = label("", 17f, bold = true, color = Color.WHITE).apply {
            setPadding(dp(14), dp(10), dp(14), dp(10))
            visibility = View.GONE
        }
        card.addView(verdictText, lp(top = 12))
        locationBtn = Button(this).apply {
            text = "Hidupkan lokasi"
            visibility = View.GONE
            setOnClickListener { onLocationButton() }
        }
        card.addView(locationBtn)

        // Jarak + peta hujan
        col.addView(header("Jarak semakan"))
        col.addView(LinearLayout(this).apply {
            for (km in RANGES) {
                val b = Button(this@MainActivity).apply {
                    text = "$km km"
                    isAllCaps = false
                    setOnClickListener { selectRange(km) }
                }
                rangeButtons[km] = b
                addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
            }
        })
        val mapCard = card()
        col.addView(mapCard, lp(top = 8))
        map = RainMapView(this).apply { rangeKm = Prefs.rangeKm(this@MainActivity).toDouble() }
        mapCard.addView(map)
        nearestText = label("", 15f, color = dark)
        mapCard.addView(nearestText, lp(top = 6))
        styleRangeButtons()

        // Ramalan
        col.addView(header("2 jam akan datang (lokasi anda)"))
        val fcCard = card()
        col.addView(fcCard, lp(top = 8))
        chart = RainChartView(this)
        fcCard.addView(chart)
        fcCard.addView(label("mm/jam setiap 15 minit", 12f, color = grey))
        forecastText = label("", 15f, color = dark)
        fcCard.addView(forecastText)

        col.addView(header("Ramalan 6 jam"))
        val hourCard = card()
        col.addView(hourCard, lp(top = 8))
        hourRow = LinearLayout(this)
        hourCard.addView(hourRow)
        hourText = label("", 15f, color = dark)
        hourCard.addView(hourText, lp(top = 6))

        col.addView(Button(this).apply {
            text = "🔄 Muat semula"
            isAllCaps = false
            setOnClickListener { refresh(force = true) }
        }, lp(top = 12))
        col.addView(label("Dikemas kini automatik setiap 5 minit semasa app dibuka.", 13f, color = grey))

        // Kemas kini app
        col.addView(header("Kemas kini app"))
        col.addView(label("Versi sekarang: " + Updater.currentVersion, 15f, color = grey))
        updateText = label("", 15f).also { col.addView(it) }
        col.addView(Button(this).apply {
            text = "Semak kemas kini"
            isAllCaps = false
            setOnClickListener { checkUpdate(quiet = false) }
        })
        col.addView(Switch(this).apply {
            text = "Semak kemas kini automatik setiap hari"
            textSize = 15f
            isChecked = Prefs.autoUpdate(this@MainActivity)
            setPadding(0, dp(12), 0, dp(12))
            setOnCheckedChangeListener { _, on ->
                Prefs.setAutoUpdate(this@MainActivity, on)
                Updater.schedule(this@MainActivity)
            }
        })
        if (!Updater.enabled) updateText?.text = "Kemas kini dimatikan (tiada repo)."
        col.addView(label("Data cuaca: Open-Meteo.com (CC BY 4.0). Ramalan model — bukan jaminan.", 12f, color = grey), lp(top = 16))

        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        val missing = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQ_PERMS)

        if (Updater.enabled) {
            Updater.schedule(this)
            val fromNotification = intent.getBooleanExtra(EXTRA_CHECK_UPDATE, false)
            val stale = System.currentTimeMillis() - Prefs.lastUpdateCheck(this) > 12 * 60 * 60_000L
            if (fromNotification || (Prefs.autoUpdate(this) && stale)) checkUpdate(quiet = !fromNotification)
        }
    }

    override fun onResume() {
        super.onResume()
        val wait = (REFRESH_MS - (System.currentTimeMillis() - lastRefresh)).coerceAtLeast(0)
        main.postDelayed(autoRefresh, if (lastRefresh == 0L) 0 else wait)
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(autoRefresh)
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

    // ------------------------------------------------------------ cuaca

    private fun hasLocationPermission() =
        checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun refresh(force: Boolean = false) {
        if (loading) return
        if (!force && System.currentTimeMillis() - lastRefresh < 60_000L) return
        if (!hasLocationPermission()) {
            showLocationProblem("JagaRider perlu kebenaran lokasi untuk semak hujan di sekeliling anda.", "Beri kebenaran lokasi")
            return
        }
        val lm = getSystemService(LocationManager::class.java)
        if (Build.VERSION.SDK_INT >= 28 && !lm.isLocationEnabled) {
            showLocationProblem("Lokasi (GPS) telefon dimatikan.", "Hidupkan lokasi")
            return
        }
        locationBtn.visibility = View.GONE
        loading = true
        statusText.text = "Sedang semak…"
        currentLocation(lm) { loc ->
            if (loc == null) {
                loading = false
                showLocationProblem("Tak dapat lokasi. Cuba di kawasan terbuka atau hidupkan GPS.", "Tetapan lokasi")
            } else {
                lastLocation = loc
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

    private fun fetchWeather(loc: Location) {
        loading = true
        statusText.text = "Sedang semak…"
        val range = Prefs.rangeKm(this).toDouble()
        val points = Weather.points(loc.latitude, loc.longitude, range)
        Thread {
            val result = runCatching { Weather.fetch(points) }
            main.post {
                loading = false
                if (isDestroyed) return@post
                result.onSuccess {
                    lastRefresh = System.currentTimeMillis()
                    render(it, range)
                }.onFailure {
                    statusText.text = "Gagal (internet?): " + it.message
                }
            }
        }.start()
    }

    private fun render(samples: List<Weather.Sample>, range: Double) {
        val center = samples.first()
        val around = samples.drop(1)
        val level = center.level
        val anyThunder = samples.any { it.thunder }

        statusText.text = "Dikemas kini " + SimpleDateFormat("HH:mm", Locale.US).format(Date())
        levelText.text = emoji(center) + " " + level.label
        levelText.setTextColor(if (level == Weather.Level.NONE) dark else level.color)
        val temp = if (center.tempC.isNaN()) "" else String.format(Locale.US, " · %.0f°C", center.tempC)
        detailText.text = String.format(Locale.US, "%.1f mm/jam · ", center.mmh) + Weather.describe(center.code) + temp
        levelBar.level = level

        // Kawasan sekeliling
        map.set(samples, range)
        val rainy = around.filter { it.mmh >= 0.1 && it.point.distKm <= range * 1.01 }
        val nearest = rainy.minWithOrNull(compareBy<Weather.Sample> { it.point.distKm }.thenByDescending { it.mmh })
        val heaviest = rainy.maxByOrNull { it.mmh }
        nearestText.text = buildString {
            when {
                nearest == null && center.mmh < 0.1 -> append("✅ Tiada hujan dalam ${Weather.formatKm(range)}.")
                nearest == null -> append("Hujan hanya di lokasi anda — sekeliling kering.")
                else -> append("🌧️ Hujan terdekat: ~${Weather.formatKm(nearest.point.distKm)} arah ${Weather.direction(nearest.point.bearing)} (${nearest.level.label})")
            }
            if (heaviest != null && heaviest != nearest && heaviest.level > (nearest?.level ?: Weather.Level.NONE)) {
                append("\nPaling lebat: ~${Weather.formatKm(heaviest.point.distKm)} arah ${Weather.direction(heaviest.point.bearing)} (${heaviest.level.label})")
            }
            if (anyThunder) append("\n⚡ Ribut petir dikesan dalam kawasan ini!")
        }

        // Ramalan 2 jam
        chart.set(center.nextMmh, center.nextTimes, center.nextCodes)
        renderHours(center.hours)
        val firstWet = center.nextMmh.indexOfFirst { it >= 0.1 }
        val firstDry = center.nextMmh.indexOfFirst { it < 0.1 }
        forecastText.text = when {
            center.mmh < 0.1 && firstWet < 0 ->
                Weather.icon(center.nextCodes.firstOrNull() ?: center.code, center.nextTimes.firstOrNull().orEmpty()) +
                    " Tiada hujan dijangka dalam 2 jam."
            center.mmh < 0.1 -> "🌧️ Hujan dijangka mula ~${center.nextTimes[firstWet]} (${Weather.Level.of(center.nextMmh[firstWet]).label})."
            firstDry >= 0 -> "🌤️ Hujan dijangka reda ~${center.nextTimes[firstDry]}."
            else -> "🌧️ Hujan dijangka berterusan 2 jam lagi."
        }

        // Keputusan ride
        val maxNext = center.nextMmh.maxOrNull() ?: 0.0
        val worstAround = rainy.maxOfOrNull { it.mmh } ?: 0.0
        val (msg, color) = when {
            level >= Weather.Level.MODERATE || center.thunder ->
                "🔴 Tangguh dulu — hujan lebat / ribut di lokasi anda" to "#C62828"
            center.mmh >= 0.1 ->
                "🟠 Sedang hujan — pakai baju hujan & bawa perlahan" to "#EF6C00"
            anyThunder || worstAround >= 4.0 || maxNext >= 4.0 ->
                "🟠 Hati-hati — hujan lebat berdekatan / akan datang" to "#EF6C00"
            rainy.isNotEmpty() || maxNext >= 0.1 ->
                "🟡 Ada hujan berdekatan — bawa baju hujan" to "#F9A825"
            else -> "🟢 Cuaca baik untuk ride" to "#2E7D32"
        }
        verdictText.text = msg
        verdictText.background = GradientDrawable().apply {
            setColor(Color.parseColor(color))
            cornerRadius = dp(10).toFloat()
        }
        verdictText.visibility = View.VISIBLE
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
                    h.code >= 95 -> "#FFE0B2"
                    h.prob >= 50 -> "#E3F2FD"
                    else -> null
                }
                if (tint != null) background = GradientDrawable().apply {
                    setColor(Color.parseColor(tint))
                    cornerRadius = dp(12).toFloat()
                }
            }
            fun line(s: String, size: Float, color: Int, bold: Boolean = false) = label(s, size, bold, color).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(1), 0, dp(1))
            }
            cell.addView(line(h.time, 12f, grey))
            cell.addView(line(Weather.icon(h.code, h.time), 26f, dark))
            cell.addView(line(if (h.tempC.isNaN()) "—" else String.format(Locale.US, "%.0f°", h.tempC), 15f, dark, bold = true))
            val probColor = when {
                h.prob >= 70 -> "#1565C0"
                h.prob >= 40 -> "#1E88E5"
                else -> "#90A4AE"
            }
            cell.addView(line("💧${h.prob}%", 12f, Color.parseColor(probColor), bold = h.prob >= 50))
            hourRow.addView(cell, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(2), 0, dp(2), 0)
            })
        }
        val storm = hours.firstOrNull { it.code >= 95 }
        val wet = hours.firstOrNull { it.prob >= 50 }
        val maxProb = hours.maxOfOrNull { it.prob } ?: 0
        hourText.text = when {
            hours.isEmpty() -> ""
            storm != null -> "⛈️ Ribut petir dijangka ~${storm.time} — elak ride waktu tu."
            wet != null -> "☔ Peluang hujan tinggi mulai ~${wet.time} (${wet.prob}%) — gerak awal atau bawa baju hujan."
            maxProb >= 30 -> "🌥️ Peluang hujan sederhana (sehingga $maxProb%)."
            else -> "👍 Peluang hujan rendah untuk 6 jam ini."
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
        Prefs.setRangeKm(this, km)
        map.rangeKm = km.toDouble()
        styleRangeButtons()
        val loc = lastLocation
        if (loc != null && !loading) fetchWeather(loc) else refresh(force = true)
    }

    private fun styleRangeButtons() {
        val sel = Prefs.rangeKm(this)
        for ((km, b) in rangeButtons) {
            val on = km == sel
            b.background = GradientDrawable().apply {
                setColor(if (on) orange else Color.WHITE)
                setStroke(dp(1), if (on) orange else Color.parseColor("#CFD8DC"))
                cornerRadius = dp(20).toFloat()
            }
            b.setTextColor(if (on) Color.WHITE else dark)
            b.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    // ------------------------------------------------------------ kemas kini

    private fun checkUpdate(quiet: Boolean) {
        if (!Updater.enabled) return
        if (!quiet) updateText?.text = "Sedang semak…"
        Updater.check { rel, err ->
            if (isFinishing) return@check
            if (err == null) Prefs.setLastUpdateCheck(this, System.currentTimeMillis())
            when {
                err != null -> if (!quiet) updateText?.text = "Tak dapat semak (internet?): " + err.message
                rel == null -> if (!quiet) updateText?.text = "✅ Dah versi terkini."
                else -> {
                    updateText?.text = "Versi baru ada: " + rel.version
                    showUpdateDialog(rel)
                }
            }
        }
    }

    private fun showUpdateDialog(rel: Updater.Release) {
        AlertDialog.Builder(this)
            .setTitle("Versi baru ${rel.version}")
            .setMessage(Updater.shortNotes(rel.notes).ifBlank { "Ada kemas kini untuk JagaRider." })
            .setPositiveButton("Kemas kini") { _, _ -> startInstall(rel) }
            .setNegativeButton("Nanti", null)
            .show()
    }

    private fun startInstall(rel: Updater.Release) {
        if (!Updater.canInstall(this)) {
            Toast.makeText(this, "Benarkan JagaRider pasang app, kemudian tekan Kemas kini sekali lagi", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        updateText?.text = "Muat turun… 0%"
        Updater.install(
            this, rel,
            onProgress = { pct -> updateText?.text = "Muat turun… $pct%" },
            onError = { e -> updateText?.text = "Muat turun gagal: " + e.message },
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_CHECK_UPDATE, false)) checkUpdate(quiet = false)
    }

    // ------------------------------------------------------------ UI kecil

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(16).toFloat()
        }
        elevation = dp(2).toFloat()
    }

    private fun label(text: String, size: Float, bold: Boolean = false, color: Int = dark) =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(4))
        }

    private fun header(text: String) = label(text, 18f, bold = true).apply { setPadding(0, dp(20), 0, dp(2)) }

    companion object {
        const val EXTRA_CHECK_UPDATE = "check_update"
        private const val REQ_PERMS = 1
        private const val REFRESH_MS = 5 * 60_000L
        private val RANGES = listOf(5, 10, 25, 50)
    }
}
