package pl.jarvis.recorder

import android.content.Context
import android.media.MediaRecorder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

const val SILENCE_DB = -90f

enum class RecorderStatus { STOPPED, LISTENING, RECORDING, PAUSED }

/**
 * Jak łatwo rejestrator uznaje dźwięk za mowę.
 * [marginDb] – o ile głośniej niż tło musi być, [minDb] – bezwzględne minimum głośności.
 */
enum class Sensitivity(val label: String, val marginDb: Float, val minDb: Float) {
    LOW("Niska", 14f, -42f),
    MEDIUM("Średnia", 10f, -50f),
    HIGH("Wysoka", 7f, -58f),
}

/** Z którego „źródła” Androida brać dźwięk. Urządzenia (i emulator) obsługują je różnie. */
enum class MicSource(val label: String, val androidSource: Int) {
    MIC("Mikrofon", MediaRecorder.AudioSource.MIC),
    VOICE_RECOGNITION("Mowa", MediaRecorder.AudioSource.VOICE_RECOGNITION),
    VOICE_COMMUNICATION("Rozmowa", MediaRecorder.AudioSource.VOICE_COMMUNICATION),
}

/** Stan rejestratora współdzielony przez usługę w tle i ekran aplikacji. */
object RecorderState {
    val status = MutableStateFlow(RecorderStatus.STOPPED)
    val levelDb = MutableStateFlow(SILENCE_DB)
    val sensitivity = MutableStateFlow(Sensitivity.MEDIUM)
    val micSource = MutableStateFlow(MicSource.MIC)

    /** Mikrofon chwilowo potrzebny komuś innemu (rozmowa z Jarvisem) – rejestrator czeka. */
    val micPaused = MutableStateFlow(false)

    /** Z czym faktycznie otworzył się mikrofon, np. „48000 Hz · Mikrofon”. */
    val micInfo = MutableStateFlow<String?>(null)
    val error = MutableStateFlow<String?>(null)

    /** Ile nagrań odrzucono jako za krótkie (od uruchomienia aplikacji). */
    val discarded = MutableStateFlow(0)

    /** Plik, który właśnie jest nagrywany – ukrywamy go na liście, dopóki się nie zakończy. */
    val activeFile = MutableStateFlow<File?>(null)

    /** Zwiększany po każdej zmianie nagrań, żeby lista na ekranie się odświeżyła. */
    val recordingsVersion = MutableStateFlow(0)

    fun recordingsChanged() = recordingsVersion.update { it + 1 }
}

object Prefs {
    private const val WANTS_RECORDING = "wants_recording"
    private const val SENSITIVITY = "sensitivity"
    private const val MIC_SOURCE = "mic_source"

    private fun prefs(context: Context) =
        context.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    /** Czy użytkownik chce, żeby Jarvis nasłuchiwał (nawet jeśli system go chwilowo zatrzymał). */
    fun wantsRecording(context: Context) = prefs(context).getBoolean(WANTS_RECORDING, false)

    fun setWantsRecording(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(WANTS_RECORDING, value).apply()

    fun sensitivity(context: Context): Sensitivity {
        val name = prefs(context).getString(SENSITIVITY, null) ?: return Sensitivity.MEDIUM
        return Sensitivity.entries.firstOrNull { it.name == name } ?: Sensitivity.MEDIUM
    }

    fun setSensitivity(context: Context, value: Sensitivity) =
        prefs(context).edit().putString(SENSITIVITY, value.name).apply()

    fun micSource(context: Context): MicSource {
        val name = prefs(context).getString(MIC_SOURCE, null) ?: return MicSource.MIC
        return MicSource.entries.firstOrNull { it.name == name } ?: MicSource.MIC
    }

    fun setMicSource(context: Context, value: MicSource) =
        prefs(context).edit().putString(MIC_SOURCE, value.name).apply()
}
