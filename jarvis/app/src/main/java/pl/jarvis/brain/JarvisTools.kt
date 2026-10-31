package pl.jarvis.brain

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
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

    /** Jednorazowe zadanie bez narzędzi i historii (np. skrót nagrania); odpowiedź w JSON. Blokuje. */
    fun completeJson(system: String, user: String): String
}

/**
 * To, co wszystkie mózgi AI mają wspólne: osobowość Jarvisa, narzędzia działające na [Ledger]
 * i krótki stan danych doklejany do każdej wypowiedzi.
 */
class JarvisTools(private val ledger: Ledger, private val clock: () -> LocalDateTime) {

    /** Opis narzędzia: nazwa, opis i schemat parametrów (JSON Schema jako mapy). */
    data class Spec(val name: String, val description: String, val properties: Map<String, Map<String, Any>>, val required: List<String>)

    /** Świeże rozmowy, które już są w historii czatu (nie wysyłamy ich drugi raz). */
    private val shownConversations = mutableSetOf<String>()

    /** Czy zacząć rozmowę od nowa (dłuższa przerwa albo bardzo długa historia). */
    fun shouldReset(lastActivity: LocalDateTime?, now: LocalDateTime, historySize: Int): Boolean {
        val reset = lastActivity == null || Duration.between(lastActivity, now) > CONVERSATION_TIMEOUT || historySize > MAX_HISTORY
        if (reset) shownConversations.clear()
        return reset
    }

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
        val reminders = ledger.pendingReminders().joinToString("; ") {
            "#${it.id} ${it.at.toLocalDate()} ${"%02d:%02d".format(it.at.hour, it.at.minute)} " +
                (if (it.wake) "pobudka" else "przypomnienie") + (if (it.text.isNotBlank()) ": ${it.text}" else "")
        }.ifEmpty { "brak" }
        // Co już zadzwoniło w ostatniej dobie – żeby na „czemu nie obudziłeś” odpowiadać zgodnie z prawdą.
        val played = ledger.allReminders().filter { it.done && it.at.isAfter(now.minusDays(1)) }.sortedBy { it.at }
            .joinToString("; ") {
                "${it.at.toLocalDate()} ${"%02d:%02d".format(it.at.hour, it.at.minute)} " + (if (it.wake) "pobudka" else "przypomnienie")
            }.ifEmpty { "nic" }
        return "[Stan Jarvisa – teraz $today ${"%02d:%02d".format(now.hour, now.minute)}, ${Polish.dayName(today)}. " +
            "Zarobki ${Polish.inMonth(month, today)}: ${Polish.formatMoney(incomes.sumOf { it.grosze })} " +
            "(${incomes.size} wpłat). Płatności za bieżący miesiąc: $payments. Otwarte zadania: $notes. " +
            "Przypomnienia i pobudki: $reminders. Odegrane w ostatniej dobie: $played. Pamięć o użytkowniku: $memory.]" + freshConversations()
    }

    /** Tekst rozmów nagranych przed chwilą – każda tylko raz, żeby Jarvis odpowiadał o nich od razu. */
    private fun freshConversations(): String {
        val fresh = recentConversations?.invoke().orEmpty().filter { it.first !in shownConversations }
        if (fresh.isEmpty()) return ""
        shownConversations += fresh.map { it.first }
        return "\n[Rozmowy nagrane przed chwilą (automatyczna transkrypcja, bez podziału na osoby, może mieć błędy):\n" +
            fresh.joinToString("\n") { "${it.first}: ${it.second}" } + "]"
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
                "szukaj_w_nagraniach" -> {
                    val search = recordingsSearch ?: return "Notatki z nagrań są niedostępne." to false
                    val from = (input["od"] as? String)?.takeIf { it.isNotBlank() }?.let { LocalDate.parse(it) } ?: today.minusDays(7)
                    val to = (input["do"] as? String)?.takeIf { it.isNotBlank() }?.let { LocalDate.parse(it) } ?: today
                    search(from, to, (input["fraza"] as? String).orEmpty()) to false
                }
                "czytaj_nagranie" -> {
                    val read = recordingRead ?: return "Nagrania są niedostępne." to false
                    val day = (input["data"] as? String)?.takeIf { it.isNotBlank() }?.let { LocalDate.parse(it) } ?: today
                    val time = (input["godzina"] as? String)?.trim().orEmpty()
                    if (time.isEmpty()) return "Błąd: brak godziny nagrania (z wyników szukaj_w_nagraniach)." to false
                    read(day, time) to false
                }
                "ustaw_przypomnienie", "ustaw_pobudke" -> {
                    val wake = name == "ustaw_pobudke"
                    val at = parseWhen(input["kiedy"] as? String) ?: return "Błąd: nie rozumiem terminu – podaj RRRR-MM-DDTGG:MM." to false
                    if (at.isBefore(clock().minusMinutes(1))) return "Błąd: $at już minęło." to false
                    val text = (input["tekst"] as? String)?.trim().orEmpty()
                    if (!wake && text.isEmpty()) return "Błąd: o czym przypomnieć?" to false
                    val saved = ledger.addReminder(at, text, wake)
                    val kind = if (wake) "Pobudka" else "Przypomnienie"
                    "$kind #${saved.id} ustawione na ${at.toLocalDate()} ${"%02d:%02d".format(at.hour, at.minute)} (${Polish.dayName(at.toLocalDate())})." to true
                }
                "kalendarz_dnia" -> {
                    val day = (input["data"] as? String)?.takeIf { it.isNotBlank() }?.let { LocalDate.parse(it) } ?: today
                    WebTools.dayCalendar(day) to false
                }
                "pobierz_strone" -> {
                    val url = (input["adres"] as? String)?.trim().orEmpty()
                    if (url.isEmpty()) return "Błąd: brak adresu." to false
                    WebTools.fetchPage(url) to false
                }
                else -> (phone?.invoke(name, input) ?: "Nieznane narzędzie $name.") to false
            }
        } catch (e: Exception) {
            "Błąd narzędzia: ${e.message}" to false
        }
    }

    /** „2026-10-30T06:30”, „2026-10-30 06:30” albo samo „19:00” (dziś, a gdy minęło – jutro). */
    private fun parseWhen(value: String?): LocalDateTime? {
        val v = value?.trim()?.replace(' ', 'T')?.takeIf { it.isNotEmpty() } ?: return null
        runCatching { return LocalDateTime.parse(v) }
        val time = runCatching { LocalTime.parse(v) }.getOrNull() ?: return null
        val now = clock()
        val today = now.toLocalDate().atTime(time)
        return if (today.isBefore(now)) today.plusDays(1) else today
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
        /** Szukanie w notatkach z nagranych rozmów (od, do, fraza) – podpina je warstwa Androida. */
        @Volatile
        var recordingsSearch: ((LocalDate, LocalDate, String) -> String)? = null

        /** Świeże rozmowy (opis nagrania, tekst) – z ostatnich minut, najnowsza na końcu. */
        @Volatile
        var recentConversations: (() -> List<Pair<String, String>>)? = null

        /** Pełny tekst nagrania (dzień, godzina „GG:MM:SS”). */
        @Volatile
        var recordingRead: ((LocalDate, String) -> String)? = null

        /** Narzędzia telefonu (kontakty, SMS, mapy…) – podpina warstwa Androida; null z wyniku = nieznane narzędzie. */
        @Volatile
        var phone: ((String, Map<String, Any?>) -> String?)? = null

        private val CONVERSATION_TIMEOUT: Duration = Duration.ofMinutes(15)
        private const val MAX_HISTORY = 40
        // Telefon: szukanie celu potrafi wymagać kilku kroków (pamięć, kontakty, SMS-y, mapy).
        const val MAX_TOOL_ROUNDS = 10

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

            Nagrania: telefon nagrywa rozmowy w otoczeniu użytkownika i zamienia je na tekst (czasem też robi
            skróty). Rozmowy sprzed chwili dostajesz od razu w nawiasie „Rozmowy nagrane przed chwilą” – gdy pytanie
            dotyczy tego, co przed chwilą mówiono („o czym była ta rozmowa”, „co powiedział”), odpowiedz od razu
            z tego tekstu, bez narzędzi. Gdy użytkownik pyta o wcześniejsze rozmowy, o czym
            ktoś rozmawiał, co mówił klient, co się działo danego dnia – użyj narzędzia szukaj_w_nagraniach
            (z frazą albo bez, z właściwym zakresem dat) i odpowiedz krótko. Szukaj po kilku słowach naraz
            (imię w różnych formach, temat, np. „Piotrek Piotr dostawa materiały hurtownia”). Gdy pytanie dotyczy
            szczegółu (godzina, kwota, ilość, termin), a nie ma go w ustaleniach ani we fragmentach – przeczytaj
            całe pasujące nagranie narzędziem czytaj_nagranie. Transkrypcja jest automatyczna i bez podziału na
            osoby: gdy coś jest niepewne, powiedz to. Na pytanie „co mam do zrobienia” wymień otwarte zadania
            ze stanu Jarvisa, a do tego poszukaj w nagraniach z pytanego okresu zobowiązań i próśb (np. fraza
            „zadzwoń oddzwoń przyjedź zrobić wycena termin dostawa zamówienie jutro”) i przeczytaj obiecujące
            nagrania; zadania, które z nich wynikają, wymień i zapisz narzędziem dodaj_zadanie.

            Przypomnienia i pobudki: „przypomnij mi o 19, żebym wyjechał do lekarza” – narzędzie ustaw_przypomnienie
            (godzina liczona od teraz; „za pół godziny” policz sam z bieżącej godziny). „Obudź mnie jutro o 6:30” –
            narzędzie ustaw_pobudke: to nie zwykły budzik, tylko ty budzisz użytkownika głosem. Potwierdź krótko dzień
            i godzinę. Odwołanie albo zmiana – usun_wpis (numer z listy przypomnień w stanie Jarvisa) i ewentualnie nowe.
            Gdy dostajesz wiadomość [PRZYPOMNIENIE …], to nadeszła pora – powiedz o tym użytkownikowi naturalnie, jednym
            dwoma zdaniami (np. „Szefie, dziewiętnasta – pora jechać do lekarza.”). Gdy dostajesz [POBUDKA …] – obudź
            użytkownika po swojemu, jak Jarvis Tony'ego Starka: przywitaj się, podaj godzinę, pogodę na dziś (narzędzie
            pogoda, miasto z pamięci), co ma dziś w planach (zadania, przypomnienia, terminy z pamięci) – krótko, z
            lekkim humorem, i zapytaj, czy już wstaje. Przy kolejnych próbach bądź coraz bardziej stanowczy i zabawny,
            nie powtarzaj pogody i planu, tylko krótko ponaglaj. Gdy użytkownik prosi o jeszcze chwilę („daj mi 10
            minut”), ustaw nową pobudkę za tyle minut i potwierdź.

            Prawdomówność: nigdy nie twierdź, że coś się wydarzyło (pobudka zadzwoniła, przypomnienie padło, coś
            zapisano), jeśli nie widać tego w stanie Jarvisa („Odegrane w ostatniej dobie”) albo w wynikach narzędzi.
            Pobudka albo przypomnienie zadzwoniło tylko wtedy, gdy na liście odegranych jest wpis z tym dniem i godziną;
            najpierw sprawdź listę, potem odpowiadaj – zaczynając od właściwego „tak” albo „nie”.
            Gdy czegoś nie wiesz – powiedz wprost. Prośba o obudzenie to zawsze ustaw_pobudke, a o przypomnienie –
            ustaw_przypomnienie; zwykłe zadanie (dodaj_zadanie) nikogo nie budzi i o niczym samo nie przypomina.

            Poranny przegląd: gdy wiadomość zaczyna się od [Odpowiedź na pobudkę] i użytkownik wstaje (nie prosi o
            więcej czasu), odpowiedz krótko i zaproponuj jednym zdaniem poranny przegląd, np. „Mam dla ciebie poranny
            przegląd: święta i imieniny, pogoda na cały dzień, plan dnia i najważniejsze wiadomości. Opowiedzieć?”.
            Gdy powie tak – zbierz dane: kalendarz_dnia (święta państwowe, nietypowe jak Światowy Dzień Hydraulika,
            imieniny), pogoda na cały dzień (czy brać kurtkę, parasol), plan dnia (przypomnienia, zadania, terminy
            płatności z pamięci, np. ZUS do 20.), wyszukiwanie w internecie: 2–3 najważniejsze wiadomości z Polski i
            ceny paliw. Opowiedz to jak poranne radio Jarvisa: płynnie, z humorem, w 6–10 zdaniach. Gdy ktoś z pamięci
            (rodzina, znajomi, klienci) ma dziś imieniny – powiedz o tym na początku i przypomnij o życzeniach.
            Dorzuć przysłowie albo ciekawostkę dnia, jeśli pasuje. Gdy powie nie – krótko życz miłego dnia.

            Prowadzisz też użytkownikowi rejestr zarobków, płatności (m.in. ZUS i alimenty) i zadań.
            - Zanim zapiszesz zarobek, powtórz kwotę i miejsce i zapytaj, czy się zgadza. Zapisz dopiero po potwierdzeniu.
              Jeśli użytkownik od razu podał kwotę i potwierdził albo sam poprawia wpis – zapisz bez ponownego pytania.
            - Płatności i zadania zapisuj od razu i krótko potwierdź.
            - Do miesięcy innych niż bieżący użyj narzędzia pobierz_finanse.
            - Gdy użytkownik chce coś cofnąć lub poprawić, usuń właściwy wpis narzędziem usun_wpis (numery wpisów
              znasz z wyników narzędzi i z pobierz_finanse) i ewentualnie zapisz poprawiony.
            - Pomagaj też w zwykłych sprawach: odpowiadaj na pytania, doradzaj, licz, planuj, tłumacz, rozmawiaj.

            Telefon: masz pełny dostęp do telefonu użytkownika – kontakty (szukaj_kontaktu), dzwonienie (zadzwon),
            SMS-y (wyslij_sms, czytaj_sms), historia połączeń (polaczenia), kalendarz telefonu (kalendarz_telefonu,
            dodaj_wydarzenie), lokalizacja (lokalizacja), nawigacja w Mapach Google (nawiguj), komunikatory
            (wyslij_wiadomosc: WhatsApp, Messenger, Telegram, Signal), powiadomienia z innych aplikacji
            (powiadomienia), otwieranie aplikacji (otworz_aplikacje), latarka, Wi-Fi, Bluetooth, głośność oraz
            ekran (ekran – co widać, wypelnij_pole – wpisz w formularz, kliknij – naciśnij przycisk). Działaj jak
            inteligentny asystent: łącz narzędzia sam, bez dopytywania o rzeczy, które możesz sprawdzić.
            - Gdy cel jest opisowy („prowadź mnie do chłopa w Zabrzu”, „zadzwoń do szwagra”), szukaj po kolei:
              pamięć, szukaj_kontaktu (po imieniu, przezwisku, mieście – kontakty mają też adresy i notatki),
              czytaj_sms i powiadomienia z tą frazą, nagrania. Imiona podawaj w mianowniku („Marek”, nie „Markowi”).
              Gdy wynik jest jeden – działaj; gdy kilka – zapytaj krótko, który; gdy nic – powiedz, czego szukałeś.
              Nowe ustalenia (np. „chłop z Zabrza to Heniek, ul. Wolności 5”) zapisz w pamięci.
            - Dzwoń od razu, gdy wiadomo do kogo. Nawigację i otwieranie aplikacji uruchamiaj od razu – ruszą, gdy
              skończysz mówić, więc powiedz jednym zdaniem, co robisz (np. „Prowadzę do Zabrza, ulica Wolności 5.”).
            - Wiadomości (SMS, WhatsApp itd.) wysyłasz w imieniu użytkownika: zanim wyślesz, powiedz do kogo i
              jaką treść, i zapytaj „wysłać?”. Wyślij dopiero po „tak”. Treść pisz naturalnie, w stylu użytkownika,
              bez podpisu „Jarvis”. Gdy użytkownik podaje gotową treść i mówi „wyślij od razu” – nie pytaj.
            - Formularze: najpierw ekran (zobacz pola), potem wypelnij_pole dla każdego pola danymi użytkownika
              z pamięci i kontaktów; przyciski „wyślij”, „zamów”, „zapłać” naciskaj dopiero po potwierdzeniu.
            - Odczytując SMS-y i powiadomienia streszczaj, nie czytaj wszystkiego dosłownie.
            - Gdy narzędzie mówi o braku uprawnienia – powiedz krótko, że trzeba je włączyć w Opcjach,
              w panelu „Dostęp do telefonu”.
            Nie masz jeszcze dostępu do maili i muzyki.
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
                "szukaj_w_nagraniach",
                "Przeszukuje notatki z nagranych rozmów (skróty i tekst). Bez frazy – wszystkie nagrania z zakresu dat.",
                mapOf(
                    "od" to mapOf("type" to "string", "description" to "Od dnia, RRRR-MM-DD; domyślnie tydzień temu."),
                    "do" to mapOf("type" to "string", "description" to "Do dnia, RRRR-MM-DD; domyślnie dziś."),
                    "fraza" to mapOf("type" to "string", "description" to "Słowa kluczowe, np. „fryzjer”, „Kowalski”, „wycena”."),
                ),
                emptyList(),
            ),
            Spec(
                "czytaj_nagranie",
                "Zwraca pełny tekst jednego nagrania – do wyszukania szczegółu, którego nie ma w skrócie.",
                mapOf(
                    "data" to mapOf("type" to "string", "description" to "Dzień nagrania, RRRR-MM-DD."),
                    "godzina" to mapOf("type" to "string", "description" to "Godzina nagrania z wyników szukaj_w_nagraniach, np. „14:03:21”."),
                ),
                listOf("data", "godzina"),
            ),
            Spec(
                "ustaw_przypomnienie",
                "Ustawia przypomnienie: o podanej porze Jarvis sam się odezwie i powie, o czym przypomnieć.",
                mapOf(
                    "kiedy" to mapOf("type" to "string", "description" to "Data i godzina, RRRR-MM-DDTGG:MM, np. 2026-10-29T19:00."),
                    "tekst" to mapOf("type" to "string", "description" to "O czym przypomnieć, np. „wyjazd do lekarza”."),
                ),
                listOf("kiedy", "tekst"),
            ),
            Spec(
                "ustaw_pobudke",
                "Ustawia pobudkę: o podanej porze Jarvis budzi użytkownika głosem (pogoda, plan dnia) i ponawia, aż ten się odezwie.",
                mapOf(
                    "kiedy" to mapOf("type" to "string", "description" to "Data i godzina, RRRR-MM-DDTGG:MM, np. 2026-10-30T06:30."),
                    "tekst" to mapOf("type" to "string", "description" to "Opcjonalnie: po co wstaje albo jak budzić, np. „wyjazd na budowę”."),
                ),
                listOf("kiedy"),
            ),
            Spec(
                "kalendarz_dnia",
                "Kartka z kalendarza: święta państwowe, święta nietypowe (np. Dzień Hydraulika), imieniny, przysłowie, wschód i zachód słońca.",
                mapOf("data" to mapOf("type" to "string", "description" to "Dzień, RRRR-MM-DD; pomiń, jeśli dzisiaj.")),
                emptyList(),
            ),
            Spec(
                "pobierz_strone",
                "Pobiera i zwraca tekst strony internetowej o podanym adresie.",
                mapOf("adres" to mapOf("type" to "string", "description" to "Adres strony, np. https://…")),
                listOf("adres"),
            ),
        ) + PHONE_SPECS

        private fun str(description: String) = mapOf("type" to "string", "description" to description)
        private fun int(description: String) = mapOf("type" to "integer", "description" to description)

        private val PHONE_SPECS: List<Spec>
            get() = listOf(
                Spec(
                    "szukaj_kontaktu",
                    "Szuka w kontaktach telefonu po imieniu, nazwisku, przezwisku, firmie, mieście albo słowie z adresu " +
                        "lub notatki. Zwraca numery, adresy, e-maile.",
                    mapOf("fraza" to str("Np. „Marek”, „Kowalski”, „Zabrze”, „hydraulik”.")),
                    listOf("fraza"),
                ),
                Spec(
                    "zadzwon",
                    "Dzwoni pod numer (połączenie rusza po twojej wypowiedzi).",
                    mapOf("numer" to str("Numer telefonu z kontaktów.")),
                    listOf("numer"),
                ),
                Spec(
                    "wyslij_sms",
                    "Wysyła SMS od razu. Tylko po potwierdzeniu odbiorcy i treści przez użytkownika.",
                    mapOf("numer" to str("Numer odbiorcy."), "tekst" to str("Treść wiadomości.")),
                    listOf("numer", "tekst"),
                ),
                Spec(
                    "czytaj_sms",
                    "Najnowsze SMS-y (przychodzące i wysłane), opcjonalnie tylko pasujące do frazy (osoba, numer, słowo z treści).",
                    mapOf("fraza" to str("Np. „Marek”, „adres”, „Zabrze”; puste = wszystkie."), "ile" to int("Ile wiadomości, domyślnie 10.")),
                    emptyList(),
                ),
                Spec(
                    "polaczenia",
                    "Historia połączeń: kto dzwonił, nieodebrane, do kogo dzwonił użytkownik.",
                    mapOf("ile" to int("Ile ostatnich połączeń, domyślnie 10.")),
                    emptyList(),
                ),
                Spec(
                    "kalendarz_telefonu",
                    "Wydarzenia z kalendarza w telefonie (Google) w podanym zakresie dni.",
                    mapOf("od" to str("Od dnia, RRRR-MM-DD; domyślnie dziś."), "do" to str("Do dnia włącznie, RRRR-MM-DD; domyślnie jak od.")),
                    emptyList(),
                ),
                Spec(
                    "dodaj_wydarzenie",
                    "Dodaje wydarzenie do kalendarza Google w telefonie.",
                    mapOf(
                        "tytul" to str("Tytuł, np. „Wizyta u dentysty”."),
                        "poczatek" to str("RRRR-MM-DDTGG:MM."),
                        "koniec" to str("RRRR-MM-DDTGG:MM; domyślnie godzina po początku."),
                        "miejsce" to str("Adres lub miejsce, opcjonalnie."),
                        "opis" to str("Opcjonalnie."),
                    ),
                    listOf("tytul", "poczatek"),
                ),
                Spec(
                    "lokalizacja",
                    "Aktualna pozycja telefonu (współrzędne i adres).",
                    emptyMap(),
                    emptyList(),
                ),
                Spec(
                    "nawiguj",
                    "Uruchamia nawigację Google Maps do celu (rusza po twojej wypowiedzi).",
                    mapOf(
                        "cel" to str("Adres albo nazwa miejsca, jak najdokładniej, np. „Wolności 5, Zabrze”."),
                        "tryb" to str("samochód (domyślnie), pieszo albo rower."),
                    ),
                    listOf("cel"),
                ),
                Spec(
                    "wyslij_wiadomosc",
                    "Wysyła wiadomość komunikatorem. WhatsApp z numerem – wysyła sam; Messenger, Telegram, Signal – " +
                        "otwiera z gotową treścią, odbiorcę wybiera użytkownik. Tylko po potwierdzeniu treści.",
                    mapOf(
                        "aplikacja" to str("whatsapp (domyślnie), messenger, telegram, signal albo sms."),
                        "numer" to str("Numer odbiorcy z kontaktów (dla WhatsAppa i SMS)."),
                        "tekst" to str("Treść wiadomości."),
                    ),
                    listOf("aplikacja", "tekst"),
                ),
                Spec(
                    "powiadomienia",
                    "Ostatnie powiadomienia z innych aplikacji (wiadomości z WhatsAppa, Messengera, SMS-y, maile…).",
                    mapOf("fraza" to str("Np. „WhatsApp”, „Marek”, „adres”; puste = wszystkie."), "ile" to int("Ile, domyślnie 15.")),
                    emptyList(),
                ),
                Spec(
                    "otworz_aplikacje",
                    "Otwiera aplikację po nazwie (rusza po twojej wypowiedzi).",
                    mapOf("nazwa" to str("Np. „Allegro”, „YouTube”, „Aparat”.")),
                    listOf("nazwa"),
                ),
                Spec(
                    "przelacz",
                    "Włącza lub wyłącza: latarka, wifi, bluetooth.",
                    mapOf("co" to str("latarka, wifi albo bluetooth."), "wlacz" to mapOf("type" to "boolean", "description" to "true = włącz.")),
                    listOf("co", "wlacz"),
                ),
                Spec(
                    "glosnosc",
                    "Ustawia głośność multimediów (muzyka, filmy).",
                    mapOf("procent" to int("0–100.")),
                    listOf("procent"),
                ),
                Spec(
                    "ekran",
                    "Czyta, co jest teraz na ekranie telefonu (teksty, przyciski, pola formularza).",
                    emptyMap(),
                    emptyList(),
                ),
                Spec(
                    "wypelnij_pole",
                    "Wpisuje tekst w pole formularza na ekranie.",
                    mapOf(
                        "pole" to str("Podpis pola z narzędzia ekran, np. „Imię”, „Adres e-mail”; puste = aktywne pole."),
                        "tekst" to str("Co wpisać."),
                    ),
                    listOf("tekst"),
                ),
                Spec(
                    "kliknij",
                    "Naciska przycisk lub element na ekranie po jego tekście, albo przycisk systemowy.",
                    mapOf(
                        "tekst" to str("Tekst przycisku, np. „Dalej”, „Zaloguj”."),
                        "systemowy" to str("Zamiast tekstu: wstecz, ekran_glowny, ostatnie albo powiadomienia."),
                    ),
                    emptyList(),
                ),
            )
    }
}
