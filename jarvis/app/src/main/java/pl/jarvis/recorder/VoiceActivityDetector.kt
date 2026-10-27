package pl.jarvis.recorder

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Prosty detektor mowy oparty na głośności.
 *
 * Poziom tła to najcichszy moment z ostatnich ~5 s. W mowie zawsze są krótkie
 * przerwy między słowami, więc tło zostaje na poziomie otoczenia. Stały hałas
 * (wentylator, suszarka) przerw nie ma, więc po kilku sekundach sam staje się tłem
 * i przestaje być nagrywany – a mowa głośniejsza od niego nadal jest wykrywana.
 * W kolejnym etapie zastąpimy go modelem Silero VAD.
 */
class VoiceActivityDetector {
    private val blockMinimums = ArrayDeque<Float>()
    private var blockMin = Float.MAX_VALUE
    private var blockFrames = 0
    private var noiseFloorDb = -60f

    fun isSpeech(frameDb: Float, sensitivity: Sensitivity): Boolean {
        blockMin = min(blockMin, frameDb)
        if (++blockFrames == BLOCK_FRAMES) {
            blockMinimums.addLast(blockMin)
            if (blockMinimums.size > WINDOW_BLOCKS) blockMinimums.removeFirst()
            noiseFloorDb = blockMinimums.min().coerceIn(-80f, -25f)
            blockMin = Float.MAX_VALUE
            blockFrames = 0
        }
        return frameDb > noiseFloorDb + sensitivity.marginDb && frameDb > sensitivity.minDb
    }

    private companion object {
        const val BLOCK_FRAMES = 16 // ~0,5 s
        const val WINDOW_BLOCKS = 10 // ~5 s
    }
}

/** Głośność ramki w dBFS (0 = maksimum, ok. -90 = cisza). */
fun levelDb(samples: ShortArray, count: Int): Float {
    if (count <= 0) return SILENCE_DB
    var sum = 0.0
    for (i in 0 until count) {
        val s = samples[i] / 32768.0
        sum += s * s
    }
    val rms = sqrt(sum / count)
    if (rms <= 0.0) return SILENCE_DB
    return max(SILENCE_DB, (20 * log10(rms)).toFloat())
}
