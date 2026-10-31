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
import java.time.Duration
import java.time.LocalDateTime

/**
 * Mózg Jarvisa oparty na Claude (przez API). Rozmawia o wszystkim, a dane
 * (zarobki, płatności, zadania) zmienia narzędziami z [JarvisTools].
 *
 * Historia rozmowy jest tylko dopisywana; po dłuższej przerwie albo wielu
 * wymianach zaczynamy nową rozmowę od zera.
 */
class ClaudeBrain(
    apiKey: String,
    ledger: Ledger,
    private val model: String,
    clock: () -> LocalDateTime = LocalDateTime::now,
    baseUrl: String? = null,
) : AiBrain {
    private val tools = JarvisTools(ledger, clock)

    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .timeout(Duration.ofSeconds(60))
        .maxRetries(2)
        .apply { if (baseUrl != null) baseUrl(baseUrl) }
        .build()

    private val history = mutableListOf<MessageParam>()
    private var lastActivity: LocalDateTime? = null

    override fun handle(userText: String, onSearching: () -> Unit, onSentence: (String) -> Unit): AiBrain.Result {
        val now = tools.now()
        if (tools.shouldReset(lastActivity, now, history.size)) history.clear()
        lastActivity = now
        history += MessageParam.ofUser("${tools.context(now)}\n\n$userText")

        var changed = false
        try {
            repeat(JarvisTools.MAX_TOOL_ROUNDS) {
                val response = client.messages().create(params())
                history += response.toParam()
                val stop = response.stopReason().orElse(StopReason.END_TURN)
                if (stop == StopReason.TOOL_USE) {
                    val results = response.content().mapNotNull { it.toolUse().orElse(null) }.map { call ->
                        @Suppress("UNCHECKED_CAST")
                        val input = call._input().convert(Map::class.java) as Map<String, Any?>
                        val (output, didChange) = tools.run(call.name(), input)
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
                    return AiBrain.Result("Wybacz, w tym nie mogę pomóc.", changed)
                }
                val text = response.content().mapNotNull { it.text().orElse(null)?.text() }
                    .joinToString(" ").trim()
                return AiBrain.Result(text.ifEmpty { "Gotowe." }, changed)
            }
            return AiBrain.Result("Zgubiłem wątek. Powiedz jeszcze raz, o co chodzi.", changed)
        } catch (e: Exception) {
            // Niedokończona wymiana (np. narzędzie bez wyniku) zepsułaby następne zapytanie.
            history.clear()
            throw e
        }
    }

    override fun completeJson(system: String, user: String): String {
        val response = client.messages().create(
            MessageCreateParams.builder()
                .model(MODEL_FAST)
                .maxTokens(2048L)
                .system("$system\nOdpowiedz wyłącznie obiektem JSON, bez żadnego innego tekstu.")
                .addUserMessage(user)
                .build(),
        )
        return response.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("").trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    }

    private fun params(): MessageCreateParams {
        val builder = MessageCreateParams.builder()
            .model(model)
            .maxTokens(4096L)
            .system(JarvisTools.SYSTEM_PROMPT)
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

    companion object {
        const val MODEL_FAST = "claude-haiku-5-5"
        const val MODEL_SMART = "claude-sonnet-5-5"

        private val TOOLS = JarvisTools.SPECS.map { spec ->
            Tool.builder()
                .name(spec.name)
                .description(spec.description)
                .inputSchema(
                    Tool.InputSchema.builder()
                        .properties(
                            Tool.InputSchema.Properties.builder().apply {
                                spec.properties.forEach { (key, schema) -> putAdditionalProperty(key, JsonValue.from(schema)) }
                            }.build(),
                        )
                        .required(spec.required)
                        .build(),
                )
                .build()
        }
    }
}
