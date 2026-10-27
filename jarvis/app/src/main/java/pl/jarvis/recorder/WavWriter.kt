package pl.jarvis.recorder

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Zapisuje dźwięk 16-bit mono do pliku WAV (format, który rozumie Whisper). */
class WavWriter(file: File, private val sampleRate: Int) : Closeable {
    private val out = RandomAccessFile(file, "rw")
    private var dataBytes = 0L

    init {
        out.setLength(0)
        out.write(header(sampleRate, 0))
    }

    fun write(samples: ShortArray, count: Int) {
        val bytes = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) bytes.putShort(samples[i])
        out.write(bytes.array())
        dataBytes += count * 2
    }

    override fun close() {
        out.seek(0)
        out.write(header(sampleRate, dataBytes))
        out.close()
    }

    companion object {
        const val HEADER_SIZE = 44

        fun header(sampleRate: Int, dataBytes: Long): ByteArray {
            val b = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            b.put("RIFF".toByteArray(Charsets.US_ASCII))
            b.putInt((36 + dataBytes).toInt())
            b.put("WAVE".toByteArray(Charsets.US_ASCII))
            b.put("fmt ".toByteArray(Charsets.US_ASCII))
            b.putInt(16)
            b.putShort(1) // PCM
            b.putShort(1) // mono
            b.putInt(sampleRate)
            b.putInt(sampleRate * 2) // bajty na sekundę
            b.putShort(2) // bajty na próbkę
            b.putShort(16) // bity na próbkę
            b.put("data".toByteArray(Charsets.US_ASCII))
            b.putInt(dataBytes.toInt())
            return b.array()
        }

        /**
         * Naprawia nagłówek pliku, którego nie zdążyliśmy zamknąć
         * (np. system zabił aplikację w trakcie nagrania), żeby dało się go odtworzyć.
         */
        fun repairIfNeeded(file: File, sampleRate: Int) {
            RandomAccessFile(file, "rw").use { raf ->
                val actual = raf.length() - HEADER_SIZE
                if (actual <= 0) return
                raf.seek(40)
                val stored = Integer.reverseBytes(raf.readInt()).toLong() and 0xffffffffL
                if (stored != actual) {
                    raf.seek(0)
                    raf.write(header(sampleRate, actual))
                }
            }
        }
    }
}
