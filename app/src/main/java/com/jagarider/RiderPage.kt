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
        act.setRiderBackHandler(code != null)
        build()
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
        rowInfo.clear()
        col.addView(LinearLayout(act).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(act.label("🏍️", 34f).apply { setPadding(0, 0, act.dp(10), 0) })
            addView(LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                addView(act.label(t("Rider", "Riders", "骑手", "ரைடர்கள்"), 26f, bold = true, color = act.pal.orange).apply { setPadding(0, 0, 0, 0) })
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
            row.addView(act.label(if (g.code in sharing) "📍" else "👥", 24f).apply { setPadding(0, 0, act.dp(12), 0) })
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
        c.addView(act.pill("🔗 " + t("Sertai dengan kod", "Join with code", "用代码加入", "குறியீட்டுடன் சேர்")) { askJoin() }, act.lp(top = 8))
    }

    private fun groupInfo(g: RiderGroup.Group, sharing: Set<String>): String {
        val parts = mutableListOf<String>()
        if (g.code in sharing) parts += t("Anda berkongsi", "You're sharing", "您正在共享", "நீங்கள் பகிர்கிறீர்கள்")
        val n = active[g.code]
        parts += when {
            n == null -> "…"
            n == 0 -> t("Tiada rider aktif", "No active riders", "没有活跃骑手", "செயலில் ரைடர் இல்லை")
            else -> t("$n rider aktif", "$n active rider" + (if (n > 1) "s" else ""), "${n} 位活跃骑手", "$n செயலில் உள்ள ரைடர்")
        }
        return parts.joinToString(" · ")
    }

    /** Halaman satu kumpulan: kod jemputan, kongsi lokasi, peta live, butiran rider. */
    private fun buildDetail(g: RiderGroup.Group) {
        col.addView(act.label("‹ " + t("Semua kumpulan", "All groups", "所有群组", "அனைத்து குழுக்கள்"), 16f, bold = true, color = act.pal.orange).apply {
            setPadding(0, act.dp(12), 0, act.dp(4))
            setOnClickListener { open(null) }
        })
        val c = act.card()
        col.addView(c, act.lp(top = 4))
        c.addView(act.label("👥 " + g.name, 20f, bold = true))
        c.addView(act.label(t("Kod jemputan:", "Invite code:", "邀请码：", "அழைப்புக் குறியீடு:"), 13f, color = act.pal.sub), act.lp(top = 4))
        c.addView(act.label(g.code, 18f, bold = true, color = act.pal.orange).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        })
        c.addView(LinearLayout(act).apply {
            addView(act.pill("📤 " + t("Jemput", "Invite", "邀请", "அழை")) { share(g) },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, act.dp(4), 0) })
            addView(act.pill("📋 " + t("Salin kod", "Copy code", "复制代码", "நகலெடு")) { copy(g) },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(act.dp(4), 0, 0, 0) })
        }, act.lp(top = 6))

        shareBtn = act.pill("", primary = true) { toggleShare(g) }
        c.addView(shareBtn, act.lp(top = 12))
        statusText = act.label("", 13f, color = act.pal.sub).apply { gravity = Gravity.CENTER }
        c.addView(statusText)
        styleShare(g)

        // Peta rider + radar hujan
        val mc = act.card()
        col.addView(mc, act.lp(top = 12))
        map = RainMapView(act).also {
            it.interactive = true
            it.showRangeCircle = false
            it.onResetView = { render() }
            mc.addView(it)
        }
        mc.addView(act.label("🤏 " + t(
            "Dua jari: cubit untuk zum / seret untuk gerak · ketik dua kali: muat semua rider",
            "Two fingers: pinch to zoom / drag to move · double-tap: fit all riders",
            "双指：缩放 / 拖动 · 双击：显示所有骑手",
            "இரு விரல்: பெரிதாக்க / நகர்த்த · இருமுறை தட்டு: அனைவரையும் காட்டு",
        ), 12f, color = act.pal.sub).apply { gravity = Gravity.CENTER })
        mc.addView(act.label("🏍️ " + t("Rider dalam kumpulan", "Riders in this group", "群组中的骑手", "குழுவில் உள்ள ரைடர்கள்"), 16f, bold = true), act.lp(top = 10))
        list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        mc.addView(list, act.lp(top = 4))
        render()

        col.addView(act.pill("🚪 " + t("Keluar kumpulan", "Leave group", "退出群组", "குழுவை விட்டு வெளியேறு")) { askLeave(g) }, act.lp(top = 16))
    }

    private fun sharingNow(): Set<String> = if (LocationShareService.running) RiderGroup.sharing(act) else emptySet()

    private fun styleShare(g: RiderGroup.Group) {
        val on = g.code in sharingNow()
        shareBtn?.text = if (on) "⏹ " + t("Henti kongsi lokasi", "Stop sharing location", "停止共享位置", "இருப்பிடப் பகிர்வை நிறுத்து")
        else "▶ " + t("Mula kongsi lokasi saya", "Start sharing my location", "开始共享我的位置", "என் இருப்பிடத்தைப் பகிரத் தொடங்கு")
        shareBtn?.let { act.stylePill(it, primary = !on) }
        statusText?.text = if (on) "📍 " + t("Ahli kumpulan ini nampak lokasi anda (dikemas kini ~10 saat).", "Members of this group can see you (updated ~every 10 s).", "此群组成员可以看到您（约每10秒更新）。", "இந்தக் குழு உறுப்பினர்கள் உங்களைப் பார்க்கலாம் (~10 வினாடிக்கு ஒருமுறை).")
        else t("Anda boleh lihat rider lain walaupun tak kongsi.", "You can see other riders even without sharing.", "即使不共享，您也能看到其他骑手。", "பகிராமலும் மற்ற ரைடர்களைப் பார்க்கலாம்.")
    }

    // ------------------------------------------------------------ data

    private fun refresh() {
        val app = act.applicationContext
        val code = selected
        if (code != null) {
            val g = RiderGroup.find(act, code) ?: return
            Thread {
                val r = runCatching { RiderGroup.fetch(app, g) }
                main.post {
                    if (!visible || act.isDestroyed || selected != code) return@post
                    r.onSuccess {
                        // sembunyi yang tak aktif lebih 2 jam
                        riders = it.filter { x -> System.currentTimeMillis() - x.time < 2 * 60 * 60_000L }
                        render()
                    }.onFailure { statusText?.text = t("Gagal (internet?): ", "Failed (internet?): ", "失败（网络？）：", "தோல்வி (இணையம்?): ") + it.message }
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
            riding -> t("Sedang ride", "Riding", "骑行中", "பயணத்தில்") + " " + String.format(Locale.US, "%.0f km/j", r.speedKmh) +
                (if (!r.bearing.isNaN()) " " + Weather.toward(r.bearing) else "")
            stale -> t("Tidak aktif", "Inactive", "不活跃", "செயலில் இல்லை")
            else -> t("Berhenti", "Stopped", "已停下", "நிறுத்தியுள்ளார்")
        }
        parts += agoText(ago)
        texts.addView(act.label(parts.joinToString(" · "), 13f, color = act.pal.sub).apply { setPadding(0, act.dp(2), 0, 0) })
        row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (riding) {
            row.addView(act.label(String.format(Locale.US, "%.0f", r.speedKmh), 22f, bold = true, color = act.pal.orange).apply { setPadding(act.dp(8), 0, 0, 0) })
            row.addView(act.label("km/j", 11f, color = act.pal.sub).apply { setPadding(act.dp(2), act.dp(8), 0, 0) })
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

    private fun askCreate() {
        if (needName()) return
        val e = input(t("Nama kumpulan, cth. Geng Ride KL", "Group name, e.g. KL Riders", "群组名称，例如 KL 骑手", "குழு பெயர், எ.கா. KL Riders"))
        AlertDialog.Builder(act)
            .setTitle("➕ " + t("Cipta kumpulan", "Create group", "创建群组", "குழுவை உருவாக்கு"))
            .setView(e)
            .setPositiveButton(t("Cipta", "Create", "创建", "உருவாக்கு")) { _, _ ->
                val name = e.text.toString().ifBlank { t("Kumpulan saya", "My group", "我的群组", "என் குழு") }
                network({ RiderGroup.create(act.applicationContext, name) }) { g ->
                    open(g.code)
                    share(g)
                }
            }
            .setNegativeButton(t("Batal", "Cancel", "取消", "ரத்து"), null)
            .show()
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
                network({ RiderGroup.join(act.applicationContext, code) }) { g ->
                    if (g == null) {
                        toast(t("Kumpulan tak dijumpai", "Group not found", "找不到群组", "குழு கிடைக்கவில்லை"))
                        build()
                    } else {
                        open(g.code)
                    }
                }
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
        LocationShareService.startGroup(act, g)
        main.postDelayed({ styleShare(g); refresh() }, 1500)
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
