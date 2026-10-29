package com.jagarider

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import java.util.Locale

/**
 * Tab Cuaca: "Hujan di laluan" (cari destinasi → hujan di sepanjang laluan ikut masa sampai, cadangan masa
 * bertolak, navigasi) dan "Tempat berteduh" (jejantas / stesen minyak / perhentian bas terdekat).
 * maps = peta yang perlu dikemas kini (peta biasa + skrin penuh kalau dibuka).
 */
class RoutePanel(
    private val act: BaseActivity,
    private val maps: () -> List<RainMapView>,
    private val myLocation: () -> Pair<Double, Double>?,
) {
    private val main = Handler(Looper.getMainLooper())

    // ------------------------------------------------------------ hujan di laluan

    private val results = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    private val out = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    private val input = EditText(act).apply {
        hint = t("Nak ke mana? cth. Seremban", "Where to? e.g. Seremban", "去哪里？例如 Seremban", "எங்கே? எ.கா. Seremban")
        setSingleLine()
        textSize = 16f
        setTextColor(act.pal.text)
        setHintTextColor(act.pal.sub)
        imeOptions = EditorInfo.IME_ACTION_SEARCH
        setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEARCH) { search(); true } else false
        }
    }

    /** Kad "Hujan di laluan" untuk dimasukkan ke halaman cuaca. */
    val routeCard: View = act.card().apply {
        addView(act.label(t(
            "Semak hujan sepanjang perjalanan — ikut masa anda sampai di setiap tempat.",
            "Check rain along your trip — at the time you will reach each place.",
            "查看沿途降雨——按您到达每个地方的时间。",
            "பயணம் முழுவதும் மழை — ஒவ்வொரு இடத்தையும் அடையும் நேரத்தில்.",
        ), 13f, color = act.pal.sub))
        addView(LinearLayout(act).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(act.pill("🔎") { search() }, LinearLayout.LayoutParams(act.dp(64), LinearLayout.LayoutParams.WRAP_CONTENT))
        })
        addView(results)
        addView(out)
    }

    private fun search() {
        val q = input.text.toString().trim()
        if (q.isEmpty()) return
        act.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(input.windowToken, 0)
        val (lat, lon) = myLocation() ?: (3.14 to 101.69)
        results.removeAllViews()
        out.removeAllViews()
        results.addView(act.label(t("Mencari…", "Searching…", "搜索中…", "தேடுகிறது…"), 14f, color = act.pal.sub))
        work({ Route.search(q, lat, lon) }) { places ->
            results.removeAllViews()
            if (places.isEmpty()) {
                results.addView(act.label(t("Tempat tak dijumpai", "Place not found", "找不到地点", "இடம் கிடைக்கவில்லை"), 14f, color = act.pal.sub))
            }
            for (p in places) results.addView(row("📍", p.name, p.detail) { plan(p) }, act.lp(top = 6))
        }
    }

    private fun plan(p: Route.Place) {
        val from = myLocation() ?: return toast(t("Lokasi belum diketahui", "Location not known yet", "尚未获取位置", "இருப்பிடம் தெரியவில்லை"))
        results.removeAllViews()
        out.removeAllViews()
        out.addView(act.label("🛣️ " + t("Menyemak laluan ke ${p.name}…", "Checking the route to ${p.name}…", "正在检查前往${p.name}的路线…", "${p.name}-க்கான பாதையைச் சரிபார்க்கிறது…"), 14f, color = act.pal.sub))
        work({ Route.plan(from.first, from.second, p) }) { show(it) }
    }

    private fun show(plan: Route.Plan) {
        out.removeAllViews()
        val arrive = Clock.time(plan.start + (plan.minutes * 60_000).toLong())
        val dur = if (plan.minutes >= 60) String.format(Locale.US, "%d j %d min", (plan.minutes / 60).toInt(), (plan.minutes % 60).toInt())
        else String.format(Locale.US, "%d min", plan.minutes.toInt())
        out.addView(act.label("🏁 " + plan.dest.name, 17f, bold = true), act.lp(top = 8))
        out.addView(act.label(
            String.format(Locale.US, "%.0f km · ~%s · ", plan.distKm, dur) + t("tiba ~$arrive", "arrive ~$arrive", "约${arrive}到达", "~$arrive வருகை"),
            14f, color = act.pal.sub,
        ))

        val worst = plan.wet.maxOfOrNull { it.level } ?: Weather.Level.NONE
        val storm = plan.wet.any { it.thunder }
        val (msg, color) = when {
            storm || worst >= Weather.Level.HEAVY -> t(
                "🔴 Hujan lebat / ribut di laluan — pertimbang tangguh",
                "🔴 Heavy rain / storm on the route — consider waiting",
                "🔴 沿途有大雨/雷暴——考虑推迟出发",
                "🔴 பாதையில் கனமழை / புயல் — தாமதிக்கக் கருதவும்",
            ) to "#C62828"
            worst >= Weather.Level.LIGHT -> t(
                "🟠 Ada hujan di laluan — bawa baju hujan",
                "🟠 Rain on the route — bring a raincoat",
                "🟠 沿途有雨——带上雨衣",
                "🟠 பாதையில் மழை — மழைக்கோட் எடுக்கவும்",
            ) to "#EF6C00"
            worst >= Weather.Level.DRIZZLE -> t(
                "🟡 Renyai di sebahagian laluan",
                "🟡 Drizzle on parts of the route",
                "🟡 部分路段有毛毛雨",
                "🟡 பாதையின் சில பகுதிகளில் தூறல்",
            ) to "#F9A825"
            else -> t(
                "🟢 Laluan kering sepanjang perjalanan (ramalan)",
                "🟢 Dry all the way (forecast)",
                "🟢 全程无雨（预报）",
                "🟢 முழுப் பயணமும் வறண்டது (முன்னறிவிப்பு)",
            ) to "#2E7D32"
        }
        out.addView(act.label(msg, 16f, bold = true, color = Color.WHITE).apply {
            setPadding(act.dp(12), act.dp(8), act.dp(12), act.dp(8))
            background = GradientDrawable().apply {
                setColor(Color.parseColor(color))
                cornerRadius = act.dp(10).toFloat()
            }
        }, act.lp(top = 8))

        for (w in plan.wet.take(5)) {
            val at = Clock.time(plan.start + (w.atMinutes * 60_000).toLong())
            val where = if (w.place.isNotBlank()) " · " + w.place else ""
            val range = if (w.toKm - w.fromKm < 1) String.format(Locale.US, "km %.0f", w.fromKm)
            else String.format(Locale.US, "km %.0f–%.0f", w.fromKm, w.toKm)
            out.addView(act.label((if (w.thunder) "⛈️ " else "🌧️ ") + "$range · ~$at · ${w.level.label}$where", 14f), act.lp(top = 4))
        }
        if (plan.bestDelayMin > 0) {
            val go = Clock.time(plan.start + plan.bestDelayMin * 60_000L)
            val d = plan.bestDelayMin
            out.addView(act.label("💡 " + t(
                "Gerak ~$go (tangguh $d min) — hujan di laluan jauh berkurang.",
                "Leave ~$go (wait $d min) — much less rain on the route.",
                "约${go}出发（推迟${d}分钟）——沿途降雨会少很多。",
                "~$go புறப்படவும் ($d நிமி. தாமதம்) — பாதையில் மழை மிகக் குறைவு.",
            ), 14f, bold = true, color = act.pal.orange), act.lp(top = 6))
        }
        out.addView(LinearLayout(act).apply {
            addView(act.pill("🧭 " + t("Navigasi", "Navigate", "导航", "வழிகாட்டு"), primary = true) { navigate(plan.dest.lat, plan.dest.lon, plan.dest.name) },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, act.dp(4), 0) })
            addView(act.pill("✕ " + t("Kosongkan", "Clear", "清除", "அழி")) { clear() },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(act.dp(4), 0, 0, 0) })
        }, act.lp(top = 8))

        for (m in maps()) {
            m.route = plan.line
            m.routeSamples = plan.samples
            m.fitTo(plan.line)
        }
    }

    private fun clear() {
        out.removeAllViews()
        results.removeAllViews()
        input.setText("")
        for (m in maps()) {
            m.route = emptyList()
            m.routeSamples = emptyList()
        }
    }

    // ------------------------------------------------------------ tempat berteduh

    private val shelterOut = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }

    /** Butang + senarai "Tempat berteduh" untuk kad peta. */
    val shelterView: View = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        addView(act.pill("🛖 " + t("Cari tempat berteduh", "Find shelter", "寻找避雨处", "தங்குமிடம் தேடு")) { findShelter() }, act.lp(top = 8))
        addView(shelterOut)
    }

    private fun findShelter() {
        val (lat, lon) = myLocation() ?: return toast(t("Lokasi belum diketahui", "Location not known yet", "尚未获取位置", "இருப்பிடம் தெரியவில்லை"))
        shelterOut.removeAllViews()
        shelterOut.addView(act.label(t("Mencari tempat berteduh…", "Looking for shelter…", "正在寻找避雨处…", "தங்குமிடம் தேடுகிறது…"), 14f, color = act.pal.sub))
        work({ Shelter.near(lat, lon) }) { spots ->
            shelterOut.removeAllViews()
            if (spots.isEmpty()) {
                shelterOut.addView(act.label(t("Tiada tempat berteduh dijumpai dalam 8 km", "No shelter found within 8 km", "8公里内找不到避雨处", "8 கி.மீ.க்குள் தங்குமிடம் இல்லை"), 14f, color = act.pal.sub))
                return@work
            }
            for (s in spots.take(8)) {
                val d = Weather.formatKm(Math.round(s.distKm * 10) / 10.0)
                shelterOut.addView(row(s.kind.icon, s.label, "$d " + Weather.toward(s.bearing)) { navigate(s.lat, s.lon, s.label) }, act.lp(top = 6))
            }
            for (m in maps()) {
                m.pois = spots
                m.fitTo(listOf(lat to lon) + spots.take(8).map { it.lat to it.lon })
            }
        }
    }

    // ------------------------------------------------------------ bantuan

    /** Buka navigasi (Google Maps mod motosikal kalau ada, kalau tak app peta lain). */
    private fun navigate(lat: Double, lon: Double, label: String) {
        val gm = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$lat,$lon&mode=l")).setPackage("com.google.android.apps.maps")
        val any = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lon?q=$lat,$lon(" + Uri.encode(label) + ")"))
        runCatching { act.startActivity(gm) }.recoverCatching { act.startActivity(any) }
            .onFailure { toast(t("Tiada app peta", "No maps app", "没有地图应用", "வரைபட ஆப் இல்லை")) }
    }

    private fun row(icon: String, title: String, sub: String, onClick: () -> Unit) = LinearLayout(act).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(act.dp(10), act.dp(8), act.dp(10), act.dp(8))
        background = GradientDrawable().apply {
            setColor(act.pal.bg)
            cornerRadius = act.dp(10).toFloat()
        }
        isClickable = true
        setOnClickListener { onClick() }
        addView(act.label(icon, 22f).apply { setPadding(0, 0, act.dp(10), 0) })
        addView(LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            addView(act.label(title, 15f, bold = true).apply { setPadding(0, 0, 0, 0) })
            if (sub.isNotBlank()) addView(act.label(sub, 12f, color = act.pal.sub).apply { setPadding(0, 0, 0, 0) })
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(act.label("›", 22f, color = act.pal.sub))
    }

    private fun toast(s: String) = Toast.makeText(act, s, Toast.LENGTH_SHORT).show()

    private fun <T> work(job: () -> T, done: (T) -> Unit) {
        Thread {
            val r = runCatching(job)
            main.post {
                if (act.isDestroyed) return@post
                r.onSuccess(done).onFailure {
                    toast(t("Gagal (internet?): ", "Failed (internet?): ", "失败（网络？）：", "தோல்வி (இணையம்?): ") + it.message)
                    out.removeAllViews()
                    shelterOut.removeAllViews()
                    results.removeAllViews()
                }
            }
        }.start()
    }
}
