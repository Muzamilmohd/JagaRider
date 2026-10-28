package com.jagarider

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import java.util.Locale
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Tab "Rider": cipta / sertai kumpulan dengan kod, kongsi lokasi live, dan lihat rider lain di peta.
 */
class RiderPage(private val act: MainActivity) {

    val root = ScrollView(act)
    private val col = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(act.dp(16), act.dp(16), act.dp(16), act.dp(24))
    }
    private val main = Handler(Looper.getMainLooper())
    private var visible = false
    private var map: RainMapView? = null
    private var list: LinearLayout? = null
    private var statusText: android.widget.TextView? = null
    private var shareBtn: android.widget.Button? = null
    private var riders: List<RiderGroup.Rider> = emptyList()

    private val poll = object : Runnable {
        override fun run() {
            refresh()
            main.postDelayed(this, POLL_MS)
        }
    }

    init {
        root.addView(col)
        build()
    }

    fun onShow() {
        visible = true
        build()
        main.removeCallbacks(poll)
        main.post(poll)
    }

    fun onHide() {
        visible = false
        main.removeCallbacks(poll)
    }

    // ------------------------------------------------------------ UI

    private fun build() {
        col.removeAllViews()
        map = null
        list = null
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

        // Nama rider
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
            setOnFocusChangeListener { v, has -> if (!has) RiderGroup.setRiderName(act, (v as EditText).text.toString()) }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) = RiderGroup.setRiderName(act, s.toString())
            })
        })

        val g = RiderGroup.active(act)
        if (g == null) buildNoGroup() else buildGroup(g)
    }

    private fun buildNoGroup() {
        val c = act.card()
        col.addView(c, act.lp(top = 12))
        c.addView(act.label("👥 " + t("Kumpulan rider", "Rider group", "骑手群组", "ரைடர் குழு"), 18f, bold = true))
        c.addView(act.label(t(
            "Cipta kumpulan dan kongsi kod dengan kawan, atau sertai kumpulan dengan kod yang diberi kawan. Lokasi disulitkan — hanya ahli kumpulan boleh lihat.",
            "Create a group and share its code with friends, or join one with a code from a friend. Locations are encrypted — only group members can see them.",
            "创建群组并把代码分享给朋友，或用朋友给的代码加入群组。位置已加密，只有群组成员能看到。",
            "குழுவை உருவாக்கி குறியீட்டை நண்பர்களுடன் பகிரவும், அல்லது நண்பர் தந்த குறியீட்டுடன் சேரவும். இருப்பிடம் மறையாக்கம் செய்யப்படும் — குழு உறுப்பினர்கள் மட்டுமே பார்க்க முடியும்.",
        ), 14f, color = act.pal.sub))
        c.addView(act.pill("➕ " + t("Cipta kumpulan", "Create group", "创建群组", "குழுவை உருவாக்கு"), primary = true) { askCreate() }, act.lp(top = 10))
        c.addView(act.pill("🔗 " + t("Sertai dengan kod", "Join with code", "用代码加入", "குறியீட்டுடன் சேர்")) { askJoin() }, act.lp(top = 8))
    }

    private fun buildGroup(g: RiderGroup.Group) {
        val c = act.card()
        col.addView(c, act.lp(top = 12))
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

        shareBtn = act.pill("", primary = true) { toggleShare() }
        c.addView(shareBtn, act.lp(top = 12))
        statusText = act.label("", 13f, color = act.pal.sub).apply { gravity = Gravity.CENTER }
        c.addView(statusText)
        styleShare()

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
        list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        mc.addView(list, act.lp(top = 8))
        render()

        col.addView(act.pill("🚪 " + t("Keluar kumpulan", "Leave group", "退出群组", "குழுவை விட்டு வெளியேறு")) { askLeave() }, act.lp(top = 16))
    }

    private fun styleShare() {
        val on = LocationShareService.running
        shareBtn?.text = if (on) "⏹ " + t("Henti kongsi lokasi", "Stop sharing location", "停止共享位置", "இருப்பிடப் பகிர்வை நிறுத்து")
        else "▶ " + t("Mula kongsi lokasi saya", "Start sharing my location", "开始共享我的位置", "என் இருப்பிடத்தைப் பகிரத் தொடங்கு")
        shareBtn?.let { act.stylePill(it, primary = !on) }
        statusText?.text = if (on) "📍 " + t("Ahli kumpulan nampak lokasi anda (dikemas kini ~10 saat).", "Group members can see you (updated ~every 10 s).", "群组成员可以看到您（约每10秒更新）。", "குழு உறுப்பினர்கள் உங்களைப் பார்க்கலாம் (~10 வினாடிக்கு ஒருமுறை).")
        else t("Anda boleh lihat rider lain walaupun tak kongsi.", "You can see other riders even without sharing.", "即使不共享，您也能看到其他骑手。", "பகிராமலும் மற்ற ரைடர்களைப் பார்க்கலாம்.")
    }

    // ------------------------------------------------------------ data

    private fun refresh() {
        val g = RiderGroup.active(act) ?: return
        val app = act.applicationContext
        Thread {
            val r = runCatching { RiderGroup.fetch(app, g) }
            main.post {
                if (!visible || act.isDestroyed) return@post
                r.onSuccess {
                    // sembunyi yang tak aktif lebih 2 jam
                    riders = it.filter { x -> System.currentTimeMillis() - x.time < 2 * 60 * 60_000L }
                    render()
                }.onFailure { statusText?.text = t("Gagal (internet?): ", "Failed (internet?): ", "失败（网络？）：", "தோல்வி (இணையம்?): ") + it.message }
                styleShare()
            }
        }.start()
    }

    private fun render() {
        val m = map ?: return
        val l = list ?: return
        val mine = riders.firstOrNull { it.me }
        val (myLat, myLon) = mine?.let { it.lat to it.lon } ?: Prefs.lastLocation(act) ?: (3.95 to 102.0)
        val others = riders.filter { !it.me }
        // Zum automatik supaya semua rider muat (sedekat 0.5 km bila rider berdekatan)
        val far = others.maxOfOrNull { distKm(myLat, myLon, it.lat, it.lon) } ?: 0.0
        val km = maxOf(0.5, far * 1.3)
        m.radarFrame = Radar.current()
        m.riders = others
        m.fitRiders(myLat, myLon, km)

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
            val ago = System.currentTimeMillis() - r.time
            val stale = ago > 5 * 60_000L
            val parts = mutableListOf<String>()
            if (!r.me) parts += Weather.formatKm(Math.round(distKm(myLat, myLon, r.lat, r.lon) * 10) / 10.0)
            if (r.speedKmh >= 3 && !stale) parts += String.format(Locale.US, "%.0f km/j", r.speedKmh)
            parts += agoText(ago)
            val name = if (r.me) r.name + " (" + t("anda", "you", "您", "நீங்கள்") + ")" else r.name
            l.addView(act.label((if (stale) "⚪ " else if (r.me) "🟠 " else "🟢 ") + name + " — " + parts.joinToString(" · "), 15f,
                color = if (stale) act.pal.sub else act.pal.text))
        }
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
                network({ RiderGroup.create(act.applicationContext, name) }) { g -> build(); share(g) }
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
                    if (g == null) toast(t("Kumpulan tak dijumpai", "Group not found", "找不到群组", "குழு கிடைக்கவில்லை"))
                    build()
                    if (visible) refresh()
                }
            }
            .setNegativeButton(t("Batal", "Cancel", "取消", "ரத்து"), null)
            .show()
    }

    private fun askLeave() {
        AlertDialog.Builder(act)
            .setTitle("🚪 " + t("Keluar kumpulan?", "Leave group?", "退出群组？", "குழுவை விட்டு வெளியேறவா?"))
            .setMessage(t("Lokasi anda akan dibuang dari kumpulan. Anda boleh sertai semula dengan kod.", "Your location will be removed from the group. You can rejoin with the code.", "您的位置将从群组中删除。您可以用代码重新加入。", "உங்கள் இருப்பிடம் குழுவிலிருந்து நீக்கப்படும். குறியீட்டுடன் மீண்டும் சேரலாம்."))
            .setPositiveButton(t("Keluar", "Leave", "退出", "வெளியேறு")) { _, _ ->
                if (LocationShareService.running) LocationShareService.stop(act)
                network({ RiderGroup.leave(act.applicationContext) }) {
                    riders = emptyList()
                    build()
                }
            }
            .setNegativeButton(t("Batal", "Cancel", "取消", "ரத்து"), null)
            .show()
    }

    private fun toggleShare() {
        if (LocationShareService.running) {
            LocationShareService.stop(act)
            main.postDelayed({ styleShare(); refresh() }, 800)
            return
        }
        if (needName()) return
        val fine = act.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = act.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            act.requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 21)
            return
        }
        LocationShareService.start(act)
        main.postDelayed({ styleShare(); refresh() }, 1500)
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
        private const val POLL_MS = 8_000L
    }
}
