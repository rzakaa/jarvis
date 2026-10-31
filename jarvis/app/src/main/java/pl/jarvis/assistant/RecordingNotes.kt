package pl.jarvis.assistant

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import pl.jarvis.brain.Polish
import pl.jarvis.recorder.NO_AUDIO
import pl.jarvis.recorder.Prefs
import pl.jarvis.recorder.RecorderState
import pl.jarvis.recorder.Recordings
import pl.jarvis.recorder.Transcriber
import pl.jarvis.recorder.sibling
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
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

    /**
     * Obietnice użytkownika z rozmowy (oferta, wyjazd do klienta, telefon) → przypomnienia. Do AI idzie tylko
     * rozmowa, w której padły słowa typu „jutro”, „przyjadę”, „oferta” – reszta nie kosztuje nic.
     */
    fun commitments(context: Context, wav: File, transcript: String) {
        val text = transcript.trim()
        if (text.length < 60 || !PROMISE_HINT.containsMatchIn(text.lowercase())) return
        val brain = Assistant.buildBrain(Prefs.assistantSettings(context), Assistant.ledger) ?: return
        val day = runCatching { LocalDate.parse(wav.parentFile?.name) }.getOrNull() ?: LocalDate.now()
        val time = runCatching { LocalTime.parse(wav.nameWithoutExtension, fileTime) }.getOrNull() ?: LocalTime.now()
        val reply = runCatching {
            brain.completeJson(
                COMMITMENTS,
                "Rozmowa nagrana ${Polish.dayName(day)} $day o ${"%02d:%02d".format(time.hour, time.minute)}.\n\nTranskrypcja:\n${mask(text).take(12_000)}",
            )
        }.getOrElse {
            Log.w(TAG, "obietnice: ${it.message}")
            return
        }
        val items = runCatching { JSONObject(reply.substring(reply.indexOf('{').coerceAtLeast(0))).optJSONArray("zobowiazania") }
            .getOrNull() ?: return
        val now = LocalDateTime.now()
        val source = "%02d.%02d %02d:%02d".format(day.dayOfMonth, day.monthValue, time.hour, time.minute)
        for (i in 0 until minOf(items.length(), 3)) {
            val o = items.optJSONObject(i) ?: continue
            val what = o.optString("co").trim().takeIf { it.isNotEmpty() } ?: continue
            val who = o.optString("kto").trim()
            val where = o.optString("gdzie").trim()
            val trip = o.optString("rodzaj") == "wyjazd"
            val whenText = o.optString("kiedy").trim().takeIf { it.isNotEmpty() && it != "null" }
            // Z godziną: wyjazd – godzinę wcześniej (dojazd), reszta – o czasie. Sam dzień: rano. Bez terminu: jutro 9:00.
            val at = whenText?.let { w ->
                runCatching { LocalDateTime.parse(w).let { if (trip) it.minusHours(1) else it } }.getOrNull()
                    ?: runCatching { LocalDate.parse(w.take(10)).atTime(if (trip) 7 else 9, 0) }.getOrNull()
            } ?: now.toLocalDate().plusDays(1).atTime(9, 0)
            if (at.isBefore(now)) continue
            val details = listOf(who, where).filter { it.isNotEmpty() }.joinToString(", ")
            val reminder = "[z rozmowy $source] $what" + (if (details.isNotEmpty()) " ($details)." else ".")
            Assistant.addFromRecording(at, reminder, if (whenText == null) "$what (z rozmowy $source)" else null)
        }
        Log.d(TAG, "${wav.name}: obietnic ${items.length()}")
    }

    /** Słowa, po których w rozmowie zwykle pada obietnica albo termin. */
    private val PROMISE_HINT = Regex(
        "jutr|pojutrz|poniedzia|wtor|środ|czwart|piąt|sobot|niedziel|tydzie|godzin|o \\d|na \\d|przyjad|przyjed|podjad|" +
            "wpadn|zadzwoni|oddzwoni|wyśl|wysł|ofert|wycen|kosztorys|obiec|umów|umawia|przywio|termin|adres|faktur|zaliczk|zrobi",
    )

    private val COMMITMENTS = """
        Jesteś Jarvis, asystent użytkownika (fachowiec, pracuje u klientów). Dostajesz automatyczną transkrypcję rozmowy
        (często rozmowy telefonicznej, słychać tylko użytkownika; mogą być błędy rozpoznawania). Wypisz WYŁĄCZNIE to, co
        użytkownik obiecał albo z czym się umówił: wysłać ofertę lub wycenę, przyjechać do kogoś, oddzwonić, coś przywieźć,
        zrobić, zapłacić. Zwróć JSON:
        {"zobowiazania": [{"co": "krótko, w drugiej osobie, np. „Wyślij ofertę na łazienkę”",
          "kto": "komu (imię, firma, „klient z Zabrza”) albo pusty",
          "gdzie": "adres lub miejsce, jeśli padło, albo pusty",
          "kiedy": "RRRR-MM-DDTGG:MM gdy padła godzina, RRRR-MM-DD gdy tylko dzień, pusty gdy bez terminu",
          "rodzaj": "wyjazd | oferta | telefon | inne"}]}
        Zasady: dni typu „jutro”, „w piątek” licz od daty nagrania; nie wymyślaj; pomiń luźne gadanie, telewizję,
        cudze plany i rzeczy już zrobione. Gdy nic takiego nie ma – {"zobowiazania": []}.
    """.trimIndent()

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
            File(root, day.toString()).listFiles().orEmpty().filter { it.extension == "wav" || it.extension == NO_AUDIO }
                .map { sibling(it, "wav") }.distinct().sortedBy { it.name }.forEach { wav ->
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

    /**
     * Rozmowy skończone w ciągu ostatnich [RECENT_MIN] minut, z tekstem – idą od razu do pytania,
     * żeby Jarvis odpowiadał o nich bez szukania. Najnowsza na końcu; długie – tylko końcówka.
     */
    fun recent(context: Context): List<Pair<String, String>> {
        val cutoff = System.currentTimeMillis() - RECENT_MIN * 60_000L
        val clock = DateTimeFormatter.ofPattern("HH:mm:ss")
        return Recordings.list(context).asSequence()
            .filter { it.file.lastModified() >= cutoff }
            .take(3)
            .mapNotNull { rec ->
                val text = Transcriber.transcriptOf(rec.file).takeIf { it.exists() }?.readText()?.trim()
                    ?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val end = Instant.ofEpochMilli(rec.file.lastModified()).atZone(ZoneId.systemDefault()).toLocalTime()
                val label = "nagranie ${rec.start.toLocalDate()} ${rec.start.toLocalTime().format(clock)}–${end.format(clock)}"
                val body = mask(text).let { if (it.length > RECENT_CHARS) "(początek pominięty – czytaj_nagranie) …" + it.takeLast(RECENT_CHARS) else it }
                label to body
            }
            .toList().reversed()
    }

    private const val RECENT_MIN = 30
    private const val RECENT_CHARS = 5000

    /** Pełny tekst jednego nagrania (dla narzędzia czytaj_nagranie). [time] – „GG:MM” albo „GG:MM:SS”. */
    fun read(context: Context, day: LocalDate, time: String): String {
        val dir = File(context.filesDir, "recordings/$day")
        val prefix = time.trim().replace(':', '-')
        val wav = dir.listFiles().orEmpty().filter { (it.extension == "wav" || it.extension == NO_AUDIO) && it.nameWithoutExtension.startsWith(prefix) }
            .map { sibling(it, "wav") }.minByOrNull { it.name } ?: return "Nie ma nagrania z $day o $time."
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
