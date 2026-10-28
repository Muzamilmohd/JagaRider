package com.jagarider

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.SystemClock
import android.view.View
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

private fun View.dp(v: Float) = v * resources.displayMetrics.density
private fun alpha(color: Int, a: Int) = Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))

/** Radar hujan: rider di tengah, kawasan hujan sekeliling dalam jarak dipilih, garisan sapuan "live". */
class RadarView(ctx: Context) : View(ctx) {

    var rangeKm = 10.0
        set(v) { field = v; invalidate() }
    var samples: List<Weather.Sample> = emptyList()
        set(v) { field = v; invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val clip = Path()
    private val sweepMatrix = Matrix()
    private val start = SystemClock.uptimeMillis()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val s = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(s, s)
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(cx, cy) - dp(22f)
        if (r <= 0) return

        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#0D1B2A")
        c.drawCircle(cx, cy, r, paint)

        // Kawasan hujan (gumpalan lembut) — dipotong ikut bulatan radar
        c.save()
        clip.reset()
        clip.addCircle(cx, cy, r, Path.Direction.CW)
        c.clipPath(clip)
        for (s in samples) {
            if (s.mmh < 0.1) continue
            val (x, y) = pos(s.point, cx, cy, r)
            val br = r * 0.45f
            val col = s.level.color
            paint.shader = RadialGradient(x, y, br, alpha(col, 210), alpha(col, 0), Shader.TileMode.CLAMP)
            c.drawCircle(x, y, br, paint)
        }
        paint.shader = null

        // Sapuan radar (berpusing setiap 4 saat)
        val angle = ((SystemClock.uptimeMillis() - start) % 4000L) / 4000f * 360f
        val green = Color.parseColor("#00E676")
        val sweep = SweepGradient(cx, cy, intArrayOf(alpha(green, 0), alpha(green, 0), alpha(green, 90)), floatArrayOf(0f, 0.75f, 1f))
        sweepMatrix.setRotate(angle - 90f, cx, cy)
        sweep.setLocalMatrix(sweepMatrix)
        paint.shader = sweep
        c.drawCircle(cx, cy, r, paint)
        paint.shader = null
        c.restore()

        // Gelang jarak + garis arah
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1f)
        paint.color = alpha(Color.WHITE, 70)
        c.drawCircle(cx, cy, r / 2, paint)
        c.drawCircle(cx, cy, r, paint)
        c.drawLine(cx - r, cy, cx + r, cy, paint)
        c.drawLine(cx, cy - r, cx, cy + r, paint)
        val a = Math.toRadians(angle.toDouble())
        paint.color = alpha(green, 200)
        paint.strokeWidth = dp(2f)
        c.drawLine(cx, cy, cx + r * sin(a).toFloat(), cy - r * cos(a).toFloat(), paint)

        // Titik semakan
        paint.style = Paint.Style.FILL
        for (s in samples.drop(1)) {
            val (x, y) = pos(s.point, cx, cy, r)
            paint.color = if (s.mmh < 0.1) alpha(Color.WHITE, 90) else s.level.color
            c.drawCircle(x, y, dp(if (s.mmh < 0.1) 2.5f else 5f), paint)
            if (s.thunder) {
                text.textSize = dp(14f)
                c.drawText("⚡", x, y - dp(6f), text)
            }
        }

        // Rider di tengah
        paint.color = Color.parseColor("#FF6F00")
        c.drawCircle(cx, cy, dp(9f), paint)
        paint.color = Color.WHITE
        c.drawCircle(cx, cy, dp(4f), paint)

        // Label arah & jarak
        text.color = Color.parseColor("#455A64")
        text.textSize = dp(13f)
        text.isFakeBoldText = true
        val off = dp(12f)
        c.drawText("U", cx, cy - r - off + dp(5f), text)
        c.drawText("S", cx, cy + r + off + dp(5f), text)
        c.drawText("T", cx + r + off, cy + dp(5f), text)
        c.drawText("B", cx - r - off, cy + dp(5f), text)
        text.isFakeBoldText = false
        text.textSize = dp(11f)
        text.color = alpha(Color.WHITE, 170)
        c.drawText(Weather.formatKm(rangeKm / 2), cx + dp(22f), cy - r / 2 - dp(4f), text)
        c.drawText(Weather.formatKm(rangeKm), cx + dp(22f), cy - r + dp(14f), text)

        postInvalidateDelayed(40)
    }

    private fun pos(p: Weather.Point, cx: Float, cy: Float, r: Float): Pair<Float, Float> {
        val f = (p.distKm / rangeKm).toFloat() * r
        val b = Math.toRadians(p.bearing)
        return (cx + f * sin(b).toFloat()) to (cy - f * cos(b).toFloat())
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
        text.textSize = dp(10.5f)
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

/** Carta hujan setiap 15 minit untuk 2 jam akan datang di lokasi rider. */
class RainChartView(ctx: Context) : View(ctx) {

    var values: List<Double> = emptyList()
    var times: List<String> = emptyList()

    fun set(values: List<Double>, times: List<String>) {
        this.values = values
        this.times = times
        invalidate()
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; color = Color.parseColor("#455A64") }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(130f).toInt())
    }

    override fun onDraw(c: Canvas) {
        if (values.isEmpty()) return
        val n = values.size
        val gap = dp(6f)
        val w = (width - gap * (n - 1)) / n
        val labelH = dp(18f)
        val valueH = dp(16f)
        val maxH = height - labelH - valueH
        text.textSize = dp(10.5f)
        for ((i, v) in values.withIndex()) {
            val x = i * (w + gap)
            // punca kuasa dua supaya hujan renyai pun nampak; 30 mm/j = penuh
            val h = if (v < 0.1) dp(3f) else maxOf(dp(6f), sqrt(min(v, 30.0) / 30.0).toFloat() * maxH)
            val bottom = valueH + maxH
            paint.color = if (v < 0.1) Color.parseColor("#CFD8DC") else Weather.Level.of(v).color
            c.drawRoundRect(x, bottom - h, x + w, bottom, dp(3f), dp(3f), paint)
            if (v >= 0.1) c.drawText(String.format(Locale.US, "%.1f", v), x + w / 2, bottom - h - dp(4f), text)
            if (i % 2 == 1 || n <= 4) c.drawText(times.getOrElse(i) { "" }, x + w / 2, height - dp(4f), text)
        }
    }
}
