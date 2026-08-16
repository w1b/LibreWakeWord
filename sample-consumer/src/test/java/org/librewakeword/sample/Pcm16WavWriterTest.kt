package org.librewakeword.sample

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Pcm16WavWriterTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun writesPlayableMonoPcmHeaderAndPayload() {
        val destination = temporaryFolder.root.resolve("capture.wav")
        val pcm = byteArrayOf(1, 2, 3, 4, 5, 6)

        Pcm16WavWriter(destination).use { it.write(pcm) }

        val wav = destination.readBytes()
        assertEquals("RIFF", wav.ascii(0, 4))
        assertEquals(36 + pcm.size, wav.littleEndianInt(4))
        assertEquals("WAVE", wav.ascii(8, 4))
        assertEquals("fmt ", wav.ascii(12, 4))
        assertEquals(1, wav.littleEndianShort(20))
        assertEquals(1, wav.littleEndianShort(22))
        assertEquals(16_000, wav.littleEndianInt(24))
        assertEquals(16, wav.littleEndianShort(34))
        assertEquals("data", wav.ascii(36, 4))
        assertEquals(pcm.size, wav.littleEndianInt(40))
        assertArrayEquals(pcm, wav.copyOfRange(44, wav.size))
    }

    @Test
    fun abortLeavesNoPublishedOrPartialFile() {
        val destination = temporaryFolder.root.resolve("capture.wav")
        val writer = Pcm16WavWriter(destination)

        writer.write(byteArrayOf(1, 2))
        writer.abort()

        assertFalse(destination.exists())
        assertFalse(temporaryFolder.root.resolve("capture.wav.part").exists())
    }

    private fun ByteArray.ascii(offset: Int, length: Int): String =
        String(this, offset, length, Charsets.US_ASCII)

    private fun ByteArray.littleEndianShort(offset: Int): Int =
        (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)

    private fun ByteArray.littleEndianInt(offset: Int): Int =
        (this[offset].toInt() and 0xff) or
            ((this[offset + 1].toInt() and 0xff) shl 8) or
            ((this[offset + 2].toInt() and 0xff) shl 16) or
            ((this[offset + 3].toInt() and 0xff) shl 24)
}
