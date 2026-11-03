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
import pl.jarvis.brain.JarvisTools
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Rozmowa na żywo przez Gemini Live: dźwięk z mikrofonu idzie prosto do modelu, a jego głos wraca
 * od razu – bez przepisywania na tekst i bez osobnej syntezy mowy. Model sam słyszy, kiedy skończyłeś
 * mówić, i milknie, gdy mu przerwiesz. Narzędzia Jarvisa (pamięć, przypomnienia, telefon) działają
 * jak przy Kimi; w internecie szuka wyszukiwarką Google.
 *
 * Jedna sesja = jedna rozmowa: kończy się po chwili ciszy, wywołaniem [stop] albo błędem.
 * Wszystkie metody [Listener] są wołane w wątku UI.
 */
class GeminiLive(
    private val apiKey: String,
    private val model: String,
    private val voice: String,
    private val instruction: String,
    private val runTool: (String, Map<String, Any?>) -> String,
    private val listener: Listener,
) {
    interface Listener {
        /** Słyszę cię na bieżąco (tekst tego, co mówisz – do podglądu). */
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

    private val main = Handler(Looper.getMainLooper())
    private val toolRunner = Executors.newSingleThreadExecutor()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var ready = false
    @Volatile private var closed = false
    @Volatile private var lastActivity = System.currentTimeMillis()
    @Volatile private var playing = false
    @Volatile private var turnComplete = false
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
        val request = Request.Builder().url("$URL?key=$apiKey").build()
        socket = HTTP.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(setup())
            }

            override fun onMessage(webSocket: WebSocket, text: String) = handle(text)

            // Gemini wysyła JSON także w ramkach binarnych.
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handle(bytes.utf8())

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                finish(if (code == 1000) null else "Gemini zamknął połączenie ($code${reason.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()})")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                finish(response?.let { "HTTP ${it.code}" } ?: t.message ?: t.javaClass.simpleName)
            }
        })
        micThread = Thread({ listen() }, "jarvis-live-mic").apply { start() }
        playThread = Thread({ play() }, "jarvis-live-play").apply { start() }
        main.postDelayed(watchdog, 1000)
    }

    /** Zamiast mówienia – tekst (np. test z komputera). Wysyłany, gdy połączenie będzie gotowe. */
    fun sendText(text: String) {
        lastActivity = System.currentTimeMillis()
        if (ready) socket?.send(textMessage(text)) else pendingText = text
    }

    /** Kończy rozmowę: cisza od razu, mikrofon wraca do rejestratora. */
    fun stop() = finish(null)

    @Synchronized
    private fun finish(error: String?) {
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

    private fun handle(text: String) {
        if (closed) return
        val msg = runCatching { MAPPER.readTree(text) }.getOrNull() ?: return
        val now = System.currentTimeMillis()
        if (msg.has("setupComplete")) {
            Log.d(TAG, "połączono")
            ready = true
            lastActivity = now
            pendingText?.let { socket?.send(textMessage(it)) }
            pendingText = null
        }
        msg.get("serverContent")?.let { content ->
            content.path("inputTranscription").path("text").asText("").takeIf { it.isNotEmpty() }?.let { t ->
                lastActivity = now
                val heard = synchronized(userText) { userText.append(t).toString() }
                main.post { listener.onHeard(heard.trim()) }
            }
            content.path("outputTranscription").path("text").asText("").takeIf { it.isNotEmpty() }?.let { t ->
                synchronized(jarvisText) { jarvisText.append(t) }
            }
            content.path("modelTurn").path("parts").forEach { part ->
                val data = part.path("inlineData").path("data").asText("")
                if (data.isNotEmpty()) playback.add(Base64.decode(data, Base64.DEFAULT))
            }
            // Przerwałeś mu – wyrzucamy resztę jego wypowiedzi.
            if (content.path("interrupted").asBoolean(false)) {
                Log.d(TAG, "przerwane")
                playGeneration++
                playback.clear()
                runCatching { track?.pause(); track?.flush(); track?.play() }
            }
            if (content.path("turnComplete").asBoolean(false)) turnComplete = true
        }
        msg.get("toolCall")?.path("functionCalls")?.forEach { call -> runToolCall(call) }
        msg.get("goAway")?.let { finish(null) }
    }

    private fun runToolCall(call: JsonNode) {
        val id = call.path("id").asText()
        val name = call.path("name").asText()
        @Suppress("UNCHECKED_CAST")
        val args = runCatching { MAPPER.convertValue(call.path("args"), Map::class.java) as Map<String, Any?> }.getOrNull().orEmpty()
        Log.d(TAG, "narzędzie: $name $args")
        toolsRunning.incrementAndGet()
        toolRunner.execute {
            val result = runCatching { runTool(name, args) }.getOrElse { "Błąd narzędzia: ${it.message}" }
            val root = MAPPER.createObjectNode()
            root.putObject("toolResponse").putArray("functionResponses").addObject()
                .put("id", id).put("name", name)
                .putObject("response").put("result", result).put("scheduling", "WHEN_IDLE")
            socket?.send(MAPPER.writeValueAsString(root))
            lastActivity = System.currentTimeMillis()
            toolsRunning.decrementAndGet()
        }
    }

    /** Mikrofon → Gemini, kawałkami po 100 ms. Zanim połączenie ruszy, dźwięk czeka w kolejce (możesz mówić od razu). */
    @SuppressLint("MissingPermission") // mikrofon jest nadany – bez niego Jarvis w ogóle nie słucha
    private fun listen() {
        // Chwila dla rejestratora na oddanie mikrofonu.
        try {
            Thread.sleep(MIC_HANDOVER_MS)
        } catch (_: InterruptedException) {
            return
        }
        val min = AudioRecord.getMinBufferSize(IN_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, IN_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, IN_RATE))
        }.getOrNull()?.takeIf { it.state == AudioRecord.STATE_INITIALIZED }
        if (record == null) {
            finish("mikrofon zajęty")
            return
        }
        // Wycinanie echa: Jarvis nie może usłyszeć sam siebie z głośnika i sobie przerwać.
        val aec = if (AcousticEchoCanceler.isAvailable()) runCatching { AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true } }.getOrNull() else null
        val waiting = ArrayDeque<String>()
        val chunk = ByteArray(IN_RATE / 10 * 2)
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
                val message = """{"realtimeInput":{"audio":{"data":"${Base64.encodeToString(chunk, 0, n, Base64.NO_WRAP)}","mimeType":"audio/pcm;rate=$IN_RATE"}}}"""
                if (!ready) {
                    waiting.addLast(message)
                    if (waiting.size > MAX_WAITING) waiting.removeFirst()
                    continue
                }
                while (waiting.isNotEmpty()) socket?.send(waiting.removeFirst())
                socket?.send(message)
            }
        } catch (e: Exception) {
            Log.w(TAG, "mikrofon: ${e.message}")
        } finally {
            runCatching { record.stop() }
            record.release()
            aec?.release()
        }
    }

    /** Głos Jarvisa z Gemini (24 kHz) na głośnik; po wybrzmieniu całej odpowiedzi – [Listener.onTurnDone]. */
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
                if (!turnComplete || toolsRunning.get() > 0) continue
                // Kolejka pusta, odpowiedź kompletna – czekamy, aż wybrzmi to, co jest w buforze.
                if (playing && player.playbackHeadPosition.toLong() < written) continue
                turnComplete = false
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
                !ready && quiet > CONNECT_TIMEOUT_MS -> finish("Gemini nie odpowiada")
                ready && !playing && toolsRunning.get() == 0 && quiet > IDLE_MS -> finish(null)
                else -> main.postDelayed(this, 500)
            }
        }
    }

    private fun setup(): String {
        val root = MAPPER.createObjectNode()
        val setup = root.putObject("setup")
        setup.put("model", "models/$model")
        val generation = setup.putObject("generationConfig")
        generation.putArray("responseModalities").add("AUDIO")
        generation.putObject("speechConfig").putObject("voiceConfig").putObject("prebuiltVoiceConfig").put("voiceName", voice)
        setup.putObject("systemInstruction").putArray("parts").addObject().put("text", instruction)
        val tools = setup.putArray("tools")
        val functions = tools.addObject().putArray("functionDeclarations")
        JarvisTools.SPECS.forEach { spec ->
            val parameters = mutableMapOf<String, Any>("type" to "OBJECT", "properties" to (upperTypes(spec.properties) ?: emptyMap<String, Any>()))
            if (spec.required.isNotEmpty()) parameters["required"] = spec.required
            functions.add(MAPPER.valueToTree<JsonNode>(mapOf("name" to spec.name, "description" to spec.description, "parameters" to parameters)))
        }
        tools.addObject().putObject("googleSearch")
        setup.putObject("inputAudioTranscription")
        setup.putObject("outputAudioTranscription")
        // Mniej czuły start mowy – resztka echa z głośnika nie przerywa Jarvisowi.
        setup.putObject("realtimeInputConfig").putObject("automaticActivityDetection")
            .put("startOfSpeechSensitivity", "START_SENSITIVITY_LOW")
            .put("silenceDurationMs", SILENCE_MS)
        setup.putObject("contextWindowCompression").putObject("slidingWindow")
        return MAPPER.writeValueAsString(root)
    }

    private fun textMessage(text: String): String {
        val root = MAPPER.createObjectNode()
        root.putObject("realtimeInput").put("text", text)
        return MAPPER.writeValueAsString(root)
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
        private const val TAG = "JarvisLive"
        private const val URL = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        const val DEFAULT_MODEL = "gemini-3.8-live"
        const val DEFAULT_VOICE = "Charon"

        /** Głosy Gemini do wyboru w Opcjach (męskie pierwsze – Jarvis). */
        val VOICES = listOf("Charon", "Orus", "Fenrir", "Puck", "Kore", "Aoede")

        private const val IN_RATE = 16_000
        private const val OUT_RATE = 24_000
        private const val MIC_HANDOVER_MS = 250L
        private const val MAX_WAITING = 50 // 5 s mowy, zanim połączenie ruszy
        private const val SILENCE_MS = 600 // tyle ciszy = skończyłeś mówić
        private const val ACTIVE_DB = -42f // głośniej – ktoś mówi (rozmowa trwa)
        private const val IDLE_MS = 8_000L // tyle ciszy po odpowiedzi kończy rozmowę
        private const val CONNECT_TIMEOUT_MS = 8_000L

        private val MAPPER = ObjectMapper()
        private val HTTP = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()

        /** Gemini chce typów wielkimi literami („STRING”), narzędzia Jarvisa mają małe. */
        @Suppress("UNCHECKED_CAST")
        private fun upperTypes(value: Any?): Any? = when (value) {
            is Map<*, *> -> value.entries.associate { (k, v) ->
                k as String to if (k == "type" && v is String) v.uppercase() else upperTypes(v)
            }
            is List<*> -> value.map { upperTypes(it) }
            else -> value
        }
    }
}
