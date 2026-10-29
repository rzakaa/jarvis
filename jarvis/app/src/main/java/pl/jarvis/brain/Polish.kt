package pl.jarvis.brain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/** Pomocnicze rzeczy do rozumienia polskich zdań: liczby, kwoty, miesiące. */
object Polish {

    /** Małe litery, bez ogonków i interpunkcji – żeby „Zapłaciłem ZUS!” i „zaplacilem zus” znaczyły to samo. */
    fun normalize(text: String): String = text.lowercase()
        .map { DIACRITICS[it] ?: it }
        .joinToString("")
        .replace(Regex("[^a-z0-9,. ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    fun words(text: String): List<String> =
        normalize(text).split(" ").map { it.trim('.', ',') }.filter { it.isNotEmpty() }

    private val DIACRITICS = mapOf(
        'ą' to 'a', 'ć' to 'c', 'ę' to 'e', 'ł' to 'l', 'ń' to 'n',
        'ó' to 'o', 'ś' to 's', 'ź' to 'z', 'ż' to 'z',
    )

    // --- Liczby słownie -------------------------------------------------------

    private val NUMBER_WORDS = mapOf(
        "zero" to 0, "jeden" to 1, "jedna" to 1, "jedno" to 1, "dwa" to 2, "dwie" to 2,
        "trzy" to 3, "cztery" to 4, "piec" to 5, "szesc" to 6, "siedem" to 7, "osiem" to 8,
        "dziewiec" to 9, "dziesiec" to 10, "jedenascie" to 11, "dwanascie" to 12,
        "trzynascie" to 13, "czternascie" to 14, "pietnascie" to 15, "szesnascie" to 16,
        "siedemnascie" to 17, "osiemnascie" to 18, "dziewietnascie" to 19,
        "dwadziescia" to 20, "trzydziesci" to 30, "czterdziesci" to 40, "piecdziesiat" to 50,
        "szescdziesiat" to 60, "siedemdziesiat" to 70, "osiemdziesiat" to 80,
        "dziewiecdziesiat" to 90, "sto" to 100, "dwiescie" to 200, "trzysta" to 300,
        "czterysta" to 400, "piecset" to 500, "szescset" to 600, "siedemset" to 700,
        "osiemset" to 800, "dziewiecset" to 900,
    )
    private val THOUSAND_WORDS = setOf("tysiac", "tysiace", "tysiecy", "tys")
    private val CURRENCY_WORDS = setOf("zl", "zloty", "zlote", "zlotych", "pln")

    private val ORDINALS = mapOf(
        "pierwsze" to 1, "pierwszy" to 1, "pierwsza" to 1, "drugie" to 2, "drugi" to 2, "druga" to 2,
        "trzecie" to 3, "trzeci" to 3, "trzecia" to 3, "czwarte" to 4, "czwarty" to 4, "czwarta" to 4,
        "piate" to 5, "piaty" to 5, "piata" to 5, "szoste" to 6, "szosty" to 6, "siodme" to 7,
        "siodmy" to 7, "osme" to 8, "osmy" to 8, "dziewiate" to 9, "dziesiate" to 10,
    )

    /** Wynik szukania kwoty: wartość w groszach i które słowa zdania ją tworzyły. */
    data class Amount(val grosze: Long, val wordIndices: Set<Int>)

    /**
     * Szuka kwoty w zdaniu (słowa po [normalize]): „1200 zł”, „1 200,50 zł”, „2 tysiące”,
     * „tysiąc dwieście złotych”. Pierwszeństwo ma liczba stojąca przy „zł”.
     */
    fun findAmount(words: List<String>): Amount? {
        val candidates = mutableListOf<Amount>()
        var i = 0
        while (i < words.size) {
            val digits = parseDigitRun(words, i)
            val spoken = if (digits == null) parseWordRun(words, i) else null
            val found = digits ?: spoken
            if (found == null) {
                i++
                continue
            }
            var (value, end) = found // value w groszach, end = indeks za liczbą
            val used = (i until end).toMutableSet()
            if (end < words.size && words[end] in THOUSAND_WORDS) {
                value *= 1000
                used += end
                end++
            }
            var currency = false
            if (end < words.size && words[end] in CURRENCY_WORDS) {
                used += end
                currency = true
            }
            candidates += Amount(value, used)
            if (currency) return candidates.last()
            i = end
        }
        return candidates.firstOrNull()
    }

    /** „1200”, „1 200”, „1.200”, „350,50” – zwraca (grosze, indeks za liczbą). */
    private fun parseDigitRun(words: List<String>, start: Int): Pair<Long, Int>? {
        val first = words[start].trimEnd('.', ',')
        if (!first.matches(Regex("\\d+([.,]\\d+)*"))) return null
        // Grupy tysięcy rozdzielone spacją: „1 200 000”.
        val parts = mutableListOf(first)
        var end = start + 1
        if (first.matches(Regex("\\d{1,3}"))) {
            while (end < words.size && words[end].trimEnd('.', ',').matches(Regex("\\d{3}([.,]\\d{1,2})?"))) {
                parts += words[end].trimEnd('.', ',')
                end++
            }
        }
        // Kropka/przecinek przed trzema cyframi to separator tysięcy („1.200”), przed jedną-dwiema – grosze („350,50”).
        val joined = parts.joinToString("").replace(Regex("[.,](?=\\d{3}(?:[.,]|$))"), "")
        val match = Regex("(\\d+)(?:[.,](\\d{1,2}))?").matchEntire(joined) ?: return null
        val zl = match.groupValues[1].toLongOrNull() ?: return null
        val gr = match.groupValues[2].padEnd(2, '0').toLong()
        return (zl * 100 + gr) to end
    }

    /** „tysiąc dwieście pięćdziesiąt” – zwraca (grosze, indeks za liczbą). */
    private fun parseWordRun(words: List<String>, start: Int): Pair<Long, Int>? {
        var total = 0L
        var current = 0L
        var end = start
        var any = false
        while (end < words.size) {
            val w = words[end]
            val n = NUMBER_WORDS[w]
            when {
                n != null -> {
                    current += n
                    any = true
                }
                w in THOUSAND_WORDS && (any || end == start) -> {
                    total += (if (current == 0L) 1 else current) * 1000
                    current = 0
                    any = true
                }
                else -> break
            }
            end++
        }
        if (!any) return null
        return (total + current) * 100 to end
    }

    /** Czy słowo (po [normalize]) jest częścią zapisu kwoty: cyfry, liczebnik, „tysiąc”, „zł”. */
    fun isAmountWord(word: String): Boolean =
        word.matches(Regex("\\d+([.,]\\d+)*")) || word in NUMBER_WORDS || word in THOUSAND_WORDS || word in CURRENCY_WORDS

    /** Numer pozycji na liście: „2”, „dwa”, „drugie”. */
    fun findIndex(words: List<String>): Int? {
        for (w in words) {
            w.toIntOrNull()?.let { return it }
            ORDINALS[w]?.let { return it }
            NUMBER_WORDS[w]?.let { if (it in 1..19) return it }
        }
        return null
    }

    // --- Kwoty i daty po polsku ------------------------------------------------

    fun formatMoney(grosze: Long): String {
        val zl = grosze / 100
        val gr = grosze % 100
        val withSpaces = zl.toString().reversed().chunked(3).joinToString(" ").reversed()
        return if (gr == 0L) "$withSpaces zł" else "$withSpaces,${gr.toString().padStart(2, '0')} zł"
    }

    val MONTHS_NOMINATIVE = listOf(
        "styczeń", "luty", "marzec", "kwiecień", "maj", "czerwiec",
        "lipiec", "sierpień", "wrzesień", "październik", "listopad", "grudzień",
    )
    val MONTHS_LOCATIVE = listOf(
        "styczniu", "lutym", "marcu", "kwietniu", "maju", "czerwcu",
        "lipcu", "sierpniu", "wrześniu", "październiku", "listopadzie", "grudniu",
    )
    val MONTHS_GENITIVE = listOf(
        "stycznia", "lutego", "marca", "kwietnia", "maja", "czerwca",
        "lipca", "sierpnia", "września", "października", "listopada", "grudnia",
    )
    private val DAYS = mapOf(
        DayOfWeek.MONDAY to "poniedziałek", DayOfWeek.TUESDAY to "wtorek", DayOfWeek.WEDNESDAY to "środa",
        DayOfWeek.THURSDAY to "czwartek", DayOfWeek.FRIDAY to "piątek", DayOfWeek.SATURDAY to "sobota",
        DayOfWeek.SUNDAY to "niedziela",
    )

    fun dayName(date: LocalDate) = DAYS.getValue(date.dayOfWeek)

    /** „27 października” */
    fun dayAndMonth(date: LocalDate) = "${date.dayOfMonth} ${MONTHS_GENITIVE[date.monthValue - 1]}"

    /** „w październiku” albo „w październiku 2025”, gdy to nie bieżący rok. */
    fun inMonth(month: YearMonth, today: LocalDate): String {
        val name = MONTHS_LOCATIVE[month.monthValue - 1]
        val prep = if (name.startsWith("w")) "we" else "w" // „we wrześniu”
        return if (month.year == today.year) "$prep $name" else "$prep $name ${month.year}"
    }

    /** Miesiąc z wypowiedzi: „w tym miesiącu”, „w zeszłym miesiącu”, „we wrześniu”, „za wrzesień”. */
    fun findMonth(words: List<String>, today: LocalDate): YearMonth? {
        val text = words.joinToString(" ")
        val current = YearMonth.from(today)
        if (Regex("\\b(zeszlym|poprzednim|ubieglym|zeszly|poprzedni|ubiegly) miesiac").containsMatchIn(text)) {
            return current.minusMonths(1)
        }
        if (Regex("\\b(tym|ten|biezacym|biezacy) miesiac").containsMatchIn(text)) return current
        for (i in 0 until 12) {
            val names = setOf(
                normalize(MONTHS_LOCATIVE[i]), normalize(MONTHS_NOMINATIVE[i]), normalize(MONTHS_GENITIVE[i]),
            )
            if (words.any { it in names }) {
                val month = YearMonth.of(today.year, i + 1)
                // „w listopadzie” powiedziane w lutym to zeszły rok.
                return if (month.isAfter(current)) month.minusYears(1) else month
            }
        }
        return null
    }
}
