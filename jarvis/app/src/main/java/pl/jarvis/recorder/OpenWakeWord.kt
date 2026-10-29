package pl.jarvis.recorder

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.nio.FloatBuffer
import kotlin.random.Random

/**
 * Wykrywanie słowa „Hey Jarvis” modelem openWakeWord (github.com/dscripka/openWakeWord) –
 * darmowe, bez konta i bez internetu.
 *
 * To przeniesienie potoku z biblioteki Pythona: co 80 ms (1280 próbek 16 kHz)
 * dźwięk → melspektrogram → embedding mowy (model Google speech_embedding)
 * → ostatnie 16 embeddingów → model „hey jarvis” → wynik 0..1.
 */
class OpenWakeWord(melModel: ByteArray, embeddingModel: ByteArray, wakeModel: ByteArray) : Closeable {
    private val env = OrtEnvironment.getEnvironment()

    // Domyślnie każda sesja bierze tyle wątków, ile rdzeni – trzy modele trzymały ~4 rdzenie
    // non stop (bateria!). Kawałek co 80 ms jeden wątek liczy z dużym zapasem.
    private val options = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(1)
        setInterOpNumThreads(1)
    }
    private val mel = env.createSession(melModel, options)
    private val embedding = env.createSession(embeddingModel, options)
    private val wake = env.createSession(wakeModel, options)

    private val melBuffer = ArrayDeque<FloatArray>()
    private val features = ArrayDeque<FloatArray>()
    private val chunk = ShortArray(CHUNK)
    private var chunkFilled = 0
    private val context = FloatArray(CONTEXT) // ostatnie próbki poprzedniego kawałka
    private var hasContext = false
    private var predictions = 0

    /** Embeddingi 4 s losowego szumu – stan początkowy bufora cech (jak w oryginale). */
    private val initialFeatures: List<FloatArray>

    init {
        val random = Random(0)
        val noise = FloatArray(16_000 * 4) { random.nextInt(-1000, 1000).toFloat() }
        val frames = melspectrogram(noise)
        initialFeatures = (0..frames.size - MEL_WINDOW step 8).map { embed(frames.subList(it, it + MEL_WINDOW)) }
        reset()
    }

    /**
     * Czyści bufory – po pauzie (rozmowa z Jarvisem) zostałoby w nich „Hey Jarvis” sprzed rozmowy
     * i detektor odpaliłby drugi raz zaraz po wznowieniu.
     */
    fun reset() {
        melBuffer.clear()
        repeat(MEL_WINDOW) { melBuffer.addLast(FloatArray(MEL_BINS) { 1f }) }
        features.clear()
        features.addAll(initialFeatures)
        chunkFilled = 0
        hasContext = false
        predictions = 0
    }

    /** Przyjmuje dowolną liczbę próbek 16 kHz; zwraca najwyższy wynik z policzonych kawałków (albo 0). */
    fun process(samples: ShortArray): Float {
        var best = 0f
        for (s in samples) {
            chunk[chunkFilled++] = s
            if (chunkFilled == CHUNK) {
                chunkFilled = 0
                best = maxOf(best, processChunk())
            }
        }
        return best
    }

    private fun processChunk(): Float {
        // Melspektrogram z kawałka poprzedzonego 480 próbkami poprzedniego (jak `[-n_samples-160*3:]`).
        val input = if (hasContext) context + FloatArray(CHUNK) { chunk[it].toFloat() } else FloatArray(CHUNK) { chunk[it].toFloat() }
        for (k in 0 until CONTEXT) context[k] = chunk[CHUNK - CONTEXT + k].toFloat()
        hasContext = true

        melspectrogram(input).forEach { melBuffer.addLast(it) }
        while (melBuffer.size > MEL_MAX) melBuffer.removeFirst()

        features.addLast(embed(melBuffer.toList().takeLast(MEL_WINDOW)))
        while (features.size > FEATURES_MAX) features.removeFirst()

        val score = predict()
        // Oryginał ignoruje pierwsze 5 przewidywań (rozgrzewka buforów).
        return if (++predictions <= 5) 0f else score
    }

    private fun melspectrogram(audio: FloatArray): List<FloatArray> {
        OnnxTensor.createTensor(env, FloatBuffer.wrap(audio), longArrayOf(1, audio.size.toLong())).use { tensor ->
            mel.run(mapOf(mel.inputNames.first() to tensor)).use { result ->
                val out = result[0] as OnnxTensor
                val data = out.floatBuffer
                val frames = data.remaining() / MEL_BINS
                return List(frames) { f -> FloatArray(MEL_BINS) { b -> data.get(f * MEL_BINS + b) / 10f + 2f } }
            }
        }
    }

    private fun embed(window: List<FloatArray>): FloatArray {
        val flat = FloatArray(MEL_WINDOW * MEL_BINS)
        window.forEachIndexed { f, frame -> frame.copyInto(flat, f * MEL_BINS) }
        OnnxTensor.createTensor(env, FloatBuffer.wrap(flat), longArrayOf(1, MEL_WINDOW.toLong(), MEL_BINS.toLong(), 1)).use { tensor ->
            embedding.run(mapOf(embedding.inputNames.first() to tensor)).use { result ->
                val data = (result[0] as OnnxTensor).floatBuffer
                return FloatArray(data.remaining()).also { data.get(it) }
            }
        }
    }

    private fun predict(): Float {
        val recent = features.toList().takeLast(WAKE_FRAMES)
        val dim = recent.first().size
        val flat = FloatArray(WAKE_FRAMES * dim)
        recent.forEachIndexed { i, f -> f.copyInto(flat, i * dim) }
        OnnxTensor.createTensor(env, FloatBuffer.wrap(flat), longArrayOf(1, WAKE_FRAMES.toLong(), dim.toLong())).use { tensor ->
            wake.run(mapOf(wake.inputNames.first() to tensor)).use { result ->
                return (result[0] as OnnxTensor).floatBuffer.get(0)
            }
        }
    }

    override fun close() {
        wake.close()
        embedding.close()
        mel.close()
        options.close()
    }

    companion object {
        const val CHUNK = 1280 // 80 ms przy 16 kHz
        private const val CONTEXT = 480
        private const val MEL_BINS = 32
        private const val MEL_WINDOW = 76
        private const val MEL_MAX = 970
        private const val FEATURES_MAX = 120
        private const val WAKE_FRAMES = 16
    }
}
