package pl.jarvis.assistant

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import pl.jarvis.brain.Brain
import pl.jarvis.brain.AiBrain
import pl.jarvis.brain.ClaudeBrain
import pl.jarvis.brain.Ledger
import pl.jarvis.brain.LedgerStore
import pl.jarvis.brain.JarvisTools
import pl.jarvis.brain.OpenAiCompatibleBrain
import pl.jarvis.brain.Reminder
import pl.jarvis.recorder.AiProvider
import pl.jarvis.recorder.AssistantSettings
import pl.jarvis.recorder.Prefs
import pl.jarvis.recorder.RecorderState
import pl.jarvis.recorder.Transcriber
import java.io.File
import java.time.LocalDateTime
import java.util.Locale
import java.util.concurrent.Executors

enum class AssistantState { IDLE, LISTENING, THINKING, SPEAKING }

/** [source] – kto odpowiedział: „kimi”, „claude”, „lokalnie”. */
data class ChatMessage(val fromUser: Boolean, val text: String, val source: String? = null)

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
    private var aiName = "lokalnie"
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

    /** Jarvis mówi odzew na „Hey Jarvis” – po nim zaczyna słuchać polecenia. */
    private var greeting = false

    /** Krótkie odzewy na „Hey Jarvis”. */
    private val GREETINGS = listOf("No?", "Słucham.", "Dawaj.", "Co tam?", "Mów.")

    /** Ile razy po cichu ponowiliśmy słuchanie po „Hey Jarvis”, gdy nic nie usłyszało. */
    private var retries = 0

    private var listeningEndedAt = 0L

    /** Trwa pobudka – Jarvis budzi co [WAKE_REPEAT_MS], dopóki użytkownik się nie odezwie. */
    private var waking: Reminder? = null
    private var wakeAttempt = 0
    private val wakeAgain = Runnable { wakeUp() }
    private var awake: PowerManager.WakeLock? = null

    /** Odpowiedź mówiona zdanie po zdaniu w trakcie pisania przez AI. [replyId] odróżnia kolejne odpowiedzi. */
    private val chunks = ArrayDeque<String>()
    private var chunkPlaying = false
    private var streamDone = false
    private var replyId = 0

    /** Jarvis zaproponował poranny przegląd – „nie” kończy rozmowę. */
    private var morningOffer = false

    /** Głośność budzika sprzed pobudki – wraca po niej (null = nie zmienialiśmy). */
    private var alarmVolumeBefore: Int? = null

    /** Ile razy budzimy, zanim damy spokój (co 2 min – razem ok. 12 min). */
    private const val MAX_WAKE_ATTEMPTS = 6
    private const val WAKE_REPEAT_MS = 2 * 60_000L

    /** Przypomnienie spóźnione o więcej (np. telefon był wyłączony) – już go nie mówimy. */
    private const val TOO_LATE_MIN = 120L

    /** Chwila dla rejestratora na oddanie mikrofonu przed startem rozpoznawania. */
    private const val MIC_HANDOVER_MS = 250L
    /** Najdłużej tyle czekamy na przepisanie rozmowy sprzed chwili. */
    private const val FRESH_WAIT_MS = 20_000L

    /** Systemowe piknięcie końca słuchania potrafi przyjść z opóźnieniem – dźwięk wraca dopiero po nim. */
    private const val UNMUTE_DELAY_MS = 700L

    /** Ile razy po cichu słuchamy ponownie po „Hey Jarvis”, zanim się poddamy. */
    private const val MAX_RETRIES = 3

    private const val TAG = "JarvisAssistant"

    /** Po tylu ms od początku mówienia zaczynamy słuchać „stop” (rejestrator musi oddać mikrofon). */
    private const val BARGE_IN_DELAY_MS = 400L

    /** To, co Jarvis właśnie mówi – jego własne „stop” z głośnika nie może go przerwać. */
    @Volatile
    private var spokenNow = ""

    /** Rozmowa na żywo przez Gemini (null = zwykła: rozpoznawanie mowy → AI → synteza). */
    private var live: LiveVoice? = null

    /** Narzędzia dla rozmowy na żywo – te same co u Kimi/Claude, działają na tych samych danych. */
    private val liveTools by lazy { JarvisTools(ledger) { LocalDateTime.now() } }

    fun init(context: Context) {
        if (appContext != null) return
        val ctx = context.applicationContext
        appContext = ctx
        Sounds.restoreAfterRestart(ctx)
        ledger = LedgerStore.load(ctx)
        localBrain = Brain(ledger)
        reloadSettings()
        PiperVoice.prepare(ctx)
        // Nagrania: tekst → skrót, zadania i fakty; Jarvis może w nich szukać.
        Transcriber.prepare(ctx)
        Transcriber.onTranscribed = { c, wav, text ->
            if (Prefs.autoSummaries(c)) RecordingNotes.process(c, wav, text)
            if (Prefs.commitments(c)) RecordingNotes.commitments(c, wav, text)
        }
        JarvisTools.recordingsSearch = { from, to, query -> RecordingNotes.search(ctx, from, to, query) }
        JarvisTools.recordingRead = { day, time -> RecordingNotes.read(ctx, day, time) }
        JarvisTools.recentConversations = { RecordingNotes.recent(ctx) }
        NotificationStore.init(ctx)
        JarvisTools.phone = { name, input -> PhoneTools.run(ctx, name, input) }
        ClaudeBrain.onCost = { Prefs.addClaudeSpent(ctx, it) }
        OpenAiCompatibleBrain.timing = { Log.d(TAG, "${System.currentTimeMillis() % 100_000} $it") }        // Alarmy znikają przy reinstalacji aplikacji – ustawiamy najbliższe przypomnienie od nowa.
        Reminders.schedule(ctx, ledger)
        addMessage(
            ChatMessage(
                false,
                if (ai != null) "Systemy gotowe. Powiedz „Hey Jarvis” albo dotknij reaktora."
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
        ai = buildBrain(s, ledger)
        aiName = if (ai == null) "lokalnie" else s.provider.name.lowercase()
        aiEnabled.value = ai != null
    }

    /** Tworzy mózg AI według ustawień (null = brak klucza/modelu). Używane też przez „Testuj AI”. */
    fun buildBrain(s: AssistantSettings, ledger: Ledger): AiBrain? = when (s.provider) {
        AiProvider.CLAUDE -> s.claudeKey.takeIf { it.isNotBlank() }?.let {
            ClaudeBrain(it, ledger, if (s.smart) ClaudeBrain.MODEL_SMART else ClaudeBrain.MODEL_FAST)
        }
        AiProvider.KIMI -> if (s.kimiKey.isBlank() || s.kimiModel.isBlank()) null
        else OpenAiCompatibleBrain(s.kimiKey, s.kimiBaseUrl, s.kimiModel, ledger)
    }

    /** Wywoływane przez rejestrator, gdy usłyszy słowo „Jarvis”. */
    fun onWakeWord() {
        if (state.value != AssistantState.IDLE) return
        conversation = true
        if (appContext?.let { liveReady(it) } == true) {
            startLive()
            return
        }
        ai?.warmUp()
        // Jarvis się odzywa („Słucham?”), a dopiero potem słucha polecenia.
        greeting = true
        say(GREETINGS.random(), show = false)
    }

    /** Nadeszła pora przypomnienia lub pobudki (alarm systemowy). */
    fun onRemindersDue() {
        val now = LocalDateTime.now()
        val due = ledger.pendingReminders().filter { !it.at.isAfter(now.plusSeconds(30)) }
        if (due.isEmpty()) {
            appContext?.let { Reminders.schedule(it, ledger) }
            return
        }
        keepAwake(3 * 60_000L)
        // W trakcie rozmowy nie przerywamy – mówimy, gdy się skończy.
        if (state.value != AssistantState.IDLE) {
            handler.postDelayed({ onRemindersDue() }, 30_000L)
            return
        }
        due.forEach { ledger.markReminderDone(it.id) }
        dataChanged()
        val fresh = due.filter { it.at.isAfter(now.minusMinutes(TOO_LATE_MIN)) }
        val wake = fresh.lastOrNull { it.wake }
        val notes = fresh.filter { !it.wake }
        when {
            wake != null -> {
                handler.removeCallbacks(wakeAgain)
                waking = wake.copy(text = (listOf(wake.text) + notes.map { it.text }).filter { it.isNotBlank() }.joinToString("; "))
                wakeAttempt = 0
                keepAwake((MAX_WAKE_ATTEMPTS + 1) * WAKE_REPEAT_MS)
                wakeUp()
            }
            notes.isNotEmpty() -> {
                val what = notes.joinToString("; ") { it.text }
                speakFromAi("[PRZYPOMNIENIE – jest ${clock(now)}. Przypomnij teraz użytkownikowi: $what]", "Przypominam: $what.", alarm = false)
            }
        }
    }

    /** Kolejna próba pobudki – Jarvis budzi po swojemu (pogoda, plan dnia), coraz bardziej stanowczo. */
    private fun wakeUp() {
        val w = waking ?: return
        if (state.value != AssistantState.IDLE) {
            handler.postDelayed(wakeAgain, 30_000L)
            return
        }
        if (++wakeAttempt > MAX_WAKE_ATTEMPTS) {
            stopWaking()
            return
        }
        raiseAlarmVolume(if (wakeAttempt == 1) 0.75f else 1f)
        val now = LocalDateTime.now()
        val reason = w.text.takeIf { it.isNotBlank() }?.let { ", powód / uwagi: $it" }.orEmpty()
        speakFromAi(
            "[POBUDKA – jest ${clock(now)}, próba $wakeAttempt z $MAX_WAKE_ATTEMPTS$reason. Obudź użytkownika.]",
            if (wakeAttempt == 1) "Dzień dobry. Jest ${clock(now)}, pora wstawać." else "Pobudka, stary! Jest ${clock(now)}.",
            alarm = true,
        )
    }

    /**
     * Pobudka mówi kanałem budzika, a ten bywa ustawiony cicho – podkręcamy go na czas pobudki
     * (pierwsza próba [fraction] = 75%, kolejne na cały regulator). Nigdy nie ściszamy.
     */
    private fun raiseAlarmVolume(fraction: Float) {
        val audio = appContext?.getSystemService(AudioManager::class.java) ?: return
        runCatching {
            val current = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            if (alarmVolumeBefore == null) alarmVolumeBefore = current
            val target = (audio.getStreamMaxVolume(AudioManager.STREAM_ALARM) * fraction).toInt()
            if (target > current) audio.setStreamVolume(AudioManager.STREAM_ALARM, target, 0)
        }
    }

    private fun stopWaking() {
        alarmVolumeBefore?.let { before ->
            appContext?.getSystemService(AudioManager::class.java)?.let { runCatching { it.setStreamVolume(AudioManager.STREAM_ALARM, before, 0) } }
        }
        alarmVolumeBefore = null
        waking = null
        handler.removeCallbacks(wakeAgain)
        awake?.let { if (it.isHeld) it.release() }
        awake = null
    }

    /** Jarvis sam zaczyna mówić (przypomnienie, pobudka): AI układa wypowiedź, potem słuchamy odpowiedzi. */
    private fun speakFromAi(prompt: String, fallback: String, alarm: Boolean) {
        conversation = true
        greeting = false
        setState(AssistantState.THINKING)
        val brain = ai
        if (brain == null) {
            say(fallback, "lokalnie", alarm = alarm)
            return
        }
        val name = aiName
        worker.execute {
            val result = runCatching { brain.handle(prompt) }.getOrNull()
            handler.post {
                if (result?.changed == true) dataChanged()
                val text = result?.text?.takeIf { it.isNotBlank() }
                say(text ?: fallback, if (text != null) name else "lokalnie", alarm = alarm)
            }
        }
    }

    /** Telefon nie może zasnąć w trakcie przypomnienia czy pobudki (gdy rejestrator jest wyłączony). */
    private fun keepAwake(ms: Long) {
        val ctx = appContext ?: return
        val lock = awake ?: ctx.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Jarvis:przypomnienie")
            .apply { setReferenceCounted(false) }
            .also { awake = it }
        lock.acquire(ms)
    }

    private fun clock(t: LocalDateTime) = "%02d:%02d".format(t.hour, t.minute)

    /** Dotknięcie reaktora: start rozmowy albo jej zakończenie. */
    fun toggleListening() {
        when (state.value) {
            AssistantState.LISTENING -> {
                if (live != null) return stopLive()
                conversation = false
                recognizer?.stopListening()
            }
            AssistantState.SPEAKING -> if (live != null) stopLive() else silence()
            else -> {
                if (live != null) return stopLive()
                conversation = true
                if (appContext?.let { liveReady(it) } == true) return startLive()
                ai?.warmUp()
                startListening(isFollowUp = false, delayMs = MIC_HANDOVER_MS)
            }
        }
    }

    /** Polecenie napisane zamiast powiedzianego. */
    fun submitText(text: String) {
        if (text.isBlank()) return
        stopLive()
        conversation = false
        greeting = false
        recognizer?.cancel()
        tts?.stop()
        PiperVoice.stop()
        CloudVoice.stop()
        handleText(text.trim())
    }

    /** Zadania i fakty wyłapane z nagranej rozmowy (wołane w tle). */
    fun importFromRecording(tasks: List<String>, facts: List<String>) {
        if (tasks.isEmpty() && facts.isEmpty()) return
        handler.post {
            val now = LocalDateTime.now()
            tasks.forEach { ledger.addNote(now, it) }
            facts.forEach { ledger.addMemory(now, it) }
            dataChanged()
        }
    }

    /** Obietnica wyłapana z nagranej rozmowy – przypomnienie i (bez terminu) zadanie; widać to w czacie. */
    fun addFromRecording(at: LocalDateTime, text: String, task: String?) {
        handler.post {
            val duplicate = ledger.pendingReminders().any { PhoneTools.normalize(it.text) == PhoneTools.normalize(text) }
            if (duplicate) return@post
            ledger.addReminder(at, text, wake = false)
            task?.let { ledger.addNote(LocalDateTime.now(), it) }
            dataChanged()
            addMessage(ChatMessage(false, "Wyłapałem z rozmowy: $text Przypomnę ${at.toLocalDate()} o ${clock(at)}.", "z nagrania"))
        }
    }

    fun removeEntry(id: Long) {
        if (ledger.remove(id)) dataChanged()
    }

    fun markNoteDone(id: Long) {
        ledger.markDone(id)
        dataChanged()
    }

    private fun startListening(isFollowUp: Boolean, delayMs: Long = MIC_HANDOVER_MS, isRetry: Boolean = false) {
        val ctx = appContext ?: return
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            conversation = false
            say("Rozpoznawanie mowy jest niedostępne na tym urządzeniu. Napisz polecenie w polu poniżej.")
            return
        }
        tts?.stop()
        PiperVoice.stop()
        CloudVoice.stop()
        followUp = isFollowUp
        if (!isRetry) retries = 0
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(ctx).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pl-PL")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Usługa Google czyta te wartości jako Integer (Long jest po cichu ignorowany).
            // Minimum to czas, przez który słuchamy nawet w ciszy – po „Hey Jarvis” trzeba
            // chwili, żeby zacząć mówić (przy 2 s usługa zamykała mikrofon przed pierwszym słowem).
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, if (isFollowUp) 4000 else 5000)
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
        // Odezwał się – pobudka zrobiła swoje (o „jeszcze 10 minut” zadba AI, ustawiając nową).
        val wakeReply = waking != null
        stopWaking()
        PhoneTools.takePending() // nic starego nie może ruszyć przy nowym poleceniu
        addMessage(ChatMessage(true, text))
        if (END_PHRASES.containsMatchIn(text.lowercase())) conversation = false
        // Odmowa porannego przeglądu – Jarvis żegna się i kończy pobudkę.
        if (morningOffer && NO_PHRASES.containsMatchIn(text.lowercase())) conversation = false
        morningOffer = wakeReply
        // AI musi wiedzieć, że to odpowiedź na pobudkę – wtedy proponuje poranny przegląd.
        val forAi = if (wakeReply) "[Odpowiedź na pobudkę] $text" else text
        setState(AssistantState.THINKING)
        val brain = ai
        if (brain == null) {
            val reply = localBrain.handle(text)
            onReply(reply.text, reply.changed, reply.expectsAnswer, "lokalnie")
            return
        }
        val name = aiName
        val id = ++replyId
        chunks.clear()
        chunkPlaying = false
        streamDone = false
        // Mówienie zdanie po zdaniu – tylko głosem Microsoftu albo Piperem (systemowy mówi całość naraz).
        val streaming = appContext?.let { CloudVoice.isEnabled(it) || (Prefs.piperVoice(it) && PiperVoice.isReady) } == true
        worker.execute {
            try {
                val started = System.currentTimeMillis()
                fun mark(what: String) = Log.d(TAG, "$what po ${System.currentTimeMillis() - started} ms")
                var announced = false
                // Rozmowa sprzed chwili jeszcze się przepisuje (zwykle tylko końcówka) – czekamy na nią,
                // żeby Jarvis wiedział, o czym mówiono. Tylko gdy pytanie jej dotyczy – inaczej szkoda czasu.
                appContext?.takeIf { ABOUT_CONVERSATION.containsMatchIn(text.lowercase()) }?.let { c ->
                    Transcriber.awaitFresh(c, FRESH_WAIT_MS) {
                        announced = true
                        handler.post { if (state.value == AssistantState.THINKING) speakAside("Chwileczkę, przepisuję rozmowę.") }
                    }
                }
                mark("rozmowy przepisane")
                var streamed = false
                val result = brain.handle(
                    forAi,
                    onSearching = {
                        if (!announced) {
                            announced = true
                            handler.post { if (state.value == AssistantState.THINKING) speakAside("Chwileczkę, sprawdzam.") }
                        }
                    },
                    onSentence = { sentence ->
                        if (streaming) {
                            if (!streamed) mark("pierwsze zdanie")
                            streamed = true
                            announced = true
                            handler.post { if (replyId == id) enqueueChunk(sentence) }
                        }
                    },
                )
                mark("cała odpowiedź")
                handler.post {
                    if (replyId != id) return@post
                    if (streamed) {
                        if (result.changed) dataChanged()
                        addMessage(ChatMessage(false, result.text, name))
                        finishStream(result.rest)
                    } else {
                        onReply(result.text, result.changed, false, name)
                    }
                }
            } catch (e: Exception) {
                // Bez internetu albo błąd API – radzimy sobie lokalnymi komendami.
                handler.post {
                    val reply = localBrain.handle(text)
                    addMessage(ChatMessage(false, "Błąd AI: ${e.message?.take(400) ?: e.javaClass.simpleName}", "$name – błąd"))
                    onReply(reply.text, reply.changed, reply.expectsAnswer, "lokalnie")
                }
            }
        }
    }

    private fun onReply(text: String, changed: Boolean, expectsAnswer: Boolean, source: String) {
        if (changed) dataChanged()
        if (expectsAnswer) conversation = true
        say(text, source)
    }

    private fun say(text: String, source: String? = null, show: Boolean = true, alarm: Boolean = false) {
        spokenNow = text
        if (show) addMessage(ChatMessage(false, text, source))
        val wait = (UNMUTE_DELAY_MS + 50 - (System.currentTimeMillis() - listeningEndedAt)).coerceAtLeast(0)
        val ctx = appContext
        val cloud = ctx != null && CloudVoice.isEnabled(ctx)
        val piper = ctx != null && Prefs.piperVoice(ctx) && PiperVoice.isReady
        if (!cloud && !piper && (tts == null || !ttsReady)) {
            afterSpeaking()
            return
        }
        setState(AssistantState.SPEAKING)
        // Mówimy dopiero po przywróceniu dźwięku wyciszonego na czas słuchania.
        handler.postDelayed({
            if (state.value != AssistantState.SPEAKING) return@postDelayed
            if (cloud && ctx != null) {
                // Głos Microsoftu; bez internetu – Piper albo systemowy.
                CloudVoice.speak(ctx, text, alarm, onDone = { afterSpeaking() }) {
                    if (state.value == AssistantState.SPEAKING) speakLocally(text, alarm)
                }
            } else {
                speakLocally(text, alarm)
            }
        }, wait)
    }

    /** Kolejne zdanie odpowiedzi, która jeszcze się pisze – mówimy od razu, po kolei. */
    private fun enqueueChunk(text: String) {
        // Użytkownik przerwał (dotknął reaktora, zaczął mówić) – resztę odpuszczamy.
        if (state.value != AssistantState.THINKING && state.value != AssistantState.SPEAKING) return
        chunks.addLast(text)
        if (!chunkPlaying) playNextChunk()
    }

    /** Odpowiedź dopisana do końca: [rest] to to, czego jeszcze nie mówiliśmy. */
    private fun finishStream(rest: String) {
        if (state.value != AssistantState.THINKING && state.value != AssistantState.SPEAKING) return
        if (rest.isNotBlank()) chunks.addLast(rest)
        streamDone = true
        if (!chunkPlaying) playNextChunk()
    }

    private fun playNextChunk() {
        val ctx = appContext
        val text = chunks.removeFirstOrNull()
        if (text == null || ctx == null) {
            chunkPlaying = false
            if (streamDone) {
                streamDone = false
                afterSpeaking()
            }
            return
        }
        chunkPlaying = true
        spokenNow = text
        setState(AssistantState.SPEAKING)
        val wait = (UNMUTE_DELAY_MS + 50 - (System.currentTimeMillis() - listeningEndedAt)).coerceAtLeast(0)
        handler.postDelayed({
            if (state.value != AssistantState.SPEAKING) {
                chunks.clear()
                chunkPlaying = false
                return@postDelayed
            }
            val next = { playNextChunk() }
            val piper = {
                if (Prefs.piperVoice(ctx) && PiperVoice.isReady) PiperVoice.speak(text) { next() } else next()
            }
            if (CloudVoice.isEnabled(ctx)) CloudVoice.speak(ctx, text, onDone = next, fallback = piper) else piper()
        }, wait)
    }

    /** Głos w telefonie: Piper, a bez niego – systemowy. Stan SPEAKING ustawia wołający. */
    private fun speakLocally(text: String, alarm: Boolean) {
        val ctx = appContext
        if (ctx != null && Prefs.piperVoice(ctx) && PiperVoice.isReady) {
            PiperVoice.speak(text, alarm) { afterSpeaking() }
            return
        }
        val engine = tts
        if (engine == null || !ttsReady) {
            afterSpeaking()
            return
        }
        val params = if (alarm) Bundle().apply { putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM) } else null
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, "jarvis-${utteranceCounter++}")
    }

    /** Krótki komunikat w trakcie myślenia („sprawdzam”) – nie zmienia stanu rozmowy. */
    private fun speakAside(text: String) {
        val ctx = appContext ?: return
        val local = {
            if (Prefs.piperVoice(ctx) && PiperVoice.isReady) {
                PiperVoice.speak(text) {}
            } else if (ttsReady) {
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis-aside-${utteranceCounter++}")
            }
        }
        if (CloudVoice.isEnabled(ctx)) CloudVoice.speak(ctx, text, onDone = {}, fallback = { local() }) else local()
    }

    private fun afterSpeaking() {
        if (state.value == AssistantState.LISTENING) return
        if (greeting) {
            // Po „Słucham?” (albo od razu, gdy nie ma głosu) – pierwsze słuchanie,
            // z cichymi ponowieniami jak po samym „Hey Jarvis”.
            greeting = false
            startListening(isFollowUp = false)
            return
        }
        // Koniec komunikatu w trakcie myślenia (speakAside) – czekamy dalej na odpowiedź.
        if (state.value != AssistantState.SPEAKING) return
        // Połączenie, nawigacja, otwarcie aplikacji – dopiero teraz, i bez dalszego słuchania.
        val action = PhoneTools.takePending()
        if (action != null) {
            endConversation()
            runCatching(action).onFailure { addMessage(ChatMessage(false, "Nie udało się: ${it.message}", "telefon")) }
            return
        }
        if (conversation) startListening(isFollowUp = true) else setState(AssistantState.IDLE)
    }

    private fun endConversation() {
        conversation = false
        followUp = false
        partial.value = ""
        setState(AssistantState.IDLE)
        // Pobudka bez odzewu – budzimy znowu za chwilę.
        if (waking != null) {
            handler.removeCallbacks(wakeAgain)
            handler.postDelayed(wakeAgain, WAKE_REPEAT_MS)
        }
    }

    private fun dataChanged() {
        appContext?.let {
            LedgerStore.save(it, ledger)
            Reminders.schedule(it, ledger)
        }
        ledgerVersion.update { it + 1 }
    }

    private fun addMessage(message: ChatMessage) {
        messages.update { (it + message).takeLast(100) }
        logTranscript(message)
    }

    /** Dziennik rozmów z Jarvisem: files/rozmowy/RRRR-MM-DD.txt (tylko w telefonie). */
    private fun logTranscript(message: ChatMessage) {
        val ctx = appContext ?: return
        runCatching {
            val now = LocalDateTime.now()
            val dir = File(ctx.filesDir, "rozmowy").apply { mkdirs() }
            val who = if (message.fromUser) "Ty" else "Jarvis" + (message.source?.let { " ($it)" } ?: "")
            File(dir, "${now.toLocalDate()}.txt")
                .appendText("${"%02d:%02d".format(now.hour, now.minute)} $who: ${message.text}\n")
        }
    }

    private fun liveReady(ctx: Context) = when (Prefs.liveProvider(ctx)) {
        "openai" -> Prefs.openaiKey(ctx).isNotBlank()
        "gemini" -> Prefs.geminiKey(ctx).isNotBlank()
        else -> false
    }

    /** Test z komputera (DEBUG_LIVE): rozmowa na żywo z tekstem zamiast mowy. */
    fun debugLive(text: String) {
        if (state.value == AssistantState.IDLE) startLive(text)
    }

    /**
     * Rozmowa na żywo: mikrofon od razu do Gemini, jego głos od razu z powrotem (bez odzewu „Słucham” –
     * możesz mówić zaraz po „Hey Jarvis”). Bez połączenia – zwykłe słuchanie.
     */
    private fun startLive(firstText: String? = null) {
        val ctx = appContext ?: return
        PhoneTools.takePending()
        partial.value = ""
        greeting = false
        conversation = true
        setState(AssistantState.LISTENING)
        // Dźwięk aktywacji – bez niego po „Hey Jarvis” nie wiadomo było, że już można mówić.
        StartSound.play()
        val liveName = if (Prefs.liveProvider(ctx) == "gemini") "gemini" else "chatgpt"
        val instruction = JarvisTools.SYSTEM_PROMPT + LIVE_RULES + "\n\n" + liveTools.context() + recentChat()
        lateinit var session: LiveVoice
        val listener = object : LiveVoice.Listener {
            override fun onHeard(text: String) {
                if (live === session) partial.value = text
            }

            override fun onSpeaking() {
                if (live === session) setState(AssistantState.SPEAKING)
            }

            override fun onTurnDone(user: String, jarvis: String) {
                if (live !== session) return
                partial.value = ""
                if (user.isNotBlank()) addMessage(ChatMessage(true, user))
                if (jarvis.isNotBlank()) addMessage(ChatMessage(false, jarvis, liveName))
                // Połączenie, nawigacja, otwarcie aplikacji – dopiero po wybrzmieniu odpowiedzi.
                val action = PhoneTools.takePending()
                if (action != null) {
                    stopLive()
                    runCatching(action).onFailure { addMessage(ChatMessage(false, "Nie udało się: ${it.message}", "telefon")) }
                    return
                }
                if (END_PHRASES.containsMatchIn(user.lowercase())) return stopLive()
                setState(AssistantState.LISTENING)
            }

            override fun onLevel(level: Float) {
                if (live === session && state.value == AssistantState.LISTENING) voiceLevel.value = level
            }

            override fun onClosed(error: String?, connected: Boolean) {
                if (live !== session) return
                live = null
                if (error != null) addMessage(ChatMessage(false, "Rozmowa na żywo: $error", "$liveName – błąd"))
                // Nie połączyło się wcale (internet, klucz) – słuchamy po staremu.
                if (error != null && !connected && firstText == null) return startListening(isFollowUp = false)
                endConversation()
            }
        }
        val runTool = { name: String, args: Map<String, Any?> ->
            if (name == SEARCH_TOOL.name) {
                search(ctx, args["pytanie"] as? String ?: "")
            } else {
                val (result, changed) = liveTools.run(name, args)
                if (changed) handler.post { dataChanged() }
                result
            }
        }
        session = if (Prefs.liveProvider(ctx) == "gemini") {
            GeminiLive(Prefs.geminiKey(ctx), Prefs.geminiModel(ctx), Prefs.geminiVoice(ctx), instruction, runTool, listener)
        } else {
            // ChatGPT nie ma wbudowanej wyszukiwarki – szuka za niego Kimi (gdy jest klucz).
            val search = if (Prefs.kimiKey(ctx).isNotBlank()) listOf(SEARCH_TOOL) else emptyList()
            OpenAiRealtime(Prefs.openaiKey(ctx), Prefs.openaiModel(ctx), Prefs.openaiVoice(ctx), instruction, search, runTool, listener)
        }
        live = session
        session.start()
        firstText?.let { session.sendText(it) }
    }

    /** Szukanie w internecie dla ChatGPT na żywo – robi je Kimi (ma wbudowaną wyszukiwarkę). */
    private fun search(ctx: Context, question: String): String {
        val s = Prefs.assistantSettings(ctx)
        val kimi = OpenAiCompatibleBrain(s.kimiKey, s.kimiBaseUrl, s.kimiModel.ifBlank { OpenAiCompatibleBrain.SEARCH_MODEL }, Ledger())
        return kimi.handle("Wyszukaj w internecie i odpowiedz krótko, konkretnie, z liczbami i datami: $question").text
    }

    private val SEARCH_TOOL = JarvisTools.Spec(
        "szukaj_w_internecie",
        "Szuka aktualnych informacji w internecie (wiadomości, ceny, godziny otwarcia, wyniki, fakty). Zwraca krótką odpowiedź.",
        mapOf("pytanie" to mapOf("type" to "string", "description" to "Czego szukać, pełnym zdaniem po polsku.")),
        listOf("pytanie"),
    )

    private fun stopLive() {
        val session = live ?: return
        live = null
        session.stop()
        endConversation()
    }

    /** Ostatnie wiadomości z czatu – rozmowa na żywo wie, o czym mówiliście przed chwilą. */
    private fun recentChat(): String {
        val recent = messages.value.filter { it.fromUser || it.source != null }.takeLast(8)
        if (recent.isEmpty()) return ""
        return "\n[Ostatnie wiadomości z czatu:\n" + recent.joinToString("\n") { (if (it.fromUser) "Ty: " else "Jarvis: ") + it.text } + "]"
    }

    /** Przerwanie w pół zdania (dotknięcie reaktora albo „stop”): cisza i koniec rozmowy. */
    private fun silence() {
        if (state.value != AssistantState.SPEAKING) return
        tts?.stop()
        PiperVoice.stop()
        CloudVoice.stop()
        chunks.clear()
        chunkPlaying = false
        streamDone = false
        replyId++ // dopisywana jeszcze odpowiedź już się nie odezwie
        PhoneTools.takePending()
        conversation = false
        greeting = false
        stopWaking()
        setState(AssistantState.IDLE)
    }

    private fun setState(newState: AssistantState) {
        val wasListening = state.value == AssistantState.LISTENING
        val wasSpeaking = state.value == AssistantState.SPEAKING
        state.value = newState
        // Gdy Jarvis mówi, słuchamy słowa „stop” (chwilę po starcie – rejestrator oddaje mikrofon).
        if (newState == AssistantState.SPEAKING) {
            if (!wasSpeaking) handler.postDelayed({
                val ctx = appContext
                // W rozmowie na żywo mikrofon ma Gemini – sam słyszy, że mu przerywasz.
                if (ctx != null && state.value == AssistantState.SPEAKING && live == null) BargeIn.start(ctx, { spokenNow }) { silence() }
            }, BARGE_IN_DELAY_MS)
        } else if (wasSpeaking) {
            BargeIn.stop()
        }
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
            // Rozpoznawanie w telefonie (SODA) potrafi oddać pusty wynik końcowy, choć wyniki
            // częściowe miały tekst – wtedy bierzemy ostatni częściowy.
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                ?.takeIf { it.isNotBlank() } ?: partial.value
            partial.value = ""
            if (text.isBlank()) {
                if (retryQuietly()) return
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
            // „Brak mowy”, choć wyniki częściowe miały tekst – traktujemy go jak wynik.
            val heard = partial.value
            if (heard.isNotBlank() && (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) {
                partial.value = ""
                handleText(heard)
                return
            }
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> if (retryQuietly()) return
                // Usługa się zacięła (często tuż po oddaniu mikrofonu) – nowa instancja zwykle działa.
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT, SpeechRecognizer.ERROR_AUDIO ->
                    if (retryQuietly(freshRecognizer = true)) return
            }
            val wasFollowUp = followUp
            endConversation()
            val message = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                    // Cisza po odpowiedzi to normalny koniec rozmowy.
                    if (wasFollowUp) return else "Nic nie usłyszałem. Powiedz „Hey Jarvis” albo dotknij reaktora."
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

    /**
     * Po „Hey Jarvis” rozpoznawanie czasem startuje za późno albo się zacina –
     * wtedy po cichu słuchamy jeszcze raz (do [MAX_RETRIES] razy), żeby nie trzeba było klikać.
     * Po odpowiedzi (dopytanie) też raz ponawiamy, gdy usługa się zacięła.
     */
    private fun retryQuietly(freshRecognizer: Boolean = false): Boolean {
        val limit = if (followUp) (if (freshRecognizer) 1 else 0) else MAX_RETRIES
        if (retries >= limit) return false
        retries++
        partial.value = ""
        if (freshRecognizer) {
            recognizer?.destroy()
            recognizer = null
        }
        startListening(isFollowUp = followUp, delayMs = if (freshRecognizer) 400 else 100, isRetry = true)
        return true
    }

    /** Odmowa („nie”, „nie trzeba”, „nie teraz”) – po porannej propozycji kończy rozmowę. */
    private val NO_PHRASES = Regex("^(nie|nie trzeba|nie teraz|nie dzięki|nie dziękuję|daj spokój|później|potem)\\b")

    /** Pytanie o rozmowę sprzed chwili – tylko wtedy czekamy, aż się przepisze. */
    private val ABOUT_CONVERSATION = Regex(
        "rozmow|rozmawia|m[óo]wi|powiedzia|gada|o czym|przed chwil|s[łl]ysza|nagra|ustali|wspomin|wspomnia|" +
            "klient|spotkani|pyta[łl]|obieca|umawia|co (on|ona|oni|one|ten|ta)\\b",
    )

    /** Dopisek do osobowości na rozmowę głosem na żywo. */
    private const val LIVE_RULES = "\n\nRozmawiasz z użytkownikiem głosem, na żywo. Mów zawsze po polsku, krótko i naturalnie " +
        "jak w rozmowie – bez list, nagłówków, emotek i numerów #id. Gdy sprawdzasz coś narzędziem albo w internecie, " +
        "możesz rzucić krótkie „sekunda”. Ton głosu: spokojny, pewny siebie, z lekką brytyjską ironią – jak elegancki " +
        "kamerdyner z gadżetami, który zna cię od lat; dalej po kumpelsku i na luzie, bez sztywnych formułek."

    /** Zwroty kończące rozmowę – po odpowiedzi Jarvis przestaje słuchać. */
    private val END_PHRASES = Regex("^(dzięki|dziękuję|dzieki|dziekuje|to wszystko|koniec|stop|nic|nieważne|niewazne|na razie)\\b")
}
