package pl.jarvis.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import pl.jarvis.brain.Brain
import pl.jarvis.brain.Ledger
import pl.jarvis.brain.LedgerStore
import pl.jarvis.recorder.RecorderState
import java.util.Locale

enum class AssistantState { IDLE, LISTENING, THINKING, SPEAKING }

data class ChatMessage(val fromUser: Boolean, val text: String)

/**
 * Rozmowa z Jarvisem: słuchanie (rozpoznawanie mowy po polsku), myślenie ([Brain])
 * i mówienie (synteza mowy). Na czas rozmowy rejestrator oddaje mikrofon.
 */
object Assistant {
    val state = MutableStateFlow(AssistantState.IDLE)
    val messages = MutableStateFlow(listOf(ChatMessage(false, "Systemy gotowe. Dotknij reaktora i powiedz, w czym pomóc.")))

    /** To, co Jarvis słyszy w trakcie mówienia (wynik częściowy). */
    val partial = MutableStateFlow("")

    /** Głośność mówiącego podczas słuchania, 0..1 – dla animacji reaktora. */
    val voiceLevel = MutableStateFlow(0f)

    /** Zwiększany po każdej zmianie danych (zarobki, płatności, notatki). */
    val ledgerVersion = MutableStateFlow(0)

    lateinit var ledger: Ledger
        private set

    private lateinit var brain: Brain
    private var appContext: Context? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null
    private var listenAfterSpeaking = false
    private var utteranceCounter = 0
    private val handler = Handler(Looper.getMainLooper())

    fun init(context: Context) {
        if (appContext != null) return
        val ctx = context.applicationContext
        appContext = ctx
        ledger = LedgerStore.load(ctx)
        brain = Brain(ledger)
        tts = TextToSpeech(ctx) init@{ status ->
            if (status != TextToSpeech.SUCCESS) return@init
            val engine = tts ?: return@init
            val result = engine.setLanguage(Locale.forLanguageTag("pl-PL"))
            ttsReady = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}

                override fun onDone(utteranceId: String?) {
                    handler.post { afterSpeaking() }
                }

                @Suppress("OVERRIDE_DEPRECATION")
                override fun onError(utteranceId: String?) {
                    handler.post { afterSpeaking() }
                }
            })
        }
    }

    fun isRecognitionAvailable(): Boolean = appContext?.let { SpeechRecognizer.isRecognitionAvailable(it) } ?: false

    /** Polecenie napisane zamiast powiedzianego. */
    fun submitText(text: String) {
        if (text.isBlank()) return
        stopEverything()
        handleText(text.trim())
    }

    fun toggleListening() {
        if (state.value == AssistantState.LISTENING) {
            recognizer?.stopListening()
        } else {
            startListening()
        }
    }

    fun startListening() {
        val ctx = appContext ?: return
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            say("Rozpoznawanie mowy jest niedostępne na tym urządzeniu. Napisz polecenie w polu poniżej.")
            return
        }
        tts?.stop()
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(ctx).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pl-PL")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        partial.value = ""
        setState(AssistantState.LISTENING)
        // Chwila dla rejestratora na oddanie mikrofonu.
        handler.postDelayed({ if (state.value == AssistantState.LISTENING) r.startListening(intent) }, 200)
    }

    fun removeEntry(id: Long) {
        if (ledger.remove(id)) dataChanged()
    }

    fun markNoteDone(id: Long) {
        ledger.markDone(id)
        dataChanged()
    }

    private fun handleText(text: String) {
        addMessage(ChatMessage(true, text))
        setState(AssistantState.THINKING)
        val reply = brain.handle(text)
        if (reply.changed) dataChanged()
        listenAfterSpeaking = reply.expectsAnswer
        say(reply.text)
    }

    private fun say(text: String) {
        addMessage(ChatMessage(false, text))
        val engine = tts
        if (engine != null && ttsReady) {
            setState(AssistantState.SPEAKING)
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis-${utteranceCounter++}")
        } else {
            afterSpeaking()
        }
    }

    private fun afterSpeaking() {
        if (state.value == AssistantState.LISTENING || state.value == AssistantState.IDLE) return
        if (listenAfterSpeaking) {
            // Jarvis zadał pytanie („Zgadza się?”) – od razu słuchamy odpowiedzi.
            listenAfterSpeaking = false
            startListening()
        } else {
            setState(AssistantState.IDLE)
        }
    }

    private fun stopEverything() {
        listenAfterSpeaking = false
        recognizer?.cancel()
        tts?.stop()
        setState(AssistantState.IDLE)
    }

    private fun dataChanged() {
        appContext?.let { LedgerStore.save(it, ledger) }
        ledgerVersion.update { it + 1 }
    }

    private fun addMessage(message: ChatMessage) {
        messages.update { (it + message).takeLast(100) }
    }

    private fun setState(newState: AssistantState) {
        state.value = newState
        if (newState != AssistantState.LISTENING) voiceLevel.value = 0f
        // Rejestrator nie nagrywa rozmowy z Jarvisem ani jego głosu.
        RecorderState.micPaused.value = newState != AssistantState.IDLE
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onRmsChanged(rmsdB: Float) {
            voiceLevel.value = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let {
                partial.value = it
            }
        }

        override fun onResults(results: Bundle?) {
            if (state.value != AssistantState.LISTENING) return
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            partial.value = ""
            if (text.isBlank()) {
                setState(AssistantState.IDLE)
                addMessage(ChatMessage(false, "Nic nie usłyszałem. Dotknij reaktora i spróbuj jeszcze raz."))
            } else {
                handleText(text)
            }
        }

        override fun onError(error: Int) {
            // Spóźniony błąd po przerwaniu słuchania – nie psujemy bieżącego stanu.
            if (state.value != AssistantState.LISTENING) return
            partial.value = ""
            setState(AssistantState.IDLE)
            val message = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                    "Nic nie usłyszałem. Dotknij reaktora i spróbuj jeszcze raz."
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
                    "Usługa rozpoznawania mowy nie odpowiada. Sprawdź internet albo napisz polecenie."
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Potrzebuję dostępu do mikrofonu."
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Rozpoznawanie mowy jest zajęte. Spróbuj za chwilę."
                SpeechRecognizer.ERROR_CLIENT -> return
                else -> "Błąd rozpoznawania mowy (kod $error). Napisz polecenie w polu poniżej."
            }
            addMessage(ChatMessage(false, message))
        }
    }
}
