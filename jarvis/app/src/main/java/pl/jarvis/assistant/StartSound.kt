package pl.jarvis.assistant

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Dźwięk aktywacji po „Hey Jarvis” (rozmowa na żywo): krótki narastający „szum systemu” i dwa
 * czyste tony – w klimacie startu interfejsu. Generowany w kodzie, bez plików, ok. 0,5 s.
 */
object StartSound {
    private const val RATE = 24_000

    private val samples: ShortArray by lazy { build() }

    fun play() {
        runCatching {
            val pcm = samples
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(pcm, 0, pcm.size)
            track.setNotificationMarkerPosition(pcm.size)
            track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(t: AudioTrack) {
                    t.release()
                }

                override fun onPeriodicNotification(t: AudioTrack) {}
            })
            track.play()
        }
    }

    private fun build(): ShortArray {
        val sweepEnd = (RATE * 0.22).toInt()
        val chime1 = (RATE * 0.20).toInt()
        val chime2 = (RATE * 0.30).toInt()
        val total = (RATE * 0.55).toInt()
        val out = ShortArray(total)
        var phase = 0.0
        for (i in 0 until total) {
            var v = 0.0
            // Narastający świst 300 → 1400 Hz, łagodnie wchodzi i gaśnie.
            if (i < sweepEnd) {
                val p = i.toDouble() / sweepEnd
                val freq = 300 + 1100 * p * p
                phase += 2 * PI * freq / RATE
                v += 0.22 * sin(phase) * sin(PI * p)
            }
            // Dwa krótkie, czyste tony (E6, A6) z wybrzmieniem.
            if (i >= chime1) {
                val dt = (i - chime1).toDouble() / RATE
                v += 0.18 * sin(2 * PI * 1318.5 * dt) * exp(-dt * 14) * (1 - exp(-dt * 400))
            }
            if (i >= chime2) {
                val dt = (i - chime2).toDouble() / RATE
                v += 0.16 * sin(2 * PI * 1760.0 * dt) * exp(-dt * 10) * (1 - exp(-dt * 400))
            }
            // Ściszenie na samym końcu – bez trzasku.
            val fade = ((total - i).toDouble() / (RATE * 0.03)).coerceAtMost(1.0)
            out[i] = (v * fade * 32767 * 0.8).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }
}
