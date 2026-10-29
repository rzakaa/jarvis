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

    /** Czy słowo „Jarvis” jest teraz wykrywane (null = wyłączone, tekst = błąd). */
    val wakeWordStatus = MutableStateFlow<String?>(null)

    /** Najwyższy wynik „Hey Jarvis” z ostatnich ~2 s i próg wykrycia – podgląd na ekranie. */
    val wakeScore = MutableStateFlow(0f)
    val wakeThreshold = MutableStateFlow(0.5f)

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
    private const val CLAUDE_KEY = "claude_key"
    private const val WAKE_SENSITIVITY = "wake_sensitivity"
    private const val WAKE_WORD = "wake_word"
    private const val SMART_MODEL = "smart_model"
    private const val PROVIDER = "ai_provider"
    private const val KIMI_KEY = "kimi_key"
    private const val KIMI_BASE_URL = "kimi_base_url"
    private const val KIMI_MODEL = "kimi_model"
    private const val PIPER_VOICE = "piper_voice"

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

    /** Klucz API Claude (platform.claude.com). Trzymany w prywatnych danych aplikacji. */
    fun claudeKey(context: Context) = prefs(context).getString(CLAUDE_KEY, "").orEmpty()

    /** Jak łatwo Jarvis reaguje na „Hey Jarvis”. */
    fun wakeSensitivity(context: Context): WakeSensitivity {
        val name = prefs(context).getString(WAKE_SENSITIVITY, null) ?: return WakeSensitivity.MEDIUM
        return WakeSensitivity.entries.firstOrNull { it.name == name } ?: WakeSensitivity.MEDIUM
    }

    fun wakeWordEnabled(context: Context) = prefs(context).getBoolean(WAKE_WORD, true)

    /** true = mądrzejszy model (Sonnet), false = szybszy i tańszy (Haiku). */
    fun smartModel(context: Context) = prefs(context).getBoolean(SMART_MODEL, true)

    /** Który dostawca AI: Claude albo Kimi (lub inne API zgodne z OpenAI). */
    fun provider(context: Context): AiProvider {
        val name = prefs(context).getString(PROVIDER, null) ?: return AiProvider.CLAUDE
        return AiProvider.entries.firstOrNull { it.name == name } ?: AiProvider.CLAUDE
    }

    fun kimiKey(context: Context) = prefs(context).getString(KIMI_KEY, "").orEmpty()

    fun kimiBaseUrl(context: Context) =
        prefs(context).getString(KIMI_BASE_URL, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_KIMI_BASE_URL

    fun kimiModel(context: Context) = prefs(context).getString(KIMI_MODEL, "").orEmpty()

    fun saveAssistantSettings(context: Context, settings: AssistantSettings) =
        prefs(context).edit()
            .putString(PROVIDER, settings.provider.name)
            .putString(CLAUDE_KEY, settings.claudeKey.trim())
            .putBoolean(SMART_MODEL, settings.smart)
            .putString(KIMI_KEY, settings.kimiKey.trim())
            .putString(KIMI_BASE_URL, settings.kimiBaseUrl.trim())
            .putString(KIMI_MODEL, settings.kimiModel.trim())
            .putString(WAKE_SENSITIVITY, settings.wakeSensitivity.name)
            .putBoolean(WAKE_WORD, settings.wakeWord)
            .apply()

    fun assistantSettings(context: Context) = AssistantSettings(
        provider = provider(context),
        claudeKey = claudeKey(context),
        smart = smartModel(context),
        kimiKey = kimiKey(context),
        kimiBaseUrl = kimiBaseUrl(context),
        kimiModel = kimiModel(context),
        wakeSensitivity = wakeSensitivity(context),
        wakeWord = wakeWordEnabled(context),
    )

    /** true = głos Piper bass-high (gdy pobrany), false = głos systemowy. */
    fun piperVoice(context: Context) = prefs(context).getBoolean(PIPER_VOICE, true)

    fun setPiperVoice(context: Context, value: Boolean) = prefs(context).edit().putBoolean(PIPER_VOICE, value).apply()

    const val DEFAULT_KIMI_BASE_URL = "https://api.moonshot.ai/v1"
}

enum class AiProvider { CLAUDE, KIMI }

/** Próg wyniku detektora „Hey Jarvis” (0..1): niższy = łatwiej reaguje, ale częściej się myli. */
enum class WakeSensitivity(val label: String, val threshold: Float) {
    LOW("Niska", 0.65f),
    MEDIUM("Średnia", 0.5f),
    HIGH("Wysoka", 0.3f),
}

data class AssistantSettings(
    val provider: AiProvider,
    val claudeKey: String,
    val smart: Boolean,
    val kimiKey: String,
    val kimiBaseUrl: String,
    val kimiModel: String,
    val wakeSensitivity: WakeSensitivity,
    val wakeWord: Boolean,
)
