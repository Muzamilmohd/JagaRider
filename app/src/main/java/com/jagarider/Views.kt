package com.jagarider

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.atan
import kotlin.math.sinh
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tan

private fun View.dp(v: Float) = v * resources.displayMetrics.density
private fun alpha(color: Int, a: Int) = Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))

/**
 * Peta OpenStreetMap berpusat pada rider, dengan taburan hujan (grid Open-Meteo, diinterpolasi)
 * dilukis di atasnya. Bulatan = jarak semakan.
 */
class RainMapView(ctx: Context) : View(ctx) {

    /** Separuh lebar kawasan yang dipapar (km) — berubah bila pengguna cubit zum. */
    private var viewKm = 10.0
    /** Jejari bulatan penunjuk (km) = pilihan slider; tak berubah bila zum. */
    private var circleKm = 10.0
    private var aroundRider = true
    /** Tab Cuaca: bulatan jarak sebagai penunjuk; tab Rider: tiada bulatan (skala lebar peta). */
    var showRangeCircle = true

    private var riderLat = Double.NaN
    private var riderLon = Double.NaN
    private var centerLat = Double.NaN
    private var centerLon = Double.NaN
    private var samples: List<Weather.Sample> = emptyList()
    private var overlay: Bitmap? = null
    private var overlayNW = 0.0 to 0.0 // lat, lon
    private var overlaySE = 0.0 to 0.0

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val pal = Ui.palette(ctx)
    private val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
        if (pal.dark) colorFilter = ColorMatrixColorFilter(darkMap())
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clip = Path()
    private val rect = RectF()

    // keadaan unjuran untuk lukisan semasa
    private var world = 0.0
    private var scale = 1f
    private var cx = 0.0
    private var cy = 0.0

    /** Frame radar RainViewer (null = tiada radar, guna ramalan model). */
    var radarFrame: Radar.Frame? = null
        set(v) { field = v; invalidate() }
    private val radarPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply { alpha = 215 }
    private val chipRect = RectF()

    /** Rider lain dalam kumpulan (tab Rider) — dilukis sebagai titik bernama. */
    var riders: List<RiderGroup.Rider> = emptyList()
        set(v) {
            // Gerak lancar: mula dari kedudukan yang sedang dipapar, menuju lokasi baru dalam ANIM_MS
            val now = SystemClock.uptimeMillis()
            for (r in v) {
                val cur = shownPos(r.uid, now)
                val far = cur == null || abs(cur.first - r.lat) + abs(cur.second - r.lon) > 0.02 // > ~2 km: lompat terus
                moveAnim[r.uid] = MoveAnim(if (far) r.lat else cur!!.first, if (far) r.lon else cur!!.second, r.lat, r.lon, now)
            }
            moveAnim.keys.retainAll(v.map { it.uid }.toSet())
            field = v
            invalidate()
        }

    private class MoveAnim(val fromLat: Double, val fromLon: Double, val toLat: Double, val toLon: Double, val start: Long)
    private val moveAnim = HashMap<String, MoveAnim>()

    /** Kedudukan rider yang sedang dipapar (antara lokasi lama & baru). */
    private fun shownPos(uid: String, now: Long): Pair<Double, Double>? {
        val a = moveAnim[uid] ?: return null
        val f = ((now - a.start) / ANIM_MS).coerceIn(0f, 1f).toDouble()
        val e = 1 - (1 - f) * (1 - f) // perlahan di hujung
        return (a.fromLat + (a.toLat - a.fromLat) * e) to (a.fromLon + (a.toLon - a.fromLon) * e)
    }

    // ------------------------------------------------------------ zum & gerak (kedua-dua tab)

    /** Cubit untuk zum, seret untuk gerak (dua jari), ketik dua kali untuk kembali ke paparan asal. */
    var interactive = false
    /** Dipanggil bila pengguna ketik dua kali (kembali ke zum automatik). */
    var onResetView: (() -> Unit)? = null
    /** true selepas pengguna zum / seret — data baru tak ubah pandangan sehingga ketik dua kali. */
    private var userView = false

    /** Tab Rider: pusat pada saya, zum muat semua rider (kecuali pengguna dah zum / gerak sendiri). */
    fun fitRiders(lat: Double, lon: Double, km: Double) {
        riderLat = lat
        riderLon = lon
        if (!userView) {
            centerLat = lat
            centerLon = lon
            viewKm = km
        }
        invalidate()
    }

