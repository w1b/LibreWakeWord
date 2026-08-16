package org.librewakeword.sample

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import org.librewakeword.client.MicrophoneHandoff
import org.librewakeword.client.WakewordAudioCapture
import org.librewakeword.client.WakewordAvailability
import org.librewakeword.client.WakewordConsumerService
import org.librewakeword.client.WakewordEvent
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Demonstrates the advanced manual claim API with a consumer-configured AudioRecord. */
class SampleCustomRecorderService : WakewordConsumerService() {
    private val recorderExecutor = Executors.newSingleThreadExecutor()
    private val captureActive = AtomicBoolean(false)

    override fun onWakewordAvailabilityChanged(availability: WakewordAvailability) {
        SampleHistory.setAvailability(this, availability)
    }

    @SuppressLint("MissingPermission")
    override fun onMicrophoneHandoff(event: WakewordEvent, handoff: MicrophoneHandoff) {
        val entryId = "custom-${event.eventId}"
        if (SampleHistory.contains(this, entryId) || !captureActive.compareAndSet(false, true)) {
            handoff.close()
            return
        }
        SampleHistory.put(
            this,
            SampleEntry(
                id = entryId,
                occurredAtEpochMillis = event.occurredAtEpochMillis,
                title = "Continuous capture: custom AudioRecord",
                detail = "Constructing the consumer’s custom recorder…",
                active = true,
            ),
        )
        var record: AudioRecord? = null
        try {
            record = AudioRecord.Builder()
                .setContext(this)
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE_HZ)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(BUFFER_BYTES)
                .build()
            check(record.state == AudioRecord.STATE_INITIALIZED) {
                "Custom AudioRecord could not be initialized"
            }
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "Custom AudioRecord did not enter the recording state"
            }
            check(handoff.claim()) { "Microphone handoff expired before it was claimed" }
            val stoppedByUser = AtomicBoolean(false)
            check(
                SampleCaptureController.register(entryId) {
                    stoppedByUser.set(true)
                    runCatching { record.stop() }
                },
            ) { "Another sample capture is already active" }
            SampleHistory.update(this, entryId) {
                it.copy(detail = "Listening with a custom AudioRecord… Tap Stop when complete.")
            }
            recorderExecutor.execute {
                capture(entryId, record, handoff, stoppedByUser)
            }
        } catch (error: Throwable) {
            SampleCaptureController.finish(entryId)
            runCatching { record?.stop() }
            record?.release()
            handoff.close()
            captureActive.set(false)
            markFailure(entryId, error)
        }
    }

    private fun capture(
        entryId: String,
        record: AudioRecord,
        handoff: MicrophoneHandoff,
        stoppedByUser: AtomicBoolean,
    ) {
        val destination = SampleHistory.newRecording(this, "custom")
        val writer = Pcm16WavWriter(destination, SAMPLE_RATE_HZ, 1)
        try {
            var total = 0L
            handoff.awaitWakewordPrefix()?.toByteArray()?.let { prefix ->
                writer.write(prefix)
                total += prefix.size
            }
            SampleHistory.update(this, entryId) {
                it.copy(detail = "Listening with a custom AudioRecord… Tap Stop when complete.")
            }
            val buffer = ByteArray(READ_BYTES)
            val deadline = SystemClock.elapsedRealtime() + MAX_CAPTURE_MILLIS
            while (!stoppedByUser.get() && SystemClock.elapsedRealtime() < deadline) {
                val count = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (count < 0 && stoppedByUser.get()) break
                check(count >= 0) { "AudioRecord.read failed with $count" }
                if (count > 0) {
                    writer.write(buffer, 0, count)
                    total += count
                }
            }
            writer.close()
            val durationMillis = total * 1_000 / BYTES_PER_SECOND
            SampleHistory.update(this, entryId) {
                it.copy(
                    detail = "Saved ${durationMillis} ms using the manual claim and prefix APIs.",
                    recordingName = destination.name,
                    active = false,
                )
            }
        } catch (error: Throwable) {
            writer.abort()
            markFailure(entryId, error)
        } finally {
            SampleCaptureController.finish(entryId)
            runCatching { record.stop() }
            record.release()
            handoff.close()
            captureActive.set(false)
        }
    }

    private fun markFailure(entryId: String, error: Throwable) {
        SampleHistory.update(this, entryId) {
            it.copy(
                detail = "Capture failed: ${error.message ?: error.javaClass.simpleName}",
                active = false,
            )
        }
    }

    override fun onDestroy() {
        recorderExecutor.shutdownNow()
        super.onDestroy()
    }

    private companion object {
        const val SAMPLE_RATE_HZ = WakewordAudioCapture.SAMPLE_RATE_HZ
        const val BUFFER_BYTES = 8_192
        const val READ_BYTES = 640
        const val MAX_CAPTURE_MILLIS = 30_000L
        const val BYTES_PER_SECOND = SAMPLE_RATE_HZ * 2L
    }
}
