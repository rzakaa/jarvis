package pl.jarvis.assistant

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import pl.jarvis.recorder.Prefs
import pl.jarvis.recorder.RecorderState
import pl.jarvis.recorder.Transcriber
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Notatki z nagranych rozmów: po przepisaniu nagrania AI robi skrót, wyłapuje zadania
 * (np. prośbę klienta) i fakty o użytkowniku. Skrót leży obok nagrania: 14-03-21.json.
 *
 * Do AI idzie tylko tekst (nigdy audio), z zamaskowanym PESEL-em i numerami kart i kont.
 */
object RecordingNotes {
    private const val TAG = "JarvisNotes"
    private val fileTime = DateTimeFormatter.ofPattern("HH-mm-ss")

    fun summaryOf(wav: File) = File(wav.parentFile, wav.nameWithoutExtension + ".json")

    /** Wołane przez [Transcriber] w tle, po przepisaniu nagrania. */
    fun process(context: Context, wav: File, transcript: String) {
        val brain = Assistant.buildBrain(Prefs.assistantSettings(context), Assistant.ledger) ?: return
        val day = runCatching { LocalDate.parse(wav.parentFile?.name) }.getOrNull()
        val time = runCatching { LocalTime.parse(wav.nameWithoutExtension, fileTime) }.getOrNull()
        val memory = Assistant.ledger.allMemories().joinToString("; ") { it.text }.ifEmpty { "pusta" }
        val reply = brain.completeJson(
            SYSTEM,
            "Nagranie z $day, godz. ${time ?: "?"}. Co już wiesz o użytkowniku: $memory.\n\nTranskrypcja:\n${mask(transcript)}",
        )
        val json = JSONObject(reply.substring(reply.indexOf('{').coerceAtLeast(0)))
        summaryOf(wav).writeText(json.toString())
        val tasks = json.optJSONArray("zadania").strings()
        val facts = json.optJSONArray("fakty").strings()
        Log.d(TAG, "${wav.name}: ${json.optString("temat")} – zadań ${tasks.size}, faktów ${facts.size}")
        val hour = time?.let { "%02d:%02d".format(it.hour, it.minute) } ?: ""
        Assistant.importFromRecording(tasks.map { "$it (z rozmowy $day $hour)" }, facts)
        RecorderState.recordingsChanged()
    }

    /** PESEL, numery kart i kont nie wychodzą z telefonu. */
    fun mask(text: String) = text
        .replace(Regex("\\bPL\\s?\\d{2}(?:[ -]?\\d{4}){6}\\b|\\b\\d{2}(?:[ -]?\\d{4}){6}\\b"), "[KONTO]")
        .replace(Regex("\\b(?:\\d[ -]?){13,19}\\b"), "[KARTA]")
        .replace(Regex("\\b\\d{11}\\b"), "[PESEL]")

