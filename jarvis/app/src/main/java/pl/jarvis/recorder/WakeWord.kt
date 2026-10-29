package pl.jarvis.recorder

import ai.picovoice.porcupine.Porcupine
import android.content.Context
import java.io.Closeable

/**
 * Wykrywa słowo „Jarvis” (Porcupine, działa na telefonie). Dostaje ten sam dźwięk 16 kHz,
 * który nagrywa rejestrator, więc nie zajmuje mikrofonu osobno.
 */
class WakeWord(context: Context, accessKey: String, sensitivity: Float = 0.6f) : Closeable {
    private val porcupine = Porcupine.Builder()
        .setAccessKey(accessKey)
        .setKeyword(Porcupine.BuiltInKeyword.JARVIS)
        .setSensitivity(sensitivity)
        .build(context)
    private val frame = ShortArray(porcupine.frameLength)
    private var filled = 0

    /** Zwraca true, jeśli w tych próbkach padło słowo „Jarvis”. */
    fun process(samples: ShortArray): Boolean {
        var detected = false
        for (sample in samples) {
            frame[filled++] = sample
            if (filled == frame.size) {
                filled = 0
                if (porcupine.process(frame) >= 0) detected = true
            }
        }
        return detected
    }

    override fun close() = porcupine.delete()
}
