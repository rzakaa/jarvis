package pl.jarvis.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

/** Kolory i czcionka interfejsu w stylu HUD-a. */
object Hud {
    val Background = Color(0xFF02070C)
    val BackgroundGlow = Color(0xFF07263A)
    val Cyan = Color(0xFF29D6FF)
    val CyanBright = Color(0xFF9AF0FF)
    val CyanDim = Color(0xFF2B7A93)
    val Amber = Color(0xFFFFB300)
    val Red = Color(0xFFFF5A5A)
    val Green = Color(0xFF4DFFA6)
    val Text = Color(0xFFD9F7FF)
    val TextDim = Color(0xFF7FA3B3)
    val Panel = Color(0x1A29D6FF)
    val Mono = FontFamily.Monospace
}

private val JarvisColors = darkColorScheme(
    primary = Hud.Cyan,
    onPrimary = Hud.Background,
    secondary = Hud.Amber,
    onSecondary = Hud.Background,
    background = Hud.Background,
    onBackground = Hud.Text,
    surface = Color(0xFF071521),
    onSurface = Hud.Text,
    surfaceVariant = Color(0xFF0B1F2C),
    onSurfaceVariant = Hud.TextDim,
    error = Hud.Red,
    outline = Hud.CyanDim,
)

@Composable
fun JarvisTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = JarvisColors, content = content)
}
