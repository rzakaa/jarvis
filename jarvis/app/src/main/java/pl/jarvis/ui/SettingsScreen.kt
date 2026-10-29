package pl.jarvis.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.jarvis.assistant.Assistant
import pl.jarvis.recorder.Prefs
import pl.jarvis.recorder.RecorderService
import pl.jarvis.recorder.RecorderState
import pl.jarvis.recorder.RecorderStatus

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    var claudeKey by remember { mutableStateOf(Prefs.claudeKey(context)) }
    var picovoiceKey by remember { mutableStateOf(Prefs.picovoiceKey(context)) }
    var wakeWord by remember { mutableStateOf(Prefs.wakeWordEnabled(context)) }
    var smart by remember { mutableStateOf(Prefs.smartModel(context)) }
    var saved by remember { mutableStateOf(false) }
    val aiEnabled by Assistant.aiEnabled.collectAsState()
    val wakeStatus by RecorderState.wakeWordStatus.collectAsState()
    val recorderStatus by RecorderState.status.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { HudLabel("ustawienia · wersja 0.6", color = Hud.Cyan, size = 13.sp) }

        item {
            HudPanel(modifier = Modifier.fillMaxWidth(), title = "sztuczna inteligencja · claude") {
                HudLabel(if (aiEnabled) "status: aktywna" else "status: brak klucza", color = if (aiEnabled) Hud.Green else Hud.Amber)
                KeyField("Klucz API Claude (sk-ant-…)", claudeKey) { claudeKey = it; saved = false }
                HudLabel("model", modifier = Modifier.padding(top = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                    HudChip("Mądry · Sonnet", smart, onClick = { smart = true; saved = false })
                    HudChip("Szybki · Haiku", !smart, onClick = { smart = false; saved = false })
                }
                Text(
                    "Klucz: platform.claude.com → API Keys → Create Key (najpierw doładuj konto, np. 5 \$). " +
                        "Mądry model to ok. 5 gr za pytanie, szybki – ułamek grosza. Ustaw limit wydatków na koncie.",
                    color = Hud.TextDim,
                    fontSize = 12.sp,
                )
            }
        }

        item {
            HudPanel(modifier = Modifier.fillMaxWidth(), title = "słowo „jarvis”") {
                HudLabel(
                    when {
                        !wakeWord -> "status: wyłączone"
                        recorderStatus == RecorderStatus.STOPPED -> "status: włącz nasłuchiwanie w zakładce nagrania"
                        else -> "status: ${wakeStatus ?: "uruchamiam…"}"
                    },
                    color = if (wakeStatus == "aktywne") Hud.Green else Hud.Amber,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                    HudChip("Włączone", wakeWord, onClick = { wakeWord = true; saved = false })
                    HudChip("Wyłączone", !wakeWord, onClick = { wakeWord = false; saved = false })
                }
                KeyField("AccessKey Picovoice", picovoiceKey) { picovoiceKey = it; saved = false }
                Text(
                    "Klucz za darmo: console.picovoice.ai → załóż konto → skopiuj AccessKey. " +
                        "Słowo „Jarvis” działa, gdy w zakładce Nagrania jest włączone nasłuchiwanie.",
                    color = Hud.TextDim,
                    fontSize = 12.sp,
                )
            }
        }

        item {
            HudButton(
                if (saved) "zapisano ✓" else "zapisz",
                onClick = {
                    Prefs.saveAssistantSettings(context, claudeKey, picovoiceKey, wakeWord, smart)
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
            Text(
                "Klucze są zapisane tylko w tym telefonie, w prywatnych danych aplikacji.",
                color = Hud.TextDim,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun KeyField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        label = { Text(label, fontSize = 12.sp) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
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
