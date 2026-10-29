package pl.jarvis.assistant

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import pl.jarvis.recorder.Prefs
import java.io.IOException
import java.time.Duration
import java.util.concurrent.Executors

/**
 * Głos Microsoftu przez oficjalną usługę Azure Speech – te same głosy, co „Czytaj na głos” w Edge.
 * Do chmury idzie tylko tekst wypowiedzi Jarvisa; klucz leży w telefonie. Dźwięk (PCM) gra już
 * w trakcie pobierania. Gdy chmura nie odpowie (brak internetu, zły klucz), mówi Piper.
 */
object CloudVoice {
    enum class Voice(val id: String, val label: String) {
        MAREK("pl-PL-MarekNeural", "Marek"),
        ZOFIA("pl-PL-ZofiaNeural", "Zofia"),
        AGNIESZKA("pl-PL-AgnieszkaNeural", "Agnieszka"),
    }

    private const val TAG = "JarvisCloudVoice"
    private const val SAMPLE_RATE = 24_000

    /** Ostatni błąd chmury (do pokazania w Opcjach); null – działa. */
    val error = MutableStateFlow<String?>(null)

    private val http = OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(4))
        .readTimeout(Duration.ofSeconds(15))
        .build()
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "jarvis-cloud-voice") }
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var generation = 0

    @Volatile
    private var track: AudioTrack? = null

    fun isEnabled(context: Context) = Prefs.azureVoice(context) && Prefs.azureKey(context).isNotBlank()

    /**
     * Mówi [text]; [onDone] na głównym wątku po zakończeniu. Gdy chmura zawiedzie, zanim cokolwiek
     * zagrało – woła [fallback] (zamiast [onDone]). Przerwana wypowiedź ([stop]) nie woła niczego.
     */
    fun speak(context: Context, text: String, alarm: Boolean = false, onDone: () -> Unit, fallback: () -> Unit) {
        val gen = ++generation
        val key = Prefs.azureKey(context)
        val region = Prefs.azureRegion(context)
        val voice = Prefs.azureVoiceName(context)
        worker.execute {
            if (gen != generation) return@execute
            var out: AudioTrack? = null
            try {
                val ssml = "<speak version='1.0' xml:lang='pl-PL'><voice name='$voice'>${escape(text)}</voice></speak>"
                val request = Request.Builder()
                    .url("https://$region.tts.speech.microsoft.com/cognitiveservices/v1")
                    .header("Ocp-Apim-Subscription-Key", key)
                    .header("X-Microsoft-OutputFormat", "raw-24khz-16bit-mono-pcm")
                    .header("User-Agent", "Jarvis")
                    .post(ssml.toRequestBody("application/ssml+xml".toMediaType()))
                    .build()
                val started = System.currentTimeMillis()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException(
                            when (response.code) {
                                401 -> "zły klucz albo region (HTTP 401)"
                                429 -> "wyczerpany limit darmowych znaków (HTTP 429)"
                                else -> "HTTP ${response.code}"
                            },
                        )
                    }
                    val input = response.body?.byteStream() ?: throw IOException("pusta odpowiedź")
                    val player = AudioTrack.Builder()
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(if (alarm) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_ASSISTANT)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build(),
                        )
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(SAMPLE_RATE)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build(),
                        )
                        .setBufferSizeInBytes(SAMPLE_RATE) // ~0,5 s zapasu
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build()
                    out = player
                    track = player
                    player.play()
                    val buffer = ByteArray(4800)
                    var pending = 0 // nieparzysty bajt z poprzedniego kawałka (próbki mają 2 bajty)
                    var frames = 0L
                    var first = true
                    while (gen == generation) {
                        val n = input.read(buffer, pending, buffer.size - pending)
                        if (n < 0) break
                        if (first) {
                            first = false
                            Log.d(TAG, "pierwszy dźwięk po ${System.currentTimeMillis() - started} ms")
                        }
                        val total = pending + n
                        val even = total and 1.inv()
                        player.write(buffer, 0, even, AudioTrack.WRITE_BLOCKING)
                        frames += even / 2
                        pending = total - even
                        if (pending == 1) buffer[0] = buffer[even]
                    }
                    // Strumień rusza dopiero po zapełnieniu bufora – dopełniamy ciszą (jak w Piperze).
                    if (gen == generation) {
                        val silence = ByteArray(player.bufferSizeInFrames * 2)
                        player.write(silence, 0, silence.size, AudioTrack.WRITE_BLOCKING)
                    }
                    var lastHead = -1
                    var stalledSince = System.currentTimeMillis()
                    while (gen == generation && player.playbackHeadPosition < frames) {
                        val head = player.playbackHeadPosition
                        if (head != lastHead) {
                            lastHead = head
                            stalledSince = System.currentTimeMillis()
                        } else if (System.currentTimeMillis() - stalledSince > 2000) {
                            break
                        }
                        Thread.sleep(20)
                    }
                }
                error.value = null
                if (gen == generation) main.post(onDone)
            } catch (e: Exception) {
                Log.w(TAG, "chmura nie mówi: ${e.message}")
                error.value = e.message ?: e.javaClass.simpleName
                // Jeszcze nic nie zagrało – mówi Piper. W połowie zdania kończymy (rzadkie).
                if (gen == generation) main.post(if (out == null) fallback else onDone)
            } finally {
                out?.let {
                    runCatching { it.stop() }
                    it.release()
                }
                if (track === out) track = null
            }
        }
    }

    fun stop() {
        generation++
        track?.let { runCatching { it.pause(); it.flush() } }
    }

    private fun escape(text: String) = text
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")
}
