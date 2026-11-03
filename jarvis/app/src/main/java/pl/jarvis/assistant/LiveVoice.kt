package pl.jarvis.assistant

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Rozmowa na żywo: dźwięk z mikrofonu idzie prosto do modelu (ChatGPT albo Gemini), a jego głos
 * wraca od razu – bez przepisywania na tekst i bez osobnej syntezy mowy. Model sam słyszy, kiedy
 * skończyłeś mówić, i milknie, gdy mu przerwiesz. Narzędzia Jarvisa działają jak przy Kimi.
 *
 * Tu jest to, co wspólne: mikrofon, głośnik, narzędzia, koniec rozmowy po ciszy. Protokół
 * dostawcy – w podklasach. Jedna sesja = jedna rozmowa: kończy się po chwili ciszy, [stop] albo błędem.
 * Wszystkie metody [Listener] są wołane w wątku UI.
 */
abstract class LiveVoice(
    private val runTool: (String, Map<String, Any?>) -> String,
    private val listener: Listener,
) {
    interface Listener {
        /** Słyszę cię (tekst tego, co mówisz – do podglądu). */
        fun onHeard(text: String)

        /** Jarvis zaczął mówić. */
        fun onSpeaking()

        /** Jarvis skończył odpowiedź (i wybrzmiała) – co powiedziałeś i co odpowiedział. */
        fun onTurnDone(user: String, jarvis: String)

        /** Głośność mówiącego 0..1 – dla reaktora. */
        fun onLevel(level: Float)

        /** Koniec sesji; [error] = null – zwykły koniec (cisza, stop). [connected] – czy połączenie w ogóle ruszyło. */
        fun onClosed(error: String?, connected: Boolean)
    }

    /** Częstotliwość próbkowania mikrofonu, jakiej chce dostawca. */
    protected abstract val inRate: Int

    protected abstract fun request(): Request

    /** Pierwsza wiadomość po połączeniu (ustawienia sesji). */
    protected abstract fun setup(): String

    protected abstract fun audioMessage(base64: String): String

    protected abstract fun textMessages(text: String): List<String>

    /** Wiadomość od serwera – podklasa woła [markReady], [heard], [said], [audio], [interrupt], [turnComplete], [callTool]. */
    protected abstract fun handle(msg: JsonNode)

    /** Wiadomości z wynikiem narzędzia. */
    protected abstract fun toolResult(id: String, name: String, result: String): List<String>

    /** Wszystkie narzędzia z bieżącej tury skończone (OpenAI: trzeba poprosić o dalszą odpowiedź). */
    protected open fun afterTools() {}

    private val main = Handler(Looper.getMainLooper())
    private val toolRunner = Executors.newSingleThreadExecutor()

    @Volatile private var socket: WebSocket? = null
    @Volatile protected var ready = false
        private set
    @Volatile private var closed = false
    @Volatile private var lastActivity = System.currentTimeMillis()
    @Volatile protected var playing = false
        private set
    @Volatile private var complete = false
    @Volatile private var playEndedAt = 0L
    @Volatile private var playGeneration = 0
    @Volatile private var pendingText: String? = null
    private val toolsRunning = AtomicInteger()
    private val playback = LinkedBlockingQueue<ByteArray>()
    private val userText = StringBuilder()
    private val jarvisText = StringBuilder()
    private var micThread: Thread? = null
    private var playThread: Thread? = null
    @Volatile private var track: AudioTrack? = null

    fun start() {
        socket = HTTP.newWebSocket(request(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(setup())
            }

            override fun onMessage(webSocket: WebSocket, text: String) = receive(text)

            // Gemini wysyła JSON także w ramkach binarnych.
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = receive(bytes.utf8())

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                finish(if (code == 1000) null else "zamknięte połączenie ($code${reason.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()})")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                finish(response?.let { "HTTP ${it.code}" + if (it.code == 401) " – zły klucz" else "" } ?: t.message ?: t.javaClass.simpleName)
            }
        })
        micThread = Thread({ listen() }, "jarvis-live-mic").apply { start() }
        playThread = Thread({ play() }, "jarvis-live-play").apply { start() }
        main.postDelayed(watchdog, 1000)
    }

    /** Zamiast mówienia – tekst (np. test z komputera). Wysyłany, gdy połączenie będzie gotowe. */
    fun sendText(text: String) {
        lastActivity = System.currentTimeMillis()
        if (ready) textMessages(text).forEach { send(it) } else pendingText = text
    }

    /** Kończy rozmowę: cisza od razu, mikrofon wraca do rejestratora. */
    fun stop() = finish(null)

    protected fun send(message: String) {
        socket?.send(message)
    }

    @Synchronized
    protected fun finish(error: String?) {
        if (closed) return
        closed = true
        main.removeCallbacks(watchdog)
        socket?.close(1000, null)
        micThread?.interrupt()
        playThread?.interrupt()
        playback.clear()
        runCatching { track?.pause(); track?.flush() }
        toolRunner.shutdown()
        if (error != null) Log.w(TAG, "koniec: $error")
        val connected = ready
        main.post { listener.onClosed(error, connected) }
    }

    private fun receive(text: String) {
        if (closed) return
        val msg = runCatching { MAPPER.readTree(text) }.getOrNull() ?: return
        handle(msg)
    }

    protected fun markReady() {
        if (ready) return
        Log.d(TAG, "połączono")
        ready = true
        lastActivity = System.currentTimeMillis()
        pendingText?.let { t -> textMessages(t).forEach { send(it) } }
        pendingText = null
    }

    protected fun heard(text: String) {
        if (text.isEmpty()) return
        lastActivity = System.currentTimeMillis()
        val all = synchronized(userText) { userText.append(text).toString() }
        main.post { listener.onHeard(all.trim()) }
    }

    protected fun said(text: String) {
        if (text.isNotEmpty()) synchronized(jarvisText) { jarvisText.append(text) }
    }

    protected fun audio(base64: String) {
        if (base64.isNotEmpty()) playback.add(Base64.decode(base64, Base64.DEFAULT))
    }

    /** Przerwałeś mu – wyrzucamy resztę jego wypowiedzi. */
    protected fun interrupt() {
        lastActivity = System.currentTimeMillis()
        if (!playing && playback.isEmpty()) return
        Log.d(TAG, "przerwane")
        playGeneration++
        playback.clear()
        runCatching { track?.pause(); track?.flush(); track?.play() }
    }

    /** Odpowiedź kompletna – po jej wybrzmieniu [Listener.onTurnDone]. */
    protected fun turnComplete() {
        complete = true
    }

    protected fun callTool(id: String, name: String, args: JsonNode?) {
        @Suppress("UNCHECKED_CAST")
        val input = runCatching { MAPPER.convertValue(args, Map::class.java) as Map<String, Any?> }.getOrNull().orEmpty()
        Log.d(TAG, "narzędzie: $name $input")
        toolsRunning.incrementAndGet()
        toolRunner.execute {
            val result = runCatching { runTool(name, input) }.getOrElse { "Błąd narzędzia: ${it.message}" }
            if (!closed) toolResult(id, name, result).forEach { send(it) }
            lastActivity = System.currentTimeMillis()
            if (toolsRunning.decrementAndGet() == 0 && !closed) afterTools()
        }
    }

    /** Mikrofon → model, kawałkami po 100 ms. Zanim połączenie ruszy, dźwięk czeka w kolejce (możesz mówić od razu). */
    @SuppressLint("MissingPermission") // mikrofon jest nadany – bez niego Jarvis w ogóle nie słucha
    private fun listen() {
        // Chwila dla rejestratora na oddanie mikrofonu.
        try {
            Thread.sleep(MIC_HANDOVER_MS)
        } catch (_: InterruptedException) {
            return
        }
        val rate = inRate
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate))
        }.getOrNull()?.takeIf { it.state == AudioRecord.STATE_INITIALIZED }
        if (record == null) {
            finish("mikrofon zajęty")
            return
        }
        // Wycinanie echa: Jarvis nie może usłyszeć sam siebie z głośnika i sobie przerwać.
        val aec = if (AcousticEchoCanceler.isAvailable()) runCatching { AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true } }.getOrNull() else null
        val waiting = ArrayDeque<String>()
        val chunk = ByteArray(rate / 10 * 2)
        var loud = 0
        var peak = -90f // najgłośniejsze echo w trakcie mówienia – do logu (strojenie progu)
        try {
            record.startRecording()
            while (!closed && !Thread.currentThread().isInterrupted) {
                val n = record.read(chunk, 0, chunk.size)
                if (n <= 0) continue
                val db = db(chunk, n)
                if (!playing) {
                    main.post { listener.onLevel(((db + 50f) / 30f).coerceIn(0f, 1f)) }
                    if (db > ACTIVE_DB) lastActivity = System.currentTimeMillis()
                }
                // Gdy Jarvis mówi, mikrofon słyszy też jego głos z głośnika – model brał to za ciebie
                // i sam sobie przerywał. Wtedy przepuszczamy tylko wyraźnie głośną mowę (ty, gdy mu
                // przerywasz – przez [LOUD_CHUNKS] kawałków z rzędu); resztę zastępuje cisza.
                if (playing || System.currentTimeMillis() - playEndedAt < ECHO_TAIL_MS) {
                    peak = maxOf(peak, db)
                    loud = if (db > BARGE_IN_DB) loud + 1 else 0
                    if (loud == LOUD_CHUNKS) Log.d(TAG, "przerywasz (%.0f dB)".format(db))
                    if (loud < LOUD_CHUNKS) chunk.fill(0, 0, n)
                } else {
                    if (peak > -90f) Log.d(TAG, "echo w trakcie mówienia: do %.0f dB".format(peak))
                    peak = -90f
                    loud = LOUD_CHUNKS
                }
                val message = audioMessage(Base64.encodeToString(chunk, 0, n, Base64.NO_WRAP))
                if (!ready) {
                    waiting.addLast(message)
                    if (waiting.size > MAX_WAITING) waiting.removeFirst()
                    continue
                }
                while (waiting.isNotEmpty()) send(waiting.removeFirst())
                send(message)
            }
        } catch (e: Exception) {
            Log.w(TAG, "mikrofon: ${e.message}")
        } finally {
            runCatching { record.stop() }
            record.release()
            aec?.release()
        }
    }

    /** Głos Jarvisa (24 kHz) na głośnik; po wybrzmieniu całej odpowiedzi – [Listener.onTurnDone]. */
    private fun play() {
        val player = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(OUT_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(OUT_RATE) // ~0,5 s zapasu
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrElse {
            finish("głośnik: ${it.message}")
            return
        }
        track = player
        var written = 0L // ramki zapisane od ostatniego wyczyszczenia (flush zeruje pozycję odtwarzania)
        var generation = playGeneration
        try {
            player.play()
            while (!closed) {
                val chunk = playback.poll(50, TimeUnit.MILLISECONDS)
                if (generation != playGeneration) {
                    generation = playGeneration
                    written = 0
                }
                if (chunk != null) {
                    if (!playing) {
                        playing = true
                        main.post { listener.onSpeaking() }
                    }
                    player.write(chunk, 0, chunk.size and 1.inv(), AudioTrack.WRITE_BLOCKING)
                    written += chunk.size / 2
                    continue
                }
                if (!complete || toolsRunning.get() > 0) continue
                // Kolejka pusta, odpowiedź kompletna – czekamy, aż wybrzmi to, co jest w buforze.
                if (playing && player.playbackHeadPosition.toLong() < written) continue
                complete = false
                if (playing) playEndedAt = System.currentTimeMillis()
                playing = false
                lastActivity = System.currentTimeMillis()
                val user = synchronized(userText) { userText.toString().trim().also { userText.clear() } }
                val jarvis = synchronized(jarvisText) { jarvisText.toString().trim().also { jarvisText.clear() } }
                main.post { listener.onTurnDone(user, jarvis) }
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            Log.w(TAG, "głośnik: ${e.message}")
        } finally {
            runCatching { player.stop() }
            player.release()
        }
    }

    /** Cisza po odpowiedzi kończy rozmowę; połączenie, które nie rusza – też. */
    private val watchdog = object : Runnable {
        override fun run() {
            if (closed) return
            val quiet = System.currentTimeMillis() - lastActivity
            when {
                !ready && quiet > CONNECT_TIMEOUT_MS -> finish("model nie odpowiada")
                ready && !playing && toolsRunning.get() == 0 && quiet > IDLE_MS -> finish(null)
                else -> main.postDelayed(this, 500)
            }
        }
    }

    private fun db(bytes: ByteArray, n: Int): Float {
        var sum = 0.0
        var count = 0
        var i = 0
        while (i + 1 < n) {
            val s = ((bytes[i + 1].toInt() shl 8) or (bytes[i].toInt() and 0xff)).toShort().toDouble()
            sum += s * s
            count++
            i += 2
        }
        val rms = sqrt(sum / count.coerceAtLeast(1)) / 32768.0
        return (20 * log10(rms.coerceAtLeast(1e-9))).toFloat()
    }

    companion object {
        const val TAG = "JarvisLive"
        const val OUT_RATE = 24_000
        private const val MIC_HANDOVER_MS = 250L
        private const val MAX_WAITING = 50 // 5 s mowy, zanim połączenie ruszy
        private const val ACTIVE_DB = -42f // głośniej – ktoś mówi (rozmowa trwa)
        private const val BARGE_IN_DB = -22f // echo Jarvisa z głośnika dochodzi do ok. -24 dB; ty z bliska – głośniej
        private const val LOUD_CHUNKS = 3 // 300 ms głośnej mowy = przerywasz mu
        private const val ECHO_TAIL_MS = 400L // echo wybrzmiewa jeszcze chwilę po końcu odpowiedzi
        private const val IDLE_MS = 8_000L // tyle ciszy po odpowiedzi kończy rozmowę
        private const val CONNECT_TIMEOUT_MS = 8_000L

        val MAPPER = ObjectMapper()
        val HTTP: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }
}
