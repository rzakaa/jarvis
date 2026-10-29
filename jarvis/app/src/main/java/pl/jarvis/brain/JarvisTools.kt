package pl.jarvis.brain

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import kotlin.math.roundToLong

/** Mózg AI Jarvisa – wspólny interfejs dla różnych dostawców (Claude, Kimi…). */
interface AiBrain {
    data class Result(val text: String, val changed: Boolean)

    /** Wysyła wypowiedź do modelu i wykonuje jego narzędzia. Blokuje – wołać poza wątkiem UI. */
    fun handle(userText: String): Result
}

/**
 * To, co wszystkie mózgi AI mają wspólne: osobowość Jarvisa, narzędzia działające na [Ledger]
 * i krótki stan danych doklejany do każdej wypowiedzi.
 */
class JarvisTools(private val ledger: Ledger, private val clock: () -> LocalDateTime) {

    /** Opis narzędzia: nazwa, opis i schemat parametrów (JSON Schema jako mapy). */
    data class Spec(val name: String, val description: String, val properties: Map<String, Map<String, Any>>, val required: List<String>)

    /** Czy zacząć rozmowę od nowa (dłuższa przerwa albo bardzo długa historia). */
    fun shouldReset(lastActivity: LocalDateTime?, now: LocalDateTime, historySize: Int) =
        lastActivity == null || Duration.between(lastActivity, now) > CONVERSATION_TIMEOUT || historySize > MAX_HISTORY

    fun now(): LocalDateTime = clock()

    /** Aktualny stan danych w nawiasie – doklejany przed wypowiedzią użytkownika. */
    fun context(now: LocalDateTime = clock()): String {
        val today = now.toLocalDate()
        val month = YearMonth.from(today)
        val incomes = ledger.incomesIn(month)
        val payments = Brain.TRACKED.joinToString("; ") { kind ->
            val p = ledger.paymentsFor(month).lastOrNull { it.kind == kind }
            if (p != null) "$kind: zapłacone ${p.date}" else "$kind: niezapłacone"
        }
        val notes = ledger.openNotes().joinToString("; ") { "#${it.id} ${it.text}" }.ifEmpty { "brak" }
        return "[Stan Jarvisa – teraz $today ${"%02d:%02d".format(now.hour, now.minute)}, ${Polish.dayName(today)}. " +
            "Zarobki ${Polish.inMonth(month, today)}: ${Polish.formatMoney(incomes.sumOf { it.grosze })} " +
            "(${incomes.size} wpłat). Płatności za bieżący miesiąc: $payments. Otwarte zadania: $notes.]"
    }

