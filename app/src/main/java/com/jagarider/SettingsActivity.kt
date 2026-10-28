package com.jagarider

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/** Tetapan: bahasa, mod paparan, kemas kini app, dan tentang / kredit pembangun. */
class SettingsActivity : BaseActivity() {

    private var updateText: TextView? = null
    private lateinit var locStatus: TextView
    private lateinit var locBtn: Button

    override fun onResume() {
        super.onResume()
        // Pulang dari skrin kebenaran Android — kemas kini status
        if (::locStatus.isInitialized) refreshLocStatus()
    }

    private fun refreshLocStatus() {
        val live = RainAlert.hasBackgroundLocation(this)
        locStatus.text = if (live) {
            "📍 " + t("Amaran ikut lokasi live anda.", "Alerts follow your live location.", "提醒跟随您的实时位置。", "எச்சரிக்கை உங்கள் நேரடி இருப்பிடத்தைப் பின்தொடரும்.")
        } else {
            "📍 " + t(
                "Amaran guna lokasi terakhir semasa app dibuka. Benarkan lokasi \"sepanjang masa\" supaya amaran ikut anda semasa ride.",
                "Alerts use your last location when the app was open. Allow location \"all the time\" so alerts follow you while riding.",
                "提醒使用上次打开应用时的位置。请“始终允许”定位，骑行时提醒会跟随您。",
                "எச்சரிக்கை ஆப் கடைசியாகத் திறந்த இடத்தைப் பயன்படுத்துகிறது. பயணத்தின்போது உங்களைப் பின்தொடர இருப்பிடத்தை \"எப்போதும்\" அனுமதிக்கவும்.",
            )
        }
        locStatus.setTextColor(if (live) Color.parseColor("#43A047") else pal.sub)
        locBtn.visibility = if (live) View.GONE else View.VISIBLE
    }

