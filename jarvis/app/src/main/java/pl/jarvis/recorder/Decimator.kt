package pl.jarvis.recorder

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Zmniejsza częstotliwość próbkowania o całkowitą krotność (np. 48 kHz → 16 kHz).
 *
 * Mikrofon nagrywa w swojej natywnej jakości (tak jak Chrome), a my sami
 * przechodzimy na 16 kHz potrzebne do rozpoznawania mowy. Przed zmniejszeniem
 * odcinamy wysokie tony filtrem dolnoprzepustowym, inaczej zamieniłyby się w szum.
 */
class Decimator(private val factor: Int) {
    private val taps = if (factor > 1) lowPassTaps(factor) else FloatArray(0)
    private val history = FloatArray(taps.size)
    private var position = 0
    private var phase = 0

    /** Zwraca próbki po zmniejszeniu; stan filtra przechodzi między kolejnymi wywołaniami. */
    fun process(input: ShortArray, count: Int): ShortArray {
        if (factor == 1) return input.copyOf(count)
        val output = ShortArray((count + phase) / factor)
        var o = 0
        for (i in 0 until count) {
            history[position] = input[i].toFloat()
            position = (position + 1) % history.size
            if (++phase == factor) {
                phase = 0
                var acc = 0f
                var h = position
                for (t in taps.indices) {
                    acc += taps[t] * history[h]
                    h = (h + 1) % history.size
                }
                if (o < output.size) output[o++] = acc.roundToInt().coerceIn(-32768, 32767).toShort()
            }
        }
        return if (o == output.size) output else output.copyOf(o)
    }

    private companion object {
        const val TAP_COUNT = 63

        /** Filtr okienkowany (sinc + okno Hamminga) z odcięciem nieco poniżej nowego Nyquista. */
        fun lowPassTaps(factor: Int): FloatArray {
            val cutoff = 0.45 / factor // w ułamku częstotliwości próbkowania wejścia
            val middle = (TAP_COUNT - 1) / 2.0
            val taps = DoubleArray(TAP_COUNT) { n ->
                val x = n - middle
                val sinc = if (x == 0.0) 2 * cutoff else sin(2 * PI * cutoff * x) / (PI * x)
                val window = 0.54 - 0.46 * cos(2 * PI * n / (TAP_COUNT - 1))
                sinc * window
            }
            val sum = taps.sum()
            return FloatArray(TAP_COUNT) { (taps[it] / sum).toFloat() }
        }
    }
}
