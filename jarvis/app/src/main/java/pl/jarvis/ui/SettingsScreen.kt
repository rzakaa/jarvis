package pl.jarvis.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.jarvis.assistant.Assistant
import pl.jarvis.assistant.PiperVoice
import pl.jarvis.brain.Ledger
import pl.jarvis.brain.OpenAiCompatibleBrain
import pl.jarvis.recorder.AiProvider
import pl.jarvis.recorder.AssistantSettings
import pl.jarvis.recorder.Prefs
import pl.jarvis.recorder.RecorderService
import pl.jarvis.recorder.RecorderState
import pl.jarvis.recorder.RecorderStatus
import pl.jarvis.recorder.WakeSensitivity

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf(Prefs.assistantSettings(context)) }
    var saved by remember { mutableStateOf(false) }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var modelsStatus by remember { mutableStateOf<String?>(null) }
    var aiTest by remember { mutableStateOf<String?>(null) }
    val aiEnabled by Assistant.aiEnabled.collectAsState()
    val wakeStatus by RecorderState.wakeWordStatus.collectAsState()
    val recorderStatus by RecorderState.status.collectAsState()

    fun update(change: (AssistantSettings) -> AssistantSettings) {
        settings = change(settings)
        saved = false
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { HudLabel("opcje · wersja 0.19", color = Hud.Cyan, size = 13.sp) }

        item {
            HudPanel(modifier = Modifier.fillMaxWidth(), title = "sztuczna inteligencja") {
                HudLabel(if (aiEnabled) "status: aktywna" else "status: brak klucza", color = if (aiEnabled) Hud.Green else Hud.Amber)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                    HudChip("Claude", settings.provider == AiProvider.CLAUDE, onClick = { update { it.copy(provider = AiProvider.CLAUDE) } })
                    HudChip("Kimi", settings.provider == AiProvider.KIMI, onClick = { update { it.copy(provider = AiProvider.KIMI) } })
                }

                if (settings.provider == AiProvider.CLAUDE) {
                    KeyField("Klucz API Claude (sk-ant-…)", settings.claudeKey) { v -> update { it.copy(claudeKey = v) } }
                    HudLabel("model", modifier = Modifier.padding(top = 8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                        HudChip("Mądry · Sonnet", settings.smart, onClick = { update { it.copy(smart = true) } })
                        HudChip("Szybki · Haiku", !settings.smart, onClick = { update { it.copy(smart = false) } })
                    }
                    Hint(
                        "Klucz: platform.claude.com → API Keys → Create Key (najpierw doładuj konto, np. 5 \$). " +
                            "Mądry model to ok. 5 gr za pytanie, szybki – ułamek grosza. Ustaw limit wydatków na koncie.",
                    )
                } else {
                    KeyField("Klucz API Kimi (sk-…)", settings.kimiKey) { v -> update { it.copy(kimiKey = v) } }
                    KeyField("Adres API", settings.kimiBaseUrl, secret = false) { v -> update { it.copy(kimiBaseUrl = v) } }
                    KeyField("Model (np. kimi-k2.6)", settings.kimiModel, secret = false) { v -> update { it.copy(kimiModel = v) } }
                    HudButton(
                        "pobierz modele",
                        onClick = {
                            modelsStatus = "pobieram…"
                            val key = settings.kimiKey
                            val base = settings.kimiBaseUrl
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    runCatching { OpenAiCompatibleBrain.listModels(key, base) }
                                }
                                result.onSuccess {
                                    models = it
                                    modelsStatus = if (it.isEmpty()) "brak modeli" else "wybierz model:"
                                }.onFailure { e -> modelsStatus = "błąd: ${e.message?.take(120)}" }
                            }
                        },
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    modelsStatus?.let { HudLabel(it, modifier = Modifier.padding(top = 8.dp)) }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 6.dp),
                    ) {
                        models.forEach { id ->
                            HudChip(id, settings.kimiModel == id, onClick = { update { it.copy(kimiModel = id) } })
                        }
                    }
                    Hint(
                        "Klucz: platform.moonshot.ai → API Keys. Adres zostaw domyślny (${Prefs.DEFAULT_KIMI_BASE_URL}); " +
                            "klucze z chińskiej platformy wymagają https://api.moonshot.cn/v1. Wpisz klucz, pobierz modele i wybierz jeden.",
                    )
                }
            }
        }

        item {
            HudPanel(modifier = Modifier.fillMaxWidth(), title = "słowo „hey jarvis”") {
                HudLabel(
                    when {
                        !settings.wakeWord -> "status: wyłączone"
                        recorderStatus == RecorderStatus.STOPPED -> "status: włącz nasłuchiwanie w zakładce nagrania"
                        else -> "status: ${wakeStatus ?: "uruchamiam…"}"
                    },
                    color = if (wakeStatus == "aktywne") Hud.Green else Hud.Amber,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                    HudChip("Włączone", settings.wakeWord, onClick = { update { it.copy(wakeWord = true) } })
                    HudChip("Wyłączone", !settings.wakeWord, onClick = { update { it.copy(wakeWord = false) } })
                }
                HudLabel("czułość", modifier = Modifier.padding(top = 6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                    WakeSensitivity.entries.forEach { option ->
                        HudChip(option.label, settings.wakeSensitivity == option, onClick = { update { it.copy(wakeSensitivity = option) } })
                    }
                }
                Hint(
                    "Powiedz „Hey Jarvis” (po angielsku albo „Hej Dżarwis”). Działa w telefonie, bez konta i internetu. " +
                        "Gdy nie reaguje – zwiększ czułość; gdy budzi się sam – zmniejsz.",
                )
            }
        }

        item {
            HudButton(
                if (saved) "zapisano ✓" else "zapisz",
                onClick = {
                    Prefs.saveAssistantSettings(context, settings)
                    Assistant.reloadSettings()
                    // Słowo-klucz działa w rejestratorze – uruchamiamy go ponownie z nowymi ustawieniami.
                    if (RecorderState.status.value != RecorderStatus.STOPPED) RecorderService.restart(context)
                    saved = true
                },
                modifier = Modifier.fillMaxWidth(),
                filled = true,
            )
        }

        item {
            HudButton(
                "testuj ai",
                onClick = {
                    aiTest = "łączę…"
                    val current = settings
                    scope.launch {
                        aiTest = withContext(Dispatchers.IO) {
                            val brain = Assistant.buildBrain(current, Ledger())
                            if (brain == null) {
                                "Brak klucza albo modelu dla wybranego AI."
                            } else {
                                runCatching { "Odpowiedź: " + brain.handle("Powiedz jednym krótkim zdaniem po polsku, że połączenie działa.").text }
                                    .getOrElse { e -> "Błąd: ${e.message ?: e.javaClass.simpleName}" }
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            aiTest?.let { Text(it, color = if (it.startsWith("Błąd")) Hud.Red else Hud.Text, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
        }

        item { VoicePanel() }

        item { Hint("Klucze są zapisane tylko w tym telefonie, w prywatnych danych aplikacji.") }
    }
}

/** Głos Jarvisa: Piper pl_PL-bass-high (pobierany raz) albo głos systemowy. */
@Composable
private fun VoicePanel() {
    val context = LocalContext.current
    val status by PiperVoice.status.collectAsState()
    val progress by PiperVoice.progress.collectAsState()
    val error by PiperVoice.error.collectAsState()
    var piper by remember { mutableStateOf(Prefs.piperVoice(context)) }
    HudPanel(modifier = Modifier.fillMaxWidth(), title = "głos jarvisa") {
        HudLabel(
            when (status) {
                PiperVoice.Status.NOT_DOWNLOADED -> "piper bass-high: nie pobrany (ok. 115 mb)"
                PiperVoice.Status.DOWNLOADING -> "pobieram… ${(progress * 100).toInt()}%"
                PiperVoice.Status.LOADING -> "wczytuję głos…"
                PiperVoice.Status.READY -> "piper bass-high: gotowy"
                PiperVoice.Status.ERROR -> "błąd: ${error ?: "?"}"
            },
            color = when (status) {
                PiperVoice.Status.READY -> Hud.Green
                PiperVoice.Status.ERROR -> Hud.Red
                else -> Hud.Amber
            },
        )
        if (status == PiperVoice.Status.DOWNLOADING) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                color = Hud.Cyan,
                trackColor = Hud.CyanDim.copy(alpha = 0.3f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            HudChip("Piper · bass-high", piper, onClick = { piper = true; Prefs.setPiperVoice(context, true) })
            HudChip("Systemowy", !piper, onClick = { piper = false; Prefs.setPiperVoice(context, false) })
        }
        if (status == PiperVoice.Status.NOT_DOWNLOADED) {
            HudButton("pobierz głos", onClick = { PiperVoice.download(context) }, filled = true)
        }
        if (status == PiperVoice.Status.ERROR) {
            HudButton(
                if (PiperVoice.canRetry(context)) "spróbuj ponownie" else "pobierz głos",
                onClick = { PiperVoice.retry(context) },
                filled = true,
            )
        }
        Hint("Głos działa w telefonie bez internetu. Pobierz raz przez Wi-Fi; do tego czasu Jarvis mówi głosem systemowym.")
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, color = Hud.TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun KeyField(label: String, value: String, secret: Boolean = true, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        label = { Text(label, fontSize = 12.sp) },
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        shape = HudShape,
        textStyle = TextStyle(color = Hud.Text, fontFamily = Hud.Mono, fontSize = 13.sp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Hud.Cyan,
            unfocusedBorderColor = Hud.CyanDim,
            cursorColor = Hud.Cyan,
            focusedLabelColor = Hud.Cyan,
            unfocusedLabelColor = Hud.TextDim,
        ),
    )
}
