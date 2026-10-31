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

    override fun handle(userText: String, onSearching: () -> Unit): AiBrain.Result {
        val now = tools.now()
        if (tools.shouldReset(lastActivity, now, history.size)) history.clear()
        lastActivity = now
        history += message("user", "${tools.context(now)}\n\n$userText")

        var changed = false
        try {
            repeat(JarvisTools.MAX_TOOL_ROUNDS) {
                val response = post("/chat/completions", requestBody())
                val reply = response.path("choices").path(0).path("message")
                if (reply !is ObjectNode) throw IOException("Nieoczekiwana odpowiedź: ${response.toString().take(200)}")
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
                            if (name == "\$web_search") onSearching()
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
                val text = reply.path("content").asText("").trim()
                return AiBrain.Result(text.ifEmpty { "Gotowe." }, changed)
            }
            return AiBrain.Result("Zgubiłem wątek. Powiedz jeszcze raz, o co chodzi.", changed)
        } catch (e: Exception) {
            history.clear()
            throw e
        }
    }

    override fun completeJson(system: String, user: String): String {
        val body = MAPPER.createObjectNode().put("model", model).put("max_tokens", 2048)
        body.putArray("messages").add(message("system", system)).add(message("user", user))
        body.set<ObjectNode>("response_format", MAPPER.createObjectNode().put("type", "json_object"))
        if (base.contains("moonshot")) body.set<ObjectNode>("thinking", MAPPER.createObjectNode().put("type", "disabled"))
        return post("/chat/completions", body).path("choices").path(0).path("message").path("content").asText("")
    }

    private fun requestBody(): ObjectNode {
        val body = MAPPER.createObjectNode().put("model", model).put("max_tokens", 2048)
        val messages = body.putArray("messages")
        messages.add(message("system", JarvisTools.SYSTEM_PROMPT))
        history.forEach { messages.add(it) }
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
        // Kimi (Moonshot) ma wbudowane wyszukiwanie w internecie. „Myślenie” wyłączamy:
        // w rozmowie głosowej odpowiedź jest 2–3× szybsza, a jakość prostych odpowiedzi ta sama.
        if (base.contains("moonshot")) {
            body.set<ObjectNode>("thinking", MAPPER.createObjectNode().put("type", "disabled"))
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

        private val MAPPER = ObjectMapper()
        // Domyślne 10 s czekania na pierwszy bajt to za mało: z wyszukiwaniem w internecie Kimi milczy dłużej.
        private val HTTP = OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(15))
            .readTimeout(Duration.ofSeconds(80))
            .callTimeout(Duration.ofSeconds(90))
            .build()

        private fun message(role: String, content: String): ObjectNode =
            MAPPER.createObjectNode().put("role", role).put("content", content)

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
