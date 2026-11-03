package pl.jarvis.assistant

import android.util.Log
import com.fasterxml.jackson.databind.JsonNode
import okhttp3.Request
import pl.jarvis.brain.JarvisTools

/**
 * Rozmowa na żywo przez OpenAI Realtime – ten sam tryb co rozmowa głosowa w ChatGPT.
 * Nie ma wbudowanej wyszukiwarki – [extraTools] (np. szukanie przez Kimi) dokłada Jarvis.
 */
class OpenAiRealtime(
    private val apiKey: String,
    private val model: String,
    private val voice: String,
    private val instruction: String,
    private val extraTools: List<JarvisTools.Spec>,
    runTool: (String, Map<String, Any?>) -> String,
    listener: Listener,
) : LiveVoice(runTool, listener) {

    override val inRate = 24_000 // Realtime przyjmuje tylko 24 kHz

    /** Trwa odpowiedź – nowej nie można zamówić, dopóki się nie skończy. */
    @Volatile private var responding = false
    @Volatile private var wantResponse = false

    override fun request(): Request = Request.Builder()
        .url("wss://api.openai.com/v1/realtime?model=$model")
        .header("Authorization", "Bearer $apiKey")
        .build()

    override fun setup(): String {
        val root = MAPPER.createObjectNode().put("type", "session.update")
        val session = root.putObject("session")
        session.put("type", "realtime")
        session.put("model", model)
        session.put("instructions", instruction)
        session.putArray("output_modalities").add("audio")
        val audio = session.putObject("audio")
        val input = audio.putObject("input")
        input.putObject("format").put("type", "audio/pcm").put("rate", inRate)
        input.putObject("transcription").put("model", "gpt-4o-mini-transcribe").put("language", "pl")
        input.putObject("noise_reduction").put("type", "far_field")
        // Wyższy próg – resztka echa z głośnika nie przerywa Jarvisowi.
        input.putObject("turn_detection").put("type", "server_vad").put("threshold", 0.6)
            .put("prefix_padding_ms", 300).put("silence_duration_ms", 500)
            .put("create_response", true).put("interrupt_response", true)
        val output = audio.putObject("output")
        output.putObject("format").put("type", "audio/pcm").put("rate", LiveVoice.OUT_RATE)
        output.put("voice", voice)
        val tools = session.putArray("tools")
        (JarvisTools.SPECS + extraTools).forEach { spec ->
            val parameters = mutableMapOf<String, Any>("type" to "object", "properties" to spec.properties)
            if (spec.required.isNotEmpty()) parameters["required"] = spec.required
            tools.add(MAPPER.valueToTree<JsonNode>(mapOf("type" to "function", "name" to spec.name, "description" to spec.description, "parameters" to parameters)))
        }
        session.put("tool_choice", "auto")
        return MAPPER.writeValueAsString(root)
    }

    override fun audioMessage(base64: String) = """{"type":"input_audio_buffer.append","audio":"$base64"}"""

    override fun textMessages(text: String): List<String> {
        val item = MAPPER.createObjectNode().put("type", "conversation.item.create")
        item.putObject("item").put("type", "message").put("role", "user")
            .putArray("content").addObject().put("type", "input_text").put("text", text)
        return listOf(MAPPER.writeValueAsString(item), """{"type":"response.create"}""")
    }

    override fun handle(msg: JsonNode) {
        when (msg.path("type").asText()) {
            "session.updated" -> markReady()
            "input_audio_buffer.speech_started" -> interrupt()
            "conversation.item.input_audio_transcription.completed" -> heard(msg.path("transcript").asText("") + " ")
            "response.created" -> responding = true
            "response.output_audio.delta" -> audio(msg.path("delta").asText(""))
            "response.output_audio_transcript.delta" -> said(msg.path("delta").asText(""))
            "response.function_call_arguments.done" -> {
                val args = runCatching { MAPPER.readTree(msg.path("arguments").asText("{}")) }.getOrNull()
                callTool(msg.path("call_id").asText(), msg.path("name").asText(), args)
            }
            "response.done" -> {
                responding = false
                val response = msg.path("response")
                val calledTools = response.path("output").any { it.path("type").asText() == "function_call" }
                if (response.path("status").asText() == "failed") Log.w(LiveVoice.TAG, "odpowiedź: ${response.path("status_details")}")
                if (wantResponse) {
                    wantResponse = false
                    send("""{"type":"response.create"}""")
                } else if (!calledTools) {
                    turnComplete()
                }
            }
            "error" -> {
                val error = msg.path("error")
                val message = error.path("message").asText("błąd")
                Log.w(LiveVoice.TAG, "błąd: ${error.path("code").asText()} $message")
                // Przed startem (zły klucz, brak środków, zły model) – koniec; potem drobne błędy pomijamy.
                if (!ready || error.path("code").asText() in FATAL) finish(message)
            }
        }
    }

    override fun toolResult(id: String, name: String, result: String): List<String> {
        val root = MAPPER.createObjectNode().put("type", "conversation.item.create")
        root.putObject("item").put("type", "function_call_output").put("call_id", id).put("output", result)
        return listOf(MAPPER.writeValueAsString(root))
    }

    /** Wyniki narzędzi wysłane – model mówi dalej (gdy jeszcze kończy poprzednią odpowiedź – zaraz po niej). */
    override fun afterTools() {
        if (responding) wantResponse = true else send("""{"type":"response.create"}""")
    }

    companion object {
        const val DEFAULT_MODEL = "gpt-realtime-2.1"
        const val DEFAULT_VOICE = "cedar"

        /** Głosy OpenAI do wyboru w Opcjach (męskie pierwsze – Jarvis). */
        val VOICES = listOf("cedar", "ash", "echo", "verse", "marin", "coral")

        private val FATAL = setOf("invalid_api_key", "insufficient_quota", "model_not_found", "session_expired")
    }
}