    /**
     * Dla narzędzia szukaj_w_nagraniach: skróty nagrań z zakresu dat, a z [query] – tylko pasujące,
     * z fragmentem tekstu wokół trafienia.
     */
    fun search(context: Context, from: LocalDate, to: LocalDate, query: String): String {
        val words = query.lowercase().split(Regex("\\W+")).filter { it.length >= 3 }
            // Bez końcówek: „Piotrkiem” znajdzie „Piotrek”, „dostawy” – „dostawa” (pierwsze 5 liter).
            .map { it.take(5) }.distinct()
        val root = File(context.filesDir, "recordings")
        val out = StringBuilder()
        var day = from
        while (!day.isAfter(to)) {
            File(root, day.toString()).listFiles().orEmpty().filter { it.extension == "wav" }.sortedBy { it.name }.forEach { wav ->
                val summary = summaryOf(wav).takeIf { it.exists() }?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }
                val transcript = Transcriber.transcriptOf(wav).takeIf { it.exists() }?.readText().orEmpty()
                val haystack = (summary?.toString().orEmpty() + " " + transcript).lowercase()
                if (words.isNotEmpty() && words.none { haystack.contains(it) }) return@forEach
                val time = wav.nameWithoutExtension.replace('-', ':')
                out.append("[nagranie $day $time] ")
                when {
                    summary != null -> {
                        out.append("${summary.optString("temat")}: ${summary.optString("skrot")}")
                        summary.optJSONArray("osoby").strings().takeIf { it.isNotEmpty() }?.let { out.append(" Osoby: ${it.joinToString()}.") }
                        summary.optJSONArray("ustalenia").strings().takeIf { it.isNotEmpty() }?.let { out.append(" Ustalenia: ${it.joinToString("; ")}.") }
                    }
                    transcript.isNotBlank() -> out.append("(bez skrótu) ${transcript.take(300)}")
                    else -> out.append("(jeszcze nie przepisane)")
                }
                // Fragment tekstu wokół każdego szukanego słowa (najwyżej trzy).
                val lower = transcript.lowercase()
                words.mapNotNull { w -> lower.indexOf(w).takeIf { it >= 0 } }.distinct().sorted().take(3).forEach { hit ->
                    out.append(" | fragment: „${transcript.substring((hit - 200).coerceAtLeast(0), (hit + 300).coerceAtMost(transcript.length))}”")
                }
                out.append('\n')
                if (out.length > 6000) return out.toString()
            }
            day = day.plusDays(1)
        }
        return out.toString().ifBlank { "Brak nagrań" + (if (words.isNotEmpty()) " pasujących do „$query”" else "") + " w dniach $from – $to." }
    }

    /** Pełny tekst jednego nagrania (dla narzędzia czytaj_nagranie). [time] – „GG:MM” albo „GG:MM:SS”. */
    fun read(context: Context, day: LocalDate, time: String): String {
        val dir = File(context.filesDir, "recordings/$day")
        val prefix = time.trim().replace(':', '-')
        val wav = dir.listFiles().orEmpty().filter { it.extension == "wav" && it.nameWithoutExtension.startsWith(prefix) }
            .minByOrNull { it.name } ?: return "Nie ma nagrania z $day o $time."
        val text = Transcriber.transcriptOf(wav).takeIf { it.exists() }?.readText()
            ?: return "Nagranie z $day ${wav.nameWithoutExtension.replace('-', ':')} nie jest jeszcze przepisane."
        return "Nagranie $day ${wav.nameWithoutExtension.replace('-', ':')} (automatyczna transkrypcja, może mieć błędy):\n" +
            mask(text).take(12_000)
    }

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).trim().takeIf { s -> s.isNotEmpty() } }

    private val SYSTEM = """
        Jesteś Jarvis, asystent użytkownika. Dostajesz automatyczną transkrypcję rozmowy nagranej w otoczeniu
        użytkownika (może mieć błędy rozpoznawania, brak podziału na osoby). Zwróć obiekt JSON:
        {"temat": "kilka słów", "skrot": "2–4 zdania: kto, o czym, co ustalono",
         "osoby": ["imiona, nazwiska, firmy wymienione w rozmowie"],
         "ustalenia": ["każdy konkret z rozmowy, z kontekstem: godziny, dni, terminy dostaw, kwoty, ceny, ilości,
                       materiały, adresy, numery – np. „Piotrek: dostawa materiałów jutro o 7:30”"],
         "zadania": ["rzeczy do zrobienia dla użytkownika: obietnice, prośby klientów, terminy, telefony do oddzwonienia"],
         "fakty": ["nowe trwałe informacje o użytkowniku i jego otoczeniu, krótko, w trzeciej osobie"]}
        Zasady: pisz po polsku; nie wymyślaj; w ustaleniach zapisz wszystkie konkrety, nawet drobne – użytkownik
        będzie o nie później pytał; zadania tylko konkretne i wynikające z rozmowy; fakty tylko nowe (pomiń to, co
        już wiadomo). Gdy to szum, telewizja, muzyka albo nic istotnego – "skrot": "" i puste listy.
    """.trimIndent()
}
