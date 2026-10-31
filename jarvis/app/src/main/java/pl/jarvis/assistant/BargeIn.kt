package pl.jarvis.assistant

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.util.Log
import pl.jarvis.recorder.Recordings
import pl.jarvis.recorder.Transcriber
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * „Stop” w trakcie mówienia: gdy Jarvis mówi, słuchamy mikrofonem z wycinaniem echa i co chwilę
 * przepuszczamy ostatnie 1,5 s przez polski model mowy w telefonie (bez internetu). Słowo stop
 * („stop”, „dość”, „przestań”, „cisza”, „wystarczy”) – Jarvis milknie.
 */
object BargeIn {
    private const val TAG = "JarvisBargeIn"
    private const val RATE = Recordings.SAMPLE_RATE
    private const val WINDOW = RATE * 3 / 2 // ostatnie 1,5 s
    private const val STEP_MS = 600L
    private const val MIN_DB = -48.0 // ciszej – nikt nic nie mówi, nie męczymy procesora

    private val STOP = Regex("\\b(stop|stoj|dosc|przestan|cisza|wystarczy|zamknij sie|starczy)\\b")

    @Volatile
    private var thread: Thread? = null

    /** Zaczyna słuchać; [speaking] – tekst, który Jarvis właśnie mówi (jego własne „stop” nie liczy się). */
    fun start(context: Context, speaking: () -> String, onStop: () -> Unit) {
        stop()
        val ctx = context.applicationContext
        if (!Transcriber.isDownloaded(ctx)) return
        thread = Thread({ listen(ctx, speaking, onStop) }, "jarvis-barge-in").apply { start() }
    }

    /** Kończy słuchanie i oddaje mikrofon (czeka chwilę na zamknięcie). */
    fun stop() {
        val t = thread ?: return
        thread = null
        t.interrupt()
        runCatching { t.join(300) }
    }

    @SuppressLint("MissingPermission") // mikrofon jest nadany – bez niego Jarvis w ogóle nie słucha
    private fun listen(context: Context, speaking: () -> String, onStop: () -> Unit) {
        val me = Thread.currentThread()
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, RATE))
        }.getOrNull()?.takeIf { it.state == AudioRecord.STATE_INITIALIZED } ?: return
        val aec = if (AcousticEchoCanceler.isAvailable()) runCatching { AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true } }.getOrNull() else null
        val ring = ShortArray(WINDOW)
        var filled = 0
        val chunk = ShortArray(RATE / 10)
        var lastCheck = System.currentTimeMillis()
        try {
            record.startRecording()
            while (thread === me && !me.isInterrupted) {
                val n = record.read(chunk, 0, chunk.size)
                if (n <= 0) continue
                // Przesuwamy okno: najstarsze próbki wypadają z przodu.
                if (filled + n > WINDOW) {
                    val drop = filled + n - WINDOW
                    System.arraycopy(ring, drop, ring, 0, filled - drop)
                    filled -= drop
                }
                System.arraycopy(chunk, 0, ring, filled, n)
                filled += n
                if (System.currentTimeMillis() - lastCheck < STEP_MS || filled < RATE / 2) continue
                lastCheck = System.currentTimeMillis()
                val window = ring.copyOf(filled)
                if (db(window) < MIN_DB) continue
                val heard = Transcriber.quickDecode(context, window, 400) ?: continue
                Log.d(TAG, "słyszę (%.0f dB): %s".format(db(window), heard))
                val words = PhoneTools.normalize(heard)
                val match = STOP.find(words)?.value ?: continue
                // Własny głos Jarvisa z głośnika: jeśli to słowo jest w tym, co mówi – pomijamy.
                if (PhoneTools.normalize(speaking()).contains(match)) continue
                Log.d(TAG, "usłyszałem „$heard” – przerywam")
                if (thread === me) PhoneTools.main.post(onStop)
                break
            }
        } catch (e: Exception) {
            Log.w(TAG, "nasłuch stop: ${e.message}")
        } finally {
            runCatching { record.stop() }
            record.release()
            aec?.release()
        }
    }

    private fun db(samples: ShortArray): Double {
        var sum = 0.0
        for (s in samples) sum += s.toDouble() * s
        val rms = sqrt(sum / samples.size.coerceAtLeast(1)) / 32768.0
        return 20 * log10(rms.coerceAtLeast(1e-9))
    }
}
