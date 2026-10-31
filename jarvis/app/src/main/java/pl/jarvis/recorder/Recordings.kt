package pl.jarvis.recorder

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** [topic]/[summary] – skrót od AI (plik .json), [transcript] – tekst (plik .txt), gdy już są. */
data class Recording(
    val file: File,
    val start: LocalDateTime,
    val durationSeconds: Long,
    val topic: String? = null,
    val summary: String? = null,
    val transcript: String? = null,
) {
    /** Dźwięk jeszcze jest (po kilku dniach zostaje sam tekst – patrz [Recordings.cleanup]). */
    val hasAudio get() = file.exists()

    /** Nagranie z plikami tekstu, skrótu i znacznikami. */
    val relatedFiles get() = listOf(file, sibling(file, "txt"), sibling(file, "json"), sibling(file, CALL), sibling(file, NO_AUDIO))
}

/** Znacznik: nagranie z rozmowy telefonicznej (dźwięk trzymamy dłużej). */
const val CALL = "call"

/** Znacznik: dźwięk usunięty, został tekst; w środku – długość nagrania w sekundach. */
const val NO_AUDIO = "bezdzwieku"

fun sibling(wav: File, extension: String) = File(wav.parentFile, wav.nameWithoutExtension + "." + extension)

/**
 * Nagrania leżą w prywatnym katalogu aplikacji (inne aplikacje nie mają do niego dostępu):
 * files/recordings/2026-10-27/14-03-21.wav
 */
object Recordings {
    const val SAMPLE_RATE = 16_000

    private val timeFormat = DateTimeFormatter.ofPattern("HH-mm-ss")

    private fun root(context: Context) = File(context.filesDir, "recordings")

    fun newFile(context: Context, start: LocalDateTime): File {
        val dayDir = File(root(context), start.toLocalDate().toString())
        dayDir.mkdirs()
        return File(dayDir, start.format(timeFormat) + ".wav")
    }

    /** Wszystkie zakończone nagrania, od najnowszego. */
    fun list(context: Context): List<Recording> {
        val active = RecorderState.activeFile.value
        val dayDirs = root(context).listFiles().orEmpty().filter { it.isDirectory }
        return dayDirs.flatMap { dayDir ->
            val day = runCatching { LocalDate.parse(dayDir.name) }.getOrNull()
                ?: return@flatMap emptyList()
            // Nagranie to plik .wav albo – po usunięciu dźwięku – sam znacznik z tekstem obok.
            dayDir.listFiles().orEmpty()
                .filter { (it.extension == "wav" && it != active) || it.extension == NO_AUDIO }
                .map { sibling(it, "wav") }
                .distinct()
                .mapNotNull { file ->
                    val time = runCatching { LocalTime.parse(file.nameWithoutExtension, timeFormat) }
                        .getOrNull() ?: return@mapNotNull null
                    val seconds = if (file.exists()) {
                        runCatching { WavWriter.repairIfNeeded(file, SAMPLE_RATE) }
                        (file.length() - WavWriter.HEADER_SIZE).coerceAtLeast(0) / (SAMPLE_RATE * 2)
                    } else {
                        runCatching { sibling(file, NO_AUDIO).readText().trim().toLong() }.getOrDefault(0L)
                    }
                    val notes = sibling(file, "json").takeIf { it.exists() }
                        ?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }
                    Recording(
                        file, day.atTime(time), seconds,
                        topic = notes?.optString("temat")?.takeIf { it.isNotBlank() },
                        summary = notes?.optString("skrot")?.takeIf { it.isNotBlank() },
                        transcript = sibling(file, "txt").takeIf { it.exists() }?.readText(),
                    )
                }
        }.sortedByDescending { it.start }
    }

    /** Dźwięk rozmowy telefonicznej trzymamy 30 dni, zwykłego nasłuchu – 7 dni od przepisania. Tekst zostaje. */
    private const val CALL_DAYS = 30L
    private const val AMBIENT_DAYS = 7L
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    /** Rozmowa telefoniczna słyszana z boku (telefon przy uchu albo na głośniku) – po typowych zwrotach. */
    private val CALL_WORDS = Regex("\\bhalo\\b|do usłyszenia|do uslyszenia|dzwonię w sprawie|oddzwonię|nie słyszę pana|nie słyszę pani")

    /**
     * Usuwa dźwięk starszych, już przepisanych nagrań (wiek liczony od przepisania); tekst i skrót zostają,
     * a w miejscu dźwięku – znacznik z długością nagrania. Nieprzepisanych nie ruszamy.
     */
    fun cleanup(context: Context) {
        val now = System.currentTimeMillis()
        var removed = 0
        list(context).filter { it.hasAudio && it.file != RecorderState.activeFile.value }.forEach { rec ->
            val text = sibling(rec.file, "txt").takeIf { it.exists() } ?: return@forEach
            val call = sibling(rec.file, CALL).exists() || CALL_WORDS.containsMatchIn(rec.transcript.orEmpty().lowercase())
            val keepDays = if (call) CALL_DAYS else AMBIENT_DAYS
            if (now - text.lastModified() < keepDays * DAY_MS) return@forEach
            sibling(rec.file, NO_AUDIO).writeText(rec.durationSeconds.toString())
            if (rec.file.delete()) removed++
        }
        if (removed > 0) {
            Log.d("JarvisRecordings", "usunięto dźwięk $removed starych nagrań (tekst został)")
            RecorderState.recordingsChanged()
        }
    }
}
