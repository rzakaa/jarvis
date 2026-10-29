package pl.jarvis.assistant

import android.content.Context
import android.media.AudioManager

/**
 * Wyciszanie bipnięć systemowego rozpoznawania mowy. Jarvis nie gra żadnych sygnałów –
 * to, że słucha, widać na reaktorze.
 *
 * Wyciszone strumienie zapisujemy w ustawieniach: gdy aplikacja zostanie zamknięta w trakcie
 * słuchania (reinstalacja, awaria), przy następnym starcie przywracamy dźwięk.
 */
object Sounds {
    private val streams = intArrayOf(AudioManager.STREAM_MUSIC, AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_SYSTEM)
    private val mutedByUs = mutableSetOf<Int>()

    private const val KEY_MUTED = "muted_streams"
    private const val KEY_FIXED = "mute_fix_v21"

    private fun prefs(context: Context) = context.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    private fun save(context: Context) =
        prefs(context).edit().putString(KEY_MUTED, mutedByUs.joinToString(",")).apply()

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
        save(context)
    }

    /** Przywraca dźwięk – tylko tym strumieniom, które sami wyciszyliśmy. */
    fun unmuteSystemBeeps(context: Context) {
        val audio = context.getSystemService(AudioManager::class.java) ?: return
        for (stream in mutedByUs.toList()) {
            runCatching { audio.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0) }
        }
        mutedByUs.clear()
        save(context)
    }

    /** Przy starcie aplikacji: przywraca dźwięk wyciszony przed jej zamknięciem. */
    fun restoreAfterRestart(context: Context) {
        val audio = context.getSystemService(AudioManager::class.java) ?: return
        val saved = prefs(context).getString(KEY_MUTED, "").orEmpty()
            .split(",").mapNotNull { it.toIntOrNull() }.toMutableSet()
        // Wersje do 0.20 nie zapisywały wyciszenia – po reinstalacji powiadomienia i dźwięki
        // systemowe zostawały wyciszone na stałe. Raz przywracamy je na pewno.
        if (!prefs(context).getBoolean(KEY_FIXED, false)) {
            saved += AudioManager.STREAM_NOTIFICATION
            saved += AudioManager.STREAM_SYSTEM
            prefs(context).edit().putBoolean(KEY_FIXED, true).apply()
        }
        for (stream in saved) {
            if (audio.isStreamMute(stream)) runCatching { audio.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0) }
        }
        prefs(context).edit().remove(KEY_MUTED).apply()
    }
}
