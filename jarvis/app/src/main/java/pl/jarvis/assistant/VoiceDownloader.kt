package pl.jarvis.assistant

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** Pobiera i rozpakowuje paczkę .tar.bz2 z głosem (bez zapisywania całego archiwum na dysk). */
object VoiceDownloader {

    /**
     * Pobiera [url] i rozpakowuje do [targetDir]. Najpierw do katalogu tymczasowego,
     * żeby przerwane pobieranie nie zostawiło połowy głosu. [onProgress] dostaje 0..1.
     */
    fun downloadAndExtract(url: String, targetDir: File, onProgress: (Float) -> Unit) {
        val connection = URL(url).openConnection()
        if (connection is HttpURLConnection) {
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
            if (connection.responseCode !in 200..299) throw IOException("HTTP ${connection.responseCode}")
        }
        val total = connection.contentLengthLong
        val tmp = File(targetDir.parentFile, targetDir.name + ".tmp").apply { deleteRecursively() }
        connection.getInputStream().use { raw ->
            val counting = CountingStream(raw.buffered()) { read -> if (total > 0) onProgress(read.toFloat() / total) }
            extractTarBz2(counting, tmp)
        }
        targetDir.deleteRecursively()
        // Paczka ma w środku jeden katalog (np. vits-piper-pl_PL-bass-high/) – jego zawartość przenosimy do celu.
        val inner = tmp.listFiles()?.singleOrNull { it.isDirectory } ?: tmp
        if (!inner.renameTo(targetDir)) throw IOException("Nie udało się przenieść głosu do ${targetDir.path}")
        tmp.deleteRecursively()
        onProgress(1f)
    }

    fun extractTarBz2(input: InputStream, into: File) {
        val root = into.canonicalFile
        TarArchiveInputStream(BZip2CompressorInputStream(input)).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                val out = File(root, entry.name).canonicalFile
                // Ochrona przed ścieżkami typu ../ w archiwum.
                if (!out.path.startsWith(root.path)) continue
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { tar.copyTo(it) }
                }
            }
        }
    }

    private class CountingStream(input: InputStream, private val onRead: (Long) -> Unit) : FilterInputStream(input) {
        private var count = 0L
        private var lastReported = 0L

        override fun read(): Int = super.read().also { if (it >= 0) advance(1) }

        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) advance(it.toLong()) }

        private fun advance(n: Long) {
            count += n
            if (count - lastReported > 512 * 1024) {
                lastReported = count
                onRead(count)
            }
        }
    }
}
