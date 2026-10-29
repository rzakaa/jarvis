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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.font.FontWeight
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
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val polish = Locale.forLanguageTag("pl-PL")
private val dayFormat = DateTimeFormatter.ofPattern("EEEE, d MMMM", polish)
private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss")

@Composable
fun MainScreen() {
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

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        if (results[Manifest.permission.RECORD_AUDIO] == true) {
            startListening(context)
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
            if (missing.isEmpty()) startListening(context)
            else permissionLauncher.launch(missing.toTypedArray())
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Header() }
            item { StatusCard(status, onToggle) }
            item { SensitivityRow() }
            item { MicSourceRow(status) }
            error?.let { message ->
                item { Text(message, color = MaterialTheme.colorScheme.error) }
            }
            item { BatteryHint(context) }
            item { TodaySummary(recordings) }

            recordings.groupBy { it.start.toLocalDate() }.forEach { (day, dayRecordings) ->
                item(key = "day-$day") {
                    Text(
                        dayLabel(day),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                items(dayRecordings, key = { it.file.absolutePath }) { recording ->
                    RecordingRow(
                        recording = recording,
                        isPlaying = player.current == recording.file,
                        onPlay = { player.toggle(recording.file) },
                        onDelete = { toDelete = recording },
                    )
                }
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
                    recording.file.delete()
                    RecorderState.recordingsChanged()
                    toDelete = null
                }) { Text("Usuń") }
            },
            dismissButton = {
                TextButton(onClick = { toDelete = null }) { Text("Anuluj") }
            },
        )
    }
}

private fun requiredPermissions(): List<String> = buildList {
    add(Manifest.permission.RECORD_AUDIO)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}

private fun startListening(context: Context) {
    Prefs.setWantsRecording(context, true)
    RecorderService.start(context)
}

@Composable
private fun Header() {
    Column {
        Text(
            "JARVIS",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            letterSpacing = 6.sp,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            "Etap 1: rejestrator rozmów · wersja 0.4",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatusCard(status: RecorderStatus, onToggle: () -> Unit) {
    val level by RecorderState.levelDb.collectAsState()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                when (status) {
                    RecorderStatus.STOPPED -> "Wyłączony"
                    RecorderStatus.LISTENING -> "Nasłuchuję…"
                    RecorderStatus.RECORDING -> "Nagrywam rozmowę"
                },
                style = MaterialTheme.typography.titleLarge,
                color = if (status == RecorderStatus.RECORDING) {
                    MaterialTheme.colorScheme.secondary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Text(
                when (status) {
                    RecorderStatus.STOPPED -> "Włącz, żeby Jarvis zapisywał rozmowy z otoczenia."
                    RecorderStatus.LISTENING -> "Czekam na mowę. Cisza nie jest zapisywana."
                    RecorderStatus.RECORDING -> "Ktoś mówi – zapisuję. Przerwy do minuty zostają w tym samym nagraniu."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (status != RecorderStatus.STOPPED) {
                LinearProgressIndicator(
                    progress = { ((level + 60f) / 60f).coerceIn(0f, 1f) }, // -60 dB = pusto, 0 dB = pełno
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Button(onClick = onToggle, modifier = Modifier.fillMaxWidth()) {
                Text(if (status == RecorderStatus.STOPPED) "Włącz nasłuchiwanie" else "Wyłącz")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SensitivityRow() {
    val context = LocalContext.current
    val sensitivity by RecorderState.sensitivity.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Czułość na mowę", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Sensitivity.entries.forEach { option ->
                FilterChip(
                    selected = option == sensitivity,
                    onClick = {
                        RecorderState.sensitivity.value = option
                        Prefs.setSensitivity(context, option)
                    },
                    label = { Text(option.label) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MicSourceRow(status: RecorderStatus) {
    val context = LocalContext.current
    val source by RecorderState.micSource.collectAsState()
    val info by RecorderState.micInfo.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Źródło mikrofonu", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MicSource.entries.forEach { option ->
                FilterChip(
                    selected = option == source,
                    onClick = {
                        RecorderState.micSource.value = option
                        Prefs.setMicSource(context, option)
                    },
                    label = { Text(option.label) },
                )
            }
        }
        Text(
            if (status == RecorderStatus.STOPPED) {
                "Zmiana działa po włączeniu nasłuchiwania."
            } else {
                "Teraz: ${info ?: "?"}. Zmiana działa po Wyłącz → Włącz."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BatteryHint(context: Context) {
    val powerManager = context.getSystemService(PowerManager::class.java)
    if (powerManager.isIgnoringBatteryOptimizations(context.packageName)) return
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Żeby system nie usypiał Jarvisa, wyłącz dla niego oszczędzanie baterii.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = {
                context.startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:${context.packageName}"),
                    ),
                )
            }) { Text("Ustaw") }
        }
    }
}

@Composable
private fun TodaySummary(recordings: List<Recording>) {
    val today = recordings.filter { it.start.toLocalDate() == LocalDate.now() }
    val minutes = today.sumOf { it.durationSeconds } / 60
    val discarded by RecorderState.discarded.collectAsState()
    Text(
        "Dziś: ${today.size} nagrań · około $minutes min mowy" +
            if (discarded > 0) " · odrzucone jako za krótkie: $discarded" else "",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun RecordingRow(
    recording: Recording,
    isPlaying: Boolean,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(recording.start.format(timeFormat), style = MaterialTheme.typography.titleMedium)
                Text(
                    formatDuration(recording.durationSeconds),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onPlay) { Text(if (isPlaying) "Stop" else "Odtwórz") }
            TextButton(onClick = onDelete) { Text("Usuń", color = MaterialTheme.colorScheme.error) }
        }
    }
}

private fun dayLabel(day: LocalDate): String = when (day) {
    LocalDate.now() -> "Dzisiaj"
    LocalDate.now().minusDays(1) -> "Wczoraj"
    else -> day.format(dayFormat).replaceFirstChar { it.titlecase(polish) }
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
