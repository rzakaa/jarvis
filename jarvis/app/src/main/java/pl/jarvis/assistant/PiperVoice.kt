package pl.jarvis.assistant

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.util.concurrent.Executors

/**
 * Głos Jarvisa: Piper pl_PL-bass-high (sherpa-onnx), działa w telefonie bez internetu.
 * Model (~130 MB) pobiera się raz, na żądanie z ustawień.
 */
object PiperVoice {
    enum class Status { NOT_DOWNLOADED, DOWNLOADING, LOADING, READY, ERROR }

    private const val VOICE = "vits-piper-pl_PL-bass-high"
    private const val URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$VOICE.tar.bz2"
    private const val MODEL = "pl_PL-bass-high.onnx"

    val status = MutableStateFlow(Status.NOT_DOWNLOADED)
    val progress = MutableStateFlow(0f)
    val error = MutableStateFlow<String?>(null)

    val isReady: Boolean get() = status.value == Status.READY

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var tts: OfflineTts? = null

    /** Numer bieżącej wypowiedzi – nowa wypowiedź albo stop() unieważnia poprzednią. */
    @Volatile
    private var generation = 0

    @Volatile
    private var track: AudioTrack? = null

    private fun dir(context: Context) = File(context.filesDir, "voices/$VOICE")

    private fun isDownloaded(context: Context) =
        File(dir(context), MODEL).exists() && File(dir(context), "tokens.txt").exists()

    /** Przy starcie aplikacji: wczytuje głos, jeśli jest już pobrany. */
    fun prepare(context: Context) {
        val ctx = context.applicationContext
        if (tts != null || status.value == Status.LOADING || status.value == Status.DOWNLOADING) return
        if (isDownloaded(ctx)) worker.execute { load(ctx) } else status.value = Status.NOT_DOWNLOADED
    }

    fun download(context: Context) {
        val ctx = context.applicationContext
        if (status.value == Status.DOWNLOADING) return
        status.value = Status.DOWNLOADING
        progress.value = 0f
        error.value = null
        worker.execute {
            try {
                VoiceDownloader.downloadAndExtract(URL, dir(ctx)) { progress.value = it }
                load(ctx)
            } catch (e: Exception) {
                error.value = e.message
                status.value = Status.ERROR
            }
        }
    }

    private fun load(context: Context) {
        status.value = Status.LOADING
        try {
            val d = dir(context).path
            tts = OfflineTts(
                config = OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        vits = OfflineTtsVitsModelConfig(
                            model = "$d/$MODEL",
                            tokens = "$d/tokens.txt",
                            dataDir = "$d/espeak-ng-data",
                            lengthScale = 1.0f,
                        ),
                        numThreads = 2,
                        debug = false,
                        provider = "cpu",
                    ),
                    // Po jednym zdaniu – pierwsze zdanie gra, zanim reszta się wygeneruje.
                    maxNumSentences = 1,
                ),
            )
            status.value = Status.READY
        } catch (e: Throwable) {
            error.value = e.message ?: e.javaClass.simpleName
            status.value = Status.ERROR
        }
    }

    /** Mówi [text]; [onDone] woła na głównym wątku po zakończeniu (albo przerwaniu). */
    fun speak(text: String, onDone: () -> Unit) {
        val gen = ++generation
        worker.execute {
            if (gen != generation) return@execute
            val engine = tts
            if (engine == null) {
                main.post(onDone)
                return@execute
            }
            val out = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(engine.sampleRate())
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(engine.sampleRate() * 4) // ~1 s zapasu
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track = out
            var written = 0L
            try {
                out.play()
                engine.generateWithCallback(forSpeech(text), 0, 1.0f) { samples ->
                    if (gen != generation) {
                        0
                    } else {
                        out.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                        written += samples.size
                        1
                    }
                }
                // Czekamy, aż wybrzmi to, co jeszcze jest w buforze.
                while (gen == generation && out.playbackHeadPosition < written) Thread.sleep(20)
            } catch (_: Exception) {
                // Błąd odtwarzania – po prostu kończymy wypowiedź.
            } finally {
                runCatching { out.stop() }
                out.release()
                track = null
                // Przerwana wypowiedź nie zgłasza końca – stan ustawił już ten, kto ją przerwał.
                if (gen == generation) main.post(onDone)
            }
        }
    }

    fun stop() {
        generation++
        track?.let { runCatching { it.pause(); it.flush() } }
    }

    /** Drobne poprawki tekstu pod syntezator: twarde spacje, „zł” czytane jako „złotych”. */
    private fun forSpeech(text: String) = text
        .replace(' ', ' ')
        .replace(Regex("(\\d)\\s*zł(?!\\p{L})"), "$1 złotych")
}