    // Dua jari = zum + gerak peta; satu jari = skrol halaman (macam Google Maps dalam senarai)
    private var lastFx = 0f
    private var lastFy = 0f

    private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
            lastFx = d.focusX
            lastFy = d.focusY
            return true
        }

        override fun onScale(d: ScaleGestureDetector): Boolean {
            panBy(lastFx - d.focusX, lastFy - d.focusY)
            lastFx = d.focusX
            lastFy = d.focusY
            viewKm = (viewKm / d.scaleFactor).coerceIn(0.15, 400.0)
            userView = true
            invalidate()
            return true
        }
    }).apply {
        // tiada zum satu jari (ketik + seret) — boleh terpicu tanpa sengaja semasa skrol halaman
        isQuickScaleEnabled = false
    }

    private fun panBy(dx: Float, dy: Float) {
        if (world == 0.0 || centerLat.isNaN()) return
        val nx = mercX(centerLon) * world + dx / scale
        val ny = mercY(centerLat) * world + dy / scale
        centerLon = nx / world * 360.0 - 180.0
        centerLat = Math.toDegrees(atan(sinh(PI * (1 - 2 * ny / world))))
    }

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onDoubleTap(e: MotionEvent): Boolean {
            userView = false
            onResetView?.invoke()
            return true
        }
    })

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!interactive) return false
        // Jari kedua turun = peta ambil alih (halaman berhenti skrol)
        if (e.actionMasked == MotionEvent.ACTION_POINTER_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
        scaler.onTouchEvent(e)
        if (e.pointerCount == 1) gestures.onTouchEvent(e)
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    /** Data baru: sampel pertama = lokasi rider. */
    fun set(samples: List<Weather.Sample>, rangeSetting: Int) {
        val r = samples.firstOrNull() ?: return
        riderLat = r.point.lat
        riderLon = r.point.lon
        this.samples = samples
        buildOverlay()
        applyArea(rangeSetting) // data baru tak reset zum / gerak pengguna
    }

    /** Lokasi diketahui tapi data cuaca belum / gagal: tetap papar peta + radar di sekeliling rider. */
    fun setCenter(lat: Double, lon: Double, rangeSetting: Int) {
        riderLat = lat
        riderLon = lon
        applyArea(rangeSetting)
    }

    /** Slider ditukar: tukar kawasan & zum serta-merta (reset zum pengguna). */
    fun preview(rangeSetting: Int) {
        userView = false
        applyArea(rangeSetting)
    }

    private fun applyArea(rangeSetting: Int) {
        if (!riderLat.isNaN()) {
            val a = Weather.area(riderLat, riderLon, rangeSetting)
            aroundRider = a.aroundRider
            circleKm = a.halfKm
            if (!userView) {
                centerLat = a.lat
                centerLon = a.lon
                // Semenanjung: muatkan Perlis–Johor dalam peta segi empat
                viewKm = if (a.aroundRider) a.halfKm else 300.0
            }
        }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val s = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(s, s)
    }

    /** Interpolasi grid mm/jam → bitmap kecil berwarna ikut tahap (lutsinar bila tiada hujan). */
    private fun buildOverlay() {
        val inGrid = samples.filter { it.point.row >= 0 }
        val n = (inGrid.maxOfOrNull { it.point.row } ?: -1) + 1
        if (n < 2) {
            overlay = null
            return
        }
        val grid = Array(n) { DoubleArray(n) }
        var nw: Weather.Point? = null
        var se: Weather.Point? = null
        for (s in inGrid) {
            grid[s.point.row][s.point.col] = s.mmh
            if (s.point.row == 0 && s.point.col == 0) nw = s.point
            if (s.point.row == n - 1 && s.point.col == n - 1) se = s.point
        }
        if (nw == null || se == null) {
            overlay = null
            return
        }
        val m = 120
        val px = IntArray(m * m)
        for (y in 0 until m) for (x in 0 until m) {
            val gx = x.toDouble() / (m - 1) * (n - 1)
            val gy = y.toDouble() / (m - 1) * (n - 1)
            val x0 = floor(gx).toInt().coerceAtMost(n - 2)
            val y0 = floor(gy).toInt().coerceAtMost(n - 2)
            val fx = gx - x0
            val fy = gy - y0
            val v = grid[y0][x0] * (1 - fx) * (1 - fy) + grid[y0][x0 + 1] * fx * (1 - fy) +
                grid[y0 + 1][x0] * (1 - fx) * fy + grid[y0 + 1][x0 + 1] * fx * fy
            // pudar di tepi grid supaya tak nampak sempadan segi empat
            val edge = (minOf(x, y, m - 1 - x, m - 1 - y) / (m * 0.08)).coerceAtMost(1.0)
            px[y * m + x] = if (v < 0.1) Color.TRANSPARENT else alpha(Weather.Level.of(v).color, (165 * edge).toInt())
        }
        overlay = Bitmap.createBitmap(px, m, m, Bitmap.Config.ARGB_8888)
        overlayNW = nw.lat to nw.lon
        overlaySE = se.lat to se.lon
    }

    private fun mercX(lon: Double) = (lon + 180.0) / 360.0
    private fun mercY(lat: Double): Double {
        val r = Math.toRadians(lat)
        return (1 - ln(tan(r) + 1 / cos(r)) / PI) / 2
    }

    private fun sx(lon: Double) = ((mercX(lon) * world - cx) * scale + width / 2.0).toFloat()
    private fun sy(lat: Double) = ((mercY(lat) * world - cy) * scale + height / 2.0).toFloat()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        c.save()
        clip.reset()
        clip.addRoundRect(0f, 0f, w, h, dp(12f), dp(12f), Path.Direction.CW)
        c.clipPath(clip)
        c.drawColor(pal.mapBg)

        if (centerLat.isNaN()) {
            text.color = pal.sub
            text.textSize = dp(15f)
            text.textAlign = Paint.Align.CENTER
            c.drawText(t("Menunggu lokasi…", "Waiting for location…", "等待位置…", "இருப்பிடத்திற்காகக் காத்திருக்கிறது…"), w / 2, h / 2, text)
            c.restore()
            return
        }

        // Zum: jarak semakan ≈ 87% separuh lebar peta
        val radiusPx = w / 2 / 1.15f
        val kmPerPx = viewKm / radiusPx
        val kmPerWorldPxZ0 = 40075.016 * cos(Math.toRadians(centerLat)) / 256.0
        val zf = ln(kmPerWorldPxZ0 / kmPerPx) / ln(2.0)
        val z = floor(zf).toInt().coerceIn(2, 18)
        world = 256.0 * 2.0.pow(z)
        scale = (kmPerWorldPxZ0 / 2.0.pow(z) / kmPerPx).toFloat()
        cx = mercX(centerLon) * world
        cy = mercY(centerLat) * world

        // Jubin peta
        val tiles = 1 shl z
        val tileSize = 256f * scale
        val left = cx - w / 2 / scale
        val top = cy - h / 2 / scale
        val tx0 = floor(left / 256).toInt()
        val ty0 = floor(top / 256).toInt()
        val tx1 = floor((left + w / scale) / 256).toInt()
        val ty1 = floor((top + h / scale) / 256).toInt()
        for (ty in ty0..ty1) {
            if (ty < 0 || ty >= tiles) continue
            for (tx in tx0..tx1) {
                val x = ((tx * 256 - cx) * scale + w / 2).toFloat()
                val y = ((ty * 256 - cy) * scale + h / 2).toFloat()
                rect.set(x, y, x + tileSize, y + tileSize)
                val bmp = MapTiles.get(context, z, Math.floorMod(tx, tiles), ty) { invalidate() }
                if (bmp != null) c.drawBitmap(bmp, null, rect, tilePaint)
            }
        }

        val rx = sx(riderLon)
        val ry = sy(riderLat)

        // Taburan hujan
        val frame = radarFrame
        // Zum dekat (≤ 50 km) = radar sebenar; zum jauh = taburan ramalan model.
        // Hujan dilukis di SELURUH peta — bulatan hanya penunjuk jarak.
        val radar = frame != null && viewKm <= 50.0
        c.save()
        if (radar) {
            // Radar sebenar: jubin zum ≤ 7 diregangkan ke zum peta
            val rz = minOf(z, Radar.ZOOM)
            val k = 2.0.pow(z - rz) // piksel peta per piksel radar
            val rn = 1 shl rz
            val rSize = (256 * k * scale).toFloat()
            for (ty in floor(top / 256 / k).toInt()..floor((top + h / scale) / 256 / k).toInt()) {
                if (ty < 0 || ty >= rn) continue
                for (tx in floor(left / 256 / k).toInt()..floor((left + w / scale) / 256 / k).toInt()) {
                    val x = ((tx * 256 * k - cx) * scale + w / 2).toFloat()
                    val y = ((ty * 256 * k - cy) * scale + h / 2).toFloat()
                    rect.set(x, y, x + rSize, y + rSize)
                    MapTiles.radar(context, frame!!, rz, Math.floorMod(tx, rn), ty) { invalidate() }
                        ?.let { c.drawBitmap(it, null, rect, radarPaint) }
                }
            }
        } else {
            overlay?.let {
                rect.set(sx(overlayNW.second), sy(overlayNW.first), sx(overlaySE.second), sy(overlaySE.first))
                c.drawBitmap(it, null, rect, bmpPaint)
            }
        }
        c.restore()
        if (!radar) {
            text.textAlign = Paint.Align.CENTER
            text.textSize = dp(16f)
            for (s in samples) if (s.thunder) c.drawText("⚡", sx(s.point.lon), sy(s.point.lat) + dp(6f), text)
        }

        if (!showRangeCircle) {
            // Peta rider: tiada bulatan — tunjuk lebar peta sebagai skala
            val widthKm = viewKm * 2 * 1.15
            val scaleText = if (widthKm < 1.0) "↔ ${(Math.round(widthKm * 20) * 50)} m" else "↔ " + Weather.formatKm(Math.round(widthKm * 10) / 10.0)
            label(c, scaleText, w / 2, dp(22f))
        } else if (aroundRider) {
            // Bulatan jarak
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(2f)
            paint.color = alpha(pal.text, 170)
            paint.pathEffect = DashPathEffect(floatArrayOf(dp(8f), dp(6f)), 0f)
            val circlePx = (circleKm / kmPerPx).toFloat()
            c.drawCircle(rx, ry, circlePx, paint)
            paint.pathEffect = null
            label(c, Weather.formatKm(circleKm), rx, ry - circlePx + dp(4f))
        } else {
            label(c, "🇲🇾 " + t("Semenanjung Malaysia", "Peninsular Malaysia", "马来西亚半岛", "தீபகற்ப மலேசியா"), w / 2, dp(22f))
        }

        // Rider lain dalam kumpulan
        val nowUp = SystemClock.uptimeMillis()
        for ((i, r) in riders.withIndex()) {
            val (lat, lon) = shownPos(r.uid, nowUp) ?: (r.lat to r.lon)
            val x = sx(lon)
            val y = sy(lat)
            val stale = System.currentTimeMillis() - r.time > 5 * 60_000L
            val color = if (stale) Color.GRAY else RIDER_COLORS[i % RIDER_COLORS.size]
            if (!stale && r.speedKmh >= RiderPage.MOVING_KMH) {
                drawRiding(c, x, y, r, color, nowUp)
                label(c, r.name + " · " + String.format(Locale.US, "%.0f km/j", r.speedKmh), x, y - dp(26f))
            } else {
                paint.style = Paint.Style.FILL
                paint.color = Color.WHITE
                c.drawCircle(x, y, dp(9f), paint)
                paint.color = color
                c.drawCircle(x, y, dp(6.5f), paint)
                label(c, r.name, x, y - dp(20f))
            }
        }

        // Rider (berdenyut = live)
        val t = (SystemClock.uptimeMillis() % 1600L) / 1600f
        paint.style = Paint.Style.FILL
        paint.color = alpha(Color.parseColor("#FF6F00"), ((1 - t) * 140).toInt())
        c.drawCircle(rx, ry, dp(9f + 20f * t), paint)
        paint.color = Color.WHITE
        c.drawCircle(rx, ry, dp(10f), paint)
        paint.color = Color.parseColor("#FF6F00")
        c.drawCircle(rx, ry, dp(7.5f), paint)

        // Utara + atribusi (wajib untuk OSM)
        label(c, "▲ " + t("U", "N", "北", "வ"), dp(26f), dp(22f))
        text.textSize = dp(10f)
        text.textAlign = Paint.Align.RIGHT
        val attr = "© OpenStreetMap · " + if (radar) "RainViewer" else "Open-Meteo"
        val aw = text.measureText(attr)
        paint.color = alpha(pal.card, 200)
        c.drawRect(w - aw - dp(8f), h - dp(16f), w, h, paint)
        text.color = pal.sub
        c.drawText(attr, w - dp(4f), h - dp(4.5f), text)

        // Label sumber lapisan (bukan butang) — tukar sendiri ikut zum
        val chip = if (radar) {
            "📡 " + t("Radar sebenar", "Live radar", "实时雷达", "நேரடி ரேடார்") + " " + Clock.time(frame!!.time)
        } else {
            "🔮 " + t("Ramalan model", "Model forecast", "模型预报", "மாதிரி முன்னறிவிப்பு")
        }
        text.textSize = dp(12f)
        text.isFakeBoldText = true
        val cw = text.measureText(chip)
        chipRect.set(dp(8f), h - dp(46f), dp(8f) + cw + dp(16f), h - dp(20f))
        paint.color = alpha(pal.card, 225)
        c.drawRoundRect(chipRect, dp(13f), dp(13f), paint)
        text.textAlign = Paint.Align.CENTER
        text.color = pal.text
        c.drawText(chip, chipRect.centerX(), chipRect.centerY() + dp(4f), text)
        text.isFakeBoldText = false

        c.restore()
        postInvalidateDelayed(50)
    }

    /**
     * Rider sedang ride: bulatan berwarna dengan 🏍️ yang melantun sedikit, gelang berdenyut, dan garis laju
     * di belakang (bertentangan arah perjalanan). Lebih laju = garis lebih panjang & bergerak lebih cepat.
     */
    private fun drawRiding(c: Canvas, x: Float, y: Float, r: RiderGroup.Rider, color: Int, now: Long) {
        val heading = if (r.bearing.isNaN()) 90.0 else r.bearing // tiada arah: anggap ke timur
        val dx = sin(Math.toRadians(heading)).toFloat()
        val dy = -cos(Math.toRadians(heading)).toFloat()
        val speedF = (r.speedKmh / 100.0).coerceIn(0.2, 1.2).toFloat()
        val period = (700 / speedF).toLong().coerceAtLeast(250L)
        val ph = (now % period) / period.toFloat()

        // gelang berdenyut
        paint.style = Paint.Style.FILL
        paint.color = alpha(color, ((1 - ph) * 110).toInt())
        c.drawCircle(x, y, dp(14f + 10f * ph), paint)

        // garis laju di belakang
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = dp(2.5f)
        for (k in 0 until 3) {
            val side = (k - 1) * dp(6f)
            val back = dp(17f) + ((ph + k * 0.33f) % 1f) * dp(14f) * speedF
            val len = dp(6f) + dp(8f) * speedF
            val sx0 = x - dx * back + -dy * side
            val sy0 = y - dy * back + dx * side
            paint.color = alpha(color, (200 * (1 - ((ph + k * 0.33f) % 1f))).toInt())
            c.drawLine(sx0, sy0, sx0 - dx * len, sy0 - dy * len, paint)
        }
        paint.strokeCap = Paint.Cap.BUTT

        // badan penanda + motosikal melantun
        val bob = sin(now / 90.0).toFloat() * dp(1.5f)
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        c.drawCircle(x, y, dp(15f), paint)
        paint.color = color
        c.drawCircle(x, y, dp(12.5f), paint)
        text.textAlign = Paint.Align.CENTER
        text.textSize = dp(15f)
        c.save()
        // emoji 🏍️ menghadap kanan — terbalikkan bila menuju ke barat
        if (heading in 180.0..360.0) c.scale(-1f, 1f, x, y)
        c.drawText("🏍️", x, y + dp(5.5f) + bob, text)
        c.restore()
    }

    /** Teks kecil berlatar putih, berpusat pada (x, y). */
    private fun label(c: Canvas, s: String, x: Float, y: Float) {
        text.textSize = dp(12f)
        text.textAlign = Paint.Align.CENTER
        text.isFakeBoldText = true
        val tw = text.measureText(s)
        paint.style = Paint.Style.FILL
        paint.color = alpha(pal.card, 225)
        rect.set(x - tw / 2 - dp(6f), y - dp(10f), x + tw / 2 + dp(6f), y + dp(8f))
        c.drawRoundRect(rect, dp(9f), dp(9f), paint)
        text.color = pal.text
        c.drawText(s, x, y + dp(4f), text)
        text.isFakeBoldText = false
    }
}

