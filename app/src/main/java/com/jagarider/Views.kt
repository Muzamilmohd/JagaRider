package com.jagarider

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import java.util.Locale
import kotlin.math.PI
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

    var rangeKm = 10.0
        set(v) { field = v; invalidate() }

    private var centerLat = Double.NaN
    private var centerLon = Double.NaN
    private var samples: List<Weather.Sample> = emptyList()
    private var overlay: Bitmap? = null
    private var overlayNW = 0.0 to 0.0 // lat, lon
    private var overlaySE = 0.0 to 0.0

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clip = Path()
    private val ring = Path()
    private val rect = RectF()

    // keadaan unjuran untuk lukisan semasa
    private var world = 0.0
    private var scale = 1f
    private var cx = 0.0
    private var cy = 0.0

    fun set(samples: List<Weather.Sample>, rangeKm: Double) {
        val c = samples.firstOrNull() ?: return
        centerLat = c.point.lat
        centerLon = c.point.lon
        this.samples = samples
        this.rangeKm = rangeKm
        buildOverlay()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val s = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(s, s)
    }

    /** Interpolasi grid mm/jam → bitmap kecil berwarna ikut tahap (lutsinar bila tiada hujan). */
    private fun buildOverlay() {
        val n = Weather.GRID
        val grid = Array(n) { DoubleArray(n) }
        var nw: Weather.Point? = null
        var se: Weather.Point? = null
        for (s in samples) {
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
            px[y * m + x] = if (v < 0.1) Color.TRANSPARENT else alpha(Weather.Level.of(v).color, 165)
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
        c.drawColor(Color.parseColor("#E5E3DF"))

        if (centerLat.isNaN()) {
            text.color = Color.parseColor("#607D8B")
            text.textSize = dp(15f)
            text.textAlign = Paint.Align.CENTER
            c.drawText("Menunggu lokasi…", w / 2, h / 2, text)
            c.restore()
            return
        }

        // Zum: jarak semakan ≈ 87% separuh lebar peta
        val radiusPx = w / 2 / 1.15f
        val kmPerPx = rangeKm / radiusPx
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
                if (bmp != null) c.drawBitmap(bmp, null, rect, bmpPaint)
            }
        }

        // Taburan hujan — dipotong ikut bulatan jarak (grid segi empat, tepinya tak cantik)
        overlay?.let {
            c.save()
            ring.reset()
            ring.addCircle(w / 2, h / 2, radiusPx, Path.Direction.CW)
            c.clipPath(ring)
            rect.set(sx(overlayNW.second), sy(overlayNW.first), sx(overlaySE.second), sy(overlaySE.first))
            c.drawBitmap(it, null, rect, bmpPaint)
            c.restore()
        }
        text.textAlign = Paint.Align.CENTER
        text.textSize = dp(16f)
        for (s in samples) if (s.thunder) c.drawText("⚡", sx(s.point.lon), sy(s.point.lat) + dp(6f), text)

        // Bulatan jarak
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2f)
        paint.color = alpha(Color.parseColor("#263238"), 170)
        paint.pathEffect = DashPathEffect(floatArrayOf(dp(8f), dp(6f)), 0f)
        c.drawCircle(w / 2, h / 2, radiusPx, paint)
        paint.pathEffect = null
        label(c, Weather.formatKm(rangeKm), w / 2, h / 2 - radiusPx + dp(4f))

        // Rider (berdenyut = live)
        val t = (SystemClock.uptimeMillis() % 1600L) / 1600f
        paint.style = Paint.Style.FILL
        paint.color = alpha(Color.parseColor("#FF6F00"), ((1 - t) * 140).toInt())
        c.drawCircle(w / 2, h / 2, dp(9f + 20f * t), paint)
        paint.color = Color.WHITE
        c.drawCircle(w / 2, h / 2, dp(10f), paint)
        paint.color = Color.parseColor("#FF6F00")
        c.drawCircle(w / 2, h / 2, dp(7.5f), paint)

        // Utara + atribusi (wajib untuk OSM)
        label(c, "▲ U", dp(26f), dp(22f))
        text.textSize = dp(10f)
        text.textAlign = Paint.Align.RIGHT
        val attr = "© OpenStreetMap · Open-Meteo"
        val aw = text.measureText(attr)
        paint.color = alpha(Color.WHITE, 200)
        c.drawRect(w - aw - dp(8f), h - dp(16f), w, h, paint)
        text.color = Color.parseColor("#37474F")
        c.drawText(attr, w - dp(4f), h - dp(4.5f), text)

        c.restore()
        postInvalidateDelayed(50)
    }

    /** Teks kecil berlatar putih, berpusat pada (x, y). */
    private fun label(c: Canvas, s: String, x: Float, y: Float) {
        text.textSize = dp(12f)
        text.textAlign = Paint.Align.CENTER
        text.isFakeBoldText = true
        val tw = text.measureText(s)
        paint.style = Paint.Style.FILL
        paint.color = alpha(Color.WHITE, 225)
        rect.set(x - tw / 2 - dp(6f), y - dp(10f), x + tw / 2 + dp(6f), y + dp(8f))
        c.drawRoundRect(rect, dp(9f), dp(9f), paint)
        text.color = Color.parseColor("#263238")
        c.drawText(s, x, y + dp(4f), text)
        text.isFakeBoldText = false
    }
}

/** Bar 6 tahap kelebatan; tahap sekarang terang + anak panah, lain pudar. */
class LevelBarView(ctx: Context) : View(ctx) {

    var level: Weather.Level? = null
        set(v) { field = v; invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; color = Color.parseColor("#455A64") }
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
                paint.color = Color.parseColor("#263238")
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
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; color = Color.parseColor("#455A64") }

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
            paint.color = if (v < 0.1) Color.parseColor("#CFD8DC") else Weather.Level.of(v).color
            c.drawRoundRect(x, bottom - h, x + w, bottom, dp(3f), dp(3f), paint)
            if (v >= 0.1) c.drawText(String.format(Locale.US, "%.1f", v), x + w / 2, bottom - h - dp(4f), text)
            if (i % 2 == 1 || n <= 4) c.drawText(times.getOrElse(i) { "" }, x + w / 2, height - dp(4f), text)
        }
    }
}
