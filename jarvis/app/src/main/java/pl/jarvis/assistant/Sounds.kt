package pl.jarvis.assistant

import android.content.Context
import android.media.AudioManager

/**
 * Wyciszanie bipnięć systemowego rozpoznawania mowy. Jarvis nie gra żadnych sygnałów –
 * to, że słucha, widać na reaktorze.
 */
object Sounds {
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
