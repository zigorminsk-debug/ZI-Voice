package com.zigorminsk.zivoice

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.ln

private const val SAMPLE_RATE = 44100
private const val REQUEST_MIC = 1
private const val MIN_MIDI = 48
private const val MAX_MIDI = 84
private const val IN_TUNE_CENTS = 10f
private const val RMS_SILENCE = 0.008f

private val NOTE_NAMES = arrayOf(
    "До", "До#", "Ре", "Ре#", "Ми", "Фа",
    "Фа#", "Соль", "Соль#", "Ля", "Ля#", "Си"
)

private fun noteName(midi: Int): String = NOTE_NAMES[midi % 12] + (midi / 12 - 1)

private fun midiToFreq(midi: Int): Double = 440.0 * Math.pow(2.0, (midi - 69) / 12.0)

/**
 * Определяет частоту основного тона по методу YIN
 * (de Cheveigné & Kawahara, 2002): разностная функция с
 * кумулятивной нормализацией + параболическая интерполяция.
 * Возвращает частоту в Гц или null, если тон не найден.
 */
private fun detectPitch(x: FloatArray, sampleRate: Int): Double? {
    val maxLag = sampleRate / 60      // нижняя граница поиска: 60 Гц
    val minLag = sampleRate / 1400    // верхняя граница поиска: 1400 Гц
    val window = 2048
    if (x.size < window + maxLag) return null

    val difference = DoubleArray(maxLag + 1)
    for (lag in minLag..maxLag) {
        var sum = 0.0
        for (i in 0 until window) {
            val diff = x[i] - x[i + lag]
            sum += diff.toDouble() * diff.toDouble()
        }
        difference[lag] = sum
    }

    val normalized = DoubleArray(maxLag + 1)
    var cumulative = 0.0
    for (lag in minLag..maxLag) {
        cumulative += difference[lag]
        normalized[lag] =
            if (cumulative > 1e-12) difference[lag] * (lag - minLag + 1) / cumulative else 1.0
    }

    var chosen = -1
    var lag = minLag
    while (lag <= maxLag) {
        if (normalized[lag] < 0.2) {
            while (lag + 1 <= maxLag && normalized[lag + 1] < normalized[lag]) lag++
            chosen = lag
            break
        }
        lag++
    }
    if (chosen < 0) {
        var best = minLag
        for (t in minLag..maxLag) {
            if (normalized[t] < normalized[best]) best = t
        }
        if (normalized[best] < 0.5) chosen = best else return null
    }

    var period = chosen.toDouble()
    if (chosen > minLag && chosen < maxLag) {
        val a = normalized[chosen - 1]
        val b = normalized[chosen]
        val c = normalized[chosen + 1]
        val denominator = a - 2.0 * b + c
        if (denominator > 1e-9) period += (a - c) / (2.0 * denominator)
    }
    if (period <= 0.0) return null
    return sampleRate / period
}

/** Индикатор отклонения в центах: цель в центре, зелёная зона ±10 центов. */
private class MeterView(context: Context) : View(context) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(42, 47, 54) }
    private val zonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 90, 200, 120) }
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(120, 128, 140) }
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(90, 200, 120) }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(150, 158, 168)
        textSize = 13f * resources.displayMetrics.density
        textAlign = Paint.Align.CENTER
    }

    var cents = 0f
        set(value) {
            field = value.coerceIn(-50f, 50f)
            invalidate()
        }

    var active = false
        set(value) {
            field = value
            invalidate()
        }

    private fun d(value: Int): Float = value * resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = d(16)
        val right = width - d(16)
        val cy = height * 0.42f
        val trackHeight = d(12)
        val track = RectF(left, cy - trackHeight / 2f, right, cy + trackHeight / 2f)
        canvas.drawRoundRect(track, trackHeight, trackHeight, trackPaint)

        val cx = (left + right) / 2f
        val half = (right - left) / 2f
        val zoneWidth = half * (IN_TUNE_CENTS / 50f)
        val zone = RectF(cx - zoneWidth, track.top - d(3), cx + zoneWidth, track.bottom + d(3))
        canvas.drawRoundRect(zone, trackHeight, trackHeight, zonePaint)
        canvas.drawLine(cx, cy - d(22), cx, cy + d(22), centerPaint)

        if (active) {
            val x = cx + half * (cents / 50f)
            needlePaint.color =
                if (abs(cents) <= IN_TUNE_CENTS) Color.rgb(90, 200, 120) else Color.rgb(230, 120, 90)
            canvas.drawCircle(x, cy, d(9), needlePaint)
        }

        val labelY = track.bottom + d(32)
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("-50", left, labelY, textPaint)
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("центы", cx, labelY, textPaint)
        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("+50", right, labelY, textPaint)
    }
}

class MainActivity : Activity() {

    private var targetMidi = 69 // Ля 4-й октавы, 440 Гц
    private var listening = false
    private var worker: Thread? = null

    private lateinit var targetText: TextView
    private lateinit var noteText: TextView
    private lateinit var freqText: TextView
    private lateinit var verdictText: TextView
    private lateinit var meter: MeterView
    private lateinit var toggleButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        updateTargetUi()
        setVerdictIdle()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopListening()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun makeLabel(sizeSp: Float, color: Int): TextView = TextView(this).apply {
        textSize = sizeSp
        setTextColor(color)
        gravity = Gravity.CENTER
    }

