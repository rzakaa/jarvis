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
import pl.jarvis.brain.AiBrain
import pl.jarvis.brain.ClaudeBrain
import pl.jarvis.brain.Ledger
import pl.jarvis.brain.LedgerStore
import pl.jarvis.brain.OpenAiCompatibleBrain
import pl.jarvis.recorder.AiProvider
import pl.jarvis.recorder.Prefs
import pl.jarvis.recorder.RecorderState
import java.util.Locale
import java.util.concurrent.Executors

enum class AssistantState { IDLE, LISTENING, THINKING, SPEAKING }

data class ChatMessage(val fromUser: Boolean, val text: String)

/**
 * Rozmowa z Jarvisem: słuchanie (rozpoznawanie mowy po polsku), myślenie (Claude, a bez
 * internetu lub klucza – lokalne komendy [Brain]) i mówienie (synteza mowy).
 *
 * Rozmowę zaczyna słowo „Jarvis” albo dotknięcie reaktora. Po każdej odpowiedzi Jarvis
 * słucha dalej; gdy nikt nic nie mówi, rozmowa się kończy i wraca nasłuchiwanie słowa „Jarvis”.
 * Na czas rozmowy rejestrator oddaje mikrofon.
 */
object Assistant {
    val state = MutableStateFlow(AssistantState.IDLE)
    val messages = MutableStateFlow(listOf<ChatMessage>())

    /** To, co Jarvis słyszy w trakcie mówienia (wynik częściowy). */
    val partial = MutableStateFlow("")

    /** Głośność mówiącego podczas słuchania, 0..1 – dla animacji reaktora. */
    val voiceLevel = MutableStateFlow(0f)

    /** Zwiększany po każdej zmianie danych (zarobki, płatności, notatki). */
    val ledgerVersion = MutableStateFlow(0)

    /** Czy działa AI (Claude). */
    val aiEnabled = MutableStateFlow(false)

    lateinit var ledger: Ledger
        private set

    private lateinit var localBrain: Brain
    private var ai: AiBrain? = null
    private var appContext: Context? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var utteranceCounter = 0

    /** Trwa rozmowa – po odpowiedzi słuchamy dalej. */
    private var conversation = false

    /** Bieżące słuchanie jest dopytaniem po odpowiedzi – cisza kończy rozmowę bez komunikatu. */
    private var followUp = false

    private var listeningEndedAt = 0L
    private const val CHIME_MS = 450L
    private const val UNMUTE_DELAY_MS = 350L

    fun init(context: Context) {
        if (appContext != null) return
        val ctx = context.applicationContext
        appContext = ctx
        ledger = LedgerStore.load(ctx)
        localBrain = Brain(ledger)
        reloadSettings()
        addMessage(
            ChatMessage(
                false,
                if (ai != null) "Systemy gotowe. Powiedz „Jarvis” albo dotknij reaktora."
                else "Systemy gotowe. Żebym był naprawdę inteligentny, wpisz klucz AI (Claude albo Kimi) w zakładce Opcje.",
            ),
        )
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

    /** Wczytuje klucz i model z ustawień (po ich zmianie). */
    fun reloadSettings() {
        val ctx = appContext ?: return
        val s = Prefs.assistantSettings(ctx)
        ai = when (s.provider) {
            AiProvider.CLAUDE -> s.claudeKey.takeIf { it.isNotBlank() }?.let {
                ClaudeBrain(it, ledger, if (s.smart) ClaudeBrain.MODEL_SMART else ClaudeBrain.MODEL_FAST)
            }
            AiProvider.KIMI -> if (s.kimiKey.isBlank() || s.kimiModel.isBlank()) null
            else OpenAiCompatibleBrain(s.kimiKey, s.kimiBaseUrl, s.kimiModel, ledger)
        }
        aiEnabled.value = ai != null
    }

    /** Wywoływane przez rejestrator, gdy usłyszy słowo „Jarvis”. */
    fun onWakeWord() {
        if (state.value != AssistantState.IDLE) return
        Sounds.playChime()
        conversation = true
        startListening(isFollowUp = false, delayMs = CHIME_MS)
    }

    /** Dotknięcie reaktora: start rozmowy albo jej zakończenie. */
    fun toggleListening() {
        when (state.value) {
            AssistantState.LISTENING -> {
                conversation = false
                recognizer?.stopListening()
            }
            AssistantState.SPEAKING -> {
                tts?.stop()
                conversation = false
                setState(AssistantState.IDLE)
            }
            else -> {
                Sounds.playChime()
                conversation = true
                startListening(isFollowUp = false, delayMs = CHIME_MS)
            }
        }
    }

    /** Polecenie napisane zamiast powiedzianego. */
    fun submitText(text: String) {
        if (text.isBlank()) return
        conversation = false
        recognizer?.cancel()
        tts?.stop()
        handleText(text.trim())
    }

    fun removeEntry(id: Long) {
        if (ledger.remove(id)) dataChanged()
    }

    fun markNoteDone(id: Long) {
        ledger.markDone(id)
        dataChanged()
    }

    private fun startListening(isFollowUp: Boolean, delayMs: Long = 300) {
        val ctx = appContext ?: return
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            conversation = false
            say("Rozpoznawanie mowy jest niedostępne na tym urządzeniu. Napisz polecenie w polu poniżej.")
            return
        }
        tts?.stop()
        followUp = isFollowUp
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
        // Chwila dla rejestratora na oddanie mikrofonu (i na wybrzmienie naszego sygnału).
        handler.postDelayed({
            if (state.value == AssistantState.LISTENING) {
                // Systemowe rozpoznawanie mowy piszczy przy starcie i końcu – wyciszamy to.
                Sounds.muteSystemBeeps(ctx)
                r.startListening(intent)
            }
        }, delayMs)
    }

