package pl.jarvis.assistant

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.flow.MutableStateFlow
import pl.jarvis.recorder.Prefs
import java.io.File
import java.util.concurrent.Executors

/**
 * Głos Jarvisa: polskie głosy Piper (sherpa-onnx), działają w telefonie bez internetu.
 * Każdy model pobiera się raz, na żądanie z ustawień.
 */
object PiperVoice {
    enum class Status { NOT_DOWNLOADED, DOWNLOADING, LOADING, READY, ERROR }

    private const val TAG = "JarvisVoice"

    /** Polskie głosy z wydania sherpa-onnx „tts-models”. Medium są 2–3× szybsze od high. */
    enum class Voice(val id: String, val label: String, val sizeMb: Int) {
        JARVIS("jarvis_wg_glos-medium", "jarvis", 64),
        DARKMAN("darkman-medium", "darkman", 64),
        MESKI("meski_wg_glos-medium", "męski", 64),
        MC_SPEECH("mc_speech-medium", "mc speech", 64),
        BASS("bass-high", "bass (wolny)", 110),
        GOSIA("gosia-medium", "gosia ♀", 64),
        ZENSKI("zenski_wg_glos-medium", "żeński ♀", 64);

        val dirName get() = "vits-piper-pl_PL-$id"
        val url get() = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$dirName.tar.bz2"

        companion object {
            val DEFAULT = JARVIS
            fun byId(id: String?) = entries.firstOrNull { it.id == id } ?: DEFAULT
        }
    }

    /** Wybrany głos (z ustawień). */
    val voice = MutableStateFlow(Voice.DEFAULT)

    val status = MutableStateFlow(Status.NOT_DOWNLOADED)
    val progress = MutableStateFlow(0f)
    val error = MutableStateFlow<String?>(null)

    val isReady: Boolean get() = status.value == Status.READY

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var tts: OfflineTts? = null
    private var appContext: Context? = null

    /** Numer bieżącej wypowiedzi – nowa wypowiedź albo stop() unieważnia poprzednią. */
    @Volatile
    private var generation = 0

    @Volatile
    private var track: AudioTrack? = null

    private fun dir(context: Context, v: Voice = voice.value) = File(context.filesDir, "voices/${v.dirName}")

    /** Plik modelu w paczce (np. pl_PL-darkman-medium.onnx) – szukamy go, zamiast zgadywać nazwę. */
    private fun modelFile(context: Context, v: Voice = voice.value) =
        dir(context, v).listFiles()?.firstOrNull { it.isFile && it.name.endsWith(".onnx") }

    fun isDownloaded(context: Context, v: Voice = voice.value) =
        modelFile(context, v) != null && File(dir(context, v), "tokens.txt").exists()

    /** Zmiana głosu z ustawień: zwalnia bieżący i wczytuje nowy (jeśli jest pobrany). */
    fun select(context: Context, v: Voice) {
        val ctx = context.applicationContext
        if (v == voice.value && tts != null) return
        if (status.value == Status.DOWNLOADING || status.value == Status.LOADING) return
        stop()
        voice.value = v
        Prefs.setPiperVoiceId(ctx, v.id)
        worker.execute {
            tts?.release()
            tts = null
            error.value = null
            if (isDownloaded(ctx, v)) load(ctx) else status.value = Status.NOT_DOWNLOADED
        }
    }

    /**
     * Znaczniki „w trakcie”: jeśli biblioteka głosu wywróci aplikację (błąd natywny, którego nie da się
     * złapać), znacznik zostaje na dysku i przy następnym starcie nie próbujemy znowu – Jarvis mówi
     * wtedy głosem systemowym, a aplikacja się otwiera.
     */
    private fun marker(context: Context, name: String) = File(context.filesDir, "voices/$name.marker")

    /** Znaczniki zostawione przez starszą wersję (z błędem, który już naprawiono) pomijamy. */
    private const val MARKER_VERSION = "13"

    private fun crashedHere(file: File) = file.exists() && runCatching { file.readText() }.getOrNull() == MARKER_VERSION

    private fun setMarker(file: File) = file.apply { parentFile?.mkdirs(); writeText(MARKER_VERSION) }

    /** Przy starcie aplikacji: wczytuje głos, jeśli jest już pobrany (i wcześniej nie wywrócił aplikacji). */
    fun prepare(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        if (tts != null || status.value == Status.LOADING || status.value == Status.DOWNLOADING) return
        voice.value = Voice.byId(Prefs.piperVoiceId(ctx))
        val crashed = listOf("loading", "speaking").firstOrNull { crashedHere(marker(ctx, it)) }
        when {
            crashed != null -> {
                error.value = if (crashed == "loading") "wczytanie głosu zamknęło aplikację" else "mówienie głosem zamknęło aplikację"
                status.value = Status.ERROR
            }
            isDownloaded(ctx) -> worker.execute { load(ctx) }
            else -> status.value = Status.NOT_DOWNLOADED
        }
    }

