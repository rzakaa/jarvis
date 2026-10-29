package pl.jarvis.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import pl.jarvis.recorder.Prefs
import pl.jarvis.recorder.RecorderService

private val TABS = listOf("◉" to "Jarvis", "≋" to "Nagrania", "zł" to "Finanse", "⚙" to "Opcje")

@Composable
fun MainScreen() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val context = LocalContext.current

    // Pierwsze uruchomienie: od razu prosimy o mikrofon i włączamy nasłuchiwanie.
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        if (results[Manifest.permission.RECORD_AUDIO] == true) {
            Prefs.setWantsRecording(context, true)
            RecorderService.start(context)
        }
    }
    LaunchedEffect(Unit) {
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) permissions.launch(needed.toTypedArray())
    }
    HudBackground {
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                NavigationBar(containerColor = Hud.Background.copy(alpha = 0.92f)) {
                    TABS.forEachIndexed { index, (icon, label) ->
                        NavigationBarItem(
                            selected = tab == index,
                            onClick = { tab = index },
                            icon = { Text(icon, fontSize = 18.sp, fontFamily = Hud.Mono) },
                            label = { Text(label.uppercase(), fontFamily = Hud.Mono, fontSize = 11.sp, letterSpacing = 2.sp) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Hud.CyanBright,
                                selectedTextColor = Hud.CyanBright,
                                indicatorColor = Hud.Cyan.copy(alpha = 0.15f),
                                unselectedIconColor = Hud.TextDim,
                                unselectedTextColor = Hud.TextDim,
                            ),
                        )
                    }
                }
            },
        ) { padding ->
            Box(modifier = Modifier.padding(padding)) {
                when (tab) {
                    0 -> JarvisScreen()
                    1 -> RecordingsScreen()
                    2 -> FinanceScreen()
                    else -> SettingsScreen()
                }
            }
        }
    }
}
