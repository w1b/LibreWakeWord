package org.librewakeword.client

import android.media.AudioFormat
import java.io.ByteArrayInputStream
import java.io.InputStream

/** An immutable, in-memory PCM prefix for advanced custom-recorder integrations. */
class WakewordAudioPrefix internal constructor(
    pcm: ByteArray,
) {
    private val bytes = pcm.copyOf()

    val sampleRateHz: Int = WakewordAudioCapture.SAMPLE_RATE_HZ
    val channelCount: Int = 1
    val encoding: Int = AudioFormat.ENCODING_PCM_16BIT
    val frameCount: Long = bytes.size / 2L

    /** Opens a new stream positioned at the first PCM16 sample. */
    fun openStream(): InputStream = ByteArrayInputStream(bytes)

    /** Returns an independent copy of the PCM16 payload. */
    fun toByteArray(): ByteArray = bytes.copyOf()
}
