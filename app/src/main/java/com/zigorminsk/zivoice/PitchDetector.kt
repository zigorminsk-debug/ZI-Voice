package com.zigorminsk.zivoice

/**
 * Определяет частоту основного тона по методу YIN
 * (de Cheveigné & Kawahara, 2002): разностная функция с
 * кумулятивной нормализацией + параболическая интерполяция.
 */
object PitchDetector {

    /**
     * @param x сигнал (значения -1..1)
     * @return частота в Гц или null, если уверенного тона нет
     */
    fun detect(x: FloatArray, sampleRate: Int, window: Int = 2048): Double? {
        val maxLag = sampleRate / 60      // нижняя граница поиска: 60 Гц
        val minLag = sampleRate / 1400    // верхняя граница поиска: 1400 Гц
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
}