    /** Ponowna próba po awarii (przycisk w ustawieniach). */
    fun retry(context: Context) {
        val ctx = context.applicationContext
        marker(ctx, "loading").delete()
        marker(ctx, "speaking").delete()
        error.value = null
        if (isDownloaded(ctx)) worker.execute { load(ctx) } else download(ctx)
    }

    fun canRetry(context: Context) = isDownloaded(context.applicationContext)

    fun download(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        if (status.value == Status.DOWNLOADING) return
        status.value = Status.DOWNLOADING
        progress.value = 0f
        error.value = null
        worker.execute {
            try {
                val v = voice.value
                VoiceDownloader.downloadAndExtract(v.url, dir(ctx, v)) { progress.value = it }
                load(ctx)
            } catch (e: Exception) {
                error.value = e.message
                status.value = Status.ERROR
            }
        }
    }

    private fun load(context: Context) {
        status.value = Status.LOADING
        val loading = setMarker(marker(context, "loading"))
        try {
            val d = dir(context).path
            val model = modelFile(context) ?: throw IllegalStateException("brak pliku modelu")
            tts = OfflineTts(
                config = OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        vits = OfflineTtsVitsModelConfig(
                            model = model.path,
                            tokens = "$d/tokens.txt",
                            dataDir = "$d/espeak-ng-data",
                            lengthScale = 1.0f,
                        ),
                        // Słabsze telefony (np. Cat S61) mają 8 słabych rdzeni – więcej wątków = szybszy głos.
                        numThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
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
        } finally {
            loading.delete()
        }
    }

    /**
     * Mówi [text]; [onDone] woła na głównym wątku po zakończeniu (albo przerwaniu).
     * [alarm] – pobudka: głośność budzika zamiast multimediów.
     */
    fun speak(text: String, alarm: Boolean = false, onDone: () -> Unit) {
        val gen = ++generation
        worker.execute {
            if (gen != generation) return@execute
            Log.d(TAG, "mówię: ${text.take(40)}")
            val engine = tts
            if (engine == null) {
                main.post(onDone)
                return@execute
            }
            val out = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(if (alarm) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_ASSISTANT)
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
            val speaking = appContext?.let { setMarker(marker(it, "speaking")) }
            try {
                out.play()
                val sink = Sink(gen, out)
                val started = System.currentTimeMillis()
                engine.generateWithCallback(forSpeech(text), 0, 1.0f, sink)
                // Znacznik chroni tylko kod natywny – zamknięcie aplikacji w trakcie odtwarzania to nie awaria głosu.
                speaking?.delete()
                val written = sink.written
                Log.d(TAG, "wygenerowano $written próbek (${engine.sampleRate()} Hz) w ${System.currentTimeMillis() - started} ms")
                // Strumień zaczyna grać dopiero po zapełnieniu bufora (~1 s) – krótkie „Słucham?”
                // nigdy by nie ruszyło. Dopełniamy bufor ciszą; czekamy potem tylko na prawdziwe próbki.
                if (gen == generation) {
                    val silence = FloatArray(out.bufferSizeInFrames)
                    out.write(silence, 0, silence.size, AudioTrack.WRITE_BLOCKING)
                }
                // Czekamy, aż wybrzmi to, co jeszcze jest w buforze. Gdy odtwarzanie stoi
                // (pozycja nie rośnie przez 2 s), kończymy – inaczej Jarvis wisiałby na „mówię”.
                var lastHead = -1
                var stalledSince = System.currentTimeMillis()
                while (gen == generation && out.playbackHeadPosition < written) {
                    val head = out.playbackHeadPosition
                    if (head != lastHead) {
                        lastHead = head
                        stalledSince = System.currentTimeMillis()
                    } else if (System.currentTimeMillis() - stalledSince > 2000) {
                        Log.w(TAG, "odtwarzanie stoi na $head z $written próbek – przerywam")
                        break
                    }
                    Thread.sleep(20)
                }
            } catch (e: Exception) {
                // Błąd odtwarzania – po prostu kończymy wypowiedź.
                Log.w(TAG, "błąd mówienia", e)
            } finally {
                speaking?.delete()
                runCatching { out.stop() }
                out.release()
                track = null
                // Przerwana wypowiedź nie zgłasza końca – stan ustawił już ten, kto ją przerwał.
                if (gen == generation) main.post(onDone)
            }
        }
    }

    /**
     * Odbiera kolejne fragmenty dźwięku z syntezatora i od razu je gra.
     * Musi to być osobna klasa, nie lambda: biblioteka (JNI) szuka metody `invoke(float[]): Integer`,
     * a lambdy w Kotlinie 2 mają tylko ogólne `invoke(Object)` – stąd wcześniejsza awaria aplikacji.
     */
    private class Sink(private val gen: Int, private val out: AudioTrack) : (FloatArray) -> Int {
        var written = 0L

        override fun invoke(samples: FloatArray): Int {
            if (gen != generation) return 0
            out.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
            written += samples.size
            return 1
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
