package pl.jarvis.assistant

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * Dźwięki Jarvisa: miękki sygnał „słucham” generowany w aplikacji zamiast systemowych piknięć
 * oraz wyciszanie bipnięć systemowego rozpoznawania mowy.
 */
object Sounds {
    private const val RATE = 44_100

    /** Krótki, cichy sygnał: dwa łagodne tony z wybrzmieniem (jak w HUD-zie, nie jak w Windowsie). */
    private val chime: ShortArray by lazy {
        val notes = listOf(0.0 to 880.0, 0.07 to 1318.5) // start [s], częstotliwość [Hz]
        val length = (RATE * 0.45).toInt()
        val mix = DoubleArray(length)
        for ((start, freq) in notes) {
            val offset = (start * RATE).toInt()
            for (i in 0 until length - offset) {
                val t = i.toDouble() / RATE
                val attack = min(1.0, t / 0.008)
                val decay = exp(-t * 9.0)
                // Ton podstawowy + delikatna oktawa wyżej – „szklany”, miękki dźwięk.
                val wave = sin(2 * PI * freq * t) + 0.25 * sin(2 * PI * freq * 2 * t)
                mix[offset + i] += wave * attack * decay
            }
        }
        val peak = mix.maxOf { kotlin.math.abs(it) }.coerceAtLeast(1e-9)
        ShortArray(length) { (mix[it] / peak * 0.22 * Short.MAX_VALUE).toInt().toShort() }
    }

    fun playChime() {
        runCatching {
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
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
                .setBufferSizeInBytes(chime.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(chime, 0, chime.size)
            track.setNotificationMarkerPosition(chime.size - 1)
            track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(t: AudioTrack) = t.release()
                override fun onPeriodicNotification(t: AudioTrack) {}
            })
            track.play()
        }
    }

    // --- Wyciszanie piknięć systemowego rozpoznawania mowy ---------------------------

    private val streams = intArrayOf(AudioManager.STREAM_MUSIC, AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_SYSTEM)
    private val mutedByUs = mutableSetOf<Int>()

    /** Wycisza strumienie, na których system piszczy przy starcie i końcu słuchania. */
    fun muteSystemBeeps(context: Context) {
        val audio = context.getSystemService(AudioManager::class.java) ?: return
        for (stream in streams) {
            if (stream in mutedByUs || audio.isStreamMute(stream)) continue
            runCatching {
                audio.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, 0)
                mutedByUs += stream
            }
        }
    }

    /** Przywraca dźwięk – tylko tym strumieniom, które sami wyciszyliśmy. */
    fun unmuteSystemBeeps(context: Context) {
        val audio = context.getSystemService(AudioManager::class.java) ?: return
        for (stream in mutedByUs.toList()) {
            runCatching { audio.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0) }
        }
        mutedByUs.clear()
    }
}
