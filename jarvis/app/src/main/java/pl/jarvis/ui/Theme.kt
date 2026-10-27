package pl.jarvis.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val JarvisColors = darkColorScheme(
    primary = Color(0xFF4FC3F7),
    onPrimary = Color(0xFF00141F),
    secondary = Color(0xFFFFB300),
    onSecondary = Color(0xFF1F1400),
    background = Color(0xFF0B1218),
    onBackground = Color(0xFFE1E8ED),
    surface = Color(0xFF111B23),
    onSurface = Color(0xFFE1E8ED),
    surfaceVariant = Color(0xFF16232D),
    onSurfaceVariant = Color(0xFF9FB3C1),
    error = Color(0xFFFF6E6E),
)

@Composable
fun JarvisTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = JarvisColors, content = content)
}
