package pl.jarvis.recorder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.os.Build
import android.os.Process
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import kotlinx.coroutines.flow.MutableStateFlow
import pl.jarvis.assistant.Assistant
import pl.jarvis.assistant.VoiceDownloader
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Zamiana nagrań na tekst w telefonie (NeMo FastConformer CTC, wielojęzyczny z polskim, int8, ~100 MB).
 * Audio nigdy nie opuszcza telefonu. Tekst zapisujemy obok nagrania: 14-03-21.wav → 14-03-21.txt.
 *
 * Model pobiera się sam na Wi-Fi. Rozmowę przepisujemy na bieżąco, w trakcie nagrywania, kawałkami
 * po ok. 25 s ([liveStart]/[liveWrite]/[liveFinish]) – po „Hey Jarvis” zostaje do przepisania tylko
 * końcówka, więc Jarvis od razu zna treść rozmowy. Działa przy ładowaniu albo od 40% baterii;
 * zaległości (od najnowszych) co 10 min. Jest też przycisk „przepisz teraz”.
 * Po każdym przepisanym nagraniu wołamy [onTranscribed] – tam powstaje skrót.
 */
object Transcriber {
    enum class Status { NOT_DOWNLOADED, DOWNLOADING, READY, WORKING, ERROR }

    private const val TAG = "JarvisAsr"
    private const val MODEL_DIR = "asr/nemo-fastconformer-ctc-int8"
    private const val URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" +
        "sherpa-onnx-nemo-fast-conformer-ctc-be-de-en-es-fr-hr-it-pl-ru-uk-20k-int8.tar.bz2"
    const val SIZE_MB = 98

    /** Kawałki ok. 25 s, cięte w najcichszym miejscu między 18 a 30 s. */
    private const val CHUNK_MIN_S = 18
    private const val CHUNK_MAX_S = 30

    /** Nagranie skończone w ciągu ostatnich 10 min to „świeża” rozmowa – na nią Jarvis czeka. */
    private const val FRESH_MS = 10 * 60_000L

    val status = MutableStateFlow(Status.NOT_DOWNLOADED)
    val progress = MutableStateFlow(0f)
    val error = MutableStateFlow<String?>(null)

    /** Ile nagrań czeka na przepisanie (podgląd na ekranie). */
    val pending = MutableStateFlow(0)

