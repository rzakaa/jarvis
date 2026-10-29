package pl.jarvis.ui

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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.jarvis.assistant.Assistant
import pl.jarvis.brain.Brain
import pl.jarvis.brain.Income
import pl.jarvis.brain.Polish
import java.time.YearMonth
import java.time.format.DateTimeFormatter

private val shortDate = DateTimeFormatter.ofPattern("dd.MM")

@Composable
fun FinanceScreen() {
    val version by Assistant.ledgerVersion.collectAsState()
    var month by remember { mutableStateOf(YearMonth.now()) }
    val ledger = Assistant.ledger
    val incomes = remember(version, month) { ledger.incomesIn(month).sortedByDescending { it.date } }
    val payments = remember(version, month) { ledger.paymentsFor(month) }
    val notes = remember(version) { ledger.openNotes() }
    var toDelete by remember { mutableStateOf<Income?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                HudButton("‹", onClick = { month = month.minusMonths(1) })
                Text(
                    "${Polish.MONTHS_NOMINATIVE[month.monthValue - 1].uppercase()} ${month.year}",
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    color = Hud.Cyan,
                    fontFamily = Hud.Mono,
                    fontSize = 18.sp,
                    letterSpacing = 3.sp,
                )
                HudButton("›", onClick = { month = month.plusMonths(1) })
            }
        }

        item {
            HudPanel(modifier = Modifier.fillMaxWidth(), title = "zarobki") {
                Text(
                    Polish.formatMoney(incomes.sumOf { it.grosze }),
                    color = Hud.CyanBright,
                    fontFamily = Hud.Mono,
                    fontWeight = FontWeight.Bold,
                    fontSize = 34.sp,
                )
                HudLabel("wpłat: ${incomes.size}")
            }
        }

        item {
            HudPanel(modifier = Modifier.fillMaxWidth(), title = "płatności") {
                Brain.TRACKED.forEach { kind ->
                    val paid = payments.lastOrNull { it.kind == kind }
                    Row(modifier = Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(kind.uppercase(), color = Hud.Text, fontFamily = Hud.Mono, modifier = Modifier.weight(1f))
                        if (paid != null) {
                            Text("✓ ${paid.date.format(shortDate)}", color = Hud.Green, fontFamily = Hud.Mono)
                        } else {
                            Text("✗ NIEZAPŁACONE", color = Hud.Red, fontFamily = Hud.Mono, fontSize = 13.sp)
                        }
                    }
                }
                payments.filter { it.kind !in Brain.TRACKED }.forEach { p ->
                    Row(modifier = Modifier.padding(vertical = 4.dp)) {
                        Text(p.kind.uppercase(), color = Hud.Text, fontFamily = Hud.Mono, modifier = Modifier.weight(1f))
                        Text(
                            (p.grosze?.let { Polish.formatMoney(it) + " · " } ?: "") + "✓ ${p.date.format(shortDate)}",
                            color = Hud.Green,
                            fontFamily = Hud.Mono,
                        )
                    }
                }
            }
        }

        if (incomes.isNotEmpty()) item { HudLabel("wpłaty", color = Hud.Cyan) }
        items(incomes, key = { it.id }) { income ->
            HudPanel(modifier = Modifier.fillMaxWidth(), accent = Hud.CyanDim) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(Polish.formatMoney(income.grosze), color = Hud.Text, fontFamily = Hud.Mono, fontSize = 17.sp)
                        HudLabel("${income.date.format(shortDate)} · ${income.place.ifEmpty { "bez miejsca" }}")
                    }
                    HudButton("✕", onClick = { toDelete = income }, accent = Hud.Red)
                }
            }
        }

        item {
            HudPanel(modifier = Modifier.fillMaxWidth(), title = "do zrobienia") {
                if (notes.isEmpty()) {
                    Text("Nic. Powiedz: „zanotuj, że…”", color = Hud.TextDim, fontSize = 14.sp)
                }
                notes.forEachIndexed { i, note ->
                    Row(modifier = Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}. ${note.text}", color = Hud.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        HudButton("✓", onClick = { Assistant.markNoteDone(note.id) }, accent = Hud.Green)
                    }
                }
            }
        }

        item {
            Text(
                "Dodawaj głosem na ekranie JARVIS, np. „zarobiłem 1200 zł na Kawodrzańskiej”, „zapłaciłem ZUS”.",
                color = Hud.TextDim,
                fontSize = 13.sp,
            )
        }
    }

    toDelete?.let { income ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Usunąć wpłatę?") },
            text = { Text("${Polish.formatMoney(income.grosze)} z ${income.date.format(shortDate)} zostanie usunięte.") },
            confirmButton = {
                TextButton(onClick = {
                    Assistant.removeEntry(income.id)
                    toDelete = null
                }) { Text("Usuń", color = Hud.Red) }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Anuluj") } },
        )
    }
}
