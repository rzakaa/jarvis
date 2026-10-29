package pl.jarvis.brain

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import kotlin.math.roundToLong

/** Mózg AI Jarvisa – wspólny interfejs dla różnych dostawców (Claude, Kimi…). */
interface AiBrain {
    data class Result(val text: String, val changed: Boolean)

    /**
     * Wysyła wypowiedź do modelu i wykonuje jego narzędzia. Blokuje – wołać poza wątkiem UI.
     * [onSearching] – model zaczął szukać w internecie (to potrwa), można o tym powiedzieć.
     */
    fun handle(userText: String, onSearching: () -> Unit = {}): Result
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
        val memory = ledger.allMemories().joinToString("; ") { "#${it.id} ${it.text}" }.ifEmpty { "pusta" }
        return "[Stan Jarvisa – teraz $today ${"%02d:%02d".format(now.hour, now.minute)}, ${Polish.dayName(today)}. " +
            "Zarobki ${Polish.inMonth(month, today)}: ${Polish.formatMoney(incomes.sumOf { it.grosze })} " +
            "(${incomes.size} wpłat). Płatności za bieżący miesiąc: $payments. Otwarte zadania: $notes. " +
            "Pamięć o użytkowniku: $memory.]"
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
                "zapamietaj" -> {
                    val text = (input["fakt"] as? String)?.trim().orEmpty()
                    if (text.isEmpty()) return "Błąd: pusty fakt." to false
                    val saved = ledger.addMemory(clock(), text)
                    "Zapamiętano #${saved.id}." to true
                }
                "pogoda" -> {
                    val city = (input["miasto"] as? String)?.trim().orEmpty()
                    if (city.isEmpty()) return "Błąd: brak miasta – zapytaj użytkownika albo weź je z pamięci." to false
                    val days = (input["dni"] as? Number)?.toInt() ?: 0
                    WebTools.weather(city, days) to false
                }
                "pobierz_strone" -> {
                    val url = (input["adres"] as? String)?.trim().orEmpty()
                    if (url.isEmpty()) return "Błąd: brak adresu." to false
                    WebTools.fetchPage(url) to false
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

            Jesteś pełnoprawnym, inteligentnym asystentem – pomagasz we wszystkim, o co użytkownik poprosi, bez
            zbędnych zastrzeżeń. Na początku każdej wiadomości użytkownika dostajesz w nawiasie kwadratowym datę,
            godzinę, stan jego danych i wszystko, co o nim pamiętasz – korzystaj z tego, ale nie cytuj dosłownie.

            Pamięć: notuj wszystko, co warto wiedzieć o użytkowniku na przyszłość – bez pytania o zgodę.
            - Narzędziem zapamietaj zapisuj od razu każdą trwałą informację: imiona i relacje (rodzina, znajomi,
              klienci), miejsce zamieszkania, pracę i zlecenia, nawyki, preferencje, plany, terminy, ustalenia,
              to, co lubi i czego nie lubi. Jeden fakt = jeden wpis, krótko, w trzeciej osobie
              (np. „Mieszka w Gdańsku”, „Córka ma na imię Ola”).
            - Nie zapisuj drugi raz tego, co już jest w pamięci. Gdy coś się zmieniło – usuń stary wpis (usun_wpis)
              i zapisz nowy. Gdy użytkownik każe o czymś zapomnieć – usuń wpis.
            - Nie mów za każdym razem, że coś zapamiętałeś – chyba że użytkownik wprost prosi o zapamiętanie.

            Internet: masz dostęp do sieci. Do aktualnych informacji (wiadomości, ceny, godziny otwarcia, wyniki,
            fakty, których nie jesteś pewien) użyj wyszukiwania w internecie, jeśli jest dostępne, a treść
            konkretnej strony przeczytaj narzędziem pobierz_strone. Pogodę sprawdzaj narzędziem pogoda – gdy
            użytkownik nie poda miasta, weź je z pamięci, a jeśli go tam nie ma, zapytaj i zapamiętaj.

            Prowadzisz też użytkownikowi rejestr zarobków, płatności (m.in. ZUS i alimenty) i zadań.
            - Zanim zapiszesz zarobek, powtórz kwotę i miejsce i zapytaj, czy się zgadza. Zapisz dopiero po potwierdzeniu.
              Jeśli użytkownik od razu podał kwotę i potwierdził albo sam poprawia wpis – zapisz bez ponownego pytania.
            - Płatności i zadania zapisuj od razu i krótko potwierdź.
            - Do miesięcy innych niż bieżący użyj narzędzia pobierz_finanse.
            - Gdy użytkownik chce coś cofnąć lub poprawić, usuń właściwy wpis narzędziem usun_wpis (numery wpisów
              znasz z wyników narzędzi i z pobierz_finanse) i ewentualnie zapisz poprawiony.
            - Pomagaj też w zwykłych sprawach: odpowiadaj na pytania, doradzaj, licz, planuj, tłumacz, rozmawiaj.

            Technicznie jeszcze nie masz dostępu do: dzwonienia, SMS-ów, maili, kalendarza, muzyki i nagranych rozmów
            (są w przygotowaniu). Jeśli ktoś o to poprosi, powiedz to krótko i zaproponuj, co możesz zrobić zamiast tego.
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
            Spec(
                "zapamietaj",
                "Zapisuje na stałe fakt o użytkowniku (osoby, miejsca, praca, preferencje, plany, ustalenia). " +
                    "Usuwa się go narzędziem usun_wpis.",
                mapOf("fakt" to mapOf("type" to "string", "description" to "Jeden fakt, krótko, w trzeciej osobie.")),
                listOf("fakt"),
            ),
            Spec(
                "pogoda",
                "Aktualna pogoda i prognoza dla miejscowości.",
                mapOf(
                    "miasto" to mapOf("type" to "string", "description" to "Nazwa miejscowości, np. „Częstochowa”."),
                    "dni" to mapOf("type" to "integer", "description" to "Ile kolejnych dni prognozy (0–6); 0 = tylko dziś."),
                ),
                listOf("miasto"),
            ),
            Spec(
                "pobierz_strone",
                "Pobiera i zwraca tekst strony internetowej o podanym adresie.",
                mapOf("adres" to mapOf("type" to "string", "description" to "Adres strony, np. https://…")),
                listOf("adres"),
            ),
        )
    }
}
