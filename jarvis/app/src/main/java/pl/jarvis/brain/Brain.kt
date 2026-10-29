package pl.jarvis.brain

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

/**
 * Rozumie polskie polecenia i odpowiada na nie. Działa w całości na telefonie, bez internetu.
 *
 * Kwoty zarobków zawsze najpierw powtarza i pyta „Zgadza się?” – rozpoznawanie mowy
 * potrafi pomylić „1200” z „200”, a do raportów ma trafić dobra liczba.
 */
class Brain(
    private val ledger: Ledger,
    private val clock: () -> LocalDateTime = LocalDateTime::now,
) {
    data class Reply(
        val text: String,
        /** Jarvis zadał pytanie i czeka na odpowiedź (np. „Zgadza się?”). */
        val expectsAnswer: Boolean = false,
        /** Zmieniły się dane – trzeba je zapisać. */
        val changed: Boolean = false,
    )

    private data class PendingIncome(val date: LocalDate, val grosze: Long, val place: String)

    private var pending: PendingIncome? = null
    private val undoStack = ArrayDeque<Long>()

    fun handle(input: String): Reply {
        val words = stripWakeWord(Polish.words(input))
        val text = words.joinToString(" ")
        val now = clock()
        val today = now.toLocalDate()

        pending?.let { income ->
            pending = null
            when {
                isYes(words) -> {
                    val saved = ledger.addIncome(income.date, income.grosze, income.place)
                    undoStack.addLast(saved.id)
                    return Reply("Zapisane. ${monthTotalSentence(YearMonth.from(income.date), today)}", changed = true)
                }
                isNo(words) -> return Reply("Anulowane. Powiedz jeszcze raz, ile i gdzie.")
                // Coś innego – traktujemy jak nowe polecenie.
            }
        }

        if (words.isEmpty()) return Reply("Jestem. W czym mogę pomóc?")

        return when {
            text.startsWith("czy ") && PAID.containsMatchIn(text) -> paymentStatus(words, today)
            PAID.containsMatchIn(text) -> payment(words, today)
            REPORT.containsMatchIn(text) -> report(words, today)
            EARNED.containsMatchIn(text) -> income(input, words, today)
            TODO_LIST.containsMatchIn(text) -> todoList()
            DONE.containsMatchIn(text) -> todoDone(words)
            UNDO.containsMatchIn(text) -> undo()
            NOTE.containsMatchIn(text) -> note(input)
            TIME.containsMatchIn(text) -> Reply("Jest ${now.hour}:${now.minute.toString().padStart(2, '0')}.")
            DATE.containsMatchIn(text) ->
                Reply("Dziś ${Polish.dayName(today)}, ${Polish.dayAndMonth(today)} ${today.year}.")
            HELP.containsMatchIn(text) -> Reply(HELP_TEXT)
            THANKS.containsMatchIn(text) -> Reply("Do usług.")
            HOW_ARE_YOU.containsMatchIn(text) -> Reply("Wszystkie systemy działają. Dzięki, że pytasz.")
            PRESENCE.containsMatchIn(text) -> Reply("Jestem. W czym mogę pomóc?")
            GREETING.containsMatchIn(text) -> Reply("Cześć! W czym mogę pomóc?")
            else -> Reply(
                "Tego jeszcze nie umiem. Powiedz „co umiesz”, żeby usłyszeć, w czym mogę pomóc.",
            )
        }
    }

    // --- Zarobki ----------------------------------------------------------------

    private fun income(original: String, words: List<String>, today: LocalDate): Reply {
        val amount = Polish.findAmount(words)
            ?: return Reply("Jaką kwotę zapisać? Powiedz na przykład: zarobiłem 1200 zł na Kawodrzańskiej.")
        val date = when {
            "przedwczoraj" in words -> today.minusDays(2)
            "wczoraj" in words -> today.minusDays(1)
            else -> today
        }
        val place = findPlace(original)
        pending = PendingIncome(date, amount.grosze, place)
        val where = if (place.isEmpty()) "bez miejsca" else place
        val whenSaid = when (date) {
            today -> "dzisiaj"
            today.minusDays(1) -> "wczoraj"
            else -> Polish.dayAndMonth(date)
        }
        return Reply("Zapisuję ${Polish.formatMoney(amount.grosze)}, $where, $whenSaid. Zgadza się?", expectsAnswer = true)
    }

    /**
     * Miejsce pracy z oryginalnego zdania (z wielkimi literami i ogonkami):
     * „Na robocie na Kawodrzańskiej zarobiłem 1200 zł” → „na Kawodrzańskiej”.
     */
    private fun findPlace(original: String): String {
        val raw = original.split(Regex("\\s+")).map { it.trim(',', '.', '!', '?', ':', ';') }.filter { it.isNotEmpty() }
        val norm = raw.map { Polish.normalize(it) }
        val kept = mutableListOf<Pair<String, String>>()
        var i = 0
        while (i < raw.size) {
            val n = norm[i]
            // „na robocie”, „w pracy” – samo słowo „robota” nic nie mówi o miejscu.
            if (n in PREPOSITIONS && i + 1 < raw.size && norm[i + 1] in WORK_WORDS) {
                i += 2
                continue
            }
            val skip = n in PLACE_FILLER || n in WAKE_WORDS || n.startsWith("zarobi") || Polish.isAmountWord(n)
            if (!skip) kept += raw[i] to n
            i++
        }
        val start = kept.indexOfFirst { it.second in PREPOSITIONS }
        if (start < 0) return kept.joinToString(" ") { it.first }
        return kept.drop(start).mapIndexed { idx, (word, n) -> if (idx == 0) n else word }.joinToString(" ")
    }

    private fun monthTotalSentence(month: YearMonth, today: LocalDate): String {
        val list = ledger.incomesIn(month)
        val sum = list.sumOf { it.grosze }
        return "Zarobki ${Polish.inMonth(month, today)}: ${Polish.formatMoney(sum)}."
    }

    // --- Płatności --------------------------------------------------------------

    private fun findKinds(words: List<String>): List<String> =
        PAYMENT_KINDS.filter { (keys, _) -> words.any { w -> keys.any { w.startsWith(it) } } }.map { it.second }

    private fun payment(words: List<String>, today: LocalDate): Reply {
        val kinds = findKinds(words)
        if (kinds.isEmpty()) return Reply("Co zostało zapłacone? Na przykład: zapłaciłem ZUS i alimenty.")
        val month = forMonth(words, today)
        val amount = if (kinds.size == 1) Polish.findAmount(words)?.grosze else null
        kinds.forEach { undoStack.addLast(ledger.addPayment(today, it, month, amount).id) }
        val what = joinPolish(kinds)
        val forWhat = "za ${Polish.MONTHS_NOMINATIVE[month.monthValue - 1]}"
        val sum = amount?.let { " (${Polish.formatMoney(it)})" } ?: ""
        return Reply("Odnotowane: $what $forWhat$sum, zapłacone dzisiaj.", changed = true)
    }

    private fun paymentStatus(words: List<String>, today: LocalDate): Reply {
        val kinds = findKinds(words).ifEmpty { TRACKED }
        val month = forMonth(words, today)
        val forWhat = "za ${Polish.MONTHS_NOMINATIVE[month.monthValue - 1]}"
        val parts = kinds.map { kind ->
            val p = ledger.payments.lastOrNull { it.kind == kind && it.month == month }
            if (p != null) "$kind $forWhat – tak, ${Polish.dayAndMonth(p.date)}" else "$kind $forWhat – jeszcze nie"
        }
        return Reply(parts.joinToString(". ") + ".")
    }

    /** Płatność „za” który miesiąc: „za wrzesień” albo bieżący. */
    private fun forMonth(words: List<String>, today: LocalDate): YearMonth =
        Polish.findMonth(words, today) ?: YearMonth.from(today)

    // --- Raport -----------------------------------------------------------------

    private fun report(words: List<String>, today: LocalDate): Reply {
        val month = Polish.findMonth(words, today) ?: YearMonth.from(today)
        val list = ledger.incomesIn(month)
        val sb = StringBuilder()
        if (list.isEmpty()) {
            sb.append("${Polish.inMonth(month, today).replaceFirstChar { it.uppercase() }} nie ma zapisanych zarobków.")
        } else {
            val sum = list.sumOf { it.grosze }
            sb.append("Zarobki ${Polish.inMonth(month, today)}: ${Polish.formatMoney(sum)} z ${list.size} ${paymentsWord(list.size)}.")
            val best = list.filter { it.place.isNotEmpty() }.groupBy { it.place }
                .mapValues { (_, v) -> v.sumOf { it.grosze } }.maxByOrNull { it.value }
            if (best != null && list.map { it.place }.distinct().size > 1) {
                sb.append(" Najwięcej ${best.key}: ${Polish.formatMoney(best.value)}.")
            }
        }
        val paid = TRACKED.filter { ledger.isPaid(it, month) }
        val unpaid = TRACKED - paid.toSet()
        if (paid.isNotEmpty()) sb.append(" Zapłacone: ${joinPolish(paid)}.")
        if (unpaid.isNotEmpty()) sb.append(" Jeszcze niezapłacone: ${joinPolish(unpaid)}.")
        return Reply(sb.toString())
    }

    private fun paymentsWord(n: Int) = if (n == 1) "wpłaty" else "wpłat"

    // --- Notatki i zadania ------------------------------------------------------

    private fun note(original: String): Reply {
        val body = original.trim()
            .replace(Regex("^(hej\\s+)?(jarvis|dżarwis)[,!.]?\\s*", RegexOption.IGNORE_CASE), "")
            .replace(NOTE_PREFIX, "")
            .trim().trimEnd('.')
        if (body.isEmpty()) return Reply("Co mam zanotować?")
        val text = body.replaceFirstChar { it.uppercase() }
        undoStack.addLast(ledger.addNote(clock(), text).id)
        return Reply("Zanotowane: $text.", changed = true)
    }

    private fun todoList(): Reply {
        val open = ledger.openNotes()
        if (open.isEmpty()) return Reply("Nie masz nic zapisanego do zrobienia.")
        val list = open.mapIndexed { i, n -> "${i + 1}. ${n.text}" }.joinToString(". ")
        val count = when (open.size) {
            1 -> "jedną sprawę"
            in 2..4 -> "${open.size} sprawy"
            else -> "${open.size} spraw"
        }
        return Reply("Masz $count: $list.")
    }

    private fun todoDone(words: List<String>): Reply {
        val open = ledger.openNotes()
        val index = Polish.findIndex(words)
            ?: return Reply("Które zadanie? Powiedz na przykład: zrobione dwa.")
        val note = open.getOrNull(index - 1) ?: return Reply("Nie ma zadania numer $index.")
        ledger.markDone(note.id)
        return Reply("Odhaczone: ${note.text}.", changed = true)
    }

    private fun undo(): Reply {
        val id = undoStack.removeLastOrNull() ?: return Reply("Nie ma czego cofnąć.")
        ledger.remove(id)
        return Reply("Cofnięte. Ostatni wpis usunięty.", changed = true)
    }

    // --- Pomocnicze ---------------------------------------------------------------

    private fun stripWakeWord(words: List<String>): List<String> {
        var w = words
        if (w.firstOrNull() == "hej") w = w.drop(1)
        if (w.firstOrNull() in WAKE_WORDS) w = w.drop(1)
        return w
    }

    private fun isYes(words: List<String>) = words.any { it in YES } && words.none { it in NO }
    private fun isNo(words: List<String>) = words.any { it in NO }

    private fun joinPolish(items: List<String>) = when (items.size) {
        0 -> ""
        1 -> items[0]
        else -> items.dropLast(1).joinToString(", ") + " i " + items.last()
    }

    companion object {
        /** Płatności, których brak Jarvis wytyka w raporcie. */
        val TRACKED = listOf("ZUS", "alimenty")

        private val WAKE_WORDS = setOf("jarvis", "dzarwis", "dzarvis", "jarwis")
        private val YES = setOf("tak", "zgadza", "potwierdzam", "dobrze", "ok", "okej", "jasne", "zgadzasie")
        private val NO = setOf("nie", "anuluj", "zle", "bledne", "blad", "pomylka")

        private val PAID = Regex("\\b(za|o)placil|\\bprzelal")
        private val EARNED = Regex("\\bzarobil|\\bzarobek|\\bdostalem za|\\bwzialem za")
        private val REPORT = Regex("\\braport|\\bile zarobil|\\bpodsumowanie|\\bzarobki")
        private val TODO_LIST = Regex("co mam (do )?zrobi|\\blista zadan|\\bmoje zadania|\\bjakie mam zadania|^zadania$|\\bco mam zalatwic")
        private val DONE = Regex("^(zrobione|odhacz|zalatwione|gotowe)\\b|\\bzadanie \\S+ (zrobione|zalatwione)")
        private val UNDO = Regex("^cofnij|usun ostatni")
        private val NOTE = Regex("^(zanotuj|zapisz|notatka|dodaj zadanie|dodaj do zadan|przypomnij mi|musze)\\b")
        private val NOTE_PREFIX = Regex(
            "^(zanotuj|zapisz|notatka|dodaj zadanie|dodaj do zadań|dodaj do zadan|przypomnij mi)[:,]?\\s*(że|ze|o tym, że|o tym ze|żeby|zeby)?\\s*",
            RegexOption.IGNORE_CASE,
        )
        private val TIME = Regex("\\bktora (jest )?godzina|\\bgodzina jest")
        private val DATE = Regex("\\bjaki (dzis|dzisiaj|jest) dzien|\\bjaka (jest )?data|\\bktorego dzis")
        private val HELP = Regex("\\bco umiesz|\\bpomoc\\b|\\bco potrafisz")
        private val THANKS = Regex("\\bdziek")
        private val HOW_ARE_YOU = Regex("\\bjak sie masz|\\bco slychac")
        private val PRESENCE = Regex("\\bjestes\\b|\\bhalo\\b|\\bslyszysz")
        private val GREETING = Regex("^(czesc|siema|hej|dzien dobry|dobry wieczor|witaj)")

        private val PREPOSITIONS = setOf("na", "w", "we", "przy", "u", "od", "dla", "z", "ze")
        private val WORK_WORDS = setOf("robocie", "robota", "robote", "pracy", "prace", "fusze", "fuchy", "zleceniu")
        private val PLACE_FILLER = setOf(
            "dzis", "dzisiaj", "wczoraj", "przedwczoraj", "zapisz", "ze", "i", "jarvis", "hej", "dzarwis",
            "zl", "zloty", "zlote", "zlotych", "pln", "tysiac", "tysiace", "tysiecy", "dostalem", "wzialem",
        )

        private val PAYMENT_KINDS = listOf(
            listOf("zus") to "ZUS",
            listOf("aliment") to "alimenty",
            listOf("prad") to "prąd",
            listOf("czynsz") to "czynsz",
            listOf("gaz") to "gaz",
            listOf("internet") to "internet",
            listOf("telefon", "abonament") to "telefon",
            listOf("podat", "pit", "vat") to "podatek",
            listOf("ubezpiecz", "oc") to "ubezpieczenie",
            listOf("rat", "kredyt") to "ratę kredytu",
            listOf("wod") to "wodę",
        )

        private const val HELP_TEXT =
            "Na razie umiem: zapisać zarobek, na przykład: zarobiłem 1200 zł na Kawodrzańskiej. " +
                "Odnotować płatność: zapłaciłem ZUS i alimenty. Sprawdzić: czy zapłaciłem ZUS. " +
                "Zrobić raport: raport za październik. Zanotować zadanie: zanotuj, że mam zadzwonić do Kowalskiego. " +
                "Powiedzieć, co masz do zrobienia, i odhaczyć zadanie. Cofnąć ostatni wpis. I powiedzieć, która godzina."
    }
}
