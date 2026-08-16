package org.librewakeword.integration

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.net.Uri
import org.librewakeword.client.WakewordAudio
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/** Stores short PCM captures as expiring WAV assets; it is not an audio streaming surface. */
class AudioAssetStore(private val context: Context) {
    private val directory = File(context.cacheDir, WakewordAudioProvider.DIRECTORY)

    fun create(
        pcm: ByteArray,
        sampleRate: Int,
        channelCount: Int,
        startTimeElapsedRealtimeNanos: Long? = null,
    ): WakewordAudio? {
        if (pcm.isEmpty() || sampleRate <= 0 || channelCount <= 0) return null
        directory.mkdirs()
        cleanup(System.currentTimeMillis(), MAX_ASSETS - 1)
        val name = "${UUID.randomUUID()}.wav"
        val file = File(directory, name)
        FileOutputStream(file).use { output ->
            output.write(wavHeader(pcm.size, sampleRate, channelCount))
            output.write(pcm)
        }
        val expires = System.currentTimeMillis() + RETENTION_MILLIS
        if (!file.setLastModified(expires)) {
            file.delete()
            throw IOException("Could not set wake-word audio expiry")
        }
        val frameCount = pcm.size.toLong() / (channelCount * 2L)
        return WakewordAudio(
            uri = assetUri(name),
            mimeType = "audio/wav",
            sampleRateHz = sampleRate,
            channelCount = channelCount,
            encoding = AudioFormat.ENCODING_PCM_16BIT,
            frameCount = frameCount,
            expiresAtEpochMillis = expires,
            startTimeElapsedRealtimeNanos = startTimeElapsedRealtimeNanos,
        )
    }

    fun cleanup(now: Long = System.currentTimeMillis()) {
        cleanup(now, MAX_ASSETS)
    }

    private fun cleanup(now: Long, maximumAssets: Int) {
        directory.listFiles()?.forEach { file ->
            if (!file.isFile || file.lastModified() <= now) delete(file)
        }
        val remaining = directory.listFiles()?.filter(File::isFile)
            ?.sortedByDescending(File::lastModified).orEmpty()
        remaining.drop(maximumAssets).forEach(::delete)
    }

    private fun delete(file: File) {
        if (file.delete()) {
            context.revokeUriPermission(
                assetUri(file.name),
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    private fun assetUri(name: String): Uri = Uri.Builder()
        .scheme("content")
        .authority(WakewordAudioProvider.AUTHORITY)
        .appendPath("v1")
        .appendPath(name)
        .build()

    private fun wavHeader(dataBytes: Int, sampleRate: Int, channels: Int): ByteArray {
        val byteRate = sampleRate * channels * 2
        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + dataBytes)
            put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1)
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort((channels * 2).toShort())
            putShort(16)
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataBytes)
        }.array()
    }

    private companion object {
        const val RETENTION_MILLIS = 5 * 60 * 1000L
        const val MAX_ASSETS = 32
    }
}