private const val ANIM_MS = 4500f

private val RIDER_COLORS = intArrayOf(
    Color.parseColor("#1E88E5"), Color.parseColor("#8E24AA"), Color.parseColor("#43A047"),
    Color.parseColor("#D81B60"), Color.parseColor("#00897B"), Color.parseColor("#5E35B1"),
)

/** Bar 6 tahap kelebatan; tahap sekarang terang + anak panah, lain pudar. */
class LevelBarView(ctx: Context) : View(ctx) {

    var level: Weather.Level? = null
        set(v) { field = v; invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pal = Ui.palette(ctx)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; color = pal.sub }
    private val arrow = Path()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(58f).toInt())
    }

    override fun onDraw(c: Canvas) {
        val levels = Weather.Level.entries
        val gap = dp(3f)
        val w = (width - gap * (levels.size - 1)) / levels.size
        val top = dp(10f)
        val h = dp(16f)
        for ((i, l) in levels.withIndex()) {
            val x = i * (w + gap)
            val on = l == level
            paint.color = if (on) l.color else alpha(l.color, 70)
            c.drawRoundRect(x, top, x + w, top + h, dp(4f), dp(4f), paint)
            text.isFakeBoldText = on
            // kecilkan tulisan kalau label lebih lebar dari segmen
            text.textSize = dp(10.5f)
            val tw = text.measureText(l.label)
            if (tw > w) text.textSize = dp(10.5f) * w / tw
            c.drawText(l.label, x + w / 2, top + h + dp(16f), text)
            if (on) {
                arrow.reset()
                arrow.moveTo(x + w / 2 - dp(6f), 0f)
                arrow.lineTo(x + w / 2 + dp(6f), 0f)
                arrow.lineTo(x + w / 2, top - dp(1f))
                arrow.close()
                paint.color = pal.text
                c.drawPath(arrow, paint)
            }
        }
    }
}