    /**
     * Android 10+: kebenaran latar mesti diminta selepas kebenaran biasa; Android 11+ terus buka skrin
     * kebenaran app (pengguna pilih "Benarkan sepanjang masa"). Terangkan dulu kenapa perlu.
     */
    private fun askBackgroundLocation() {
        val fg = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fg.none { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            requestPermissions(fg, REQ_FG)
            return
        }
        if (Build.VERSION.SDK_INT < 29) {
            refreshLocStatus()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("📍 " + t("Lokasi sepanjang masa", "Location all the time", "始终允许定位", "எப்போதும் இருப்பிடம்"))
            .setMessage(
                t(
                    "Supaya amaran hujan ikut lokasi anda semasa ride walaupun app ditutup, pilih \"Benarkan sepanjang masa\" di skrin seterusnya.\n\nLokasi hanya digunakan untuk semak cuaca dan tidak disimpan di mana-mana pelayan.",
                    "So rain alerts follow you while riding even when the app is closed, choose \"Allow all the time\" on the next screen.\n\nLocation is only used to check the weather and is not stored on any server.",
                    "为了在应用关闭时骑行提醒也能跟随您的位置，请在下一个页面选择“始终允许”。\n\n位置仅用于查询天气，不会存储在任何服务器上。",
                    "ஆப் மூடப்பட்டிருந்தாலும் பயணத்தின்போது மழை எச்சரிக்கை உங்களைப் பின்தொடர, அடுத்த திரையில் \"எப்போதும் அனுமதி\" என்பதைத் தேர்ந்தெடுக்கவும்.\n\nஇருப்பிடம் வானிலையைச் சரிபார்க்க மட்டுமே பயன்படும், எந்தச் சேவையகத்திலும் சேமிக்கப்படாது.",
                )
            )
            .setPositiveButton(t("Teruskan", "Continue", "继续", "தொடரவும்")) { _, _ ->
                requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), REQ_BG)
            }
            .setNegativeButton(t("Nanti", "Later", "稍后", "பின்னர்"), null)
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        when (requestCode) {
            REQ_FG -> if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) askBackgroundLocation()
            REQ_BG -> {
                refreshLocStatus()
                // "Jangan tanya lagi" — buka tetapan app terus
                if (!RainAlert.hasBackgroundLocation(this) && !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                }
            }
        }
    }

    companion object {
        private const val REQ_FG = 11
        private const val REQ_BG = 12
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
        }
        val scroll = ScrollView(this).apply { addView(col) }
        setContentView(scroll)
        setupWindow(scroll)

        // Tajuk + kembali
        col.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(label("←", 26f, bold = true).apply {
                setPadding(dp(4), dp(4), dp(16), dp(4))
                setOnClickListener { finish() }
            })
            addView(label(t("Tetapan", "Settings", "设置", "அமைப்புகள்"), 24f, bold = true))
        })

        // Bahasa
        col.addView(header(t("Bahasa", "Language", "语言", "மொழி")))
        val langCard = card()
        col.addView(langCard, lp(top = 8))
        langCard.addView(radios(Lang.entries.map { it.nativeName }, Lang.entries.indexOf(I18n.lang)) { i ->
            Prefs.setLang(this, Lang.entries[i])
            recreate()
        })

        // Paparan
        col.addView(header(t("Paparan", "Display", "显示", "காட்சி")))
        val themeCard = card()
        col.addView(themeCard, lp(top = 8))
        val themes = listOf(Prefs.THEME_SYSTEM, Prefs.THEME_LIGHT, Prefs.THEME_DARK)
        themeCard.addView(
            radios(
                listOf(
                    "📱  " + t("Ikut sistem", "Follow system", "跟随系统", "கணினி அமைப்பு"),
                    "☀️  " + t("Cerah", "Light", "浅色", "வெளிர்"),
                    "🌙  " + t("Gelap", "Dark", "深色", "இருண்ட"),
                ),
                themes.indexOf(Prefs.theme(this)).coerceAtLeast(0),
            ) { i ->
                Prefs.setTheme(this, themes[i])
                recreate()
            }
        )

        // Amaran hujan
        col.addView(header(t("Amaran hujan", "Rain alerts", "降雨提醒", "மழை எச்சரிக்கை")))
        val alertCard = card()
        col.addView(alertCard, lp(top = 8))
        alertCard.addView(Switch(this).apply {
            text = "🔔 " + t("Notifikasi berbunyi bila hujan di kawasan saya", "Sound alert when it rains in my area", "我所在地区下雨时响铃提醒", "என் பகுதியில் மழை பெய்தால் ஒலி எச்சரிக்கை")
            textSize = 15f
            setTextColor(pal.text)
            isChecked = Prefs.rainAlert(this@SettingsActivity)
            setPadding(0, dp(4), 0, dp(8))
            setOnCheckedChangeListener { _, on ->
                Prefs.setRainAlert(this@SettingsActivity, on)
                RainAlert.schedule(this@SettingsActivity)
            }
        })
        alertCard.addView(label(t("Kawasan amaran (jarak dari anda)", "Alert area (distance from you)", "提醒范围（离您的距离）", "எச்சரிக்கை பகுதி (உங்களிடமிருந்து தூரம்)"), 14f, bold = true, color = pal.sub), lp(top = 4))
        val alertKms = listOf(5, 10, 25)
        alertCard.addView(
            radios(
                alertKms.map { "$it km" },
                alertKms.indexOf(Prefs.alertKm(this)).coerceAtLeast(0),
            ) { i -> Prefs.setAlertKm(this, alertKms[i]) }.apply {
                orientation = LinearLayout.HORIZONTAL
            }
        )
        alertCard.addView(
            label(
                t(
                    "🌧️ Hujan biasa = bunyi hujan\n⛈️ Hujan lebat / ribut petir = bunyi hujan + guruh\nDisemak ~setiap 15 minit.",
                    "🌧️ Normal rain = rain sound\n⛈️ Heavy rain / thunderstorm = rain + thunder sound\nChecked about every 15 minutes.",
                    "🌧️ 普通降雨 = 雨声\n⛈️ 大雨/雷暴 = 雨声 + 雷声\n约每15分钟检查一次。",
                    "🌧️ சாதாரண மழை = மழை ஒலி\n⛈️ கனமழை / இடிமழை = மழை + இடி ஒலி\n~15 நிமிடத்திற்கு ஒருமுறை சரிபார்க்கப்படும்.",
                ),
                13f, color = pal.sub,
            ),
            lp(top = 6),
        )

        // Lokasi untuk amaran: live (sepanjang masa) atau lokasi terakhir semasa app dibuka
        locStatus = label("", 14f, bold = true)
        alertCard.addView(locStatus, lp(top = 8))
        locBtn = pill("📍 " + t("Benarkan lokasi sepanjang masa", "Allow location all the time", "始终允许定位", "எப்போதும் இருப்பிடத்தை அனுமதிக்கவும்"), primary = true) {
            askBackgroundLocation()
        }
        alertCard.addView(locBtn, lp(top = 4))
        refreshLocStatus()
        alertCard.addView(LinearLayout(this).apply {
            addView(pill("🌧️ " + t("Cuba bunyi hujan", "Test rain", "试听雨声", "மழை ஒலி")) { RainAlert.test(this@SettingsActivity, RainAlert.RAIN) },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, dp(4), 0) })
            addView(pill("⛈️ " + t("Cuba bunyi ribut", "Test storm", "试听雷雨", "புயல் ஒலி")) { RainAlert.test(this@SettingsActivity, RainAlert.STORM) },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(4), 0, 0, 0) })
        }, lp(top = 8))

        // Kemas kini app
        col.addView(header(t("Kemas kini app", "App updates", "应用更新", "ஆப் புதுப்பிப்புகள்")))
        val upCard = card()
        col.addView(upCard, lp(top = 8))
        upCard.addView(label(t("Versi sekarang: ", "Current version: ", "当前版本：", "தற்போதைய பதிப்பு: ") + Updater.currentVersion, 15f, color = pal.sub))
        updateText = label("", 15f).also { upCard.addView(it) }
        if (Updater.enabled) {
            upCard.addView(pill(t("Semak kemas kini", "Check for updates", "检查更新", "புதுப்பிப்பைச் சரிபார்க்கவும்"), primary = true) {
                UpdateUi.check(this, quiet = false) { updateText?.text = it }
            }, lp(top = 6))
            upCard.addView(Switch(this).apply {
                text = t("Semak kemas kini automatik setiap hari", "Check for updates automatically every day", "每天自动检查更新", "தினமும் தானாகப் புதுப்பிப்பைச் சரிபார்க்கவும்")
                textSize = 15f
                setTextColor(pal.text)
                isChecked = Prefs.autoUpdate(this@SettingsActivity)
                setPadding(0, dp(12), 0, dp(4))
                setOnCheckedChangeListener { _, on ->
                    Prefs.setAutoUpdate(this@SettingsActivity, on)
                    Updater.schedule(this@SettingsActivity)
                }
            })
        } else {
            updateText?.text = t("Kemas kini dimatikan (tiada repo).", "Updates disabled (no repo).", "更新已关闭（无仓库）。", "புதுப்பிப்பு முடக்கப்பட்டது.")
        }

        // Tentang
        col.addView(header(t("Tentang", "About", "关于", "பற்றி")))
        val about = card().apply { gravity = Gravity.CENTER_HORIZONTAL }
        col.addView(about, lp(top = 8))
        about.addView(ImageView(this).apply { setImageResource(R.drawable.ic_launcher) }, LinearLayout.LayoutParams(dp(72), dp(72)))
        about.addView(label("JagaRide v${BuildConfig.VERSION_NAME}", 20f, bold = true, color = pal.orange).apply { gravity = Gravity.CENTER })
        about.addView(label(t("Semak hujan sebelum ride", "Check the rain before you ride", "出发前查看降雨", "பயணத்திற்கு முன் மழையைச் சரிபார்க்கவும்"), 14f, color = pal.sub).apply { gravity = Gravity.CENTER })
        about.addView(label(t("Dibangunkan oleh", "Developed by", "开发者", "உருவாக்கியவர்"), 13f, color = pal.sub).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
        })
        about.addView(label("👨‍💻 Mohd Muzamil", 18f, bold = true).apply { gravity = Gravity.CENTER })
        about.addView(
            label(
                t(
                    "Data cuaca: Open-Meteo.com (CC BY 4.0)\nPeta: © OpenStreetMap contributors\nRamalan model — bukan jaminan.",
                    "Weather data: Open-Meteo.com (CC BY 4.0)\nMap: © OpenStreetMap contributors\nModel forecast — not a guarantee.",
                    "天气数据：Open-Meteo.com（CC BY 4.0）\n地图：© OpenStreetMap contributors\n模型预报，仅供参考。",
                    "வானிலை தரவு: Open-Meteo.com (CC BY 4.0)\nவரைபடம்: © OpenStreetMap contributors\nமாதிரி முன்னறிவிப்பு — உத்தரவாதம் அல்ல.",
                ),
                12f, color = pal.sub,
            ).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(16), 0, 0)
            }
        )
    }

    /**
     * Senarai pilihan radio; onPick dipanggil hanya bila pengguna tekan pilihan lain.
     * ID unik + tiada simpan keadaan: kalau tidak, recreate() memulihkan butang ikut ID dan kumpulan
     * Bahasa & Paparan (ID sama) bertukar pilihan sendiri — bahasa tersangkut.
     */
    private fun radios(options: List<String>, selected: Int, onPick: (Int) -> Unit) = RadioGroup(this).apply {
        isSaveEnabled = false
        options.forEachIndexed { i, s ->
            val b = RadioButton(this@SettingsActivity).apply {
                id = View.generateViewId()
                isSaveEnabled = false
                text = s
                textSize = 16f
                setTextColor(pal.text)
                setPadding(dp(8), dp(10), 0, dp(10))
                setOnClickListener { if (i != selected) onPick(i) }
            }
            addView(b)
            if (i == selected) check(b.id)
        }
    }
}
