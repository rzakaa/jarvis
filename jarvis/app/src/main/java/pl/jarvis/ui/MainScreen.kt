package pl.jarvis.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp

private val TABS = listOf("◉" to "Jarvis", "≋" to "Nagrania", "zł" to "Finanse")

@Composable
fun MainScreen() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
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
                    else -> FinanceScreen()
                }
            }
        }
    }
}
