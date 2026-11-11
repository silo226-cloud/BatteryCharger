package com.silo.chargemonitor

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.max
import kotlin.math.min

fun Context.dp(v: Float) = v * resources.displayMetrics.density

/** Big battery-level ring with a gradient sweep and a pulsing glow while charging. */
class LevelRing(ctx: Context) : View(ctx) {
    var level = 0f; set(v) { field = v; invalidate() }
    var charging = false
        set(v) { if (field != v) { field = v; if (v) pulse.start() else { pulse.cancel(); glow = 0f }; invalidate() } }
    var title = ""; var subtitle = ""

    private var glow = 0f
    private val pulse = ValueAnimator.ofFloat(0f, 1f, 0f).apply {
        duration = 1800; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { glow = it.animatedValue as Float; invalidate() }
    }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = 0x22FFFFFF }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val glowP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB3FFFFFF.toInt(); textAlign = Paint.Align.CENTER }
    private val rect = RectF()

    override fun onMeasure(w: Int, h: Int) {
        val s = MeasureSpec.getSize(w)
        setMeasuredDimension(s, (s * 0.78f).toInt())
    }

    override fun onDraw(c: Canvas) {
        val size = min(width.toFloat(), height.toFloat())
        val stroke = size * 0.075f
        val cx = width / 2f; val cy = height / 2f
        val r = size / 2f - stroke
        rect.set(cx - r, cy - r, cx + r, cy + r)
        track.strokeWidth = stroke
        arc.strokeWidth = stroke
        c.drawArc(rect, 135f, 270f, false, track)

        val col = levelColor(level)
        arc.shader = SweepGradient(cx, cy, intArrayOf(0xFFEF4444.toInt(), 0xFFF59E0B.toInt(), 0xFF22C55E.toInt(), 0xFF06B6D4.toInt(), 0xFFEF4444.toInt()),
            floatArrayOf(0f, 0.18f, 0.45f, 0.75f, 1f)).also {
            val m = Matrix(); m.setRotate(125f, cx, cy); it.setLocalMatrix(m)
        }
        val sweep = 270f * (level / 100f).coerceIn(0f, 1f)
        if (charging) {
            glowP.strokeWidth = stroke * (1.2f + glow)
            glowP.color = col
            glowP.alpha = (40 + 80 * glow).toInt()
            glowP.maskFilter = BlurMaskFilter(stroke, BlurMaskFilter.Blur.NORMAL)
            c.drawArc(rect, 135f, max(sweep, 1f), false, glowP)
        }
        c.drawArc(rect, 135f, max(sweep, 1f), false, arc)

        big.textSize = size * 0.24f
        c.drawText("${level.toInt()}%", cx, cy + big.textSize * 0.3f, big)
        small.textSize = size * 0.065f
        small.color = col
        c.drawText(title, cx, cy - big.textSize * 0.55f, small)
        small.color = 0xB3FFFFFF.toInt()
        c.drawText(subtitle, cx, cy + big.textSize * 0.85f, small)
    }

    override fun onDetachedFromWindow() { pulse.cancel(); super.onDetachedFromWindow() }

    companion object {
        fun levelColor(l: Float) = when {
            l < 15 -> 0xFFEF4444.toInt(); l < 35 -> 0xFFF59E0B.toInt()
            l < 80 -> 0xFF22C55E.toInt(); else -> 0xFF06B6D4.toInt()
        }
    }
}

