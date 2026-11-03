package pl.jarvis.ui

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.jarvis.assistant.Assistant
import pl.jarvis.assistant.CloudVoice
import pl.jarvis.assistant.GeminiLive
import pl.jarvis.assistant.OpenAiRealtime
import pl.jarvis.assistant.PhoneTools
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
        item { HudLabel("opcje · wersja 0.52", color = Hud.Cyan, size = 13.sp) }

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
                    ClaudeBudget()
                    Hint(
                        "Klucz: platform.claude.com → API Keys → Create Key (najpierw doładuj konto, np. 5 \$). " +
                            "Mądry model to ok. 5 gr za pytanie, szybki – ułamek grosza. Ustaw limit wydatków na koncie.",
                    )
                } else {
                    KeyField("Klucz API Kimi (sk-…)", settings.kimiKey) { v -> update { it.copy(kimiKey = v) } }
                    KeyField("Adres API", settings.kimiBaseUrl, secret = false) { v -> update { it.copy(kimiBaseUrl = v) } }
                    KeyField("Model (najszybszy: kimi-k3.1-pro-highspeed)", settings.kimiModel, secret = false) { v -> update { it.copy(kimiModel = v) } }
                    KimiBalance(settings.kimiKey, settings.kimiBaseUrl)
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

        item { LivePanel() }

        item { VoicePanel() }

        item { PhonePanel() }

        item { Hint("Klucze są zapisane tylko w tym telefonie, w prywatnych danych aplikacji.") }
    }
}

/** Głos Jarvisa: jeden z głosów Piper (każdy pobierany raz) albo głos systemowy. */
@Composable
private fun VoicePanel() {
    val context = LocalContext.current
    val status by PiperVoice.status.collectAsState()
    val progress by PiperVoice.progress.collectAsState()
    val error by PiperVoice.error.collectAsState()
    val voice by PiperVoice.voice.collectAsState()
    var piper by remember { mutableStateOf(Prefs.piperVoice(context)) }
    var azure by remember { mutableStateOf(Prefs.azureVoice(context)) }
    HudPanel(modifier = Modifier.fillMaxWidth(), title = "głos jarvisa") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
            HudChip("Microsoft", azure, onClick = { azure = true; Prefs.setAzureVoice(context, true) })
            HudChip("Piper", !azure && piper, onClick = {
                azure = false; piper = true
                Prefs.setAzureVoice(context, false); Prefs.setPiperVoice(context, true)
            })
            HudChip("Systemowy", !azure && !piper, onClick = {
                azure = false; piper = false
                Prefs.setAzureVoice(context, false); Prefs.setPiperVoice(context, false)
            })
        }
        if (azure) {
            AzurePanel()
            Hint("Bez internetu (albo gdy Microsoft nie odpowie) Jarvis mówi głosem Piper poniżej.")
        }
        HudLabel(
            when (status) {
                PiperVoice.Status.NOT_DOWNLOADED -> "${voice.label}: nie pobrany (ok. ${voice.sizeMb} mb)"
                PiperVoice.Status.DOWNLOADING -> "pobieram ${voice.label}… ${(progress * 100).toInt()}%"
                PiperVoice.Status.LOADING -> "wczytuję głos…"
                PiperVoice.Status.READY -> "${voice.label}: gotowy"
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
        if (piper || azure) {
            // Głosy po trzy w rzędzie; „✓” = już pobrany.
            PiperVoice.Voice.entries.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    row.forEach { v ->
                        val mark = if (PiperVoice.isDownloaded(context, v)) " ✓" else ""
                        HudChip(v.label + mark, v == voice, onClick = { PiperVoice.select(context, v) })
                    }
                }
            }
        }
        if (status == PiperVoice.Status.NOT_DOWNLOADED) {
            HudButton("pobierz głos", onClick = { PiperVoice.download(context) }, filled = true)
        }
        if (status == PiperVoice.Status.READY) {
            HudButton("posłuchaj", onClick = { PiperVoice.speak("Dzień dobry. Tu Jarvis, do usług.") {} })
        }
        if (status == PiperVoice.Status.ERROR) {
            HudButton(
                if (PiperVoice.canRetry(context)) "spróbuj ponownie" else "pobierz głos",
                onClick = { PiperVoice.retry(context) },
                filled = true,
            )
        }
        Hint("Głos działa w telefonie bez internetu. Każdy głos pobierz raz przez Wi-Fi; do tego czasu Jarvis mówi głosem systemowym. Głosy „medium” są szybsze od „bass”.")
    }
}

