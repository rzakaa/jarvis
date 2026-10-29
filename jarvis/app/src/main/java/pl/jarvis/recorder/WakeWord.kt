package pl.jarvis.recorder

import android.content.Context
import java.io.Closeable

/**
 * Wykrywa słowo „Hey Jarvis” (openWakeWord, działa na telefonie, bez konta i internetu).
 * Dostaje ten sam dźwięk 16 kHz, który nagrywa rejestrator, więc nie zajmuje mikrofonu osobno.
 */
class WakeWord(context: Context, val threshold: Float) : Closeable {
    private val detector = OpenWakeWord(
        asset(context, "melspectrogram.onnx"),
        asset(context, "embedding_model.onnx"),
        asset(context, "hey_jarvis_v0.1.onnx"),
    )
    private var lastDetection = 0L

    /** Ostatni wynik detektora (0..1) – do podglądu na ekranie. */
    var lastScore = 0f
        private set

    /** Zwraca true, jeśli w tych próbkach padło „Hey Jarvis” (najwyżej raz na 2 s). */
    fun process(samples: ShortArray): Boolean {
        val score = detector.process(samples)
        if (score > 0f || samples.size >= OpenWakeWord.CHUNK) lastScore = score
        val now = System.currentTimeMillis()
        if (score >= threshold && now - lastDetection > 2000) {
            lastDetection = now
            return true
        }
        return false
    }

    /** Po wznowieniu nasłuchu: czyste bufory i jeszcze ok. 1 s bez wykrywania (cichnie echo głosu Jarvisa). */
    fun reset() {
        detector.reset()
        lastScore = 0f
        lastDetection = System.currentTimeMillis() - 1000
    }

    override fun close() = detector.close()

    private companion object {
        fun asset(context: Context, name: String) = context.assets.open("wakeword/$name").use { it.readBytes() }
    }
}