    private fun handleText(text: String) {
        addMessage(ChatMessage(true, text))
        if (END_PHRASES.containsMatchIn(text.lowercase())) conversation = false
        setState(AssistantState.THINKING)
        val brain = ai
        if (brain == null) {
            val reply = localBrain.handle(text)
            onReply(reply.text, reply.changed, reply.expectsAnswer)
            return
        }
        worker.execute {
            try {
                val result = brain.handle(text)
                handler.post { onReply(result.text, result.changed, false) }
            } catch (e: Exception) {
                // Bez internetu albo błąd API – radzimy sobie lokalnymi komendami.
                handler.post {
                    val reply = localBrain.handle(text)
                    addMessage(ChatMessage(false, "(Brak połączenia z AI: ${e.message?.take(80)})"))
                    onReply(reply.text, reply.changed, reply.expectsAnswer)
                }
            }
        }
    }

    private fun onReply(text: String, changed: Boolean, expectsAnswer: Boolean) {
        if (changed) dataChanged()
        if (expectsAnswer) conversation = true
        say(text)
    }

    private fun say(text: String) {
        addMessage(ChatMessage(false, text))
        val engine = tts
        if (engine != null && ttsReady) {
            setState(AssistantState.SPEAKING)
            // Mówimy dopiero po przywróceniu dźwięku wyciszonego na czas słuchania.
            val wait = (UNMUTE_DELAY_MS + 50 - (System.currentTimeMillis() - listeningEndedAt)).coerceAtLeast(0)
            val id = "jarvis-${utteranceCounter++}"
            handler.postDelayed({
                if (state.value == AssistantState.SPEAKING) engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
            }, wait)
        } else {
            afterSpeaking()
        }
    }

    private fun afterSpeaking() {
        if (state.value == AssistantState.LISTENING || state.value == AssistantState.IDLE) return
        if (conversation) startListening(isFollowUp = true) else setState(AssistantState.IDLE)
    }

    private fun endConversation() {
        conversation = false
        followUp = false
        partial.value = ""
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
        val wasListening = state.value == AssistantState.LISTENING
        state.value = newState
        if (wasListening && newState != AssistantState.LISTENING) {
            // Końcowe piknięcie systemu gra tuż po zakończeniu słuchania – dźwięk wraca chwilę później.
            listeningEndedAt = System.currentTimeMillis()
            handler.postDelayed({
                if (state.value != AssistantState.LISTENING) appContext?.let { Sounds.unmuteSystemBeeps(it) }
            }, UNMUTE_DELAY_MS)
        }
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
                val wasFollowUp = followUp
                endConversation()
                if (!wasFollowUp) addMessage(ChatMessage(false, "Nic nie usłyszałem."))
            } else {
                handleText(text)
            }
        }

        override fun onError(error: Int) {
            // Spóźniony błąd po przerwaniu słuchania – nie psujemy bieżącego stanu.
            if (state.value != AssistantState.LISTENING) return
            val wasFollowUp = followUp
            endConversation()
            val message = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                    // Cisza po odpowiedzi to normalny koniec rozmowy.
                    if (wasFollowUp) return else "Nic nie usłyszałem. Powiedz „Jarvis” albo dotknij reaktora."
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

    /** Zwroty kończące rozmowę – po odpowiedzi Jarvis przestaje słuchać. */
    private val END_PHRASES = Regex("^(dzięki|dziękuję|dzieki|dziekuje|to wszystko|koniec|stop|nic|nieważne|niewazne|na razie)\\b")
}
