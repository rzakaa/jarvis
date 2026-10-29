package pl.jarvis.brain

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolResultBlockParam
import com.anthropic.models.messages.ToolUseBlock
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import kotlin.math.roundToLong

/**
 * Mózg Jarvisa oparty na Claude (przez API). Rozmawia o wszystkim, a dane
 * (zarobki, płatności, zadania) zmienia narzędziami działającymi na [Ledger].
 *
 * Do każdej wypowiedzi dokleja krótki stan danych, więc Claude zna bieżące liczby
 * bez dodatkowych zapytań. Historia rozmowy jest tylko dopisywana; po dłuższej
 * przerwie albo wielu wymianach zaczynamy nową rozmowę od zera.
 */
class ClaudeBrain(
    apiKey: String,
    private val ledger: Ledger,
    private val model: String,
    private val clock: () -> LocalDateTime = LocalDateTime::now,
    baseUrl: String? = null,
) {
    data class Result(val text: String, val changed: Boolean)

    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .timeout(Duration.ofSeconds(60))
        .maxRetries(2)
        .apply { if (baseUrl != null) baseUrl(baseUrl) }
        .build()

    private val history = mutableListOf<MessageParam>()
    private var lastActivity: LocalDateTime? = null

    /** Wysyła wypowiedź do Claude i wykonuje jego narzędzia. Blokuje – wołać poza wątkiem UI. */
    fun handle(userText: String): Result {
        val now = clock()
        val last = lastActivity
        if (last == null || Duration.between(last, now) > CONVERSATION_TIMEOUT || history.size > MAX_HISTORY) {
            history.clear()
        }
        lastActivity = now
        history += MessageParam.ofUser("${context(now)}\n\n$userText")

        var changed = false
        try {
            repeat(MAX_TOOL_ROUNDS) {
                val response = client.messages().create(params())
                history += response.toParam()
                val stop = response.stopReason().orElse(StopReason.END_TURN)
                if (stop == StopReason.TOOL_USE) {
                    val results = response.content().mapNotNull { it.toolUse().orElse(null) }.map { call ->
                        val (output, didChange) = runTool(call)
                        if (didChange) changed = true
                        ContentBlockParam.ofToolResult(
                            ToolResultBlockParam.builder().toolUseId(call.id()).content(output).build(),
                        )
                    }
                    history += MessageParam.ofUserOfContentBlockParams(results)
                    return@repeat
                }
                if (stop == StopReason.REFUSAL) {
                    history.clear()
                    return Result("Wybacz, w tym nie mogę pomóc.", changed)
                }
                val text = response.content().mapNotNull { it.text().orElse(null)?.text() }
                    .joinToString(" ").trim()
                return Result(text.ifEmpty { "Gotowe." }, changed)
            }
            return Result("Zgubiłem wątek. Powiedz jeszcze raz, o co chodzi.", changed)
        } catch (e: Exception) {
            // Niedokończona wymiana (np. narzędzie bez wyniku) zepsułaby następne zapytanie.
            history.clear()
            throw e
        }
    }

    private fun params(): MessageCreateParams {
        val builder = MessageCreateParams.builder()
            .model(model)
            .maxTokens(4096L)
            .system(SYSTEM_PROMPT)
            .messages(history)
            // Rozmowa głosowa: krótkie, szybkie odpowiedzi.
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
            .cacheControl(CacheControlEphemeral.builder().build())
        TOOLS.forEach { builder.addTool(it) }
        if (model == MODEL_SMART) {
            // Gdyby model odmówił, serwer sam przekieruje pytanie do innego modelu.
            builder.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
            builder.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        }
        return builder.build()
    }

    // --- Stan danych dla Claude ------------------------------------------------

    private fun context(now: LocalDateTime): String {
        val today = now.toLocalDate()
        val month = YearMonth.from(today)
        val incomes = ledger.incomesIn(month)
        val payments = Brain.TRACKED.joinToString("; ") { kind ->
            val p = ledger.payments.lastOrNull { it.kind == kind && it.month == month }
            if (p != null) "$kind: zapłacone ${p.date}" else "$kind: niezapłacone"
        }
        val notes = ledger.openNotes().joinToString("; ") { "#${it.id} ${it.text}" }.ifEmpty { "brak" }
        return "[Stan Jarvisa – teraz ${today} ${"%02d:%02d".format(now.hour, now.minute)}, ${Polish.dayName(today)}. " +
            "Zarobki ${Polish.inMonth(month, today)}: ${Polish.formatMoney(incomes.sumOf { it.grosze })} " +
            "(${incomes.size} wpłat). Płatności za bieżący miesiąc: $payments. Otwarte zadania: $notes.]"
    }

    // --- Narzędzia ---------------------------------------------------------------

    private fun runTool(call: ToolUseBlock): Pair<String, Boolean> {
        @Suppress("UNCHECKED_CAST")
        val input = call._input().convert(Map::class.java) as Map<String, Any?>
        val today = clock().toLocalDate()
        return try {
            when (call.name()) {
                "zapisz_zarobek" -> {
                    val grosze = money(input["kwota_zl"]) ?: return "Błąd: brak kwoty." to false
                    val date = (input["data"] as? String)?.let { LocalDate.parse(it) } ?: today
                    val place = (input["miejsce"] as? String).orEmpty()
                    val saved = ledger.addIncome(date, grosze, place)
                    "Zapisano wpis #${saved.id}: ${Polish.formatMoney(grosze)}, ${place.ifEmpty { "bez miejsca" }}, $date." to true
                }
                "zapisz_platnosc" -> {
                    val kind = (input["rodzaj"] as? String)?.trim().orEmpty()
                    if (kind.isEmpty()) return "Błąd: brak rodzaju płatności." to false
                    val normalized = Brain.TRACKED.firstOrNull { it.equals(kind, ignoreCase = true) } ?: kind
                    val month = (input["miesiac"] as? String)?.let { YearMonth.parse(it) } ?: YearMonth.from(today)
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
                    if (ledger.notes.none { it.id == id }) return "Nie ma zadania #$id." to false
                    ledger.markDone(id)
                    "Zadanie #$id odhaczone." to true
                }
                "usun_wpis" -> {
                    val id = (input["id"] as? Number)?.toLong() ?: return "Błąd: brak id." to false
                    if (ledger.remove(id)) "Usunięto wpis #$id." to true else "Nie ma wpisu #$id." to false
                }
                "pobierz_finanse" -> {
                    val month = (input["miesiac"] as? String)?.let { YearMonth.parse(it) } ?: YearMonth.from(today)
                    financeReport(month) to false
                }
                else -> "Nieznane narzędzie ${call.name()}." to false
            }
        } catch (e: Exception) {
            "Błąd narzędzia: ${e.message}" to false
        }
    }

    private fun money(value: Any?): Long? = (value as? Number)?.toDouble()?.let { (it * 100).roundToLong() }

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
        const val MODEL_FAST = "claude-haiku-5-5"
        const val MODEL_SMART = "claude-sonnet-5-5"

        private val CONVERSATION_TIMEOUT: Duration = Duration.ofMinutes(15)
        private const val MAX_HISTORY = 40
        private const val MAX_TOOL_ROUNDS = 6

        private val SYSTEM_PROMPT = """
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

        private fun tool(name: String, description: String, properties: Map<String, Map<String, Any>>, required: List<String>): Tool =
            Tool.builder()
                .name(name)
                .description(description)
                .inputSchema(
                    Tool.InputSchema.builder()
                        .properties(
                            Tool.InputSchema.Properties.builder().apply {
                                properties.forEach { (key, schema) -> putAdditionalProperty(key, JsonValue.from(schema)) }
                            }.build(),
                        )
                        .required(required)
                        .build(),
                )
                .build()

        private val TOOLS = listOf(
            tool(
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
            tool(
                "zapisz_platnosc",
                "Odnotowuje, że użytkownik zapłacił rachunek lub zobowiązanie (np. ZUS, alimenty, prąd, czynsz).",
                mapOf(
                    "rodzaj" to mapOf("type" to "string", "description" to "Np. „ZUS”, „alimenty”, „prąd”, „czynsz”."),
                    "miesiac" to mapOf("type" to "string", "description" to "Za który miesiąc, RRRR-MM; pomiń, jeśli bieżący."),
                    "kwota_zl" to mapOf("type" to "number", "description" to "Kwota w złotych, jeśli podana."),
                ),
                listOf("rodzaj"),
            ),
            tool(
                "dodaj_zadanie",
                "Dodaje zadanie lub notatkę do listy rzeczy do zrobienia.",
                mapOf("tekst" to mapOf("type" to "string", "description" to "Treść zadania, krótko, od wielkiej litery.")),
                listOf("tekst"),
            ),
            tool(
                "odhacz_zadanie",
                "Oznacza zadanie jako zrobione.",
                mapOf("id" to mapOf("type" to "integer", "description" to "Numer zadania (#id ze stanu Jarvisa).")),
                listOf("id"),
            ),
            tool(
                "usun_wpis",
                "Usuwa wpis (zarobek, płatność albo zadanie) o podanym numerze – np. gdy użytkownik chce cofnąć pomyłkę.",
                mapOf("id" to mapOf("type" to "integer", "description" to "Numer wpisu (#id).")),
                listOf("id"),
            ),
            tool(
                "pobierz_finanse",
                "Zwraca listę zarobków (z numerami wpisów) i płatności za wybrany miesiąc.",
                mapOf("miesiac" to mapOf("type" to "string", "description" to "Miesiąc w formacie RRRR-MM.")),
                listOf("miesiac"),
            ),
        )
    }
}
