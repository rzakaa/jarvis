package pl.jarvis.recorder

/**
 * Decyduje, kiedy zacząć i skończyć nagranie rozmowy, na podstawie kolejnych ramek
 * (po 30 ms) i informacji, czy w ramce jest mowa.
 *
 * - Nagranie zaczyna się, gdy w ostatnich ramkach jest dość mowy (z ~1 s zapasu wstecz).
 * - Przerwy w rozmowie nie kończą nagrania, dopóki nie trwają dłużej niż [endSilenceFrames];
 *   w pliku zostaje z każdej przerwy tylko [keptPauseFrames], resztę ciszy pomijamy.
 * - Nagrania z mniej niż [minSpeechFrames] mowy są odrzucane (stuknięcia, trzaski).
 */
class Segmenter(
    private val sink: Sink,
    private val preRollFrames: Int = 33,
    private val startWindowFrames: Int = 10,
    private val startMinSpeechFrames: Int = 4,
    private val endSilenceFrames: Int = 60 * 33,
    private val keptPauseFrames: Int = 2 * 33,
    private val resumeLeadFrames: Int = 15,
    private val minSpeechFrames: Int = 33,
    private val minSpeechFramesOnPause: Int = 5 * 33,
    private val maxSegmentFrames: Int = 30 * 60 * 33,
) {
    interface Sink {
        fun startSegment()
        fun write(frame: ShortArray)
        /** [keep] = false: nagranie za krótkie, do usunięcia. */
        fun finishSegment(keep: Boolean)
    }

    var isRecording = false
        private set

    private val preRoll = ArrayDeque<ShortArray>()
    private val skipped = ArrayDeque<ShortArray>()
    private val recent = BooleanArray(startWindowFrames)
    private var recentIndex = 0
    private var speechFrames = 0
    private var silenceFrames = 0
    private var writtenFrames = 0

    fun process(frame: ShortArray, speech: Boolean) {
        recent[recentIndex] = speech
        recentIndex = (recentIndex + 1) % recent.size

        if (!isRecording) {
            preRoll.addLast(frame)
            if (preRoll.size > preRollFrames) preRoll.removeFirst()
            if (recent.count { it } >= startMinSpeechFrames) start()
            return
        }

        if (speech) {
            // Wznowienie po długiej przerwie: dopisz kawałek tuż sprzed mowy, żeby nie uciąć słowa.
            skipped.forEach { write(it) }
            skipped.clear()
            write(frame)
            speechFrames++
            silenceFrames = 0
        } else {
            silenceFrames++
            if (silenceFrames <= keptPauseFrames) {
                write(frame)
            } else {
                skipped.addLast(frame)
                if (skipped.size > resumeLeadFrames) skipped.removeFirst()
            }
        }

        if (silenceFrames >= endSilenceFrames || writtenFrames >= maxSegmentFrames) finish()
    }

    /** Kończy bieżące nagranie (np. przy wyłączaniu nasłuchiwania). */
    fun close() {
        if (isRecording) finish()
    }

    /**
     * Kończy nagranie, bo mikrofon przejmuje rozmowa z Jarvisem. Krótkie nagranie to zwykle
     * samo „Hey Jarvis” – zostaje tylko, gdy było w nim co najmniej [minSpeechFramesOnPause] mowy.
     */
    fun closeForAssistant() {
        if (isRecording) finish(minSpeechFramesOnPause)
    }

    private fun start() {
        isRecording = true
        speechFrames = recent.count { it }
        silenceFrames = 0
        writtenFrames = 0
        skipped.clear()
        sink.startSegment()
        preRoll.forEach { write(it) }
        preRoll.clear()
    }

    private fun write(frame: ShortArray) {
        sink.write(frame)
        writtenFrames++
    }

    private fun finish(minSpeech: Int = minSpeechFrames) {
        isRecording = false
        recent.fill(false)
        skipped.clear()
        sink.finishSegment(keep = speechFrames >= minSpeech)
    }
}