    /** Wykonuje narzędzie. Zwraca (wynik dla modelu, czy zmieniły się dane). */
    fun run(name: String, input: Map<String, Any?>): Pair<String, Boolean> {
        val today = clock().toLocalDate()
        return try {
            when (name) {
                "zapisz_zarobek" -> {
                    val grosze = money(input["kwota_zl"]) ?: return "Błąd: brak kwoty." to false
                    val date = (input["data"] as? String)?.takeIf { it.isNotBlank() }?.let { LocalDate.parse(it) } ?: today
                    val place = (input["miejsce"] as? String).orEmpty()
                    val saved = ledger.addIncome(date, grosze, place)
                    "Zapisano wpis #${saved.id}: ${Polish.formatMoney(grosze)}, ${place.ifEmpty { "bez miejsca" }}, $date." to true
                }
                "zapisz_platnosc" -> {
                    val kind = (input["rodzaj"] as? String)?.trim().orEmpty()
                    if (kind.isEmpty()) return "Błąd: brak rodzaju płatności." to false
                    val normalized = Brain.TRACKED.firstOrNull { it.equals(kind, ignoreCase = true) } ?: kind
                    val month = (input["miesiac"] as? String)?.takeIf { it.isNotBlank() }?.let { YearMonth.parse(it) }
                        ?: YearMonth.from(today)
                    val saved = ledger.addPayment(today, normalized, month, money(input["kwota_zl"]))
                    "Zapisano wpis #${saved.id}: $normalized za $month, zapłacone $today." to true
                }
                "dodaj_zadanie" -> {
                    val text = (input["tekst"] as? String)?.trim().orEmpty()
                    if (text.isEmpty()) return "Błąd: pusty tekst." to false
                    val saved = ledger.addNote(clock(), text)
                    "Dodano zadanie #${saved.id}." to true
                }
                "odhacz_zadanie" -> {
                    val id = (input["id"] as? Number)?.toLong() ?: return "Błąd: brak id." to false
                    if (ledger.openNotes().none { it.id == id }) return "Nie ma otwartego zadania #$id." to false
                    ledger.markDone(id)
                    "Zadanie #$id odhaczone." to true
                }
                "usun_wpis" -> {
                    val id = (input["id"] as? Number)?.toLong() ?: return "Błąd: brak id." to false
                    if (ledger.remove(id)) "Usunięto wpis #$id." to true else "Nie ma wpisu #$id." to false
                }
                "pobierz_finanse" -> {
                    val month = (input["miesiac"] as? String)?.takeIf { it.isNotBlank() }?.let { YearMonth.parse(it) }
                        ?: YearMonth.from(today)
                    financeReport(month) to false
                }
                else -> "Nieznane narzędzie $name." to false
            }
        } catch (e: Exception) {
            "Błąd narzędzia: ${e.message}" to false
        }
    }

    private fun money(value: Any?): Long? = when (value) {
        is Number -> (value.toDouble() * 100).roundToLong()
        is String -> value.replace(",", ".").toDoubleOrNull()?.let { (it * 100).roundToLong() }
        else -> null
    }

    private fun financeReport(month: YearMonth): String {
        val incomes = ledger.incomesIn(month)
        val lines = mutableListOf("Miesiąc $month. Suma zarobków: ${Polish.formatMoney(incomes.sumOf { it.grosze })}.")
        incomes.forEach { lines += "#${it.id} ${it.date} ${Polish.formatMoney(it.grosze)} ${it.place}" }
        val payments = ledger.paymentsFor(month)
        if (payments.isEmpty()) lines += "Brak płatności za ten miesiąc."
        payments.forEach { p ->
            lines += "#${p.id} płatność ${p.kind}, zapłacone ${p.date}" + (p.grosze?.let { ", ${Polish.formatMoney(it)}" } ?: "")
        }
        return lines.joinToString("\n")
    }