/** Rozmowa na żywo (ChatGPT albo Gemini): klucz, głos i model – zapisane tylko w telefonie. */
@Composable
private fun LivePanel() {
    val context = LocalContext.current
    var provider by remember { mutableStateOf(Prefs.liveProvider(context)) }
    fun keyOf(p: String) = if (p == "gemini") Prefs.geminiKey(context) else Prefs.openaiKey(context)
    fun voiceOf(p: String) = if (p == "gemini") Prefs.geminiVoice(context) else Prefs.openaiVoice(context)
    fun modelOf(p: String) = if (p == "gemini") Prefs.geminiModel(context) else Prefs.openaiModel(context)
    var key by remember { mutableStateOf(keyOf(provider)) }
    var voice by remember { mutableStateOf(voiceOf(provider)) }
    var model by remember { mutableStateOf(modelOf(provider)) }
    fun save() = Prefs.saveLive(context, provider, key, voice, model)
    fun choose(p: String) {
        provider = p
        Prefs.saveLive(context, p, keyOf(p), voiceOf(p), modelOf(p))
        key = keyOf(p); voice = voiceOf(p); model = modelOf(p)
    }
    HudPanel(modifier = Modifier.fillMaxWidth(), title = "rozmowa na żywo") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
            HudChip("ChatGPT", provider == "openai", onClick = { choose("openai") })
            HudChip("Gemini", provider == "gemini", onClick = { choose("gemini") })
            HudChip("Wyłączona", provider == "off", onClick = { choose("off") })
        }
        if (provider != "off") {
            KeyField(if (provider == "gemini") "Klucz Gemini API" else "Klucz OpenAI API", key) { key = it; save() }
            HudLabel("głos", modifier = Modifier.padding(top = 6.dp))
            (if (provider == "gemini") GeminiLive.VOICES else OpenAiRealtime.VOICES).chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                    row.forEach { v -> HudChip(v, voice == v, onClick = { voice = v; save() }) }
                }
            }
            val previewStatus by Assistant.previewStatus.collectAsState()
            HudButton(
                "▶ posłuchaj: $voice",
                onClick = {
                    save()
                    Assistant.previewVoice(provider, voice)
                },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
            previewStatus?.let { Text(it, color = if (it.startsWith("Błąd")) Hud.Red else Hud.TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
            KeyField("Model", model, secret = false) { model = it; save() }
        }
        Hint(
            "Po „Hey Jarvis” twój głos idzie prosto do modelu, a jego głos wraca od razu – bez przepisywania na tekst. " +
                "Mów zaraz po „Hey Jarvis”, możesz mu przerwać w każdej chwili. " +
                (if (provider == "gemini") "Klucz: aistudio.google.com → Get API key (trzeba włączyć płatności). "
                else "Klucz: platform.openai.com → API keys (konto z doładowaniem). Model tańszy: gpt-realtime-2.1-mini. ") +
                "Bez klucza albo internetu Jarvis działa jak dotąd. Nasłuch i nagrania dalej przepisuje telefon.",
        )
    }
}

/** Głos Microsoftu (Azure Speech): wybór głosu, klucz i region – zapisane tylko w telefonie. */
@Composable
private fun AzurePanel() {
    val context = LocalContext.current
    val error by CloudVoice.error.collectAsState()
    var key by remember { mutableStateOf(Prefs.azureKey(context)) }
    var region by remember { mutableStateOf(Prefs.azureRegion(context)) }
    var voiceName by remember { mutableStateOf(Prefs.azureVoiceName(context)) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CloudVoice.Voice.entries.forEach { v ->
            HudChip(v.label, v.id == voiceName, onClick = {
                voiceName = v.id
                Prefs.saveAzure(context, key, region, voiceName)
            })
        }
    }
    KeyField("Klucz Azure Speech", key) { key = it }
    KeyField("Region (np. westeurope)", region, secret = false) { region = it }
    HudLabel(
        when {
            key.isBlank() -> "brak klucza – mówi Piper"
            error != null -> "błąd: $error"
            else -> "gotowy"
        },
        color = if (key.isNotBlank() && error == null) Hud.Green else Hud.Amber,
    )
    HudButton("zapisz i posłuchaj", filled = true, onClick = {
        Prefs.saveAzure(context, key, region, voiceName)
        CloudVoice.speak(context, "Siema, tu Jarvis. Tak teraz brzmię.", onDone = {}, fallback = {})
    })
    Hint(
        "Klucz: portal.azure.com → Utwórz zasób → „Speech” (Usługi mowy) → warstwa cenowa Free F0, region np. Germany West Central " +
            "(West Europe nie przyjmuje nowych). Potem „Klucze i punkt końcowy” → Klucz 1 i region. Za darmo 500 tys. znaków miesięcznie.",
    )
}

