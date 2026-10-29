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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Zamiana nagrań na tekst w telefonie (NeMo FastConformer CTC, wielojęzyczny z polskim, int8, ~100 MB).
 * Audio nigdy nie opuszcza telefonu. Tekst zapisujemy obok nagrania: 14-03-21.wav → 14-03-21.txt.
 *
 * Model pobiera się sam na Wi-Fi; nagranie przepisujemy zaraz po zakończeniu (przy ładowaniu
 * albo od 40% baterii), zaległości co 10 min. Jest też przycisk „przepisz teraz”.
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
    private var recognizer: OfflineRecognizer? = null

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
                    if (intent.action == "pl.jarvis.DEBUG_ASK") intent.getStringExtra("tekst")?.let { Assistant.submitText(it) }
                    else runPending(ctx)
                }
            }
            val filter = IntentFilter("pl.jarvis.DEBUG_TRANSCRIBE").apply { addAction("pl.jarvis.DEBUG_ASK") }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            else ctx.registerReceiver(receiver, filter)
        }
        timer.scheduleWithFixedDelay({
            if (!isDownloaded(ctx)) {
                if (status.value != Status.DOWNLOADING && onWifi(ctx)) download(ctx)
            } else if (batteryAllows(ctx)) {
                runPending(ctx)
            }
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

    /** Nagrania bez tekstu, od najstarszego; pomijamy to, które właśnie się nagrywa. */
    private fun waiting(context: Context): List<File> =
        Recordings.list(context).map { it.file }.filter { !transcriptOf(it).exists() }.reversed()

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
        val rec = engine(context)
        val parts = mutableListOf<String>()
        var start = 0
        while (start < samples.size) {
            val end = cutPoint(samples, start, rate)
            val stream = rec.createStream()
            try {
                stream.acceptWaveform(FloatArray(end - start) { samples[start + it] / 32768f }, rate)
                rec.decode(stream)
                rec.getResult(stream).text.trim().takeIf { it.isNotEmpty() }?.let { parts += it }
            } finally {
                stream.release()
            }
            start = end
        }
        return parts.joinToString(" ")
    }

    /** Koniec kawałka: najcichsze 100 ms między [CHUNK_MIN_S] a [CHUNK_MAX_S] – żeby nie ciąć w pół słowa. */
    private fun cutPoint(samples: ShortArray, start: Int, rate: Int): Int {
        if (samples.size - start <= CHUNK_MAX_S * rate) return samples.size
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