/** Carta hujan setiap 15 minit untuk 2 jam akan datang di lokasi rider, dengan ikon cuaca di atas. */
class RainChartView(ctx: Context) : View(ctx) {

    var values: List<Double> = emptyList()
    var times: List<String> = emptyList()
    var codes: List<Int> = emptyList()

    fun set(values: List<Double>, times: List<String>, codes: List<Int>) {
        this.values = values
        this.times = times
        this.codes = codes
        requestLayout()
        invalidate()
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pal = Ui.palette(ctx)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; color = pal.sub }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Tiada hujan langsung: carta rendah supaya tak banyak ruang kosong
        val dry = values.all { it < 0.1 }
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(if (dry) 80f else 150f).toInt())
    }

    override fun onDraw(c: Canvas) {
        if (values.isEmpty()) return
        val n = values.size
        val gap = dp(6f)
        val w = (width - gap * (n - 1)) / n
        val iconH = dp(30f)
        val labelH = dp(18f)
        val valueH = dp(16f)
        val maxH = height - iconH - labelH - valueH
        for ((i, code) in codes.withIndex()) {
            text.textSize = min(dp(20f), w * 0.8f)
            c.drawText(Weather.icon(code, times.getOrElse(i) { "" }), i * (w + gap) + w / 2, dp(22f), text)
        }
        text.textSize = dp(10.5f)
        for ((i, v) in values.withIndex()) {
            val x = i * (w + gap)
            // punca kuasa dua supaya hujan renyai pun nampak; 30 mm/j = penuh
            val h = if (v < 0.1) dp(3f) else maxOf(dp(6f), sqrt(min(v, 30.0) / 30.0).toFloat() * maxH)
            val bottom = iconH + valueH + maxH
            paint.color = if (v < 0.1) pal.border else Weather.Level.of(v).color
            c.drawRoundRect(x, bottom - h, x + w, bottom, dp(3f), dp(3f), paint)
            if (v >= 0.1) c.drawText(String.format(Locale.US, "%.1f", v), x + w / 2, bottom - h - dp(4f), text)
            if (i % 2 == 1 || n <= 4) {
                // label 12 jam ("5:00 PM") lebih lebar — jangan terkeluar tepi kanan
                val s = Clock.hm(times.getOrElse(i) { "" })
                val half = text.measureText(s) / 2
                c.drawText(s, (x + w / 2).coerceAtMost(width - half), height - dp(4f), text)
            }
        }
    }
}

/** Penapis peta mod gelap: songsang warna + putar hue 180° (jalan kekal berwarna), sedikit malap. */
private fun darkMap(): ColorMatrix {
    val invert = ColorMatrix(
        floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        )
    )
    val hue180 = ColorMatrix(
        floatArrayOf(
            -0.574f, 1.43f, 0.144f, 0f, 0f,
            0.426f, 0.43f, 0.144f, 0f, 0f,
            0.426f, 1.43f, -0.856f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
    )
    hue180.preConcat(invert)
    hue180.postConcat(ColorMatrix().apply { setScale(0.85f, 0.85f, 0.85f, 1f) })
    return hue180
}
