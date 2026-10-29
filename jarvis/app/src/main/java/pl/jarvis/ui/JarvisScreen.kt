package pl.jarvis.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.jarvis.assistant.Assistant
import pl.jarvis.assistant.AssistantState
import pl.jarvis.assistant.ChatMessage
import pl.jarvis.recorder.Prefs
import pl.jarvis.recorder.RecorderState
import pl.jarvis.recorder.RecorderStatus

@Composable
fun JarvisScreen() {
    val context = LocalContext.current
    val state by Assistant.state.collectAsState()
    val messages by Assistant.messages.collectAsState()
    val partial by Assistant.partial.collectAsState()
    val voiceLevel by Assistant.voiceLevel.collectAsState()
    val ambientDb by RecorderState.levelDb.collectAsState()
    val recorderStatus by RecorderState.status.collectAsState()
    val aiEnabled by Assistant.aiEnabled.collectAsState()
    val wakeStatus by RecorderState.wakeWordStatus.collectAsState()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) Assistant.toggleListening()
    }
    val onReactorTap: () -> Unit = {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            Assistant.toggleListening()
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    val mode = when (state) {
        AssistantState.IDLE -> ReactorMode.IDLE
        AssistantState.LISTENING -> ReactorMode.LISTENING
        AssistantState.THINKING -> ReactorMode.THINKING
        AssistantState.SPEAKING -> ReactorMode.SPEAKING
    }
    val level = when {
        state == AssistantState.LISTENING -> voiceLevel
        recorderStatus == RecorderStatus.LISTENING || recorderStatus == RecorderStatus.RECORDING ->
            ((ambientDb + 60f) / 60f).coerceIn(0f, 1f) * 0.6f
        else -> 0f
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "J.A.R.V.I.S.",
                color = Hud.Cyan,
                fontFamily = Hud.Mono,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
                letterSpacing = 4.sp,
            )
            HudLabel(
                (if (aiEnabled) "ai · " else "lokalnie · ") + when (recorderStatus) {
                    RecorderStatus.STOPPED -> "rejestr: off"
                    RecorderStatus.LISTENING -> "rejestr: nasłuch"
                    RecorderStatus.RECORDING -> "rejestr: zapis"
                    RecorderStatus.PAUSED -> "rejestr: pauza"
                },
                color = if (recorderStatus == RecorderStatus.RECORDING) Hud.Amber else Hud.CyanDim,
            )
        }

        val wakeEnabled = remember(wakeStatus, recorderStatus) { Prefs.wakeWordEnabled(context) }
        HudLabel(
            when {
                !wakeEnabled -> "słowo „jarvis”: wyłączone w opcjach"
                wakeStatus == "aktywne" -> "powiedz „hey jarvis” – nasłuch aktywny"
                recorderStatus == RecorderStatus.STOPPED -> "nasłuch wyłączony – włącz w zakładce nagrania"
                wakeStatus != null -> "słowo „jarvis”: $wakeStatus"
                else -> "uruchamiam nasłuch…"
            },
            color = if (wakeStatus == "aktywne") Hud.Green else Hud.Amber,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
        )

        Box(
            modifier = Modifier
                .padding(vertical = 8.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onReactorTap,
                ),
            contentAlignment = Alignment.Center,
        ) {
            ArcReactor(level = level, mode = mode, size = 210.dp)
        }

        HudLabel(
            when (state) {
                AssistantState.IDLE -> if (wakeStatus == "aktywne") "powiedz „hey jarvis” albo dotknij" else "dotknij, aby mówić"
                AssistantState.LISTENING -> partial.ifEmpty { "słucham…" }
                AssistantState.THINKING -> "przetwarzam…"
                AssistantState.SPEAKING -> "mówię…"
            },
            color = if (state == AssistantState.LISTENING) Hud.CyanBright else Hud.CyanDim,
            size = 12.sp,
            align = TextAlign.Center,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(messages) { _, message -> ChatBubble(message) }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val send: () -> Unit = {
                Assistant.submitText(input)
                input = ""
            }
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Napisz do Jarvisa…", color = Hud.TextDim, fontFamily = Hud.Mono, fontSize = 13.sp) },
                singleLine = true,
                shape = HudShape,
                textStyle = androidx.compose.ui.text.TextStyle(color = Hud.Text, fontFamily = Hud.Mono, fontSize = 14.sp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Hud.Cyan,
                    unfocusedBorderColor = Hud.CyanDim,
                    cursorColor = Hud.Cyan,
                ),
            )
            HudButton("›", onClick = send, modifier = Modifier.padding(start = 8.dp), filled = true)
        }
    }
}

@Composable
private fun ChatBubble(message: ChatMessage) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (message.fromUser) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(HudShape)
                .background(if (message.fromUser) Hud.Background.copy(alpha = 0.6f) else Hud.Panel)
                .border(1.dp, (if (message.fromUser) Hud.CyanDim else Hud.Cyan).copy(alpha = 0.5f), HudShape)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            HudLabel(if (message.fromUser) "ty" else "jarvis", size = 9.sp, color = if (message.fromUser) Hud.TextDim else Hud.Cyan)
            Text(message.text, color = Hud.Text, fontSize = 15.sp)
        }
    }
}
