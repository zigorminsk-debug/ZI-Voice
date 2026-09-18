package com.zigorminsk.zivoice

/** Одна нота упражнения: абсолютный MIDI-номер и длительность в долях. */
data class NoteStep(val midi: Int, val beats: Int = 1)

// Общие константы аудиотракта (используются детектором, графиком и активностью)
internal const val SAMPLE_RATE = 44100
internal const val CHUNK = 2048

/** Упражнение: последовательность нот в заданном темпе. */
data class Exercise(val name: String, val bpm: Int, val steps: List<NoteStep>) {
    val beatSec: Float get() = 60f / bpm
    val totalSec: Float get() = steps.sumOf { it.beats } * (60.0 / bpm).toFloat()
}

fun midiToFreq(midi: Int): Double = 440.0 * Math.pow(2.0, (midi - 69) / 12.0)

private val NOTE_NAMES = arrayOf(
    "До", "До#", "Ре", "Ре#", "Ми", "Фа",
    "Фа#", "Соль", "Соль#", "Ля", "Ля#", "Си"
)

fun noteName(midi: Int): String = NOTE_NAMES[midi % 12] + (midi / 12 - 1)

/**
 * Библиотека упражнений. Все мелодии — учебные последовательности
 * (гаммы, арпеджио, интервалы), легко расширяется новыми элементами.
 */
object ExerciseLibrary {

    private fun scale(name: String, base: Int): Exercise {
        val up = (0..7).map { NoteStep(base + it) }
        val down = (6 downTo 0).map { NoteStep(base + it) }
        return Exercise(name, 95, up + down)
    }

    private fun arpeggio(name: String, base: Int): Exercise =
        Exercise(name, 80, listOf(0, 4, 7, 12, 7, 4, 0).map { NoteStep(base + it, 2) })

    private fun fifths(name: String, base: Int): Exercise {
        val steps = ArrayList<NoteStep>()
        for (i in 0..4) {
            steps.add(NoteStep(base + i))
            steps.add(NoteStep(base + i + 7))
        }
        steps.add(NoteStep(base, 2))
        return Exercise(name, 90, steps)
    }

    private fun octaves(name: String, base: Int): Exercise {
        val steps = ArrayList<NoteStep>()
        for (i in 0..4 step 2) {
            steps.add(NoteStep(base + i))
            steps.add(NoteStep(base + i + 12))
        }
        steps.add(NoteStep(base, 2))
        return Exercise(name, 80, steps)
    }

    private fun chromatic(name: String, base: Int): Exercise {
        val up = (0..7).map { NoteStep(base + it) }
        val down = (6 downTo 0).map { NoteStep(base + it) }
        return Exercise(name, 110, up + down)
    }

    val all: List<Exercise> = listOf(
        scale("Гамма До-мажор", 60),
        scale("Гамма Ля-минор", 57),
        arpeggio("Арпеджио До-мажор", 60),
        arpeggio("Арпеджио Ля-минор", 57),
        fifths("Интервалы: квинты", 60),
        octaves("Скачки на октаву", 60),
        chromatic("Хроматика (полутоны)", 60)
    )
}
