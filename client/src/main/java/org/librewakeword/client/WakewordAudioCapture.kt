package org.librewakeword.client

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Consumer-owned continuous PCM capture beginning with LibreWakeWord's bounded pre-roll.
 *
 * [read] returns 16 kHz mono PCM16 from the verifier lookback first, then continues with frames
 * captured by this process's [AudioRecord]. LibreWakeWord never proxies ongoing audio.
 */
class WakewordAudioCapture private constructor(
    private val record: AudioRecord,
    private val audioManager: AudioManager,
    private val lifecycle: HandoffLifecycle,
    private val includeWakeword: Boolean,
) : InputStream() {
    val format: AudioFormat = AUDIO_FORMAT
    val sampleRateHz: Int = SAMPLE_RATE_HZ
    val channelCount: Int = 1
    val encoding: Int = AudioFormat.ENCODING_PCM_16BIT

    private val stateLock = Object()
    private data class LiveChunk(val data: ByteArray)

    internal data class Prefix(val pcm: ByteArray)

    private val liveChunks = ArrayDeque<LiveChunk>()
    private val stopped = AtomicBoolean()
    private var liveBytes = 0
    private var liveOffset = 0
    private var prefix: Prefix? = null
    private var prefixOffset = 0
    @Volatile private var clientSilenced: Boolean? = null
    private var pumpFinished = false
    private var failure: IOException? = null
    private val prefixDeadline = SystemClock.elapsedRealtime() + PREFIX_WAIT_MILLIS
    private val pump = Thread(::pumpAudio, "wakeword-live-audio")
    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
            updateClientSilenced(configs)
        }
    }

    init {
        lifecycle.attachCapture(this)
        lifecycle.invokeOnClose(::stopInternal)
    }

    @SuppressLint("MissingPermission")
    private fun start() {
        try {
            audioManager.registerAudioRecordingCallback(recordingCallback, null)
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "AudioRecord did not enter RECORDSTATE_RECORDING"
            }
            refreshClientSilenced()
            // Drain the recorder before asking LibreWakeWord to release the microphone. This keeps
            // policy-silenced frames from accumulating in AudioRecord's client-side queue.
            pump.start()
            check(lifecycle.claim()) { "Microphone handoff expired before capture started" }
        } catch (error: Throwable) {
            stopInternal()
            throw error
        }
    }

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
    }

    @Throws(IOException::class)
    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        require(offset >= 0 && length >= 0 && offset <= target.size - length) {
            "Invalid offset=$offset length=$length for array size=${target.size}"
        }
        if (length == 0) return 0

        synchronized(stateLock) {
            if (includeWakeword) awaitPrefix()
            prefix?.takeIf { includeWakeword }?.let { value ->
                if (prefixOffset < value.pcm.size) {
                    val count = minOf(length, value.pcm.size - prefixOffset)
                    value.pcm.copyInto(target, offset, prefixOffset, prefixOffset + count)
                    prefixOffset += count
                    return count
                }
            }

            while (true) {
                failure?.let { throw it }
                val chunk = liveChunks.peekFirst()
                if (chunk != null) {
                    val count = minOf(length, chunk.data.size - liveOffset)
                    chunk.data.copyInto(target, offset, liveOffset, liveOffset + count)
                    liveOffset += count
                    liveBytes -= count
                    if (liveOffset == chunk.data.size) {
                        liveChunks.removeFirst()
                        liveOffset = 0
                    }
                    stateLock.notifyAll()
                    return count
                }
                if (pumpFinished || stopped.get()) return -1
                stateLock.wait()
            }
        }
    }

    override fun available(): Int = synchronized(stateLock) {
        val prefixBytes = if (includeWakeword) {
            prefix?.let { maxOf(0, it.pcm.size - prefixOffset) } ?: 0
        } else {
            0
        }
        prefixBytes + liveBytes
    }

    internal fun offerPrefix(value: Prefix) {
        synchronized(stateLock) {
            if (prefix == null) {
                prefix = value
                stateLock.notifyAll()
            }
        }
        Log.i(TAG, "Prefix ready bytes=${value.pcm.size}")
    }

    override fun close() {
        lifecycle.close()
        stopInternal()
    }

    private fun awaitPrefix() {
        while (prefix == null && !stopped.get()) {
            val remaining = prefixDeadline - SystemClock.elapsedRealtime()
            if (remaining <= 0L) {
                prefix = Prefix(EMPTY)
                break
            }
            stateLock.wait(remaining)
        }
        if (prefix == null) {
            prefix = Prefix(EMPTY)
        }
    }

    private fun pumpAudio() {
        val buffer = ByteArray(STEADY_READ_BYTES)
        var activationUnsilencedReads = 0
        var activationComplete = false
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            while (!stopped.get()) {
                val silencedAtReadStart = clientSilenced
                val requestedBytes = if (activationComplete) {
                    STEADY_READ_BYTES
                } else {
                    ACTIVATION_READ_BYTES
                }
                val count = record.read(buffer, 0, requestedBytes, AudioRecord.READ_BLOCKING)
                if (count <= 0) {
                    if (!stopped.get()) {
                        throw IOException("AudioRecord.read failed with $count")
                    }
                    break
                }
                val completeBytes = count - (count % BYTES_PER_FRAME)
                if (completeBytes == 0) continue
                if (clientSilenced == null) refreshClientSilenced()
                val silencedAtReadEnd = clientSilenced
                if (silencedAtReadStart != false || silencedAtReadEnd != false) {
                    activationUnsilencedReads = 0
                    continue
                }
                if (!activationComplete) {
                    activationUnsilencedReads++
                    if (activationUnsilencedReads <= ACTIVATION_SETTLE_READS) continue
                    activationComplete = true
                }
                // The finalized prefix and this recorder are ordered by microphone ownership,
                // not by independent AudioRecord clocks. Activation can offset those clocks by
                // tens of milliseconds, so never discard valid consumer frames as "overlap".
                val chunk = LiveChunk(buffer.copyOf(completeBytes))
                synchronized(stateLock) {
                    if (liveBytes + completeBytes > MAX_LIVE_BUFFER_BYTES) {
                        throw IOException(
                            "Consumer did not drain wake-word audio within " +
                                "${MAX_LIVE_BUFFER_BYTES * 1000 / BYTES_PER_SECOND} ms",
                        )
                    }
                    liveChunks.addLast(chunk)
                    liveBytes += completeBytes
                    stateLock.notifyAll()
                }
            }
        } catch (error: Throwable) {
            if (!stopped.get()) {
                synchronized(stateLock) {
                    failure = error as? IOException
                        ?: IOException("Continuous microphone capture failed", error)
                    stateLock.notifyAll()
                }
            }
        } finally {
            synchronized(stateLock) {
                pumpFinished = true
                stateLock.notifyAll()
            }
            if (!stopped.get()) lifecycle.close()
        }
    }

    private fun stopInternal() {
        if (!stopped.compareAndSet(false, true)) return
        runCatching { audioManager.unregisterAudioRecordingCallback(recordingCallback) }
        runCatching { record.stop() }
        if (Thread.currentThread() !== pump) {
            runCatching { pump.join(STOP_JOIN_MILLIS) }
        }
        record.release()
        synchronized(stateLock) {
            stateLock.notifyAll()
        }
    }

    private fun refreshClientSilenced() {
        updateClientSilenced(audioManager.activeRecordingConfigurations)
    }

    private fun updateClientSilenced(configs: List<AudioRecordingConfiguration>) {
        val value = configs.firstOrNull {
            it.clientAudioSessionId == record.audioSessionId
        }?.isClientSilenced ?: return
        val previous = clientSilenced
        clientSilenced = value
        if (previous != value) {
            Log.i(TAG, "Audio policy clientSilenced=$value session=${record.audioSessionId}")
        }
    }

    companion object {
        const val SAMPLE_RATE_HZ = 16_000
        private const val BYTES_PER_FRAME = 2
        private const val STEADY_READ_BYTES = 640
        private const val ACTIVATION_READ_MILLIS = 2
        private const val ACTIVATION_READ_BYTES =
            SAMPLE_RATE_HZ * BYTES_PER_FRAME * ACTIVATION_READ_MILLIS / 1_000
        private const val ACTIVATION_SETTLE_READS = 2
        private const val BYTES_PER_SECOND = SAMPLE_RATE_HZ * 2
        private const val PREFIX_WAIT_MILLIS = 2_000L
        private const val STOP_JOIN_MILLIS = 250L
        private const val MAX_LIVE_BUFFER_BYTES = 512 * 1024
        private const val MAX_PREFIX_BYTES = 1024 * 1024
        private val EMPTY = ByteArray(0)

        private const val TAG = "WakewordAudioCapture"
        val AUDIO_FORMAT: AudioFormat by lazy {
            AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE_HZ)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()
        }

        fun defaultBufferSizeInBytes(): Int = maxOf(
            AudioRecord.getMinBufferSize(
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            ),
            4096,
        )

        @SuppressLint("MissingPermission")
        internal fun open(
            context: Context,
            lifecycle: HandoffLifecycle,
            includeWakeword: Boolean,
            bufferSizeInBytes: Int,
        ): WakewordAudioCapture {
            require(bufferSizeInBytes > 0) { "bufferSizeInBytes must be positive" }
            val builder = AudioRecord.Builder()
                .setContext(context)
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(AUDIO_FORMAT)
                .setBufferSizeInBytes(bufferSizeInBytes)
            val record = builder.build()
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                throw IllegalStateException("AudioRecord could not be initialized")
            }
            return WakewordAudioCapture(
                record,
                context.getSystemService(AudioManager::class.java),
                lifecycle,
                includeWakeword,
            ).also { it.start() }
        }

        /** Pays the process's first AudioRecord construction cost without activating the mic. */
        @SuppressLint("MissingPermission")
        internal fun warmUp(context: Context) {
            val record = runCatching {
                AudioRecord.Builder()
                    .setContext(context)
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(AUDIO_FORMAT)
                    .setBufferSizeInBytes(defaultBufferSizeInBytes())
                    .build()
            }.onFailure {
                Log.w(TAG, "Could not warm AudioRecord construction", it)
            }.getOrNull() ?: return
            record.release()
        }

        internal fun readPrefix(
            context: Context,
            audio: WakewordAudio?,
        ): Prefix {
            if (audio == null) return Prefix(EMPTY)
            require(audio.sampleRateHz == SAMPLE_RATE_HZ) {
                "Unsupported prefix sample rate ${audio.sampleRateHz}"
            }
            require(audio.channelCount == 1) {
                "Unsupported prefix channel count ${audio.channelCount}"
            }
            require(audio.encoding == AudioFormat.ENCODING_PCM_16BIT) {
                "Unsupported prefix encoding ${audio.encoding}"
            }
            val wav = context.contentResolver.openInputStream(audio.uri)?.use { input ->
                readBounded(input, MAX_PREFIX_BYTES)
            } ?: throw IOException("Could not open wake-word audio")
            return Prefix(parsePcm16Wav(wav))
        }

        internal fun parsePcm16Wav(wav: ByteArray): ByteArray {
            if (wav.size < 12 || ascii(wav, 0) != "RIFF" || ascii(wav, 8) != "WAVE") {
                throw IOException("Invalid WAV container")
            }
            var offset = 12
            var formatValid = false
            var pcm: ByteArray? = null
            while (offset <= wav.size - 8) {
                val id = ascii(wav, offset)
                val size = littleEndianInt(wav, offset + 4)
                if (size < 0 || offset + 8L + size > wav.size) {
                    throw IOException("Invalid WAV chunk size")
                }
                val dataOffset = offset + 8
                when (id) {
                    "fmt " -> {
                        if (size < 16) throw IOException("Invalid WAV format chunk")
                        val pcmFormat = littleEndianShort(wav, dataOffset)
                        val channels = littleEndianShort(wav, dataOffset + 2)
                        val sampleRate = littleEndianInt(wav, dataOffset + 4)
                        val bits = littleEndianShort(wav, dataOffset + 14)
                        formatValid = pcmFormat == 1 && channels == 1 &&
                            sampleRate == SAMPLE_RATE_HZ && bits == 16
                    }
                    "data" -> pcm = wav.copyOfRange(dataOffset, dataOffset + size)
                }
                offset = dataOffset + size + (size and 1)
            }
            if (!formatValid || pcm == null) throw IOException("Unsupported WAV format")
            return pcm
        }

        private fun readBounded(input: InputStream, maximum: Int): ByteArray {
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (output.size() <= maximum) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                output.write(buffer, 0, count)
            }
            if (output.size() > maximum) throw IOException("Wake-word audio is too large")
            return output.toByteArray()
        }

        private fun ascii(bytes: ByteArray, offset: Int): String =
            String(bytes, offset, 4, Charsets.US_ASCII)

        private fun littleEndianShort(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xff) or
                ((bytes[offset + 1].toInt() and 0xff) shl 8)

        private fun littleEndianInt(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xff) or
                ((bytes[offset + 1].toInt() and 0xff) shl 8) or
                ((bytes[offset + 2].toInt() and 0xff) shl 16) or
                ((bytes[offset + 3].toInt() and 0xff) shl 24)

    }
}