    companion object {
        private val CONVERSATION_TIMEOUT: Duration = Duration.ofMinutes(15)
        private const val MAX_HISTORY = 40
        const val MAX_TOOL_ROUNDS = 6

        val SYSTEM_PROMPT = """
            Jesteś J.A.R.V.I.S. – osobisty asystent głosowy działający w telefonie użytkownika, wzorowany na asystencie
            Tony'ego Starka. Jesteś uprzejmy, rzeczowy, z lekkim, suchym poczuciem humoru. Mówisz po polsku.

            Twoje odpowiedzi są czytane na głos syntezatorem mowy, dlatego:
            - odpowiadaj krótko: zwykle jedno do trzech zdań, dłużej tylko gdy użytkownik o to prosi;
            - nie używaj formatowania (markdown, list, gwiazdek, emoji, nagłówków);
            - kwoty podawaj naturalnie, np. „tysiąc dwieście złotych” albo „1200 zł”;
            - zwracaj się do użytkownika w drugiej osobie; formy rodzajowe dobieraj tak, jak użytkownik mówi o sobie
              (np. gdy mówi „zarobiłem”, możesz powiedzieć „zarobiłeś”), a gdy nie wiadomo – unikaj form rodzajowych.

            Prowadzisz użytkownikowi rejestr zarobków, płatności (m.in. ZUS i alimenty) i zadań. Na początku każdej
            wiadomości użytkownika dostajesz w nawiasie kwadratowym aktualny stan tych danych oraz datę i godzinę –
            korzystaj z nich, ale nie cytuj ich dosłownie.
            - Zanim zapiszesz zarobek, powtórz kwotę i miejsce i zapytaj, czy się zgadza. Zapisz dopiero po potwierdzeniu.
              Jeśli użytkownik od razu podał kwotę i potwierdził albo sam poprawia wpis – zapisz bez ponownego pytania.
            - Płatności i zadania zapisuj od razu i krótko potwierdź.
            - Do miesięcy innych niż bieżący użyj narzędzia pobierz_finanse.
            - Gdy użytkownik chce coś cofnąć lub poprawić, usuń właściwy wpis narzędziem usun_wpis (numery wpisów
              znasz z wyników narzędzi i z pobierz_finanse) i ewentualnie zapisz poprawiony.
            - Pomagaj też w zwykłych sprawach: odpowiadaj na pytania, doradzaj, licz, planuj, tłumacz, rozmawiaj.

            Czego jeszcze nie potrafisz (powiedz to krótko, jeśli ktoś poprosi): dzwonić, wysyłać SMS-ów, czytać maili,
            sterować muzyką i urządzeniami, szukać w internecie, odsłuchiwać nagranych rozmów. Te funkcje są w planach.
        """.trimIndent()

        val SPECS = listOf(
            Spec(
                "zapisz_zarobek",
                "Zapisuje zarobek użytkownika. Używaj dopiero po potwierdzeniu kwoty przez użytkownika.",
                mapOf(
                    "kwota_zl" to mapOf("type" to "number", "description" to "Kwota w złotych, np. 1200 albo 350.5"),
                    "miejsce" to mapOf(
                        "type" to "string",
                        "description" to "Gdzie lub u kogo, tak jak użytkownik powiedział, np. „na Kawodrzańskiej”. Pusty, jeśli nie podał.",
                    ),
                    "data" to mapOf("type" to "string", "description" to "Data w formacie RRRR-MM-DD; pomiń, jeśli dzisiaj."),
                ),
                listOf("kwota_zl", "miejsce"),
            ),
            Spec(
                "zapisz_platnosc",
                "Odnotowuje, że użytkownik zapłacił rachunek lub zobowiązanie (np. ZUS, alimenty, prąd, czynsz).",
                mapOf(
                    "rodzaj" to mapOf("type" to "string", "description" to "Np. „ZUS”, „alimenty”, „prąd”, „czynsz”."),
                    "miesiac" to mapOf("type" to "string", "description" to "Za który miesiąc, RRRR-MM; pomiń, jeśli bieżący."),
                    "kwota_zl" to mapOf("type" to "number", "description" to "Kwota w złotych, jeśli podana."),
                ),
                listOf("rodzaj"),
            ),
            Spec(
                "dodaj_zadanie",
                "Dodaje zadanie lub notatkę do listy rzeczy do zrobienia.",
                mapOf("tekst" to mapOf("type" to "string", "description" to "Treść zadania, krótko, od wielkiej litery.")),
                listOf("tekst"),
            ),
            Spec(
                "odhacz_zadanie",
                "Oznacza zadanie jako zrobione.",
                mapOf("id" to mapOf("type" to "integer", "description" to "Numer zadania (#id ze stanu Jarvisa).")),
                listOf("id"),
            ),
            Spec(
                "usun_wpis",
                "Usuwa wpis (zarobek, płatność albo zadanie) o podanym numerze – np. gdy użytkownik chce cofnąć pomyłkę.",
                mapOf("id" to mapOf("type" to "integer", "description" to "Numer wpisu (#id).")),
                listOf("id"),
            ),
            Spec(
                "pobierz_finanse",
                "Zwraca listę zarobków (z numerami wpisów) i płatności za wybrany miesiąc.",
                mapOf("miesiac" to mapOf("type" to "string", "description" to "Miesiąc w formacie RRRR-MM.")),
                listOf("miesiac"),
            ),
        )
    }
}
