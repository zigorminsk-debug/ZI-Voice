package com.zigorminsk.zivoice

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/**
 * График тренировки: по горизонтали время (окно ~8 секунд, прокручивается),
 * по вертикали — отклонение голоса от идеальной ноты в центах.
 * Идеал — центральная линия; вверх = поёте ниже ноты (нужно выше),
 * вниз = поёте выше ноты (нужно ниже).
 */
class PitchGraphView(context: Context) : View(context) {

    companion object {
        const val WINDOW_SEC = 8f
        const val IN_TUNE_CENTS = 15f
        const val OK_CENTS = 40f
        const val RANGE_CENTS = 100f
    }

    private val dp = resources.displayMetrics.density

    // Данные упражнения
    private var segStart = FloatArray(0)
    private var segEnd = FloatArray(0)
    private var segNames = emptyArray<String>()
    private var melodyStart = 0f
    private var melodyEnd = 0f
    private var totalSec = 0f
    private var chunkSec = CHUNK.toFloat() / SAMPLE_RATE

    // Данные голоса
    private var dev = FloatArray(0)
    private var pointCount = 0

    private val bgPaint = Paint().apply { color = Color.rgb(16, 20, 24) }
    private val melodyBgPaint = Paint().apply { color = Color.rgb(26, 31, 38) }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(44, 51, 60)
        strokeWidth = dp
    }
    private val separatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(58, 66, 78)
        strokeWidth = dp
        pathEffect = DashPathEffect(floatArrayOf(dp * 4, dp * 4), 0f)
    }
    private val okLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 240, 190, 90)
        strokeWidth = dp
        pathEffect = DashPathEffect(floatArrayOf(dp * 3, dp * 5), 0f)
    }
    private val idealPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(120, 200, 255)
        strokeWidth = dp * 2
    }
    private val inTuneBandPaint = Paint().apply { color = Color.argb(34, 90, 200, 120) }
    private val hardBandPaint = Paint().apply { color = Color.argb(20, 230, 120, 90) }
    private val playheadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(220, 226, 235) }
    private val axisTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(120, 128, 140)
        textSize = 10f * dp
    }
    private val noteTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(150, 200, 235)
        textSize = 11f * dp
        textAlign = Paint.Align.CENTER
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp * 2.6f
        strokeCap = Paint.Cap.ROUND
    }

    private val green = Color.rgb(90, 200, 120)
    private val yellow = Color.rgb(240, 190, 90)
    private val red = Color.rgb(230, 120, 90)

    /** Задать разметку упражнения. */
    fun configure(
        starts: FloatArray,
        ends: FloatArray,
        names: Array<String>,
        melodyStartSec: Float,
        melodyEndSec: Float,
        totalSeconds: Float
    ) {
        segStart = starts
        segEnd = ends
        segNames = names
        melodyStart = melodyStartSec
        melodyEnd = melodyEndSec
        totalSec = totalSeconds
        pointCount = 0
        invalidate()
    }

    /** Обновить точки отклонения (points[i] — время (i+1)*chunkSec). */
    fun setPoints(points: FloatArray, count: Int) {
        dev = points
        pointCount = count.coerceIn(0, points.size)
        invalidate()
    }

    private fun xOf(t: Float, leftEdge: Float, width: Float): Float =
        (t - leftEdge) / WINDOW_SEC * width

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cy = h * 0.5f
        val half = h * 0.5f - dp * 16
        val playT = pointCount * chunkSec
        val rightEdge = maxOf(WINDOW_SEC, playT)
        val leftEdge = rightEdge - WINDOW_SEC

        canvas.drawRect(0f, 0f, w, h, bgPaint)

        val xm0 = xOf(melodyStart, leftEdge, w)
        val xm1 = xOf(melodyEnd, leftEdge, w)
        if (xm1 > 0 && xm0 < w) {
            canvas.drawRect(
                xm0.coerceAtLeast(0f), 0f, xm1.coerceAtMost(w), h, melodyBgPaint
            )
        }

        // Зоны: зелёная ±IN_TUNE, жёлтая граница ±OK, красные хвосты до ±RANGE
        fun yOf(cents: Float): Float = cy - cents / RANGE_CENTS * half
        val greenTop = yOf(IN_TUNE_CENTS)
        val greenBottom = yOf(-IN_TUNE_CENTS)
        val okTop = yOf(OK_CENTS)
        val okBottom = yOf(-OK_CENTS)
        val topEdge = yOf(RANGE_CENTS)
        val bottomEdge = yOf(-RANGE_CENTS)
        val bx0 = 0f
        val bx1 = w
        canvas.drawRect(bx0, greenTop, bx1, greenBottom, inTuneBandPaint)
        canvas.drawRect(bx0, topEdge, bx1, okTop, hardBandPaint)
        canvas.drawRect(bx0, okBottom, bx1, bottomEdge, hardBandPaint)

        // Сетка и подписи оси
        for (cents in intArrayOf(100, 50, 0, -50, -100)) {
            val y = yOf(cents.toFloat())
            canvas.drawLine(bx0, y, bx1, y, gridPaint)
            val label = if (cents == 0) "идеал" else (if (cents > 0) "+" else "") + cents
            canvas.drawText(label, dp * 5, y - dp * 3, axisTextPaint)
        }
        canvas.drawLine(bx0, okTop, bx1, okTop, okLinePaint)
        canvas.drawLine(bx0, okBottom, bx1, okBottom, okLinePaint)

        // Идеальная линия по центру (в области мелодии — ярче)
        canvas.drawLine(bx0, cy, bx1, cy, idealPaint)

        // Границы нот и названия
        for (i in segStart.indices) {
            val x0 = xOf(segStart[i], leftEdge, w)
            val x1 = xOf(segEnd[i], leftEdge, w)
            if (x1 < 0 || x0 > w) continue
            if (x0 >= 0) canvas.drawLine(x0, 0f, x0, h, separatorPaint)
            val cx = (x0 + x1) / 2f
            if (x1 - x0 > dp * 22 && cx > dp * 10 && cx < w - dp * 10) {
                canvas.drawText(segNames[i], cx, dp * 12, noteTextPaint)
            }
        }

        // Линия голоса
        var path: Path? = null
        var pathColor = 0
        fun flush() {
            path?.let { p ->
                linePaint.color = pathColor
                canvas.drawPath(p, linePaint)
            }
            path = null
        }
        if (pointCount > 1) {
            for (i in 1 until pointCount) {
                val t0 = i * chunkSec
                if (t0 < leftEdge) continue
                if (t0 > rightEdge) break
                val d0 = dev[i - 1]
                val d1 = dev[i]
                if (d0.isNaN() || d1.isNaN()) {
                    flush()
                    continue
                }
                val x0 = xOf((i - 1) * chunkSec, leftEdge, w)
                val x1 = xOf(t0, leftEdge, w)
                val y0 = cy - d0.coerceIn(-RANGE_CENTS, RANGE_CENTS) / RANGE_CENTS * half
                val y1 = cy - d1.coerceIn(-RANGE_CENTS, RANGE_CENTS) / RANGE_CENTS * half
                val avg = (Math.abs(d0) + Math.abs(d1)) / 2f
                val color = when {
                    avg <= IN_TUNE_CENTS -> green
                    avg <= OK_CENTS -> yellow
                    else -> red
                }
                if (path == null || pathColor != color) {
                    flush()
                    path = Path().apply { moveTo(x0, y0) }
                    pathColor = color
                }
                path!!.lineTo(x1, y1)
            }
            flush()
        }

        // Плейхед
        val px = xOf(playT, leftEdge, w)
        if (pointCount > 0 && px in 0f..w) {
            canvas.drawLine(px, 0f, px, h, playheadPaint)
            val tri = Path().apply {
                moveTo(px - dp * 6, 0f)
                lineTo(px + dp * 6, 0f)
                lineTo(px, dp * 8)
                close()
            }
            canvas.drawPath(tri, playheadPaint)
        }

        // Рамка
        canvas.drawRect(
            RectF(0.5f * dp, 0.5f * dp, w - 0.5f * dp, h - 0.5f * dp),
            Paint().apply {
                style = Paint.Style.STROKE
                strokeWidth = dp
                color = Color.rgb(52, 60, 70)
            }
        )
    }
}
