package com.jagarider

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Tab "Rider": senarai kumpulan (boleh banyak) → tekan kumpulan untuk peta live + butiran rider.
 * Cipta / sertai kumpulan dengan kod, kongsi lokasi dengan kumpulan pilihan.
 */
class RiderPage(private val act: MainActivity) {

    val root = ScrollView(act)
    private val col = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(act.dp(16), act.dp(16), act.dp(16), act.dp(24))
    }
    private val main = Handler(Looper.getMainLooper())
    private var visible = false

    /** Kod kumpulan yang sedang dibuka (null = senarai kumpulan). */
    private var selected: String? = null
    val inDetail: Boolean get() = selected != null

    // halaman butiran
    private var map: RainMapView? = null
    private var list: LinearLayout? = null
    private var statusText: TextView? = null
    private var shareBtn: Button? = null
    private var riders: List<RiderGroup.Rider> = emptyList()
    private var msgs: List<RiderGroup.Msg> = emptyList()
    private var meet: RiderGroup.Meet? = null
    private val trails = HashMap<String, MutableList<Pair<Double, Double>>>()
    private var msgFeed: LinearLayout? = null
    private var meetInfo: TextView? = null
    private val animators = mutableListOf<ValueAnimator>()

    // halaman senarai: bilangan rider aktif setiap kumpulan
    private val active = HashMap<String, Int>()
    private val rowInfo = HashMap<String, TextView>()

    private val poll = object : Runnable {
        override fun run() {
            refresh()
            main.postDelayed(this, if (selected != null) DETAIL_POLL_MS else LIST_POLL_MS)
        }
    }

    init {
        root.addView(col)
        build()
    }

    fun onShow() {
        visible = true
        // Rider pernah tekan "Mula kongsi" tapi servis terhenti (phone restart / app dikemas kini) — sambung semula
        if (RideLog.solo(act) && !LocationShareService.running && hasLocation()) LocationShareService.startSolo(act)
        if (Firebase.enabled && RiderGroup.sharing(act).isNotEmpty() && !LocationShareService.running && hasLocation()) {
            RiderGroup.groups(act).firstOrNull { it.code in RiderGroup.sharing(act) }?.let { LocationShareService.startGroup(act, it) }
        }
        build()
        main.removeCallbacks(poll)
        main.post(poll)
    }

    fun onHide() {
        visible = false
        main.removeCallbacks(poll)
        stopAnimations()
    }

    /** Butang Kembali: dari butiran kumpulan ke senarai. True kalau diurus di sini. */
    fun onBack(): Boolean {
        if (selected == null) return false
        open(null)
        return true
    }

    private fun open(code: String?) {
        selected = code
        riders = emptyList()
        msgs = emptyList()
        meet = null
        trails.clear()
        act.setRiderBackHandler(code != null)
        build()
        // Nama / ikon mungkin ditukar oleh ahli lain — semak pelayan
        RiderGroup.find(act, code ?: "")?.let { g ->
            network({ RiderGroup.refreshMeta(act.applicationContext, g) }) { fresh ->
                if (selected == code && (fresh.name != g.name || fresh.icon != g.icon)) build()
            }
        }
        main.removeCallbacks(poll)
        if (visible) main.post(poll)
    }

    // ------------------------------------------------------------ UI

    private fun build() {
        stopAnimations()
        col.removeAllViews()
        map = null
        list = null
        statusText = null
        shareBtn = null
        msgFeed = null
        meetInfo = null
        rowInfo.clear()
        col.addView(LinearLayout(act).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(act.label("👥", 34f).apply { setPadding(0, 0, act.dp(10), 0) })
            addView(LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                addView(act.label(t("Ride Kumpulan", "Group Ride", "群组骑行", "குழுப் பயணம்"), 26f, bold = true, color = act.pal.orange).apply { setPadding(0, 0, 0, 0) })
                addView(act.label(t("Kongsi lokasi live dengan kawan ride", "Share live location with your riding buddies", "与骑行伙伴共享实时位置", "சக ரைடர்களுடன் நேரடி இருப்பிடத்தைப் பகிரவும்"), 14f, color = act.pal.sub).apply { setPadding(0, 0, 0, 0) })
            })
        })

        if (!Firebase.enabled) {
            val c = act.card()
            col.addView(c, act.lp(top = 14))
            c.addView(act.label("⚙️ " + t(
                "Fungsi rider belum disediakan dalam versi ini (pelayan Firebase belum disambung).",
                "Rider features aren't set up in this version yet (Firebase server not connected).",
                "此版本尚未设置骑手功能（未连接 Firebase 服务器）。",
                "இந்தப் பதிப்பில் ரைடர் அம்சம் இன்னும் அமைக்கப்படவில்லை (Firebase சேவையகம் இணைக்கப்படவில்லை).",
            ), 15f))
            return
        }

        val g = selected?.let { RiderGroup.find(act, it) }
        if (g == null) {
            selected = null
            buildNameCard()
            buildList()
            buildSaver()
        } else {
            buildDetail(g)
        }
    }

    private fun buildNameCard() {
        val nameCard = act.card()
        col.addView(nameCard, act.lp(top = 14))
        nameCard.addView(act.label(t("Nama anda (dilihat ahli kumpulan)", "Your name (seen by group members)", "您的名字（群组成员可见）", "உங்கள் பெயர் (குழு உறுப்பினர்கள் பார்ப்பார்கள்)"), 14f, bold = true, color = act.pal.sub))
        nameCard.addView(EditText(act).apply {
            setText(RiderGroup.riderName(act))
            hint = t("cth. Ali", "e.g. Ali", "例如 Ali", "எ.கா. Ali")
            setSingleLine()
            textSize = 17f
            setTextColor(act.pal.text)
            setHintTextColor(act.pal.sub)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) = RiderGroup.setRiderName(act, s.toString())
            })
        })
    }

    /** Senarai kumpulan — tekan untuk buka peta & butiran. */
    private fun buildList() {
        val groups = RiderGroup.groups(act)
        val c = act.card()
        col.addView(c, act.lp(top = 12))
        c.addView(act.label("👥 " + t("Kumpulan saya", "My groups", "我的群组", "என் குழுக்கள்"), 18f, bold = true))
        if (groups.isEmpty()) {
            c.addView(act.label(t(
                "Belum ada kumpulan. Cipta kumpulan dan kongsi kod dengan kawan, atau sertai kumpulan dengan kod yang diberi kawan. Lokasi disulitkan — hanya ahli kumpulan boleh lihat.",
                "No groups yet. Create a group and share its code with friends, or join one with a code from a friend. Locations are encrypted — only group members can see them.",
                "还没有群组。创建群组并把代码分享给朋友，或用朋友给的代码加入群组。位置已加密，只有群组成员能看到。",
                "இன்னும் குழு இல்லை. குழுவை உருவாக்கி குறியீட்டை நண்பர்களுடன் பகிரவும், அல்லது நண்பர் தந்த குறியீட்டுடன் சேரவும். இருப்பிடம் மறையாக்கம் செய்யப்படும்.",
            ), 14f, color = act.pal.sub))
        }
        val sharing = sharingNow()
        for (g in groups) {
            val row = LinearLayout(act).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(act.dp(12), act.dp(12), act.dp(12), act.dp(12))
                background = GradientDrawable().apply {
                    setColor(act.pal.bg)
                    cornerRadius = act.dp(12).toFloat()
                }
                isClickable = true
                setOnClickListener { open(g.code) }
            }
            row.addView(act.label(g.icon, 26f).apply { setPadding(0, 0, act.dp(12), 0) })
            val texts = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(act.label(g.name, 17f, bold = true).apply { setPadding(0, 0, 0, 0) })
            val info = act.label(groupInfo(g, sharing), 13f, color = act.pal.sub).apply { setPadding(0, act.dp(2), 0, 0) }
            rowInfo[g.code] = info
            texts.addView(info)
            row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(act.label("›", 26f, color = act.pal.sub))
            c.addView(row, act.lp(top = 8))
        }
        c.addView(act.pill("➕ " + t("Cipta kumpulan", "Create group", "创建群组", "குழுவை உருவாக்கு"), primary = true) { askCreate() }, act.lp(top = 14))
        c.addView(LinearLayout(act).apply {
            addView(act.pill("📷 " + t("Imbas QR", "Scan QR", "扫描二维码", "QR ஸ்கேன்")) { scanQr() },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, act.dp(4), 0) })
            addView(act.pill("🔗 " + t("Masukkan kod", "Enter code", "输入代码", "குறியீடு")) { askJoin() },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(act.dp(4), 0, 0, 0) })
        }, act.lp(top = 8))
    }

    /** Mod jimat bateri (ride jauh) — di sini supaya mudah ditukar sebelum / semasa kongsi. */
    private fun buildSaver() {
        val c = act.card()
        col.addView(c, act.lp(top = 12))
        c.addView(saverSwitch())
        c.addView(act.label(t(
            "Kongsi lokasi setiap 30 saat (bukan 10 saat) dan GPS lebih jarang — sesuai untuk ride jauh. Ahli lain nampak kedudukan anda kurang kerap.",
            "Shares location every 30 s (instead of 10 s) with less frequent GPS — good for long rides. Others see your position update less often.",
            "每30秒（而非10秒）共享一次位置，GPS 更少更新——适合长途骑行。其他人看到您的位置更新会较慢。",
            "30 வினாடிக்கு ஒருமுறை (10 அல்ல) இருப்பிடம் பகிரும், GPS குறைவாக — நீண்ட பயணத்துக்கு ஏற்றது. மற்றவர்கள் உங்கள் இடத்தைக் குறைவாகப் பார்ப்பார்கள்.",
        ), 13f, color = act.pal.sub))
    }

    private fun saverSwitch() = android.widget.Switch(act).apply {
        text = "🔋 " + t("Mod jimat bateri (ride jauh)", "Battery saver (long rides)", "省电模式（长途骑行）", "பேட்டரி சேமிப்பு (நீண்ட பயணம்)")
        textSize = 15f
        setTextColor(act.pal.text)
        setPadding(act.dp(4), act.dp(6), act.dp(4), act.dp(6))
        isChecked = Prefs.batterySaver(act)
        setOnCheckedChangeListener { _, on ->
            Prefs.setBatterySaver(act, on)
            // Sedang berkongsi — mula semula GPS dengan kadar baru
            if (LocationShareService.running) act.startForegroundService(android.content.Intent(act, LocationShareService::class.java))
            toast(if (on) "🔋 " + t("Jimat bateri: kongsi setiap 30 saat", "Battery saver: sharing every 30 s", "省电：每30秒共享", "பேட்டரி சேமிப்பு: 30 வினாடிக்கு ஒருமுறை")
                else "⚡ " + t("Kongsi setiap 10 saat", "Sharing every 10 s", "每10秒共享", "10 வினாடிக்கு ஒருமுறை பகிர்வு"))
        }
    }

    private fun groupInfo(g: RiderGroup.Group, sharing: Set<String>): String {
        val parts = mutableListOf<String>()
        if (g.code in sharing) parts += "📍 " + t("Anda berkongsi", "You're sharing", "您正在共享", "நீங்கள் பகிர்கிறீர்கள்")
        val n = active[g.code]
        parts += when {
            n == null -> "…"
            n == 0 -> t("Tiada rider aktif", "No active riders", "没有活跃骑手", "செயலில் ரைடர் இல்லை")
            else -> t("$n rider aktif", "$n active rider" + (if (n > 1) "s" else ""), "${n} 位活跃骑手", "$n செயலில் உள்ள ரைடர்")
        }
        return parts.joinToString(" · ")
    }

    /** Halaman satu kumpulan: peta besar di atas, kongsi lokasi, butiran rider, jemputan (kod / QR). */
    private fun buildDetail(g: RiderGroup.Group) {
        // Tajuk: ‹ kembali · ikon · nama · ✏️ ubah
        col.addView(LinearLayout(act).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, act.dp(10), 0, act.dp(2))
            addView(act.label("‹", 30f, bold = true, color = act.pal.orange).apply {
                setPadding(0, 0, act.dp(12), 0)
                setOnClickListener { open(null) }
            })
            addView(act.label(g.icon, 28f).apply { setPadding(0, 0, act.dp(8), 0) })
            addView(act.label(g.name, 20f, bold = true).apply { setPadding(0, 0, 0, 0) },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(act.label("✏️", 22f).apply {
                setPadding(act.dp(10), act.dp(4), act.dp(4), act.dp(4))
                contentDescription = t("Ubah kumpulan", "Edit group", "编辑群组", "குழுவைத் திருத்து")
                setOnClickListener { askEdit(g) }
            })
        })

        // Peta besar di atas (lebih tinggi dari lebar) — rider + radar hujan
        map = RainMapView(act).also {
            it.interactive = true
            it.showRangeCircle = false
            it.aspect = 1.25f
            it.onResetView = { render() }
            col.addView(it, act.lp(top = 6))
        }
        col.addView(act.label("🤏 " + t(
            "Dua jari: cubit untuk zum / seret untuk gerak · ketik dua kali: muat semua rider",
            "Two fingers: pinch to zoom / drag to move · double-tap: fit all riders",
            "双指：缩放 / 拖动 · 双击：显示所有骑手",
            "இரு விரல்: பெரிதாக்க / நகர்த்த · இருமுறை தட்டு: அனைவரையும் காட்டு",
        ), 12f, color = act.pal.sub).apply { gravity = Gravity.CENTER })

        // Kongsi lokasi
        shareBtn = act.pill("", primary = true) { toggleShare(g) }
        col.addView(shareBtn, act.lp(top = 8))
        statusText = act.label("", 13f, color = act.pal.sub).apply { gravity = Gravity.CENTER }
        col.addView(statusText)
        styleShare(g)
        col.addView(saverSwitch(), act.lp(top = 2))

        // Mesej pantas: satu tekan semasa ride (ahli dapat notifikasi + suara)
        col.addView(android.widget.HorizontalScrollView(act).apply {
            isHorizontalScrollBarEnabled = false
            addView(LinearLayout(act).apply {
                for (q in RiderGroup.Quick.entries) {
                    addView(act.pill(q.icon + " " + q.text) { sendQuick(g, q) }.apply {
                        textSize = 14f
                        setPadding(act.dp(14), 0, act.dp(14), 0)
                    }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, act.dp(46)).apply { setMargins(0, 0, act.dp(6), 0) })
                }
            })
        }, act.lp(top = 10))
        msgFeed = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        col.addView(msgFeed, act.lp(top = 4))
        renderMsgs()

        // Titik berkumpul
        col.addView(LinearLayout(act).apply {
            gravity = Gravity.CENTER_VERTICAL
            meetInfo = act.label("", 14f).also { addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)) }
            addView(act.pill("🚩 " + t("Titik berkumpul", "Meeting point", "集合点", "சந்திப்பு இடம்")) { askMeet(g) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }, act.lp(top = 8))

        // Butiran rider
        val rc = act.card()
        col.addView(rc, act.lp(top = 10))
        rc.addView(act.label("🏍️ " + t("Rider dalam kumpulan", "Riders in this group", "群组中的骑手", "குழுவில் உள்ள ரைடர்கள்"), 16f, bold = true))
        list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        rc.addView(list, act.lp(top = 4))
        render()

        // Jemput rider: kod, kongsi, salin, QR
        val c = act.card()
        col.addView(c, act.lp(top = 12))
        c.addView(act.label("➕ " + t("Jemput rider", "Invite riders", "邀请骑手", "ரைடர்களை அழை"), 16f, bold = true))
        c.addView(act.label(g.code, 17f, bold = true, color = act.pal.orange).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        })
        c.addView(LinearLayout(act).apply {
            addView(act.pill("📤 " + t("Kongsi", "Share", "分享", "பகிர்")) { share(g) },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, act.dp(3), 0) })
            addView(act.pill("📋 " + t("Salin", "Copy", "复制", "நகல்")) { copy(g) },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(act.dp(3), 0, act.dp(3), 0) })
            addView(act.pill("🔳 QR") { showQr(g) },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(act.dp(3), 0, 0, 0) })
        }, act.lp(top = 6))

        col.addView(act.pill("🚪 " + t("Keluar kumpulan", "Leave group", "退出群组", "குழுவை விட்டு வெளியேறு")) { askLeave(g) }, act.lp(top = 16))
    }

    private fun sharingNow(): Set<String> = if (LocationShareService.running) RiderGroup.sharing(act) else emptySet()

    private fun styleShare(g: RiderGroup.Group) {
        val on = g.code in sharingNow()
        shareBtn?.text = if (on) "⏹ " + t("Henti kongsi lokasi", "Stop sharing location", "停止共享位置", "இருப்பிடப் பகிர்வை நிறுத்து")
        else "▶ " + t("Mula kongsi lokasi saya", "Start sharing my location", "开始共享我的位置", "என் இருப்பிடத்தைப் பகிரத் தொடங்கு")
        shareBtn?.let { act.stylePill(it, primary = !on) }
        val until = RiderGroup.shareUntil(act, g.code)
        statusText?.text = when {
            on && until > 0 -> {
                val at = Clock.time(until)
                "📍 " + t("Berkongsi sehingga $at", "Sharing until $at", "共享至${at}", "$at வரை பகிர்கிறது")
            }
            on -> "📍 " + t("Berkongsi sehingga anda tekan Henti", "Sharing until you tap Stop", "共享直到您点击停止", "நிறுத்து அழுத்தும் வரை பகிர்கிறது")
            else -> t("Anda boleh lihat rider lain walaupun tak kongsi.", "You can see other riders even without sharing.", "即使不共享，您也能看到其他骑手。", "பகிராமலும் மற்ற ரைடர்களைப் பார்க்கலாம்.")
        }
    }

    // ------------------------------------------------------------ mesej pantas & titik berkumpul

    /** Mesej 30 minit terakhir + maklumat titik berkumpul. */
    private fun renderMsgs() {
        val f = msgFeed ?: return
        f.removeAllViews()
        val now = System.currentTimeMillis()
        for (m in msgs.filter { now - it.time < 30 * 60_000L }.take(4)) {
            val who = if (m.me) t("Anda", "You", "您", "நீங்கள்") else m.name
            f.addView(act.label("${m.quick.icon} $who: ${m.quick.text} · " + agoText(now - m.time), 14f).apply {
                setPadding(act.dp(10), act.dp(6), act.dp(10), act.dp(6))
                background = GradientDrawable().apply {
                    setColor(act.pal.rainTint)
                    cornerRadius = act.dp(10).toFloat()
                }
            }, act.lp(top = 4))
        }
        meetInfo?.text = meet?.let { mp ->
            "🚩 " + mp.label.ifBlank { t("Titik berkumpul", "Meeting point", "集合点", "சந்திப்பு இடம்") } +
                (if (mp.by.isNotBlank()) " · " + mp.by else "")
        } ?: t("Belum ada titik berkumpul", "No meeting point yet", "尚未设置集合点", "சந்திப்பு இடம் இல்லை")
    }

    private fun sendQuick(g: RiderGroup.Group, q: RiderGroup.Quick) {
        if (needName()) return
        network({ RiderGroup.sendQuick(act.applicationContext, g, q) }) {
            toast(q.icon + " " + t("Dihantar", "Sent", "已发送", "அனுப்பப்பட்டது"))
            refresh()
        }
    }

    /** Letak / buang titik berkumpul: lokasi saya sekarang, atau cari tempat. */
    private fun askMeet(g: RiderGroup.Group) {
        val opts = mutableListOf(
            "📍 " + t("Lokasi saya sekarang", "My current location", "我的当前位置", "என் தற்போதைய இடம்"),
            "🔎 " + t("Cari tempat…", "Search a place…", "搜索地点…", "இடம் தேடு…"),
        )
        if (meet != null) {
            opts += "🧭 " + t("Navigasi ke titik berkumpul", "Navigate to meeting point", "导航至集合点", "சந்திப்பு இடத்திற்கு வழிகாட்டு")
            opts += "🗑️ " + t("Buang titik berkumpul", "Remove meeting point", "删除集合点", "சந்திப்பு இடத்தை நீக்கு")
        }
        AlertDialog.Builder(act)
            .setTitle("🚩 " + t("Titik berkumpul", "Meeting point", "集合点", "சந்திப்பு இடம்"))
            .setItems(opts.toTypedArray()) { _, i ->
                when (i) {
                    0 -> {
                        val me = riders.firstOrNull { it.me }?.let { it.lat to it.lon } ?: Prefs.lastLocation(act)
                        if (me == null) {
                            toast(t("Lokasi belum diketahui", "Location not known yet", "尚未获取位置", "இருப்பிடம் தெரியவில்லை"))
                        } else {
                            val label = t("Lokasi ", "Location of ", "", "") + RiderGroup.riderName(act) + t("", "", "的位置", " இருப்பிடம்")
                            network({ RiderGroup.setMeet(act.applicationContext, g, me.first, me.second, label) }) { refresh() }
                        }
                    }
                    1 -> searchMeet(g)
                    2 -> meet?.let { mp ->
                        val gm = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("google.navigation:q=${mp.lat},${mp.lon}&mode=l")).setPackage("com.google.android.apps.maps")
                        runCatching { act.startActivity(gm) }.onFailure {
                            act.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("geo:${mp.lat},${mp.lon}?q=${mp.lat},${mp.lon}")))
                        }
                    }
                    3 -> network({ RiderGroup.clearMeet(act.applicationContext, g) }) {
                        meet = null
                        renderMsgs()
                        render()
                    }
                }
            }
            .show()
    }

    private fun searchMeet(g: RiderGroup.Group) {
        val e = input(t("Nama tempat, cth. Petronas Bangi", "Place name, e.g. Petronas Bangi", "地点名称，例如 Petronas Bangi", "இடப் பெயர், எ.கா. Petronas Bangi"))
        AlertDialog.Builder(act)
            .setTitle("🔎 " + t("Cari tempat berkumpul", "Find a meeting place", "查找集合地点", "சந்திப்பு இடம் தேடு"))
            .setView(e)
            .setPositiveButton(t("Cari", "Search", "搜索", "தேடு")) { _, _ ->
                val q = e.text.toString().trim()
                if (q.isEmpty()) return@setPositiveButton
                val (lat, lon) = Prefs.lastLocation(act) ?: (3.14 to 101.69)
                network({ Route.search(q, lat, lon) }) { places ->
                    if (places.isEmpty()) {
                        toast(t("Tempat tak dijumpai", "Place not found", "找不到地点", "இடம் கிடைக்கவில்லை"))
                    } else {
                        AlertDialog.Builder(act)
                            .setItems(places.map { "📍 " + it.name + if (it.detail.isNotBlank()) " — " + it.detail else "" }.toTypedArray()) { _, i ->
                                val p = places[i]
                                network({ RiderGroup.setMeet(act.applicationContext, g, p.lat, p.lon, p.name) }) { refresh() }
                            }
                            .show()
                    }
                }
            }
            .setNegativeButton(t("Batal", "Cancel", "取消", "ரத்து"), null)
            .show()
    }

    // ------------------------------------------------------------ data

    private fun refresh() {
        val app = act.applicationContext
        val code = selected
        if (code != null) {
            val g = RiderGroup.find(act, code) ?: return
            Thread {
                val r = runCatching { RiderGroup.fetch(app, g) }
                val q = runCatching { RiderGroup.fetchQuick(app, g) }.getOrNull()
                val p = runCatching { RiderGroup.fetchMeet(app, g) }
                main.post {
                    if (!visible || act.isDestroyed || selected != code) return@post
                    q?.let { msgs = it }
                    p.onSuccess { meet = it }
                    r.onSuccess {
                        // sembunyi yang tak aktif lebih 2 jam
                        riders = it.filter { x -> System.currentTimeMillis() - x.time < 2 * 60 * 60_000L }
                        // jejak laluan (hanya semasa halaman dibuka) — titik baru bila bergerak > 20 m
                        for (x in riders) {
                            val tr = trails.getOrPut(x.uid) { mutableListOf() }
                            if (tr.isEmpty() || distKm(tr.last().first, tr.last().second, x.lat, x.lon) > 0.02) tr += x.lat to x.lon
                            while (tr.size > 400) tr.removeAt(0)
                        }
                        render()
                    }.onFailure { statusText?.text = t("Gagal (internet?): ", "Failed (internet?): ", "失败（网络？）：", "தோல்வி (இணையம்?): ") + it.message }
                    renderMsgs()
                    styleShare(g)
                }
            }.start()
        } else {
            val groups = RiderGroup.groups(act)
            if (groups.isEmpty()) return
            Thread {
                val counts = groups.associate { g ->
                    g.code to runCatching { RiderGroup.fetch(app, g).count { System.currentTimeMillis() - it.time < 5 * 60_000L } }.getOrNull()
                }
                main.post {
                    if (!visible || act.isDestroyed || selected != null) return@post
                    val sharing = sharingNow()
                    for (g in groups) {
                        counts[g.code]?.let { active[g.code] = it }
                        rowInfo[g.code]?.text = groupInfo(g, sharing)
                    }
                }
            }.start()
        }
    }

    private fun moving(r: RiderGroup.Rider) = r.speedKmh >= MOVING_KMH && System.currentTimeMillis() - r.time < 5 * 60_000L

    private fun render() {
        val m = map ?: return
        val l = list ?: return
        val mine = riders.firstOrNull { it.me }
        val (myLat, myLon) = mine?.let { it.lat to it.lon } ?: Prefs.lastLocation(act) ?: (3.95 to 102.0)
        val others = riders.filter { !it.me }
        // Zum automatik supaya semua rider muat (sedekat 0.5 km bila rider berdekatan)
        val far = others.maxOfOrNull { distKm(myLat, myLon, it.lat, it.lon) } ?: 0.0
        m.radarFrame = Radar.current()
        m.meet = meet?.let { it.lat to it.lon }
        m.trails = trails.mapValues { it.value.toList() }
        m.riders = others
        m.fitRiders(myLat, myLon, maxOf(0.5, far * 1.3))

        stopAnimations()
        l.removeAllViews()
        if (riders.isEmpty()) {
            l.addView(act.label(t(
                "Belum ada rider berkongsi lokasi. Jemput kawan dan tekan \"Mula kongsi\".",
                "No riders sharing yet. Invite friends and tap \"Start sharing\".",
                "还没有骑手共享位置。邀请朋友并点击“开始共享”。",
                "இன்னும் யாரும் பகிரவில்லை. நண்பர்களை அழைத்து \"பகிரத் தொடங்கு\" அழுத்தவும்.",
            ), 14f, color = act.pal.sub))
            return
        }
        for (r in riders.sortedWith(compareBy<RiderGroup.Rider> { !it.me }.thenBy { distKm(myLat, myLon, it.lat, it.lon) })) {
            l.addView(riderRow(r, myLat, myLon), act.lp(top = 6))
        }
    }

    /** Satu baris rider: ikon (🏍️ bergoyang bila sedang ride), nama, jarak, kelajuan, masa. */
    private fun riderRow(r: RiderGroup.Rider, myLat: Double, myLon: Double): View {
        val ago = System.currentTimeMillis() - r.time
        val stale = ago > 5 * 60_000L
        val riding = moving(r)
        val row = LinearLayout(act).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(act.dp(10), act.dp(8), act.dp(10), act.dp(8))
            background = GradientDrawable().apply {
                setColor(if (riding) act.pal.rainTint else act.pal.bg)
                cornerRadius = act.dp(12).toFloat()
            }
        }
        val icon = act.label(
            when {
                riding -> "🏍️"
                stale -> "⚪"
                r.me -> "🟠"
                else -> "🟢"
            }, 24f,
        ).apply { setPadding(0, 0, act.dp(10), 0) }
        row.addView(icon)
        if (riding) {
            // animasi "sedang ride": motosikal bergoyang ke depan & belakang
            animators += ObjectAnimator.ofFloat(icon, "translationX", -act.dp(3).toFloat(), act.dp(3).toFloat()).apply {
                duration = 260
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        }
        val texts = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val name = if (r.me) r.name + " (" + t("anda", "you", "您", "நீங்கள்") + ")" else r.name
        texts.addView(act.label(name, 16f, bold = true, color = if (stale) act.pal.sub else act.pal.text).apply { setPadding(0, 0, 0, 0) })
        val parts = mutableListOf<String>()
        if (!r.me) parts += Weather.formatKm(Math.round(distKm(myLat, myLon, r.lat, r.lon) * 10) / 10.0)
        parts += when {
            riding -> t("Sedang ride", "Riding", "骑行中", "பயணத்தில்") + " " + String.format(Locale.US, "%.0f ", r.speedKmh) + Weather.kmh +
                (if (!r.bearing.isNaN()) " " + Weather.toward(r.bearing) else "")
            stale -> t("Tidak aktif", "Inactive", "不活跃", "செயலில் இல்லை")
            else -> t("Berhenti", "Stopped", "已停下", "நிறுத்தியுள்ளார்")
        }
        // ETA ke titik berkumpul: laju semasa kalau sedang ride, kalau tak anggap ~40 km/j
        meet?.let { mp ->
            val d = distKm(r.lat, r.lon, mp.lat, mp.lon)
            if (d < 0.15) {
                parts += "🚩 " + t("Di titik berkumpul", "At the meeting point", "已在集合点", "சந்திப்பு இடத்தில்")
            } else if (!stale) {
                val min = (d / (if (riding) r.speedKmh else 40.0) * 60).toInt().coerceAtLeast(1)
                parts += "🚩 " + t("~$min min", "~$min min", "约${min}分钟", "~$min நிமி.")
            }
        }
        parts += agoText(ago)
        texts.addView(act.label(parts.joinToString(" · "), 13f, color = act.pal.sub).apply { setPadding(0, act.dp(2), 0, 0) })
        row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (riding) {
            row.addView(act.label(String.format(Locale.US, "%.0f", r.speedKmh), 22f, bold = true, color = act.pal.orange).apply { setPadding(act.dp(8), 0, 0, 0) })
            row.addView(act.label(Weather.kmh, 11f, color = act.pal.sub).apply { setPadding(act.dp(2), act.dp(8), 0, 0) })
        }
        return row
    }

    private fun stopAnimations() {
        for (a in animators) a.cancel()
        animators.clear()
    }

    private fun agoText(ms: Long): String {
        val min = ms / 60_000L
        return when {
            min < 1 -> t("baru tadi", "just now", "刚刚", "இப்போது")
            min < 60 -> t("$min min lalu", "$min min ago", "${min}分钟前", "$min நிமி. முன்")
            else -> (min / 60).let { h -> t("$h jam lalu", "$h h ago", "${h}小时前", "$h மணி முன்") }
        }
    }

    private fun distKm(a1: Double, o1: Double, a2: Double, o2: Double): Double =
        hypot((a2 - a1) * 111.32, (o2 - o1) * 111.32 * cos(Math.toRadians((a1 + a2) / 2)))

    // ------------------------------------------------------------ tindakan

    private fun needName(): Boolean {
        if (RiderGroup.riderName(act).isNotBlank()) return false
        Toast.makeText(act, t("Isi nama anda dulu", "Enter your name first", "请先填写您的名字", "முதலில் உங்கள் பெயரை உள்ளிடவும்"), Toast.LENGTH_SHORT).show()
        return true
    }

    private fun hasLocation() =
        act.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            act.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun input(hint: String, prefill: String = "") = EditText(act).apply {
        this.hint = hint
        setText(prefill)
        setSingleLine()
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        setPadding(act.dp(20), act.dp(16), act.dp(20), act.dp(16))
    }

    /** Borang nama + pilihan ikon (cipta / ubah kumpulan). Pulang (view, ambil nama, ambil ikon). */
    private fun groupForm(name: String, icon: String): Triple<View, () -> String, () -> String> {
        var picked = icon
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(act.dp(20), act.dp(8), act.dp(20), 0)
        }
        val e = input(t("Nama kumpulan, cth. Geng Ride KL", "Group name, e.g. KL Riders", "群组名称，例如 KL 骑手", "குழு பெயர், எ.கா. KL Riders"), name).apply {
            setPadding(act.dp(4), act.dp(12), act.dp(4), act.dp(12))
        }
        box.addView(e)
        box.addView(act.label(t("Ikon kumpulan", "Group icon", "群组图标", "குழு சின்னம்"), 14f, bold = true, color = act.pal.sub), act.lp(top = 10))
        val cells = mutableListOf<TextView>()
        fun mark() = cells.forEach { c ->
            c.background = if (c.text == picked) GradientDrawable().apply {
                setColor(act.pal.rainTint)
                setStroke(act.dp(2), act.pal.orange)
                cornerRadius = act.dp(10).toFloat()
            } else null
        }
        for (rowIcons in RiderGroup.ICONS.chunked(6)) {
            val row = LinearLayout(act)
            for (ic in rowIcons) {
                val cell = act.label(ic, 26f).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, act.dp(6), 0, act.dp(6))
                    setOnClickListener { picked = ic; mark() }
                }
                cells += cell
                row.addView(cell, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            box.addView(row)
        }
        mark()
        return Triple(box, { e.text.toString() }, { picked })
    }

    private fun askCreate() {
        if (needName()) return
        val (view, name, icon) = groupForm("", RiderGroup.DEFAULT_ICON)
        AlertDialog.Builder(act)
            .setTitle("➕ " + t("Cipta kumpulan", "Create group", "创建群组", "குழுவை உருவாக்கு"))
            .setView(view)
            .setPositiveButton(t("Cipta", "Create", "创建", "உருவாக்கு")) { _, _ ->
                val n = name().ifBlank { t("Kumpulan saya", "My group", "我的群组", "என் குழு") }
                val ic = icon()
                network({ RiderGroup.create(act.applicationContext, n, ic) }) { g ->
                    open(g.code)
                    share(g)
                }
            }
            .setNegativeButton(t("Batal", "Cancel", "取消", "ரத்து"), null)
            .show()
    }

    /** Tukar nama / ikon kumpulan — untuk semua ahli (perlu peraturan Firebase v2.6). */
    private fun askEdit(g: RiderGroup.Group) {
        val (view, name, icon) = groupForm(g.name, g.icon)
        AlertDialog.Builder(act)
            .setTitle("✏️ " + t("Ubah kumpulan", "Edit group", "编辑群组", "குழுவைத் திருத்து"))
            .setView(view)
            .setPositiveButton(t("Simpan", "Save", "保存", "சேமி")) { _, _ ->
                val n = name()
                val ic = icon()
                network({ RiderGroup.edit(act.applicationContext, g, n, ic) }) { synced ->
                    if (!synced) toast(t(
                        "Disimpan dalam phone ini sahaja (pelayan menolak — kemas kini peraturan Firebase).",
                        "Saved on this phone only (server refused — update the Firebase rules).",
                        "仅保存在此手机（服务器拒绝——请更新 Firebase 规则）。",
                        "இந்த தொலைபேசியில் மட்டும் சேமிக்கப்பட்டது (Firebase விதிகளைப் புதுப்பிக்கவும்).",
                    ))
                    build()
                }
            }
            .setNegativeButton(t("Batal", "Cancel", "取消", "ரத்து"), null)
            .show()
    }

    /** Tunjuk QR kod kumpulan — rider sebelah imbas dengan JagaRide (Rider → Imbas QR). */
    private fun showQr(g: RiderGroup.Group) {
        val size = act.dp(260)
        val bmp = runCatching {
            val m = com.google.zxing.qrcode.QRCodeWriter().encode(
                "JagaRide ${g.code}", com.google.zxing.BarcodeFormat.QR_CODE, size, size,
                mapOf(com.google.zxing.EncodeHintType.MARGIN to 1),
            )
            val px = IntArray(size * size) { i -> if (m[i % size, i / size]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
            android.graphics.Bitmap.createBitmap(px, size, size, android.graphics.Bitmap.Config.ARGB_8888)
        }.getOrNull() ?: return
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(act.dp(16), act.dp(12), act.dp(16), 0)
            addView(android.widget.ImageView(act).apply {
                setImageBitmap(bmp)
                setBackgroundColor(android.graphics.Color.WHITE)
                setPadding(act.dp(8), act.dp(8), act.dp(8), act.dp(8))
            }, LinearLayout.LayoutParams(size + act.dp(16), size + act.dp(16)))
            addView(act.label(g.icon + " " + g.name, 18f, bold = true).apply { gravity = Gravity.CENTER })
            addView(act.label(t(
                "Rider sebelah: buka JagaRide → Rider → 📷 Imbas QR",
                "Nearby rider: open JagaRide → Riders → 📷 Scan QR",
                "身边的骑手：打开 JagaRide → 骑手 → 📷 扫描二维码",
                "அருகிலுள்ள ரைடர்: JagaRide → ரைடர்கள் → 📷 QR ஸ்கேன்",
            ), 14f, color = act.pal.sub).apply { gravity = Gravity.CENTER })
        }
        AlertDialog.Builder(act).setView(box).setPositiveButton(t("Tutup", "Close", "关闭", "மூடு"), null).show()
    }

    /** Imbas QR kumpulan dengan pengimbas Google (tiada kebenaran kamera diperlukan). */
    private fun scanQr() {
        if (needName()) return
        val opts = com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE)
            .build()
        com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(act, opts).startScan()
            .addOnSuccessListener { barcode ->
                val code = RiderGroup.parse(barcode.rawValue.orEmpty())
                if (code == null) {
                    toast(t("QR ini bukan kumpulan JagaRide", "This QR is not a JagaRide group", "这不是 JagaRide 群组二维码", "இது JagaRide குழு QR அல்ல"))
                } else {
                    joinCode(code)
                }
            }
            .addOnFailureListener {
                toast(t(
                    "Pengimbas belum sedia (Google Play sedang muat turun) — cuba lagi sekejap, atau guna kod.",
                    "Scanner not ready (Google Play is downloading it) — try again shortly, or use the code.",
                    "扫描器尚未就绪（Google Play 正在下载）——请稍后再试，或使用代码。",
                    "ஸ்கேனர் தயாராக இல்லை — சிறிது நேரத்தில் மீண்டும் முயற்சிக்கவும், அல்லது குறியீட்டைப் பயன்படுத்தவும்.",
                ))
            }
    }

    private fun joinCode(code: String) {
        RiderGroup.find(act, code)?.let { return open(it.code) } // dah ahli
        network({ RiderGroup.join(act.applicationContext, code) }) { g ->
            if (g == null) {
                toast(t("Kumpulan tak dijumpai", "Group not found", "找不到群组", "குழு கிடைக்கவில்லை"))
                build()
            } else {
                open(g.code)
            }
        }
    }

    private fun askJoin() {
        if (needName()) return
        val clip = act.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(act)?.toString().orEmpty()
        val e = input("JR-XXXX-XXXX-XXXX-XXXX-XXXX", RiderGroup.parse(clip).orEmpty()).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        AlertDialog.Builder(act)
            .setTitle("🔗 " + t("Sertai dengan kod", "Join with code", "用代码加入", "குறியீட்டுடன் சேர்"))
            .setMessage(t("Tampal kod (atau mesej jemputan penuh) dari kawan anda.", "Paste the code (or the whole invite message) from your friend.", "粘贴朋友发来的代码（或整条邀请消息）。", "நண்பர் அனுப்பிய குறியீட்டை (அல்லது முழு அழைப்புச் செய்தியை) ஒட்டவும்."))
            .setView(e)
            .setPositiveButton(t("Sertai", "Join", "加入", "சேர்")) { _, _ ->
                val code = RiderGroup.parse(e.text.toString())
                if (code == null) {
                    toast(t("Kod tak sah", "Invalid code", "代码无效", "தவறான குறியீடு"))
                    return@setPositiveButton
                }
                joinCode(code)
            }
            .setNegativeButton(t("Batal", "Cancel", "取消", "ரத்து"), null)
            .show()
    }

    private fun askLeave(g: RiderGroup.Group) {
        AlertDialog.Builder(act)
            .setTitle("🚪 " + t("Keluar kumpulan?", "Leave group?", "退出群组？", "குழுவை விட்டு வெளியேறவா?"))
            .setMessage(t("Lokasi anda akan dibuang dari kumpulan \"${g.name}\". Anda boleh sertai semula dengan kod.", "Your location will be removed from \"${g.name}\". You can rejoin with the code.", "您的位置将从“${g.name}”中删除。您可以用代码重新加入。", "உங்கள் இருப்பிடம் \"${g.name}\"-இலிருந்து நீக்கப்படும். குறியீட்டுடன் மீண்டும் சேரலாம்."))
            .setPositiveButton(t("Keluar", "Leave", "退出", "வெளியேறு")) { _, _ ->
                if (g.code in sharingNow()) LocationShareService.stopGroup(act, g)
                network({ RiderGroup.leave(act.applicationContext, g) }) { open(null) }
            }
            .setNegativeButton(t("Batal", "Cancel", "取消", "ரத்து"), null)
            .show()
    }

    private fun toggleShare(g: RiderGroup.Group) {
        if (g.code in sharingNow()) {
            LocationShareService.stopGroup(act, g)
            main.postDelayed({ styleShare(g); refresh() }, 800)
            return
        }
        if (needName()) return
        if (!hasLocation()) {
            act.requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 21)
            return
        }
        // Berapa lama nak kongsi?
        val mins = listOf(30, 60, 240, 480, 0)
        val labels = arrayOf(
            "⏱️ " + t("30 minit", "30 minutes", "30分钟", "30 நிமிடம்"),
            "⏱️ " + t("1 jam", "1 hour", "1小时", "1 மணி நேரம்"),
            "⏱️ " + t("4 jam", "4 hours", "4小时", "4 மணி நேரம்"),
            "⏱️ " + t("8 jam", "8 hours", "8小时", "8 மணி நேரம்"),
            "♾️ " + t("Sehingga saya tekan Henti", "Until I tap Stop", "直到我点击停止", "நான் நிறுத்தும் வரை"),
        )
        AlertDialog.Builder(act)
            .setTitle("📍 " + t("Kongsi lokasi berapa lama?", "Share location for how long?", "共享位置多久？", "எவ்வளவு நேரம் பகிர வேண்டும்?"))
            .setItems(labels) { _, i ->
                val until = if (mins[i] == 0) 0L else System.currentTimeMillis() + mins[i] * 60_000L
                LocationShareService.startGroup(act, g, until)
                main.postDelayed({ styleShare(g); refresh() }, 1500)
            }
            .setNegativeButton(t("Batal", "Cancel", "取消", "ரத்து"), null)
            .show()
    }

    private fun share(g: RiderGroup.Group) {
        val text = t(
            "🏍️ Jom join kumpulan rider \"${g.name}\" dalam JagaRide!\nKod: ${g.code}\n\n1. Muat turun JagaRide: https://github.com/Muzamilmohd/JagaRider/releases/latest\n2. Buka tab Rider → Sertai dengan kod → tampal kod ini.",
            "🏍️ Join my rider group \"${g.name}\" on JagaRide!\nCode: ${g.code}\n\n1. Download JagaRide: https://github.com/Muzamilmohd/JagaRider/releases/latest\n2. Open the Riders tab → Join with code → paste this code.",
            "🏍️ 加入我的 JagaRide 骑手群组“${g.name}”！\n代码：${g.code}\n\n1. 下载 JagaRide：https://github.com/Muzamilmohd/JagaRider/releases/latest\n2. 打开“骑手”页 → 用代码加入 → 粘贴此代码。",
            "🏍️ JagaRide-இல் என் ரைடர் குழு \"${g.name}\"-இல் சேருங்கள்!\nகுறியீடு: ${g.code}\n\n1. JagaRide பதிவிறக்கம்: https://github.com/Muzamilmohd/JagaRider/releases/latest\n2. ரைடர்கள் பக்கம் → குறியீட்டுடன் சேர் → இந்தக் குறியீட்டை ஒட்டவும்.",
        )
        act.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
    }

    private fun copy(g: RiderGroup.Group) {
        act.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("JagaRide", g.code))
        toast(t("Kod disalin", "Code copied", "已复制代码", "குறியீடு நகலெடுக்கப்பட்டது"))
    }

    private fun toast(s: String) = Toast.makeText(act, s, Toast.LENGTH_SHORT).show()

    /** Jalankan kerja rangkaian di latar, kemudian onDone di thread utama. */
    private fun <T> network(work: () -> T, onDone: (T) -> Unit) {
        Thread {
            val r = runCatching(work)
            main.post {
                if (act.isDestroyed) return@post
                r.onSuccess(onDone).onFailure { toast(t("Gagal (internet?): ", "Failed (internet?): ", "失败（网络？）：", "தோல்வி (இணையம்?): ") + it.message) }
            }
        }.start()
    }

    companion object {
        private const val LIST_POLL_MS = 15_000L
        private const val DETAIL_POLL_MS = 5_000L
        /** Laju minimum (km/j) dikira "sedang ride". */
        const val MOVING_KMH = 5.0
    }
}
