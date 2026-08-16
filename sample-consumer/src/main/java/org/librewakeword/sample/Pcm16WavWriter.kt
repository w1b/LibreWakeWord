package org.librewakeword.sample

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/** Writes a standard little-endian PCM WAV that can be played by ordinary Android apps. */
class Pcm16WavWriter(
    private val destination: File,
    private val sampleRateHz: Int = 16_000,
    private val channelCount: Int = 1,
) : Closeable {
    private val temporary = File(destination.parentFile, "${destination.name}.part")
    private val output = RandomAccessFile(temporary, "rw")
    private var dataBytes = 0L
    private var closed = false

    init {
        require(sampleRateHz > 0)
        require(channelCount > 0)
        output.setLength(0)
        output.write(ByteArray(HEADER_BYTES))
    }

    fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
        check(!closed) { "WAV writer is closed" }
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
        output.write(bytes, offset, length)
        dataBytes += length
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            writeHeader()
            output.fd.sync()
            output.close()
            check(temporary.renameTo(destination)) { "Could not publish ${destination.name}" }
        } catch (error: Throwable) {
            runCatching { output.close() }
            temporary.delete()
            throw error
        }
    }

    fun abort() {
        if (closed) return
        closed = true
        runCatching { output.close() }
        temporary.delete()
    }

    private fun writeHeader() {
        val bitsPerSample = 16
        val blockAlign = channelCount * bitsPerSample / 8
        val byteRate = sampleRateHz * blockAlign
        output.seek(0)
        output.writeAscii("RIFF")
        output.writeLittleEndianInt((36L + dataBytes).coerceAtMost(UINT_MAX).toInt())
        output.writeAscii("WAVE")
        output.writeAscii("fmt ")
        output.writeLittleEndianInt(16)
        output.writeLittleEndianShort(1)
        output.writeLittleEndianShort(channelCount)
        output.writeLittleEndianInt(sampleRateHz)
        output.writeLittleEndianInt(byteRate)
        output.writeLittleEndianShort(blockAlign)
        output.writeLittleEndianShort(bitsPerSample)
        output.writeAscii("data")
        output.writeLittleEndianInt(dataBytes.coerceAtMost(UINT_MAX).toInt())
    }

    private fun RandomAccessFile.writeAscii(value: String) {
        write(value.toByteArray(Charsets.US_ASCII))
    }

    private fun RandomAccessFile.writeLittleEndianShort(value: Int) {
        write(value and 0xff)
        write(value ushr 8 and 0xff)
    }

    private fun RandomAccessFile.writeLittleEndianInt(value: Int) {
        write(value and 0xff)
        write(value ushr 8 and 0xff)
        write(value ushr 16 and 0xff)
        write(value ushr 24 and 0xff)
    }

    private companion object {
        const val HEADER_BYTES = 44
        const val UINT_MAX = 0xffff_ffffL
    }
}
