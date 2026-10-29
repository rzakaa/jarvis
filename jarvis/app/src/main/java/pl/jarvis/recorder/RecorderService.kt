package pl.jarvis.recorder

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioFormat
import android.media.AudioRecord
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.util.Log
import kotlinx.coroutines.flow.update
import pl.jarvis.MainActivity
import pl.jarvis.assistant.Assistant
import pl.jarvis.R
import java.io.File
import java.time.LocalDateTime

/**
 * Usługa, która cały czas słucha mikrofonu i zapisuje tylko fragmenty z mową.
 *
 * Cisza nie jest zapisywana. Kiedy zaczyna się i kończy nagranie, decyduje [Segmenter].
 */
class RecorderService : Service() {

    @Volatile
    private var running = false
    private var wakeLock: PowerManager.WakeLock? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Prefs.setWantsRecording(this, false)
            shutdown()
            return START_NOT_STICKY
        }
        if (running) return START_STICKY

        createChannel()
        try {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(RecorderStatus.LISTENING),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } catch (e: Exception) {
            // Android nie pozwala włączyć mikrofonu, gdy aplikacja jest w tle
            // (np. gdy system sam wznawia usługę). Prosimy użytkownika o otwarcie aplikacji.
            showResumeNotification()
            RecorderState.status.value = RecorderStatus.STOPPED
            stopSelf()
            return START_NOT_STICKY
        }

        startRecording()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        releaseWakeLock()
        super.onDestroy()
    }

    private fun startRecording() {
        running = true
        RecorderState.error.value = null
        RecorderState.status.value = RecorderStatus.LISTENING
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Jarvis:rejestrator")
            .apply {
                setReferenceCounted(false)
                acquire()
            }
        Thread({
            try {
                recordLoop()
            } catch (e: Exception) {
                RecorderState.error.value = "Błąd nagrywania: ${e.message}"
                // Pętla przerwała się sama (a nie przez „Wyłącz”) – zamykamy usługę.
                if (running) mainHandler.post { shutdown() }
            }
        }, "jarvis-recorder").start()
    }

    private fun shutdown() {
        running = false
        releaseWakeLock()
        RecorderState.status.value = RecorderStatus.STOPPED
        RecorderState.levelDb.value = SILENCE_DB
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    @SuppressLint("MissingPermission") // uprawnienie sprawdzamy poniżej
    private fun recordLoop() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw IllegalStateException("brak uprawnienia do mikrofonu")
        }
        // Nagrywanie to najważniejsza praca aplikacji – nie może gubić dźwięku.
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val sampleRate = Recordings.SAMPLE_RATE
        val (record, captureRate) = openMicrophone()
        val factor = captureRate / sampleRate
        val decimator = Decimator(factor)
        val captureFrame = ShortArray(FRAME_SAMPLES * factor)

        val token = Any()
        currentLoop = token
        val vad = VoiceActivityDetector()
        val wakeWord = createWakeWord()
        wakeWord?.let { RecorderState.wakeThreshold.value = it.threshold }
        var wakePeak = 0f
        var frameCounter = 0
        val segmenter = Segmenter(object : Segmenter.Sink {
            private var writer: WavWriter? = null
            private var file: File? = null

            override fun startSegment() {
                val newFile = Recordings.newFile(this@RecorderService, LocalDateTime.now())
                RecorderState.activeFile.value = newFile
                file = newFile
                writer = WavWriter(newFile, sampleRate)
                setStatus(RecorderStatus.RECORDING)
            }

            override fun write(frame: ShortArray) {
                writer?.write(frame, frame.size)
            }

            override fun finishSegment(keep: Boolean) {
                writer?.close()
                writer = null
                RecorderState.activeFile.value = null
                // Za mało mowy (stuknięcie, trzask) – usuwamy, ale liczymy, żeby było to widać.
                if (!keep) {
                    file?.delete()
                    RecorderState.discarded.update { it + 1 }
                }
                file = null
                if (running) setStatus(RecorderStatus.LISTENING)
                RecorderState.recordingsChanged()
            }
        })

        record.startRecording()
        try {
            while (running) {
                if (RecorderState.micPaused.value) {
                    // Rozmowa z Jarvisem: kończymy bieżące nagranie i oddajemy mikrofon.
                    segmenter.close()
                    record.stop()
                    RecorderState.levelDb.value = SILENCE_DB
                    setStatus(RecorderStatus.PAUSED)
                    while (running && RecorderState.micPaused.value) Thread.sleep(50)
                    if (!running) break
                    record.startRecording()
                    setStatus(RecorderStatus.LISTENING)
                    continue
                }
                var captured = 0
                while (captured < captureFrame.size && running) {
                    val n = record.read(captureFrame, captured, captureFrame.size - captured)
                    if (n < 0) throw IllegalStateException("mikrofon zwrócił błąd $n")
                    captured += n
                }
                val frame = decimator.process(captureFrame, captured)
                if (frame.isEmpty()) continue

                val db = levelDb(frame, frame.size)
                if (++frameCounter % 3 == 0) RecorderState.levelDb.value = db
                // Diagnostyka (adb logcat -s JarvisMic): poziom i wynik detektora co ok. 1 s.
                if (frameCounter % 33 == 0) Log.d("JarvisMic", "db=%.1f wake=%.3f".format(db, wakePeak))
                if (wakeWord != null) {
                    if (wakeWord.process(frame)) mainHandler.post { Assistant.onWakeWord() }
                    // Podgląd: szczyt wyniku, powoli opadający (żeby dało się go odczytać na ekranie).
                    wakePeak = maxOf(wakeWord.lastScore, wakePeak * 0.985f)
                    if (frameCounter % 3 == 0) RecorderState.wakeScore.value = wakePeak
                }
                segmenter.process(frame, vad.isSpeech(db, RecorderState.sensitivity.value))
            }
        } finally {
            wakeWord?.close()
            RecorderState.wakeWordStatus.value = null
            segmenter.close()
            record.stop()
            record.release()
            // Jeśli w międzyczasie ruszyło nowe nasłuchiwanie, nie nadpisujemy jego stanu.
            if (currentLoop === token) RecorderState.status.value = RecorderStatus.STOPPED
        }
    }

    /** Słowo „Hey Jarvis” – jeśli włączone w ustawieniach. */
    private fun createWakeWord(): WakeWord? {
        if (!Prefs.wakeWordEnabled(this)) return null
        return try {
            WakeWord(this, Prefs.wakeSensitivity(this).threshold).also { RecorderState.wakeWordStatus.value = "aktywne" }
        } catch (e: Exception) {
            RecorderState.wakeWordStatus.value = "błąd: ${e.message}"
            null
        }
    }

    /**
     * Otwiera mikrofon w jego natywnej jakości (48 kHz), a dopiero gdy to się nie uda – w 16 kHz.
     * Część urządzeń (m.in. emulator) źle przelicza dźwięk na 16 kHz i nagrywa wtedy trzaski.
     */
    @SuppressLint("MissingPermission") // sprawdzane w recordLoop()
    private fun openMicrophone(): Pair<AudioRecord, Int> {
        val source = RecorderState.micSource.value
        for (rate in CAPTURE_RATES) {
            val minBuffer = AudioRecord.getMinBufferSize(
                rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuffer <= 0) continue
            val record = AudioRecord(
                source.androidSource,
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, rate / 2 * 2), // pół sekundy zapasu
            )
            if (record.state == AudioRecord.STATE_INITIALIZED) {
                RecorderState.micInfo.value = "$rate Hz · ${source.label}"
                return record to rate
            }
            record.release()
        }
        throw IllegalStateException("nie udało się otworzyć mikrofonu")
    }

    private fun setStatus(status: RecorderStatus) {
        if (RecorderState.status.value == status) return
        RecorderState.status.value = status
        if (running) {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(status))
        }
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Nasłuchiwanie", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun buildNotification(status: RecorderStatus): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, RecorderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val text = when (status) {
            RecorderStatus.RECORDING -> "Nagrywam rozmowę"
            RecorderStatus.PAUSED -> "Wstrzymane – rozmowa z Jarvisem"
            else -> "Nasłuchuję"
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Jarvis")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_mic), "Zatrzymaj", stop,
                ).build(),
            )
            .build()
    }

    private fun showResumeNotification() {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Jarvis przestał nasłuchiwać")
            .setContentText("Dotknij, aby wznowić.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(RESUME_NOTIFICATION_ID, notification)
    }

    companion object {
        private const val ACTION_START = "pl.jarvis.action.START"
        private const val ACTION_STOP = "pl.jarvis.action.STOP"
        private const val CHANNEL_ID = "jarvis_recorder"
        private const val NOTIFICATION_ID = 1
        private const val RESUME_NOTIFICATION_ID = 2

        // Jedna ramka = 30 ms dźwięku przy 16 kHz.
        private const val FRAME_SAMPLES = 480
        private val CAPTURE_RATES = intArrayOf(48_000, Recordings.SAMPLE_RATE)

        @Volatile
        private var currentLoop: Any? = null

        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, RecorderService::class.java).setAction(ACTION_START),
            )
        }

        /** Ponowne uruchomienie, np. po zmianie ustawień słowa-klucza. */
        fun restart(context: Context) {
            stop(context)
            Handler(Looper.getMainLooper()).postDelayed({ start(context) }, 800)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, RecorderService::class.java).setAction(ACTION_STOP))
        }
    }
}