/** Semicircular speedometer for watts, colored by charging speed. */
class PowerGauge(ctx: Context) : View(ctx) {
    var watts = 0f; set(v) { field = v; invalidate() }
    var maxWatts = 0f; set(v) { field = v; invalidate() }   // charger negotiated limit (marker)
    var scale = 30f
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = 0x22FFFFFF }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x88FFFFFF.toInt(); textAlign = Paint.Align.CENTER }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    private val rect = RectF()

    override fun onMeasure(w: Int, h: Int) {
        val s = MeasureSpec.getSize(w)
        setMeasuredDimension(s, (s * 0.58f).toInt())
    }

    override fun onDraw(c: Canvas) {
        val stroke = width * 0.06f
        val r = min(width / 2f - stroke, height - stroke * 1.6f)
        val cx = width / 2f; val cy = height - stroke * 0.8f
        rect.set(cx - r, cy - r, cx + r, cy + r)
        scale = listOf(10f, 20f, 30f, 45f, 65f, 100f, 150f).first { it >= max(watts, maxWatts) * 1.05f || it == 150f }
        track.strokeWidth = stroke; arc.strokeWidth = stroke
        c.drawArc(rect, 180f, 180f, false, track)
        arc.shader = LinearGradient(cx - r, 0f, cx + r, 0f,
            intArrayOf(0xFF3B82F6.toInt(), 0xFF22C55E.toInt(), 0xFFF59E0B.toInt(), 0xFFEC4899.toInt()), null, Shader.TileMode.CLAMP)
        val sweep = 180f * (watts / scale).coerceIn(0f, 1f)
        if (sweep > 0.5f) c.drawArc(rect, 180f, sweep, false, arc)

        if (maxWatts > 0f) {
            val a = Math.toRadians(180.0 + 180.0 * (maxWatts / scale).coerceIn(0f, 1f))
            marker.strokeWidth = stroke * 0.18f
            val r1 = r - stroke * 0.9f; val r2 = r + stroke * 0.9f
            c.drawLine(cx + (r1 * Math.cos(a)).toFloat(), cy + (r1 * Math.sin(a)).toFloat(),
                cx + (r2 * Math.cos(a)).toFloat(), cy + (r2 * Math.sin(a)).toFloat(), marker)
        }
        tick.textSize = width * 0.035f
        for (k in 0..4) {
            val v = scale * k / 4f
            val a = Math.toRadians(180.0 + 45.0 * k)
            val rr = r - stroke * 1.6f
            c.drawText(v.toInt().toString(), cx + (rr * Math.cos(a)).toFloat(), cy + (rr * Math.sin(a)).toFloat() + tick.textSize / 3, tick)
        }
        big.textSize = width * 0.13f
        c.drawText(String.format("%.1f", watts), cx, cy - r * 0.22f, big)
        tick.textSize = width * 0.045f
        c.drawText("וואט נכנס לטלפון", cx, cy - r * 0.02f, tick)
    }
}

/** Filled area chart of recent samples. */
class Sparkline(ctx: Context) : View(ctx) {
    private val data = ArrayDeque<Float>()
    var capacity = 120
    var color = 0xFF22C55E.toInt()
    var unit = "W"
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grid = Paint().apply { color = 0x15FFFFFF }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x88FFFFFF.toInt() }

    fun push(v: Float) { data.addLast(v); while (data.size > capacity) data.removeFirst(); invalidate() }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), context.dp(110f).toInt())

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val pad = context.dp(6f)
        for (k in 1..3) c.drawLine(0f, h * k / 4, w, h * k / 4, grid)
        if (data.size < 2) return
        val hi = max(data.max() * 1.15f, 1f)
        val lo = min(0f, data.min())
        val path = Path(); val area = Path()
        data.forEachIndexed { idx, v ->
            val x = w * idx / (capacity - 1)
            val y = pad + (h - 2 * pad) * (1f - (v - lo) / (hi - lo))
            if (idx == 0) { path.moveTo(x, y); area.moveTo(x, h); area.lineTo(x, y) } else { path.lineTo(x, y); area.lineTo(x, y) }
        }
        area.lineTo(w * (data.size - 1) / (capacity - 1), h); area.close()
        fill.shader = LinearGradient(0f, 0f, 0f, h, (color and 0x00FFFFFF) or 0x88000000.toInt(), color and 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.drawPath(area, fill)
        line.color = color; line.strokeWidth = context.dp(2.5f)
        c.drawPath(path, line)
        label.textSize = context.dp(10f)
        c.drawText(String.format("%.1f %s", hi, unit), pad, label.textSize + pad, label)
    }
}

/** Thin horizontal bar used for health/temperature meters. */
class MeterBar(ctx: Context) : View(ctx) {
    var fraction = 0f; set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    var colors = intArrayOf(0xFFEF4444.toInt(), 0xFFF59E0B.toInt(), 0xFF22C55E.toInt())
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22FFFFFF }
    private val fg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), context.dp(14f).toInt())
    override fun onDraw(c: Canvas) {
        val h = height.toFloat(); val r = h / 2
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        c.drawRoundRect(0f, 0f, width.toFloat(), h, r, r, bg)
        fg.shader = LinearGradient(if (rtl) width.toFloat() else 0f, 0f, if (rtl) 0f else width.toFloat(), 0f, colors, null, Shader.TileMode.CLAMP)
        c.drawRoundRect(0f, 0f, width.toFloat(), h, r, r, fg.apply { alpha = 255 })
        // Dim the unfilled part and mark the current position.
        val x = if (rtl) width * (1 - fraction) else width * fraction
        val dim = Paint().apply { color = 0xAA0B1020.toInt() }
        if (rtl) c.drawRoundRect(0f, 0f, x, h, r, r, dim) else c.drawRoundRect(x, 0f, width.toFloat(), h, r, r, dim)
        c.drawCircle(x.coerceIn(r, width - r), r, r * 0.9f, dot)
    }
}
