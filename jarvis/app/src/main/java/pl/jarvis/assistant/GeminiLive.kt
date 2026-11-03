package pl.jarvis.assistant

import com.fasterxml.jackson.databind.JsonNode
import okhttp3.Request
import pl.jarvis.brain.JarvisTools

/** Rozmowa na żywo przez Gemini Live (Google). W internecie szuka wyszukiwarką Google. */
class GeminiLive(
    private val apiKey: String,
    private val model: String,
    private val voice: String,
    private val instruction: String,
    runTool: (String, Map<String, Any?>) -> String,
    listener: Listener,
    useMic: Boolean = true,
) : LiveVoice(runTool, listener, useMic) {

    override val inRate = 16_000

    override fun request(): Request = Request.Builder().url("$URL?key=$apiKey").build()

    override fun setup(): String {
        val root = MAPPER.createObjectNode()
        val setup = root.putObject("setup")
        setup.put("model", "models/$model")
        val generation = setup.putObject("generationConfig")
        generation.putArray("responseModalities").add("AUDIO")
        generation.putObject("speechConfig").putObject("voiceConfig").putObject("prebuiltVoiceConfig").put("voiceName", voice)
        setup.putObject("systemInstruction").putArray("parts").addObject().put("text", instruction)
        val tools = setup.putArray("tools")
        val functions = tools.addObject().putArray("functionDeclarations")
        JarvisTools.SPECS.forEach { spec ->
            val parameters = mutableMapOf<String, Any>("type" to "OBJECT", "properties" to (upperTypes(spec.properties) ?: emptyMap<String, Any>()))
            if (spec.required.isNotEmpty()) parameters["required"] = spec.required
            functions.add(MAPPER.valueToTree<JsonNode>(mapOf("name" to spec.name, "description" to spec.description, "parameters" to parameters)))
        }
        tools.addObject().putObject("googleSearch")
        setup.putObject("inputAudioTranscription")
        setup.putObject("outputAudioTranscription")
        // Mniej czuły start mowy – resztka echa z głośnika nie przerywa Jarvisowi. Koniec mowy: dopiero
        // po 3 s ciszy i mało czule – przy krótszym czekaniu wcinał się w każdą chwilę namysłu.
        setup.putObject("realtimeInputConfig").putObject("automaticActivityDetection")
            .put("startOfSpeechSensitivity", "START_SENSITIVITY_LOW")
            .put("endOfSpeechSensitivity", "END_SENSITIVITY_LOW")
            .put("silenceDurationMs", 3000)
        setup.putObject("contextWindowCompression").putObject("slidingWindow")
        return MAPPER.writeValueAsString(root)
    }

    override fun audioMessage(base64: String) =
        """{"realtimeInput":{"audio":{"data":"$base64","mimeType":"audio/pcm;rate=$inRate"}}}"""

    override fun textMessages(text: String): List<String> {
        val root = MAPPER.createObjectNode()
        root.putObject("realtimeInput").put("text", text)
        return listOf(MAPPER.writeValueAsString(root))
    }

    override fun handle(msg: JsonNode) {
        if (msg.has("setupComplete")) markReady()
        msg.get("serverContent")?.let { content ->
            heard(content.path("inputTranscription").path("text").asText(""))
            said(content.path("outputTranscription").path("text").asText(""))
            content.path("modelTurn").path("parts").forEach { audio(it.path("inlineData").path("data").asText("")) }
            if (content.path("interrupted").asBoolean(false)) interrupt()
            if (content.path("turnComplete").asBoolean(false)) turnComplete()
        }
        msg.get("toolCall")?.path("functionCalls")?.forEach { callTool(it.path("id").asText(), it.path("name").asText(), it.path("args")) }
        msg.get("goAway")?.let { finish(null) }
        msg.get("usageMetadata")?.let { android.util.Log.d(TAG, "zużycie: $it") }
    }

    override fun toolResult(id: String, name: String, result: String): List<String> {
        val root = MAPPER.createObjectNode()
        root.putObject("toolResponse").putArray("functionResponses").addObject()
            .put("id", id).put("name", name)
            .putObject("response").put("result", result).put("scheduling", "WHEN_IDLE")
        return listOf(MAPPER.writeValueAsString(root))
    }

    companion object {
        private const val URL = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        const val DEFAULT_MODEL = "gemini-3.8-live"
        const val DEFAULT_VOICE = "Charon"

        /** Głosy Gemini do wyboru w Opcjach (męskie pierwsze – Jarvis). */
        val VOICES = listOf("Charon", "Orus", "Fenrir", "Iapetus", "Algenib", "Rasalgethi", "Schedar", "Alnilam", "Puck", "Kore", "Aoede")

        /** Gemini chce typów wielkimi literami („STRING”), narzędzia Jarvisa mają małe. */
        private fun upperTypes(value: Any?): Any? = when (value) {
            is Map<*, *> -> value.entries.associate { (k, v) ->
                k as String to if (k == "type" && v is String) v.uppercase() else upperTypes(v)
            }
            is List<*> -> value.map { upperTypes(it) }
            else -> value
        }
    }
}
