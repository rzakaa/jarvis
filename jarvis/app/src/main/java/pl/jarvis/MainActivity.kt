package pl.jarvis

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import pl.jarvis.assistant.Assistant
import pl.jarvis.recorder.Prefs
import pl.jarvis.recorder.RecorderService
import pl.jarvis.recorder.RecorderState
import pl.jarvis.recorder.RecorderStatus
import pl.jarvis.ui.JarvisTheme
import pl.jarvis.ui.MainScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        RecorderState.sensitivity.value = Prefs.sensitivity(this)
        RecorderState.micSource.value = Prefs.micSource(this)
        Assistant.init(this)
        setContent {
            JarvisTheme {
                MainScreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Jeśli system zatrzymał nasłuchiwanie, a użytkownik go nie wyłączał – wznawiamy.
        if (Prefs.wantsRecording(this) &&
            RecorderState.status.value == RecorderStatus.STOPPED &&
            hasMicPermission(this)
        ) {
            RecorderService.start(this)
        }
    }
}

fun hasMicPermission(context: Context) =
    context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
