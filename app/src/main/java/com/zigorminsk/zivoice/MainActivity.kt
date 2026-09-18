package com.zigorminsk.zivoice

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin

private const val REQUEST_MIC = 2
private const val RMS_SILENCE = 0.008f
private const val COUNTDOWN_BEATS = 4
private const val TAIL_SEC = 0.9f
private const val OCTAVE_CENTS = 600f

/** Размер буфера анализа: окно YIN + максимальный лаг (SAMPLE_RATE/60). */
private val ANALYSIS_SIZE = CHUNK + SAMPLE_RATE / 60

class MainActivity : Activity() {

    private var exerciseIndex = 0
    @Volatile private var running = false
    @Volatile private var manualStop = false
    @Volatile private var refPlaying = false

    private var micThread: Thread? = null
    private var audioThread: Thread? = null
    @Volatile private var audioTrack: AudioTrack? = null
    @Volatile private var audioRecord: AudioRecord? = null

    // Разметка текущего запуска (для графика и подсчёта результатов)
    private var runStarts = FloatArray(0)
    private var runEnds = FloatArray(0)
    private var runNames = emptyArray<String>()
    private var runCountdownSec = 0f
    private var graphPoints: FloatArray = FloatArray(0)

    private lateinit var exerciseName: TextView
    private lateinit var currentNoteText: TextView
    private lateinit var hintText: TextView
    private lateinit var statusText: TextView
    private lateinit var resultText: TextView
    private lateinit var graph: PitchGraphView
    private lateinit var listenButton: Button
    private lateinit var startButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        showExercise()
        showLastCrashIfAny()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopEverything()
    }

    // ---------- UI ----------

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun label(sizeSp: Float, color: Int): TextView = TextView(this).apply {
        textSize = sizeSp
        setTextColor(color)
        gravity = Gravity.CENTER
    }

    private fun grey(): Int = Color.rgb(150, 158, 168)

    private fun buildUi(): LinearLayout {
        val white = Color.WHITE
        val grey = grey()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.rgb(16, 20, 24))
            setPadding(dp(16), dp(16), dp(16), dp(12))
            keepScreenOn = true
        }

        val title = label(22f, white).apply { text = "ZI-Voice" }
        val subtitle = label(13f, grey).apply {
            text = "Линия выше центра — пойте выше, ниже центра — пойте ниже"
        }

        exerciseName = label(16f, white)

        val selectorRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val prev = smallButton("◀")
        val next = smallButton("▶")
        prev.setOnClickListener {
            if (!running) {
                exerciseIndex =
                    (exerciseIndex + ExerciseLibrary.all.size - 1) % ExerciseLibrary.all.size
                showExercise()
            }
        }
        next.setOnClickListener {
            if (!running) {
                exerciseIndex = (exerciseIndex + 1) % ExerciseLibrary.all.size
                showExercise()
            }
        }
        selectorRow.addView(prev)
        selectorRow.addView(
            exerciseName,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        selectorRow.addView(next)

        currentNoteText = label(44f, white)
        hintText = label(18f, grey)

        graph = PitchGraphView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(240)
            ).apply { topMargin = dp(10) }
        }

        statusText = label(14f, grey)
        resultText = label(15f, white)

        val buttonsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        listenButton = Button(this).apply { text = "♪ Прослушать" }
        startButton = Button(this).apply { text = "● Старт" }
        listenButton.setOnClickListener { onListenClicked() }
        startButton.setOnClickListener { onStartClicked() }
        buttonsRow.addView(listenButton)
        buttonsRow.addView(startButton)

        root.addView(title)
        root.addView(subtitle)
        root.addView(selectorRow)
        root.addView(
            currentNoteText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        )
        root.addView(hintText)
        root.addView(graph)
        root.addView(
            statusText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        )
        root.addView(resultText)
        root.addView(
            buttonsRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        )
        return root
    }

    private fun smallButton(text: String): Button = Button(this).apply {
        this.text = text
        textSize = 16f
        layoutParams = LinearLayout.LayoutParams(dp(64), dp(52))
    }

    private fun showExercise() {
        val ex = ExerciseLibrary.all[exerciseIndex]
        exerciseName.text = ex.name
        val notes = ex.steps.joinToString(" ") { noteName(it.midi) }
        statusText.text = "Ноты: $notes"
        resultText.text = ""
        currentNoteText.text = "—"
        hintText.text = "«Прослушать» — эталон, «Старт» — пойте сами"
        hintText.setTextColor(grey())
        graph.configure(FloatArray(0), FloatArray(0), emptyArray(), 0f, 0f, 0f)
        graph.setPoints(FloatArray(0), 0)
    }

    /** Показать причину прошлого сбоя (если был). */
    private fun showLastCrashIfAny() {
        val text = try {
            openFileInput("last_crash.txt").bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            null
        } ?: return
        AlertDialog.Builder(this)
            .setTitle("Прошлый сбой")
            .setMessage(text.take(1400))
            .setPositiveButton("Понятно") { _, _ -> deleteFile("last_crash.txt") }
            .setCancelable(true)
            .show()
    }

    // ---------- Прослушивание эталона ----------

    private fun onListenClicked() {
        if (refPlaying) {
            stopAudioPlayback()
            return
        }
        if (running) stopRun()
        val ex = ExerciseLibrary.all[exerciseIndex]
        refPlaying = true
        listenButton.text = "■ Стоп"
        statusText.text = "Прослушивание: ${ex.name}"
        audioThread = Thread {
            val pcm = renderExerciseAudio(ex, withNotes = true, tickAmp = 0.30f)
            playPcm(pcm)
            runOnUiThread {
                refPlaying = false
                if (isDestroyed || isFinishing) return@runOnUiThread
                listenButton.text = "♪ Прослушать"
                statusText.text = "Теперь пойте сами — нажмите «Старт»"
            }
        }.also { it.start() }
    }

    // ---------- Запись тренировки ----------

    private fun onStartClicked() {
        if (running) stopRun() else startRun()
    }

    @SuppressLint("MissingPermission")
    private fun startRun() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
            return
        }
        stopAudioPlayback()

        val ex = ExerciseLibrary.all[exerciseIndex]
        val beat = ex.beatSec
        val countdownSec = COUNTDOWN_BEATS * beat
        val totalSec = countdownSec + ex.totalSec + TAIL_SEC
        val chunkSec = CHUNK.toFloat() / SAMPLE_RATE
        val maxPoints = (totalSec / chunkSec).toInt() + 4
        val points = FloatArray(maxPoints) { Float.NaN }
        graphPoints = points

        val n = ex.steps.size
        runStarts = FloatArray(n)
        runEnds = FloatArray(n)
        runNames = arrayOfNulls<String>(n).requireNoNulls()
        var t = countdownSec
        for (i in 0 until n) {
            runStarts[i] = t
            runNames[i] = noteName(ex.steps[i].midi)
            t += ex.steps[i].beats * beat
            runEnds[i] = t
        }
        runCountdownSec = countdownSec

        graph.configure(
            runStarts, runEnds, runNames,
            countdownSec, countdownSec + ex.totalSec, totalSec
        )
        resultText.text = ""
        statusText.text = "Идёт запись…"
        startButton.text = "■ Стоп"
        listenButton.isEnabled = false

        // Флаг ставим сразу — защита от двойного нажатия
        running = true
        manualStop = false

        micThread = Thread { runSession(ex, points, maxPoints, chunkSec) }.also { it.start() }
    }

    /** Весь аудио-путь в фоновом потоке: создание, запись, обработка. */
    @SuppressLint("MissingPermission")
    private fun runSession(ex: Exercise, points: FloatArray, maxPoints: Int, chunkSec: Float) {
        var record: AudioRecord? = null
        var idx = 0
        try {
            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuffer <= 0) {
                runOnUiThread { toastAndReset("Микрофон недоступен") }
                return
            }
            val rec = AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, CHUNK * 4)
            )
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                runOnUiThread { toastAndReset("Микрофон занят другим приложением") }
                return
            }
            record = rec
            audioRecord = rec

            // Тихий метроном: громкие отсчёты + тихие доли мелодии
            if (running) {
                val monitorPcm = renderExerciseAudio(ex, withNotes = false, tickAmp = 0.13f)
                audioThread = Thread { playPcm(monitorPcm) }.also { it.start() }
            }

            rec.startRecording()

            val buffer = ShortArray(CHUNK)
            val analysis = FloatArray(ANALYSIS_SIZE) // перекрывающийся буфер
            val lastMidi = FloatArray(3) { Float.NaN }

            while (running && idx < maxPoints) {
                val read = rec.read(buffer, 0, CHUNK)
                if (read <= 0) {
                    try {
                        Thread.sleep(15)
                    } catch (_: InterruptedException) {
                        break
                    }
                    continue
                }
                var sumSquares = 0.0
                // сдвигаем перекрытие и дописываем новые сэмплы
                System.arraycopy(analysis, read, analysis, 0, ANALYSIS_SIZE - read)
                for (i in 0 until read) {
                    val v = buffer[i] / 32768f
                    analysis[ANALYSIS_SIZE - read + i] = v
                    sumSquares += v.toDouble() * v.toDouble()
                }
                val rms = Math.sqrt(sumSquares / read).toFloat()
                val freq =
                    if (rms >= RMS_SILENCE) PitchDetector.detect(analysis, SAMPLE_RATE) else null

                // Медиана трёх последних оценок — убирает скачки октавы
                if (freq != null) {
                    val midi = (69.0 + 12.0 * ln(freq / 440.0) / ln(2.0)).toFloat()
                    lastMidi[0] = lastMidi[1]
                    lastMidi[1] = lastMidi[2]
                    lastMidi[2] = midi
                } else {
                    lastMidi[0] = Float.NaN
                    lastMidi[1] = Float.NaN
                    lastMidi[2] = Float.NaN
                }
                val med = median3(lastMidi)

                val tNow = (idx + 1) * chunkSec
                val tMelody = tNow - runCountdownSec
                val target = targetAt(tMelody, ex)
                points[idx] = if (!med.isNaN() && target != null) {
                    ((med - target) * 100).toFloat()
                } else Float.NaN

                val devNow = points[idx]
                val targetNow = target
                val snapshot = idx + 1
                runOnUiThread { updateLive(tMelody, targetNow, devNow, snapshot) }
                idx++
                if (tMelody > ex.totalSec) break
            }
        } catch (e: SecurityException) {
            runOnUiThread { toastAndReset("Нет разрешения на микрофон") }
        } catch (e: Exception) {
            runOnUiThread { toastAndReset("Ошибка микрофона: " + e.javaClass.simpleName) }
        } finally {
            record?.let {
                try {
                    it.stop()
                } catch (_: IllegalStateException) {
                }
                try {
                    it.release()
                } catch (_: Exception) {
                }
            }
            audioRecord = null
        }
        val captured = points
        val starts = runStarts
        val ends = runEnds
        val names = runNames
        val countdown = runCountdownSec
        runOnUiThread { finishRun(captured, idx, starts, ends, names, countdown, ex) }
    }

    private fun targetAt(tMelody: Float, ex: Exercise): Int? {
        if (tMelody < 0 || tMelody >= ex.totalSec) return null
        var t = 0f
        for (step in ex.steps) {
            t += step.beats * ex.beatSec
            if (tMelody < t) return step.midi
        }
        return null
    }

    private fun median3(v: FloatArray): Float {
        if (v[0].isNaN() || v[1].isNaN() || v[2].isNaN()) return Float.NaN
        val s = v.sorted()
        return s[1]
    }

    private fun updateLive(tMelody: Float, target: Int?, dev: Float, snapshot: Int) {
        if (isDestroyed || isFinishing) return
        val ex = ExerciseLibrary.all[exerciseIndex]
        graph.setPoints(graphPoints, snapshot)
        currentNoteText.text = when {
            tMelody < 0 -> "Приготовьтесь… ${ceil(-tMelody / ex.beatSec).toInt()}"
            target != null -> noteName(target)
            else -> "Финиш"
        }
        when {
            dev.isNaN() -> {
                hintText.text =
                    if (tMelody in 0f..ex.totalSec) "Пойте ♪" else ""
                hintText.setTextColor(grey())
            }
            abs(dev) > OCTAVE_CENTS -> {
                hintText.text = "Другая октава? Сверьтесь с эталоном"
                hintText.setTextColor(Color.rgb(170, 130, 235))
            }
            abs(dev) <= PitchGraphView.IN_TUNE_CENTS -> {
                hintText.text = "В цель ✓"
                hintText.setTextColor(Color.rgb(90, 200, 120))
            }
            dev < 0 -> {
                hintText.text = "Выше ↑"
                hintText.setTextColor(Color.rgb(240, 190, 90))
            }
            else -> {
                hintText.text = "Ниже ↓"
                hintText.setTextColor(Color.rgb(240, 190, 90))
            }
        }
    }

    private fun finishRun(
        points: FloatArray,
        count: Int,
        starts: FloatArray,
        ends: FloatArray,
        names: Array<String>,
        countdownSec: Float,
        ex: Exercise
    ) {
        if (isDestroyed || isFinishing) return
        running = false
        startButton.text = "● Старт"
        listenButton.isEnabled = true
        if (manualStop) {
            manualStop = false
            hintText.text = ""
            return
        }
        currentNoteText.text = "Готово"
        hintText.text = ""

        val chunkSec = CHUNK.toFloat() / SAMPLE_RATE
        val perNoteVoiced = IntArray(starts.size)
        val perNoteInTune = IntArray(starts.size)
        val perNoteDevSum = FloatArray(starts.size)
        var voicedAll = 0
        var inTuneAll = 0
        for (i in 0 until count) {
            val d = points[i]
            if (d.isNaN()) continue
            val tMelody = (i + 1) * chunkSec - countdownSec
            for (s in starts.indices) {
                if (tMelody >= starts[s] && tMelody < ends[s]) {
                    perNoteVoiced[s]++
                    perNoteDevSum[s] += d
                    if (abs(d) <= PitchGraphView.IN_TUNE_CENTS) {
                        perNoteInTune[s]++
                        inTuneAll++
                    }
                    voicedAll++
                    break
                }
            }
        }
        if (voicedAll < 10) {
            resultText.text = "Голос не услышан. Пойте громче, ближе к микрофону."
            resultText.setTextColor(Color.rgb(230, 120, 90))
            statusText.text = "«Старт» — попробовать снова"
            return
        }
        val accuracy = inTuneAll * 100 / voicedAll
        val weak = (0 until starts.size)
            .map { s ->
                Triple(
                    names[s],
                    if (perNoteVoiced[s] > 0) perNoteDevSum[s] / perNoteVoiced[s] else 0f,
                    perNoteVoiced[s]
                )
            }
            .filter { it.third >= 3 && abs(it.second) >= 20f }
            .sortedByDescending { abs(it.second) }
            .take(3)
        resultText.setTextColor(
            when {
                accuracy >= 85 -> Color.rgb(90, 200, 120)
                accuracy >= 60 -> Color.rgb(240, 190, 90)
                else -> Color.rgb(230, 120, 90)
            }
        )
        resultText.text = if (weak.isEmpty()) {
            "Точность: $accuracy% — отлично! 🎯"
        } else {
            "Точность: $accuracy%. Слабые ноты: " + weak.joinToString(", ") { w ->
                "${w.first} (${if (w.second > 0) "+" else ""}${w.second.roundToInt()}¢)"
            }
        }
        statusText.text = "«Старт» — ещё раз, ◀ ▶ — другое упражнение"
    }

    // ---------- Синтез звука ----------

    /**
     * PCM всего запуска: отсчёт (4 громких тика), затем ноты эталона
     * (если withNotes) и тик на каждую долю с громкостью tickAmp.
     */
    private fun renderExerciseAudio(ex: Exercise, withNotes: Boolean, tickAmp: Float): ShortArray {
        val beat = ex.beatSec
        val countdownSec = COUNTDOWN_BEATS * beat
        val totalSec = countdownSec + ex.totalSec + 0.3f
        val out = ShortArray((totalSec * SAMPLE_RATE).toInt())

        fun addTick(atSec: Float, freq: Double, dur: Float, amp: Float) {
            val start = (atSec * SAMPLE_RATE).toInt()
            val len = (dur * SAMPLE_RATE).toInt()
            for (i in 0 until len) {
                val pos = start + i
                if (pos >= out.size) break
                val env =
                    if (i < len * 0.1f) i / (len * 0.1f)
                    else 1f - (i - len * 0.1f) / (len * 0.9f)
                val v = sin(2.0 * Math.PI * freq * i / SAMPLE_RATE) * amp * env
                out[pos] = (out[pos] + (v * Short.MAX_VALUE)).toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
        }

        fun addNote(atSec: Float, midi: Int, durSec: Float) {
            val start = (atSec * SAMPLE_RATE).toInt()
            val len = (durSec * SAMPLE_RATE).toInt()
            val f = midiToFreq(midi)
            val attack = (0.012f * SAMPLE_RATE).toInt().coerceAtLeast(1)
            val release = (0.03f * SAMPLE_RATE).toInt().coerceAtLeast(1)
            for (i in 0 until len) {
                val pos = start + i
                if (pos >= out.size) break
                val env = when {
                    i < attack -> i.toFloat() / attack
                    i > len - release -> (len - i).toFloat() / release
                    else -> 1f
                }
                val v = 0.22f * env / 1.47 * (
                    sin(2.0 * Math.PI * f * i / SAMPLE_RATE) +
                        0.35 * sin(2.0 * Math.PI * 2 * f * i / SAMPLE_RATE) +
                        0.12 * sin(2.0 * Math.PI * 3 * f * i / SAMPLE_RATE)
                    )
                out[pos] = (out[pos] + (v * Short.MAX_VALUE)).toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
        }

        for (b in 0 until COUNTDOWN_BEATS) addTick(b * beat, 1100.0, 0.07f, 0.35f)

        var t = countdownSec
        for (step in ex.steps) {
            val dur = step.beats * beat
            for (b in 0 until step.beats) addTick(t + b * beat, 750.0, 0.05f, tickAmp)
            if (withNotes) addNote(t, step.midi, dur - 0.04f)
            t += dur
        }
        return out
    }

    private fun playPcm(pcm: ShortArray) {
        var track: AudioTrack? = null
        try {
            val minBuf = AudioTrack.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            track = AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
                maxOf(minBuf, 16384),
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )
            audioTrack = track
            track.play()
            track.write(pcm, 0, pcm.size)
            while (audioTrack === track && track.playbackHeadPosition < pcm.size) {
                Thread.sleep(60)
            }
        } catch (_: Exception) {
            // воспроизведение необязательно — не даём упасть потоку
        } finally {
            if (audioTrack === track) audioTrack = null
            track?.let {
                try {
                    it.stop()
                } catch (_: IllegalStateException) {
                }
                try {
                    it.release()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun stopAudioPlayback() {
        audioTrack?.let { track ->
            audioTrack = null
            try {
                track.stop()
            } catch (_: IllegalStateException) {
            }
        }
        audioThread?.interrupt()
        audioThread = null
    }

    private fun stopRun() {
        manualStop = running
        running = false
        stopAudioPlayback()
        audioRecord?.let { rec ->
            try {
                rec.stop()
            } catch (_: IllegalStateException) {
            }
        }
        startButton.text = "● Старт"
        listenButton.isEnabled = true
        statusText.text = "Остановлено"
        currentNoteText.text = "—"
        hintText.text = ""
    }

    private fun stopEverything() {
        running = false
        stopAudioPlayback()
        audioRecord?.let { rec ->
            try {
                rec.stop()
            } catch (_: IllegalStateException) {
            }
        }
        audioRecord = null
    }

    private fun toastAndReset(message: String) {
        running = false
        manualStop = false
        startButton.text = "● Старт"
        listenButton.isEnabled = true
        statusText.text = message
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
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
            startRun()
        } else {
            Toast.makeText(this, "Нужно разрешение на микрофон", Toast.LENGTH_SHORT).show()
        }
    }
}