/** Saldo konta Kimi – pobierane z API przy otwarciu Opcji i na żądanie. */
@Composable
private fun KimiBalance(key: String, baseUrl: String) {
    var status by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }
    LaunchedEffect(key, baseUrl, refresh) {
        if (key.isBlank()) {
            status = null
            return@LaunchedEffect
        }
        status = "sprawdzam saldo…"
        status = withContext(Dispatchers.IO) {
            runCatching { OpenAiCompatibleBrain.balance(key, baseUrl) }.fold(
                { b ->
                    "do wydania: ${money(b.available)} ${b.currency}" +
                        if (b.voucher > 0) " (w tym bonus ${money(b.voucher)} ${b.currency})" else ""
                },
                { e -> "saldo: błąd – ${e.message?.take(80)}" },
            )
        }
    }
    status?.let {
        HudLabel(it, color = if (it.startsWith("do wydania")) Hud.Green else Hud.Amber, modifier = Modifier.padding(top = 10.dp))
        HudButton("odśwież saldo", onClick = { refresh++ }, modifier = Modifier.padding(top = 6.dp))
    }
}

/** Claude nie podaje salda przez API – liczymy koszt każdego pytania od wpisanego stanu konta. */
@Composable
private fun ClaudeBudget() {
    val context = LocalContext.current
    var budget by remember { mutableStateOf(Prefs.claudeBudget(context)) }
    var spent by remember { mutableStateOf(Prefs.claudeSpent(context)) }
    var input by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2000)
            spent = Prefs.claudeSpent(context)
        }
    }
    if (budget > 0) {
        val left = budget - spent
        HudLabel(
            "do wydania ok. ${money(left)} \$ (wydane ${money(spent)} \$ z ${money(budget)} \$)",
            color = if (left > budget * 0.1) Hud.Green else Hud.Amber,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
    KeyField("Stan konta Claude w \$ (z platform.claude.com → Billing)", input, secret = false) { input = it }
    HudButton("zapisz stan konta", onClick = {
        input.replace(',', '.').toDoubleOrNull()?.takeIf { it >= 0 }?.let {
            Prefs.setClaudeBudget(context, it)
            budget = it
            spent = 0.0
            input = ""
        }
    }, modifier = Modifier.padding(top = 6.dp))
    Hint("Claude nie podaje salda aplikacjom – Jarvis odejmuje koszt każdego pytania (wg cennika) od wpisanej kwoty. Po doładowaniu wpisz nowy stan.")
}

private fun money(value: Double) = "%.2f".format(value).replace('.', ',')

/** Dostęp do telefonu: uprawnienia Androida, powiadomienia i sterowanie ekranem. */
@Composable
private fun PhonePanel() {
    val context = LocalContext.current
    var missing by remember { mutableStateOf(PhoneTools.missingPermissions(context)) }
    var notifications by remember { mutableStateOf(PhoneTools.notificationsEnabled(context)) }
    var screen by remember { mutableStateOf(PhoneTools.screenControlEnabled(context)) }
    // Dostępy włącza się w ustawieniach Androida – po powrocie odświeżamy status.
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            missing = PhoneTools.missingPermissions(context)
            notifications = PhoneTools.notificationsEnabled(context)
            screen = PhoneTools.screenControlEnabled(context)
        }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        missing = PhoneTools.missingPermissions(context)
    }
    fun open(action: String) = context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    HudPanel(modifier = Modifier.fillMaxWidth(), title = "dostęp do telefonu") {
        HudLabel(
            if (missing.isEmpty()) "kontakty, sms, kalendarz, lokalizacja: tak"
            else "brak ${missing.size} z ${PhoneTools.PERMISSIONS.size} uprawnień",
            color = if (missing.isEmpty()) Hud.Green else Hud.Amber,
        )
        if (missing.isNotEmpty()) {
            HudButton("nadaj uprawnienia", filled = true, onClick = { launcher.launch(missing.toTypedArray()) }, modifier = Modifier.padding(top = 6.dp))
        }
        HudLabel("powiadomienia: ${if (notifications) "tak" else "nie"}", color = if (notifications) Hud.Green else Hud.Amber, modifier = Modifier.padding(top = 10.dp))
        if (!notifications) HudButton("włącz dostęp do powiadomień", onClick = { open(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }, modifier = Modifier.padding(top = 6.dp))
        HudLabel("sterowanie ekranem: ${if (screen) "tak" else "nie"}", color = if (screen) Hud.Green else Hud.Amber, modifier = Modifier.padding(top = 10.dp))
        if (!screen) HudButton("włącz sterowanie ekranem", onClick = { open(Settings.ACTION_ACCESSIBILITY_SETTINGS) }, modifier = Modifier.padding(top = 6.dp))
        Hint(
            "Jarvis dzwoni, pisze SMS-y i wiadomości na WhatsAppie, prowadzi w Mapach Google, czyta kalendarz i powiadomienia, " +
                "wypełnia formularze. Wiadomości wysyła dopiero po twoim „tak”. Powiadomienia: znajdź na liście „Jarvis”. " +
                "Sterowanie ekranem: Ułatwienia dostępu → Jarvis → włącz.",
        )
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