    private fun buildUi(): LinearLayout {
        val white = Color.WHITE
        val grey = Color.rgb(150, 158, 168)
        val accent = Color.rgb(90, 200, 120)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.rgb(16, 20, 24))
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }

        val title = makeLabel(26f, white).apply { text = "ZI-Voice" }
        val subtitle = makeLabel(14f, grey).apply { text = "Тренировка попадания в ноты" }

        targetText = makeLabel(20f, accent)
        noteText = makeLabel(72f, white)
        freqText = makeLabel(18f, grey)
        verdictText = makeLabel(18f, grey)

        meter = MeterView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(300), dp(110))
        }

        val targetRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val prevButton = makeSmallButton("\u25C0")
        val nextButton = makeSmallButton("\u25B6")
        prevButton.setOnClickListener { shiftTarget(-1) }
        nextButton.setOnClickListener { shiftTarget(1) }
        targetRow.addView(prevButton)
        targetRow.addView(nextButton)

        toggleButton = Button(this).apply {
            text = "Старт"
            textSize = 18f
        }
        val toggleParams = LinearLayout.LayoutParams(dp(240), ViewGroup.LayoutParams.WRAP_CONTENT)
        toggleParams.topMargin = dp(16)
        toggleButton.layoutParams = toggleParams
        toggleButton.setOnClickListener { if (listening) stopListening() else startListening() }

        root.addView(title)
        root.addView(subtitle)
        root.addView(
            targetText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(20) }
        )
        root.addView(targetRow)
        root.addView(
            noteText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        )
        root.addView(freqText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(meter)
        root.addView(verdictText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(toggleButton)
        return root
    }

    private fun makeSmallButton(text: String): Button = Button(this).apply {
        this.text = text
        textSize = 18f
        layoutParams = LinearLayout.LayoutParams(dp(72), dp(56)).apply {
            setMargins(dp(8), dp(12), dp(8), 0)
        }
    }

    private fun shiftTarget(delta: Int) {
        targetMidi = (targetMidi + delta).coerceIn(MIN_MIDI, MAX_MIDI)
        updateTargetUi()
    }

    private fun updateTargetUi() {
        targetText.text = "Цель: " + noteName(targetMidi) + " - " +
            String.format("%.1f", midiToFreq(targetMidi)) + " Гц"
    }

    private fun setVerdictIdle() {
        noteText.text = "-"
        freqText.text = ""
        verdictText.text = if (listening) "Слушаю..." else "Нажмите «Старт» и спойте целевую ноту"
        verdictText.setTextColor(Color.rgb(150, 158, 168))
        meter.active = false
        meter.cents = 0f
    }

    @SuppressLint("MissingPermission")
    private fun startListening() {
        if (listening) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
            return
        }

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            Toast.makeText(this, "Микрофон недоступен", Toast.LENGTH_SHORT).show()
            return
        }
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, 16384)
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            Toast.makeText(this, "Не удалось открыть микрофон", Toast.LENGTH_SHORT).show()
            return
        }

        listening = true
        toggleButton.text = "Стоп"
        setVerdictIdle()

        worker = Thread {
            try {
                record.startRecording()
                val buffer = ShortArray(4096)
                val signal = FloatArray(buffer.size)
                while (listening) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read <= 0) {
                        try {
                            Thread.sleep(20)
                        } catch (_: InterruptedException) {
                            return@Thread
                        }
                        continue
                    }
                    var sumSquares = 0.0
                    for (i in 0 until read) {
                        val v = buffer[i] / 32768f
                        signal[i] = v
                        sumSquares += v.toDouble() * v.toDouble()
                    }
                    val rms = Math.sqrt(sumSquares / read).toFloat()
                    val freq = if (rms >= RMS_SILENCE) detectPitch(signal, SAMPLE_RATE) else null
                    runOnUiThread { publishResult(freq, rms) }
                }
                try {
                    record.stop()
                } catch (_: IllegalStateException) {
                }
            } finally {
                record.release()
            }
        }.also { it.start() }
    }

    private fun stopListening() {
        if (!listening) return
        listening = false
        worker?.let { current ->
            try {
                current.join(800)
            } catch (_: InterruptedException) {
            }
        }
        worker = null
        toggleButton.text = "Старт"
        setVerdictIdle()
        verdictText.text = "Остановлено"
    }

    private fun publishResult(freq: Double?, rms: Float) {
        if (isFinishing || isDestroyed) return
        if (freq == null || rms < RMS_SILENCE) {
            noteText.text = "-"
            freqText.text = ""
            verdictText.text = "Слушаю..."
            verdictText.setTextColor(Color.rgb(150, 158, 168))
            meter.active = false
            return
        }

        val midiFloat = 69.0 + 12.0 * ln(freq / 440.0) / ln(2.0)
        val centsToTarget = ((midiFloat - targetMidi) * 100.0).toFloat()
        val nearestMidi = Math.round(midiFloat).toInt()

        meter.active = true
        meter.cents = centsToTarget
        noteText.text = noteName(nearestMidi)
        freqText.text = String.format("%.1f Гц", freq)

        when {
            abs(centsToTarget) <= IN_TUNE_CENTS -> {
                verdictText.text = "Попадание!"
                verdictText.setTextColor(Color.rgb(90, 200, 120))
            }
            abs(centsToTarget) <= 25f -> {
                verdictText.text = "Почти в цель"
                verdictText.setTextColor(Color.rgb(240, 190, 90))
            }
            centsToTarget < 0f -> {
                verdictText.text = "Ниже цели - подтянись выше"
                verdictText.setTextColor(Color.rgb(230, 120, 90))
            }
            else -> {
                verdictText.text = "Выше цели - опустись ниже"
                verdictText.setTextColor(Color.rgb(230, 120, 90))
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MIC &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            startListening()
        } else {
            Toast.makeText(this, "Нужно разрешение на микрофон", Toast.LENGTH_SHORT).show()
        }
    }
}
