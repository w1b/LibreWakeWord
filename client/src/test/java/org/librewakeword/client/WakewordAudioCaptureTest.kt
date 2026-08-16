package org.librewakeword.client

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class WakewordAudioCaptureTest {
    @Test
    fun wavParserReturnsPcmPayload() {
        val pcm = byteArrayOf(1, 2, 3, 4, 5, 6)

        assertArrayEquals(pcm, WakewordAudioCapture.parsePcm16Wav(wav(pcm)))
    }

    @Test(expected = IOException::class)
    fun wavParserRejectsUnsupportedSampleRate() {
        WakewordAudioCapture.parsePcm16Wav(wav(byteArrayOf(1, 2), sampleRate = 8_000))
    }

    private fun wav(pcm: ByteArray, sampleRate: Int = 16_000): ByteArray =
        ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + pcm.size)
            put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1)
            putShort(1)
            putInt(sampleRate)
            putInt(sampleRate * 2)
            putShort(2)
            putShort(16)
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(pcm.size)
            put(pcm)
        }.array()
}
