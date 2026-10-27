package pl.jarvis.recorder

import android.content.Context
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

data class Recording(val file: File, val start: LocalDateTime, val durationSeconds: Long)

/**
 * Nagrania leżą w prywatnym katalogu aplikacji (inne aplikacje nie mają do niego dostępu):
 * files/recordings/2026-10-27/14-03-21.wav
 */
object Recordings {
    const val SAMPLE_RATE = 16_000

    private val timeFormat = DateTimeFormatter.ofPattern("HH-mm-ss")

    private fun root(context: Context) = File(context.filesDir, "recordings")

    fun newFile(context: Context, start: LocalDateTime): File {
        val dayDir = File(root(context), start.toLocalDate().toString())
        dayDir.mkdirs()
        return File(dayDir, start.format(timeFormat) + ".wav")
    }

    /** Wszystkie zakończone nagrania, od najnowszego. */
    fun list(context: Context): List<Recording> {
        val active = RecorderState.activeFile.value
        val dayDirs = root(context).listFiles().orEmpty().filter { it.isDirectory }
        return dayDirs.flatMap { dayDir ->
            val day = runCatching { LocalDate.parse(dayDir.name) }.getOrNull()
                ?: return@flatMap emptyList()
            dayDir.listFiles().orEmpty()
                .filter { it.extension == "wav" && it != active }
                .mapNotNull { file ->
                    val time = runCatching { LocalTime.parse(file.nameWithoutExtension, timeFormat) }
                        .getOrNull() ?: return@mapNotNull null
                    runCatching { WavWriter.repairIfNeeded(file, SAMPLE_RATE) }
                    val dataBytes = (file.length() - WavWriter.HEADER_SIZE).coerceAtLeast(0)
                    Recording(file, day.atTime(time), dataBytes / (SAMPLE_RATE * 2))
                }
        }.sortedByDescending { it.start }
    }
}
