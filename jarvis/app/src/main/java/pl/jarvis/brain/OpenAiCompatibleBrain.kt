package pl.jarvis.brain

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Duration
import java.time.LocalDateTime

/**
 * Mózg Jarvisa na API zgodnym z OpenAI (Chat Completions) – np. Kimi (Moonshot).
 * Te same narzędzia i ta sama osobowość co przy Claude ([JarvisTools]).
 * Odpowiedź przychodzi strumieniem – gotowe zdania idą do mówienia, zanim model skończy pisać.
 */
class OpenAiCompatibleBrain(
    private val apiKey: String,
    baseUrl: String,
    private val model: String,
    ledger: Ledger,
    clock: () -> LocalDateTime = LocalDateTime::now,
) : AiBrain {
    private val tools = JarvisTools(ledger, clock)
    private val base = baseUrl.trim().trimEnd('/')
    private val history = mutableListOf<ObjectNode>()
    private var lastActivity: LocalDateTime? = null

    /** W bieżącej odpowiedzi było wbudowane wyszukiwanie – modele k3 nie trawią jego wyników, kończy [SEARCH_MODEL]. */
    private var searched = false

    @Volatile
    private var lastRequestAt = 0L

    override fun handle(userText: String, onSearching: () -> Unit, onSentence: (String) -> Unit): AiBrain.Result {
        val now = tools.now()
        if (tools.shouldReset(lastActivity, now, history.size)) {
            history.clear()
            searched = false
        }
        lastActivity = now
        val turnStart = history.size
        history += message("user", "${tools.context(now)}\n\n$userText")

        var changed = false
        try {
            repeat(JarvisTools.MAX_TOOL_ROUNDS) {
                // Modele kimi-k3 psują rundę po wbudowanym wyszukiwaniu („tokenization failed”) – kończy ją k2.6.
                val roundModel = if (searched && model.startsWith("kimi-k3")) SEARCH_MODEL else model
                val (reply, rest) = stream(requestBody(history, roundModel), onSentence)
                timing?.invoke("runda ${it + 1}: " + reply.path("tool_calls").joinToString { c -> c.path("function").path("name").asText() }.ifEmpty { "odpowiedź" })
                // Odpowiedź modelu wraca do historii w całości (z ewentualnym rozumowaniem modelu).
                history += reply
                val calls = reply.path("tool_calls")
                if (calls.isArray && calls.size() > 0) {
                    for (call in calls) {
                        val function = call.path("function")
                        val arguments = function.path("arguments").asText("{}").ifBlank { "{}" }
                        val name = function.path("name").asText()
                        if (name.startsWith("$")) {
                            // Wbudowane narzędzie Kimi (np. $web_search) – serwer robi wszystko sam,
                            // my tylko odsyłamy jego argumenty bez zmian. Szukanie trwa kilkanaście sekund.
                            if (name == "\$web_search") {
                                searched = true
                                onSearching()
                            }
                            history += MAPPER.createObjectNode()
                                .put("role", "tool")
                                .put("tool_call_id", call.path("id").asText())
                                .put("name", name)
                                .put("content", arguments)
                            continue
                        }
                        @Suppress("UNCHECKED_CAST")
                        val input = runCatching { MAPPER.readValue(arguments, Map::class.java) as Map<String, Any?> }
                            .getOrDefault(emptyMap())
                        val (output, didChange) = tools.run(name, input)
                        if (didChange) changed = true
                        history += MAPPER.createObjectNode()
                            .put("role", "tool")
                            .put("tool_call_id", call.path("id").asText())
                            .put("content", output)
                    }
                    return@repeat
                }
                if (searched) {
                    // Surowe wyniki wyszukiwania wypadają z historii (zostaje pytanie i odpowiedź) –
                    // dzięki temu kolejne pytania idą znowu szybkim modelem.
                    history.subList(turnStart + 1, history.size - 1).clear()
                    searched = false
                }
                val text = reply.path("content").asText("").trim()
                return if (text.isEmpty()) AiBrain.Result("Gotowe.", changed) else AiBrain.Result(text, changed, rest)
            }
            return AiBrain.Result("Zgubiłem wątek. Powiedz jeszcze raz, o co chodzi.", changed)
        } catch (e: Exception) {
            history.clear()
            searched = false
            throw e
        }
    }

    /**
     * Rozgrzewka: to samo polecenie systemowe i narzędzia, jedno słowo odpowiedzi. Otwiera połączenie
     * i wpisuje początek zapytania do pamięci podręcznej Kimi – wtedy prawdziwe pytanie idzie szybciej.
     */
    override fun warmUp() {
        if (System.currentTimeMillis() - lastRequestAt < WARM_FOR_MS) return
        lastRequestAt = System.currentTimeMillis()
        Thread {
            runCatching {
                val body = requestBody(listOf(message("user", "."))).put("max_tokens", 1)
                call(apiKey, base, "/chat/completions", MAPPER.writeValueAsString(body))
            }
        }.start()
    }

    override fun completeJson(system: String, user: String): String {
        val body = MAPPER.createObjectNode().put("model", model).put("max_tokens", 2048)
        body.putArray("messages").add(message("system", system)).add(message("user", user))
        body.set<ObjectNode>("response_format", MAPPER.createObjectNode().put("type", "json_object"))
        if (base.contains("moonshot") && noThinking(model)) body.set<ObjectNode>("thinking", MAPPER.createObjectNode().put("type", "disabled"))
        return post("/chat/completions", body).path("choices").path(0).path("message").path("content").asText("")
    }

    /**
     * Wysyła zapytanie strumieniem i składa z kawałków wiadomość modelu (treść, rozumowanie, wywołania
     * narzędzi). Gotowe zdania treści oddaje [onSentence] na bieżąco; zwraca też niewypowiedzianą resztę.
     */
    private fun stream(body: ObjectNode, onSentence: (String) -> Unit): Pair<ObjectNode, String> {
        body.put("stream", true)
        lastRequestAt = System.currentTimeMillis()
        val request = Request.Builder()
            .url("$base/chat/completions")
            .header("Authorization", "Bearer ${apiKey.trim()}")
            .post(MAPPER.writeValueAsString(body).toRequestBody("application/json".toMediaType()))
            .build()
        val content = StringBuilder()
        val reasoning = StringBuilder()
        val calls = sortedMapOf<Int, ToolCall>()
        var spoken = 0
        HTTP.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}: ${response.body?.string().orEmpty().take(200)}")
            val source = response.body?.source() ?: throw IOException("pusta odpowiedź")
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break
                val delta = MAPPER.readTree(data).path("choices").path(0).path("delta")
                delta.path("reasoning_content").takeIf { it.isTextual }?.let { reasoning.append(it.asText()) }
                delta.path("tool_calls").forEach { c ->
                    val slot = calls.getOrPut(c.path("index").asInt(0)) { ToolCall() }
                    c.path("id").takeIf { it.isTextual }?.let { slot.id.append(it.asText()) }
                    c.path("type").takeIf { it.isTextual }?.let { slot.type = it.asText() }
                    c.path("function").path("name").takeIf { it.isTextual }?.let { slot.name.append(it.asText()) }
                    c.path("function").path("arguments").takeIf { it.isTextual }?.let { slot.arguments.append(it.asText()) }
                }
                val piece = delta.path("content").takeIf { it.isTextual }?.asText().orEmpty()
                if (piece.isEmpty()) continue
                content.append(piece)
                // Zdanie skończone – mówimy je od razu (o ile model nie woła właśnie narzędzi).
                if (calls.isEmpty()) {
                    val end = sentenceEnd(content, spoken)
                    if (end > spoken) {
                        content.substring(spoken, end).trim().takeIf { it.isNotEmpty() }?.let(onSentence)
                        spoken = end
                    }
                }
            }
        }
        val reply = MAPPER.createObjectNode().put("role", "assistant").put("content", content.toString())
        if (reasoning.isNotEmpty()) reply.put("reasoning_content", reasoning.toString())
        if (calls.isNotEmpty()) {
            val array = reply.putArray("tool_calls")
            calls.values.forEach { c ->
                array.addObject().put("id", c.id.toString()).put("type", c.type)
                    .putObject("function").put("name", c.name.toString()).put("arguments", c.arguments.toString())
            }
        }
        return reply to content.substring(spoken).trim()
    }

    /** Wywołanie narzędzia składane z kawałków strumienia ($web_search ma typ „builtin_function”). */
    private class ToolCall {
        val id = StringBuilder()
        var type = "function"
        val name = StringBuilder()
        val arguments = StringBuilder()
    }

    /** Koniec ostatniego pełnego zdania po [from] (kropka, wykrzyknik, pytajnik + spacja); [from], gdy brak. */
    private fun sentenceEnd(text: CharSequence, from: Int): Int {
        var end = from
        for (i in from + MIN_SENTENCE until text.length - 1) {
            if (text[i] in ".!?" && text[i + 1].isWhitespace() && !text[i - 1].isDigit()) end = i + 1
        }
        return end
    }

    private fun requestBody(messagesSoFar: List<ObjectNode>, model: String = this.model): ObjectNode {
        val body = MAPPER.createObjectNode().put("model", model).put("max_tokens", 2048)
        val messages = body.putArray("messages")
        messages.add(message("system", JarvisTools.SYSTEM_PROMPT))
        messagesSoFar.forEach { messages.add(it) }
        val toolArray = body.putArray("tools")
        JarvisTools.SPECS.forEach { spec ->
            toolArray.add(
                MAPPER.valueToTree<JsonNode>(
                    mapOf(
                        "type" to "function",
                        "function" to mapOf(
                            "name" to spec.name,
                            "description" to spec.description,
                            "parameters" to mapOf("type" to "object", "properties" to spec.properties, "required" to spec.required),
                        ),
                    ),
                ),
            )
        }
        // Kimi (Moonshot) ma wbudowane wyszukiwanie w internecie.
        if (base.contains("moonshot")) {
            if (noThinking(model)) body.set<ObjectNode>("thinking", MAPPER.createObjectNode().put("type", "disabled"))
            toolArray.add(
                MAPPER.createObjectNode().put("type", "builtin_function")
                    .set<ObjectNode>("function", MAPPER.createObjectNode().put("name", "\$web_search")),
            )
        }
        return body
    }

    private fun post(path: String, body: JsonNode): JsonNode = call(apiKey, base, path, MAPPER.writeValueAsString(body))

    companion object {
        const val KIMI_BASE_URL = "https://api.moonshot.ai/v1"

        /**
         * „Myślenie” wyłączamy tylko w k2.x – tam odpowiedź jest 2–3× szybsza, a narzędzia działają (12/12 w teście).
         * Modele k3 bez myślenia pomijają narzędzia (mówią „zapamiętane”, nic nie zapisując: 3–6/12), a z
         * myśleniem trafiają 12/12 i nadal odpowiadają w ok. 3,5 s.
         */
        private fun noThinking(model: String) = !model.startsWith("kimi-k3")

        /** Model kończący odpowiedź po wyszukiwaniu w internecie (k3 tego nie umie). */
        const val SEARCH_MODEL = "kimi-k2.6"

        /** Dziennik czasów (podpina warstwa Androida) – do mierzenia, co spowalnia odpowiedź. */
        @Volatile
        var timing: ((String) -> Unit)? = null


        /** Najkrótszy kawałek mówiony osobno – krótsze zdania doklejamy do następnego. */
        private const val MIN_SENTENCE = 25

        /** Po tylu ms od ostatniego zapytania połączenie i pamięć podręczna Kimi mogą być już zimne. */
        private const val WARM_FOR_MS = 60_000L

        private val MAPPER = ObjectMapper()

        // Domyślne 10 s czekania na pierwszy bajt to za mało: z wyszukiwaniem w internecie Kimi milczy dłużej.
        private val HTTP = OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(15))
            .readTimeout(Duration.ofSeconds(80))
            .callTimeout(Duration.ofSeconds(90))
            .build()

        private fun message(role: String, content: String): ObjectNode =
            MAPPER.createObjectNode().put("role", role).put("content", content)

        /** Saldo konta Kimi: do wydania (razem), w tym bonus (voucher); waluta $ (moonshot.ai) albo ¥ (moonshot.cn). */
        data class Balance(val available: Double, val voucher: Double, val currency: String)

        /** Saldo konta (GET /users/me/balance). Blokuje. */
        fun balance(apiKey: String, baseUrl: String): Balance {
            val base = baseUrl.trim().trimEnd('/')
            val data = call(apiKey, base, "/users/me/balance", null).path("data")
            return Balance(
                data.path("available_balance").asDouble(),
                data.path("voucher_balance").asDouble(),
                if (base.contains(".cn")) "¥" else "$",
            )
        }

        /** Lista modeli dostępnych dla klucza (GET /models) – do wyboru w ustawieniach. Blokuje. */
        fun listModels(apiKey: String, baseUrl: String): List<String> =
            call(apiKey, baseUrl.trim().trimEnd('/'), "/models", null)
                .path("data").mapNotNull { it.path("id").asText(null) }.sorted()

        private fun call(apiKey: String, base: String, path: String, jsonBody: String?): JsonNode {
            val request = Request.Builder()
                .url(base + path)
                .header("Authorization", "Bearer ${apiKey.trim()}")
                .apply { if (jsonBody != null) post(jsonBody.toRequestBody("application/json".toMediaType())) }
                .build()
            HTTP.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}: ${text.take(200)}")
                return MAPPER.readTree(text)
            }
        }
    }
}
