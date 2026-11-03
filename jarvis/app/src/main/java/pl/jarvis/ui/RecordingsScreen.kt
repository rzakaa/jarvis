package pl.jarvis.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.jarvis.recorder.MicSource
import pl.jarvis.recorder.Prefs
import pl.jarvis.recorder.RecorderService
import pl.jarvis.recorder.RecorderState
import pl.jarvis.recorder.RecorderStatus
import pl.jarvis.recorder.Recording
import pl.jarvis.recorder.Recordings
import pl.jarvis.recorder.Sensitivity
import pl.jarvis.recorder.Transcriber
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val polish = Locale.forLanguageTag("pl-PL")
private val dayFormat = DateTimeFormatter.ofPattern("EEEE, d MMMM", polish)
private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss")

@Composable
fun RecordingsScreen() {
    val context = LocalContext.current
    val status by RecorderState.status.collectAsState()
    val error by RecorderState.error.collectAsState()
    val version by RecorderState.recordingsVersion.collectAsState()
    val recordings by produceState(initialValue = emptyList<Recording>(), version) {
        value = withContext(Dispatchers.IO) { Recordings.list(context) }
    }
    val player = remember { SimplePlayer() }
    DisposableEffect(Unit) { onDispose { player.stop() } }
    var toDelete by remember { mutableStateOf<Recording?>(null) }
    var toRead by remember { mutableStateOf<Recording?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        if (results[Manifest.permission.RECORD_AUDIO] == true) {
            startRecorder(context)
        } else {
            RecorderState.error.value = "Bez dostępu do mikrofonu Jarvis nie może nasłuchiwać."
        }
    }
    val onToggle: () -> Unit = {
        if (status != RecorderStatus.STOPPED) {
            RecorderService.stop(context)
        } else {
            val missing = requiredPermissions().filter {
                context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isEmpty()) startRecorder(context) else permissionLauncher.launch(missing.toTypedArray())
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { HudLabel("rejestrator rozmów · wersja 0.44", color = Hud.Cyan, size = 13.sp) }
        item { StatusPanel(status, onToggle) }
        item { SettingsPanel(status) }
        error?.let { message -> item { Text(message, color = Hud.Red, fontSize = 14.sp) } }
        item { BatteryHint(context) }
        item { TranscriptionPanel() }
        item { TodaySummary(recordings) }

        recordings.groupBy { it.start.toLocalDate() }.forEach { (day, dayRecordings) ->
            item(key = "day-$day") { HudLabel(dayLabel(day), color = Hud.Cyan, modifier = Modifier.padding(top = 8.dp)) }
            items(dayRecordings, key = { it.file.absolutePath }) { recording ->
                RecordingRow(
                    recording = recording,
                    isPlaying = player.current == recording.file,
                    onPlay = { player.toggle(recording.file) },
                    onDelete = { toDelete = recording },
                    onRead = { toRead = recording },
                )
            }
        }
    }

    toDelete?.let { recording ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Usunąć nagranie?") },
            text = { Text("Nagranie z ${recording.start.format(timeFormat)} zostanie trwale usunięte.") },
            confirmButton = {
                TextButton(onClick = {
                    if (player.current == recording.file) player.stop()
                    recording.relatedFiles.forEach { it.delete() }
                    RecorderState.recordingsChanged()
                    toDelete = null
                }) { Text("Usuń", color = Hud.Red) }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Anuluj") } },
        )
    }

    toRead?.let { recording ->
        AlertDialog(
            onDismissRequest = { toRead = null },
            title = { Text(recording.topic ?: "Nagranie z ${recording.start.format(timeFormat)}") },
            text = {
                LazyColumn {
                    recording.summary?.let { item { Text(it, color = Hud.Cyan, modifier = Modifier.padding(bottom = 12.dp)) } }
                    item { Text(recording.transcript ?: "Jeszcze nie przepisane.") }
                }
            },
            confirmButton = { TextButton(onClick = { toRead = null }) { Text("Zamknij") } },
        )
    }
}

/** Zamiana nagrań na tekst: model w telefonie, przepisywanie podczas ładowania. */
@Composable
private fun TranscriptionPanel() {
    val context = LocalContext.current
    val status by Transcriber.status.collectAsState()
    val progress by Transcriber.progress.collectAsState()
    val pending by Transcriber.pending.collectAsState()
    val error by Transcriber.error.collectAsState()
    HudPanel(modifier = Modifier.fillMaxWidth(), title = "notatki z rozmów") {
        HudLabel(
            when (status) {
                Transcriber.Status.NOT_DOWNLOADED -> "model mowy: nie pobrany (ok. ${Transcriber.SIZE_MB} mb)"
                Transcriber.Status.DOWNLOADING -> "pobieram… ${(progress * 100).toInt()}%"
                Transcriber.Status.READY -> if (pending > 0) "czeka na przepisanie: $pending" else "wszystko przepisane"
                Transcriber.Status.WORKING -> "przepisuję… zostało $pending"
                Transcriber.Status.ERROR -> "błąd: ${error ?: "?"}"
            },
            color = when (status) {
                Transcriber.Status.ERROR -> Hud.Red
                Transcriber.Status.READY -> Hud.Green
                else -> Hud.Amber
            },
        )
        if (status == Transcriber.Status.DOWNLOADING) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                color = Hud.Cyan,
                trackColor = Hud.CyanDim.copy(alpha = 0.3f),
            )
        }
        when {
            status == Transcriber.Status.NOT_DOWNLOADED || (status == Transcriber.Status.ERROR && !Transcriber.isDownloaded(context)) ->
                HudButton("pobierz model", onClick = { Transcriber.download(context) }, filled = true, modifier = Modifier.padding(top = 8.dp))
            (status == Transcriber.Status.READY || status == Transcriber.Status.ERROR) && pending > 0 ->
                HudButton("przepisz teraz", onClick = { Transcriber.runPending(context) }, modifier = Modifier.padding(top = 8.dp))
        }
        var summaries by remember { mutableStateOf(Prefs.autoSummaries(context)) }
        HudLabel("skróty ai po każdej rozmowie", modifier = Modifier.padding(top = 10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
            HudChip("wyłączone", !summaries, onClick = { summaries = false; Prefs.setAutoSummaries(context, false) })
            HudChip("włączone", summaries, onClick = { summaries = true; Prefs.setAutoSummaries(context, true) })
        }
        var promises by remember { mutableStateOf(Prefs.commitments(context)) }
        HudLabel("przypominanie obietnic z rozmów", modifier = Modifier.padding(top = 10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
            HudChip("wyłączone", !promises, onClick = { promises = false; Prefs.setCommitments(context, false) })
            HudChip("włączone", promises, onClick = { promises = true; Prefs.setCommitments(context, true) })
        }
        Text(
            "Gdy w rozmowie obiecasz ofertę, wyjazd do klienta czy telefon, Jarvis ustawi przypomnienie (wyjazd – godzinę " +
                "wcześniej, bez terminu – jutro o 9). Do AI idą tylko rozmowy ze słowami typu „jutro”, „przyjadę”, „oferta”.",
            color = Hud.TextDim,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            "Tekst powstaje w telefonie, za darmo – samo nagranie nigdy go nie opuszcza. Model pobiera się sam na Wi-Fi, a nagrania przepisuję zaraz po zakończeniu (przy ładowaniu albo od 40% baterii). " +
                "Gdy zapytasz o rozmowę, Jarvis szuka w tekstach i wysyła do AI tylko pasujące fragmenty. " +
                "Skróty AI (kosztują tokeny) dodatkowo same wyłapują zadania z każdej rozmowy. " +
                "Dźwięk znika sam po przepisaniu: nasłuch po 7 dniach, rozmowy telefoniczne po 30 – tekst zostaje na zawsze.",
            color = Hud.TextDim,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

private fun requiredPermissions(): List<String> = buildList {
    add(Manifest.permission.RECORD_AUDIO)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
}

private fun startRecorder(context: Context) {
    Prefs.setWantsRecording(context, true)
    RecorderService.start(context)
}

@Composable
private fun StatusPanel(status: RecorderStatus, onToggle: () -> Unit) {
    val level by RecorderState.levelDb.collectAsState()
    val accent = if (status == RecorderStatus.RECORDING) Hud.Amber else Hud.Cyan
    HudPanel(modifier = Modifier.fillMaxWidth(), accent = accent, title = "status") {
        Text(
            when (status) {
                RecorderStatus.STOPPED -> "WYŁĄCZONY"
                RecorderStatus.LISTENING -> "NASŁUCHUJĘ"
                RecorderStatus.RECORDING -> "NAGRYWAM ROZMOWĘ"
                RecorderStatus.PAUSED -> "PAUZA – ROZMOWA Z JARVISEM"
            },
            color = accent,
            fontFamily = Hud.Mono,
            fontSize = 20.sp,
        )
        Text(
            when (status) {
                RecorderStatus.STOPPED -> "Włącz, żeby Jarvis zapisywał rozmowy z otoczenia."
                RecorderStatus.LISTENING -> "Czekam na mowę. Cisza nie jest zapisywana."
                RecorderStatus.RECORDING -> "Przerwy do minuty zostają w tym samym nagraniu."
                RecorderStatus.PAUSED -> "Wracam do nasłuchu, gdy skończysz rozmawiać z Jarvisem."
            },
            color = Hud.TextDim,
            fontSize = 14.sp,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        if (status == RecorderStatus.LISTENING || status == RecorderStatus.RECORDING) {
            LinearProgressIndicator(
                progress = { ((level + 60f) / 60f).coerceIn(0f, 1f) }, // -60 dB = pusto, 0 dB = pełno
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp),
                color = accent,
                trackColor = Hud.CyanDim.copy(alpha = 0.3f),
            )
        }
        HudButton(
            if (status == RecorderStatus.STOPPED) "włącz nasłuchiwanie" else "wyłącz",
            onClick = onToggle,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            filled = status == RecorderStatus.STOPPED,
        )
    }
}

@Composable
private fun SettingsPanel(status: RecorderStatus) {
    val context = LocalContext.current
    val sensitivity by RecorderState.sensitivity.collectAsState()
    val source by RecorderState.micSource.collectAsState()
    val info by RecorderState.micInfo.collectAsState()
    HudPanel(modifier = Modifier.fillMaxWidth(), title = "ustawienia") {
        HudLabel("czułość na mowę")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
            Sensitivity.entries.forEach { option ->
                HudChip(option.label, option == sensitivity, onClick = {
                    RecorderState.sensitivity.value = option
                    Prefs.setSensitivity(context, option)
                })
            }
        }
        HudLabel("źródło mikrofonu", modifier = Modifier.padding(top = 6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
            MicSource.entries.forEach { option ->
                HudChip(option.label, option == source, onClick = {
                    RecorderState.micSource.value = option
                    Prefs.setMicSource(context, option)
                })
            }
        }
        Text(
            if (status == RecorderStatus.STOPPED) "Zmiana źródła działa po włączeniu nasłuchiwania."
            else "Teraz: ${info ?: "?"}. Zmiana źródła działa po Wyłącz → Włącz.",
            color = Hud.TextDim,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun BatteryHint(context: Context) {
    val powerManager = context.getSystemService(PowerManager::class.java)
    if (powerManager.isIgnoringBatteryOptimizations(context.packageName)) return
    HudPanel(modifier = Modifier.fillMaxWidth(), accent = Hud.Amber, title = "uwaga") {
        Text("Żeby system nie usypiał Jarvisa, wyłącz dla niego oszczędzanie baterii.", color = Hud.Text, fontSize = 14.sp)
        HudButton(
            "ustaw",
            onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
                )
            },
            modifier = Modifier.padding(top = 10.dp),
            accent = Hud.Amber,
        )
    }
}

@Composable
private fun TodaySummary(recordings: List<Recording>) {
    val today = recordings.filter { it.start.toLocalDate() == LocalDate.now() }
    val minutes = today.sumOf { it.durationSeconds } / 60
    val discarded by RecorderState.discarded.collectAsState()
    HudLabel(
        "dziś: ${today.size} nagrań · ~$minutes min mowy" + if (discarded > 0) " · odrzucone: $discarded" else "",
    )
}

@Composable
private fun RecordingRow(
    recording: Recording,
    isPlaying: Boolean,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
    onRead: () -> Unit,
) {
    HudPanel(modifier = Modifier.fillMaxWidth(), accent = if (isPlaying) Hud.Amber else Hud.CyanDim) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(enabled = recording.transcript != null, onClick = onRead),
            ) {
                Text(recording.start.format(timeFormat), color = Hud.Text, fontFamily = Hud.Mono, fontSize = 17.sp)
                HudLabel(formatDuration(recording.durationSeconds) + (recording.topic?.let { " · $it" } ?: ""))
                val preview = recording.summary ?: recording.transcript?.take(140)
                if (!preview.isNullOrBlank()) Text(preview, color = Hud.TextDim, fontSize = 13.sp, maxLines = 3)
            }
            // Po kilku dniach dźwięk znika (zostaje tekst) – wtedy nie ma czego odtwarzać.
            if (recording.hasAudio) HudButton(if (isPlaying) "stop" else "▶", onClick = onPlay, accent = if (isPlaying) Hud.Amber else Hud.Cyan)
            else HudLabel("sam tekst")
            HudButton("✕", onClick = onDelete, accent = Hud.Red, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

private fun dayLabel(day: LocalDate): String = when (day) {
    LocalDate.now() -> "dzisiaj"
    LocalDate.now().minusDays(1) -> "wczoraj"
    else -> day.format(dayFormat)
}

private fun formatDuration(seconds: Long) = "%d:%02d".format(seconds / 60, seconds % 60)

/** Odtwarza jedno nagranie naraz. */
private class SimplePlayer {
    private var player: MediaPlayer? = null
    var current by mutableStateOf<File?>(null)
        private set

    fun toggle(file: File) {
        val wasPlaying = current == file
        stop()
        if (wasPlaying) return
        try {
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener { stop() }
                prepare()
                start()
            }
            current = file
        } catch (e: Exception) {
            stop()
            RecorderState.error.value = "Nie udało się odtworzyć nagrania: ${e.message}"
        }
    }

    fun stop() {
        player?.release()
        player = null
        current = null
    }
}