    /** Wołane w tle po przepisaniu nagrania (skrót, zadania, fakty). */
    @Volatile
    var onTranscribed: ((Context, File, String) -> Unit)? = null

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "jarvis-asr") }
    private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "jarvis-asr-timer").apply { isDaemon = true } }
    /** Osobny wątek dla bieżącej rozmowy – nie czeka za zaległościami. */
    private val liveWorker = Executors.newSingleThreadExecutor { r -> Thread(r, "jarvis-asr-live") }
    /** Model jest jeden; uczciwa blokada – bieżąca rozmowa czeka najwyżej na jeden kawałek zaległości. */
    private val engineLock = ReentrantLock(true)
    private var recognizer: OfflineRecognizer? = null

    /** Nagranie przepisywane na bieżąco (bufor wypełnia wątek nagrywania). */
    private class LiveSession(val context: Context, val wav: File) {
        val buffer = ShortArray((CHUNK_MAX_S + 1) * Recordings.SAMPLE_RATE)
        var size = 0
        val parts = mutableListOf<String>()
        @Volatile var cancelled = false
    }

    private var live: LiveSession? = null
    /** Nagrania, które przepisuje [liveWorker] – zaległości ich nie ruszają. */
    private val liveFiles = ConcurrentHashMap.newKeySet<File>()
    private val liveJobs = AtomicInteger(0)
    private val liveDone = Object()

    @Volatile
    private var queued = false
    private var autoStarted = false

    private fun dir(context: Context) = File(context.filesDir, MODEL_DIR)

    fun isDownloaded(context: Context) =
        File(dir(context), "model.int8.onnx").exists() && File(dir(context), "tokens.txt").exists()

    fun transcriptOf(wav: File) = sibling(wav, "txt")

    fun prepare(context: Context) {
        if (status.value == Status.DOWNLOADING || status.value == Status.WORKING) return
        status.value = if (isDownloaded(context)) Status.READY else Status.NOT_DOWNLOADED
        updatePending(context)
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
                Log.d(TAG, "model pobrany")
                status.value = Status.READY
                updatePending(ctx)
                if (batteryAllows(ctx)) runPending(ctx)
            } catch (e: Exception) {
                error.value = e.message
                status.value = Status.ERROR
            }
        }
    }

    /**
     * Raz na usługę: model pobiera się sam na Wi-Fi, a co 10 min nadrabiamy zaległe nagrania
     * (gdy bateria na to pozwala). Świeże nagrania przepisuje [onRecordingFinished].
     */
    @Synchronized
    fun startAuto(context: Context) {
        if (autoStarted) return
        autoStarted = true
        val ctx = context.applicationContext
        // Tylko wersja testowa (adb shell am broadcast -a …):
        // DEBUG_TRANSCRIBE – przepisz od razu; DEBUG_ASK --es tekst "…" – zapytaj Jarvisa jak z klawiatury.
        if (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    when (intent.action) {
                        "pl.jarvis.DEBUG_ASK" -> intent.getStringExtra("tekst")?.let { Assistant.submitText(it) }
                        // DEBUG_LIVE --es tekst "…" – rozmowa na żywo (Gemini) z tekstem zamiast mowy.
                        "pl.jarvis.DEBUG_LIVE" -> intent.getStringExtra("tekst")?.let { Assistant.debugLive(it) }
                        // DEBUG_PROMISE --es tekst "…" – udawana rozmowa sprzed chwili, do testu wyłapywania obietnic.
                        "pl.jarvis.DEBUG_PROMISE" -> intent.getStringExtra("tekst")?.let { text ->
                            val now = java.time.LocalDateTime.now()
                            val wav = File(File(ctx.filesDir, "recordings/${now.toLocalDate()}"), "%02d-%02d-%02d.wav".format(now.hour, now.minute, now.second))
                            Thread { pl.jarvis.assistant.RecordingNotes.commitments(ctx, wav, text) }.start()
                        }
                        else -> runPending(ctx)
                    }
                }
            }
            val filter = IntentFilter("pl.jarvis.DEBUG_TRANSCRIBE").apply {
                addAction("pl.jarvis.DEBUG_ASK")
                addAction("pl.jarvis.DEBUG_PROMISE")
                addAction("pl.jarvis.DEBUG_LIVE")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            else ctx.registerReceiver(receiver, filter)
        }
        timer.scheduleWithFixedDelay({
            if (!isDownloaded(ctx)) {
                if (status.value != Status.DOWNLOADING && onWifi(ctx)) download(ctx)
            } else if (batteryAllows(ctx)) {
                runPending(ctx)
            }
            // Co 10 minut sprzątamy stary dźwięk (sam tekst zostaje).
            runCatching { Recordings.cleanup(ctx) }
        }, 10, 600, TimeUnit.SECONDS)
    }

    /** Rejestrator zapisał nagranie – przepisujemy od razu, jeśli bateria pozwala. */
    fun onRecordingFinished(context: Context) {
        val ctx = context.applicationContext
        // Wołane z wątku nagrywania – listę plików czytamy w tle, żeby nie gubić dźwięku.
        worker.execute { updatePending(ctx) }
        if (batteryAllows(ctx)) runPending(ctx)
    }

    /** Przepisywanie zużywa procesor: przy ładowaniu zawsze, na baterii – od 40%. */
    private fun batteryAllows(context: Context): Boolean {
        val battery = context.getSystemService(BatteryManager::class.java) ?: return true
        return battery.isCharging || battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) >= 40
    }

    private fun onWifi(context: Context): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = connectivity.activeNetwork ?: return false
        return !connectivity.isActiveNetworkMetered && connectivity.getNetworkCapabilities(network) != null
    }

    /** Przepisuje wszystkie nagrania bez tekstu (po kolei, w tle). */
    fun runPending(context: Context) {
        val ctx = context.applicationContext
        if (!isDownloaded(ctx) || queued) {
            Log.d(TAG, "runPending pominięte: pobrany=${isDownloaded(ctx)}, w kolejce=$queued")
            return
        }
        queued = true
        worker.execute {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                Log.d(TAG, "start: czeka ${waiting(ctx).size} nagrań")
                while (true) {
                    val next = waiting(ctx).firstOrNull() ?: break
                    Log.d(TAG, "przepisuję ${next.parentFile?.name}/${next.name} (${next.length() / 32_000} s)")
                    status.value = Status.WORKING
                    val started = System.currentTimeMillis()
                    val text = transcribe(ctx, next)
                    transcriptOf(next).writeText(text)
                    Log.d(TAG, "${next.name}: ${text.length} znaków w ${System.currentTimeMillis() - started} ms")
                    updatePending(ctx)
                    RecorderState.recordingsChanged()
                    if (text.isNotBlank()) runCatching { onTranscribed?.invoke(ctx, next, text) }
                        .onFailure { Log.w(TAG, "skrót nie wyszedł", it) }
                }
                status.value = Status.READY
            } catch (e: Throwable) {
                Log.w(TAG, "błąd transkrypcji", e)
                error.value = e.message ?: e.javaClass.simpleName
                status.value = Status.ERROR
            } finally {
                queued = false
            }
        }
    }

    /** Nagrania bez tekstu, od najnowszego (o nie pyta się najczęściej); pomijamy to, które się nagrywa. */
    private fun waiting(context: Context): List<File> =
        Recordings.list(context).filter { it.hasAudio }.map { it.file }.filter { !transcriptOf(it).exists() && it !in liveFiles }

    /** Rejestrator zaczął nagranie – przepisujemy je w trakcie (jeśli jest model i bateria pozwala). */
    fun liveStart(context: Context, wav: File) {
        live = null
        val ctx = context.applicationContext
        if (!isDownloaded(ctx) || !batteryAllows(ctx)) return
        liveFiles += wav
        live = LiveSession(ctx, wav)
    }

    /** Kolejna ramka nagrania (wątek nagrywania – tylko kopiujemy, praca idzie w tle). */
    fun liveWrite(frame: ShortArray) {
        val s = live ?: return
        val n = frame.size.coerceAtMost(s.buffer.size - s.size)
        System.arraycopy(frame, 0, s.buffer, s.size, n)
        s.size += n
        if (s.size < CHUNK_MAX_S * Recordings.SAMPLE_RATE) return
        val cut = cutPoint(s.buffer, s.size, 0, Recordings.SAMPLE_RATE)
        val chunk = s.buffer.copyOf(cut)
        System.arraycopy(s.buffer, cut, s.buffer, 0, s.size - cut)
        s.size -= cut
        submitLive(s, chunk)
    }

    /** Nagranie się skończyło: przepisujemy końcówkę i zapisujemy tekst ([keep] = false – nagranie usunięte). */
    fun liveFinish(keep: Boolean) {
        val s = live ?: return
        live = null
        if (!keep) {
            s.cancelled = true
            liveFiles -= s.wav
            return
        }
        submitLive(s, s.buffer.copyOf(s.size))
        liveJobs.incrementAndGet()
        liveWorker.execute {
            try {
                val text = s.parts.joinToString(" ")
                transcriptOf(s.wav).writeText(text)
                Log.d(TAG, "${s.wav.name}: przepisane na bieżąco, ${text.length} znaków")
                liveFiles -= s.wav
                updatePending(s.context)
                RecorderState.recordingsChanged()
                if (text.isNotBlank()) runCatching { onTranscribed?.invoke(s.context, s.wav, text) }
                    .onFailure { Log.w(TAG, "skrót nie wyszedł", it) }
            } catch (e: Throwable) {
                Log.w(TAG, "zapis tekstu nie wyszedł", e)
                liveFiles -= s.wav
            } finally {
                liveDone()
            }
        }
    }

    private fun submitLive(s: LiveSession, chunk: ShortArray) {
        liveJobs.incrementAndGet()
        liveWorker.execute {
            try {
                if (!s.cancelled && chunk.isNotEmpty()) {
                    val started = System.currentTimeMillis()
                    decode(s.context, chunk)?.let { s.parts += it }
                    Log.d(TAG, "${s.wav.name}: kawałek ${chunk.size / Recordings.SAMPLE_RATE} s w ${System.currentTimeMillis() - started} ms")
                }
            } catch (e: Throwable) {
                Log.w(TAG, "błąd przepisywania na bieżąco", e)
            } finally {
                liveDone()
            }
        }
    }

    private fun liveDone() {
        liveJobs.decrementAndGet()
        synchronized(liveDone) { liveDone.notifyAll() }
    }

    /**
     * Czeka (najwyżej [timeoutMs]), aż świeże rozmowy będą przepisane – żeby Jarvis odpowiadał
     * o rozmowie sprzed chwili. Wraca od razu, gdy nic nie czeka. Blokuje – nie z wątku UI.
     */
    fun awaitFresh(context: Context, timeoutMs: Long, onWaiting: () -> Unit = {}): Boolean {
        val ctx = context.applicationContext
        if (!isDownloaded(ctx)) return true
        val started = System.currentTimeMillis()
        var announced = false
        fun freshMissing() = Recordings.list(ctx).take(3).any {
            !transcriptOf(it.file).exists() && System.currentTimeMillis() - it.file.lastModified() < FRESH_MS
        }
        while (System.currentTimeMillis() - started < timeoutMs) {
            if (liveJobs.get() == 0) {
                if (!freshMissing()) return true
                // Nagranie bez bieżącego przepisywania (np. słaba bateria) – pytanie jest ważniejsze.
                if (!queued) runPending(ctx)
            }
            // Dłuższe czekanie – Jarvis mówi „chwileczkę”, żeby nie było ciszy.
            if (!announced && System.currentTimeMillis() - started > 1500) {
                announced = true
                onWaiting()
            }
            synchronized(liveDone) { liveDone.wait(200) }
        }
        return false
    }

    private fun updatePending(context: Context) {
        pending.value = runCatching { waiting(context).size }.getOrDefault(0)
    }

    private fun engine(context: Context): OfflineRecognizer = recognizer ?: run {
        Log.d(TAG, "wczytuję model mowy")
        val d = dir(context).path
        OfflineRecognizer(
            config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = Recordings.SAMPLE_RATE, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    nemo = OfflineNemoEncDecCtcModelConfig(model = "$d/model.int8.onnx"),
                    tokens = "$d/tokens.txt",
                    numThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
                    debug = false,
                    provider = "cpu",
                ),
            ),
        ).also { recognizer = it }
    }

    private fun transcribe(context: Context, wav: File): String {
        val samples = readWav(wav)
        val rate = Recordings.SAMPLE_RATE
        val parts = mutableListOf<String>()
        var start = 0
        while (start < samples.size) {
            val end = cutPoint(samples, samples.size, start, rate)
            decode(context, samples.copyOfRange(start, end))?.let { parts += it }
            start = end
        }
        return parts.joinToString(" ")
    }

    /** Jeden kawałek (do 30 s) na tekst; null – nic nie rozpoznano. */
    /**
     * Szybkie rozpoznanie krótkiego kawałka (np. „stop” w trakcie mówienia Jarvisa). Gdy silnik jest zajęty
     * dłużej niż [waitMs] (przepisywanie zaległości) – null, bez czekania w kolejce.
     */
    fun quickDecode(context: Context, samples: ShortArray, waitMs: Long): String? {
        if (!isDownloaded(context) || !engineLock.tryLock(waitMs, TimeUnit.MILLISECONDS)) return null
        return try {
            decode(context, samples)
        } finally {
            engineLock.unlock()
        }
    }

    private fun decode(context: Context, samples: ShortArray): String? = engineLock.withLock {
        val rec = engine(context)
        val stream = rec.createStream()
        try {
            stream.acceptWaveform(FloatArray(samples.size) { samples[it] / 32768f }, Recordings.SAMPLE_RATE)
            rec.decode(stream)
            rec.getResult(stream).text.trim().takeIf { it.isNotEmpty() }
        } finally {
            stream.release()
        }
    }

    /** Koniec kawałka: najcichsze 100 ms między [CHUNK_MIN_S] a [CHUNK_MAX_S] – żeby nie ciąć w pół słowa. */
    private fun cutPoint(samples: ShortArray, size: Int, start: Int, rate: Int): Int {
        if (size - start <= CHUNK_MAX_S * rate) return size
        val window = rate / 10
        var best = start + CHUNK_MAX_S * rate
        var bestEnergy = Long.MAX_VALUE
        var pos = start + CHUNK_MIN_S * rate
        while (pos + window <= start + CHUNK_MAX_S * rate) {
            var energy = 0L
            for (i in pos until pos + window) energy += samples[i].toLong() * samples[i]
            if (energy < bestEnergy) {
                bestEnergy = energy
                best = pos + window / 2
            }
            pos += window
        }
        return best
    }

    private fun readWav(file: File): ShortArray = RandomAccessFile(file, "r").use { raf ->
        val bytes = ByteArray((raf.length() - WavWriter.HEADER_SIZE).coerceAtLeast(0).toInt())
        raf.seek(WavWriter.HEADER_SIZE.toLong())
        raf.readFully(bytes)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        ShortArray(buffer.remaining()).also { buffer.get(it) }
    }
}
