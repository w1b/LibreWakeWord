package org.librewakeword.sample

import android.os.SystemClock
import org.librewakeword.client.MicrophoneHandoff
import org.librewakeword.client.WakewordAudioCapture
import org.librewakeword.client.WakewordAvailability
import org.librewakeword.client.WakewordConsumerService
import org.librewakeword.client.WakewordEvent
import java.util.LinkedHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Demonstrates the standard prefix-plus-live microphone handoff. */
open class SampleAssistantService : WakewordConsumerService() {
    private val recorderExecutor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        LinkedBlockingQueue(),
    )
    private val captureActive = AtomicBoolean(false)
    private val handledEvents = object : LinkedHashMap<String, Unit>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?): Boolean =
            size > MAX_REMEMBERED_EVENTS
    }
    protected open val triggerStage = WakewordEvent.STAGE_CONFIRMED
    protected open val includeWakeword = true
    protected open val sampleTitle = "Continuous capture: wake word + live mic"

    override fun onCreate() {
        super.onCreate()
        // An idle prestarted worker consumes no CPU and avoids creating a thread on the wake path.
        recorderExecutor.prestartCoreThread()
    }

    override fun onWakewordAvailabilityChanged(availability: WakewordAvailability) {
        SampleHistory.setAvailability(this, availability)
    }

    override fun onMicrophoneHandoff(event: WakewordEvent, handoff: MicrophoneHandoff) {
        val entryId = "handler-${javaClass.simpleName}-${event.eventId}"
        if (event.stage != triggerStage || SampleHistory.contains(this, entryId) ||
            !claimEvent(event.eventId) ||
            !captureActive.compareAndSet(false, true)
        ) {
            handoff.close()
            return
        }
        SampleHistory.put(
            this,
            SampleEntry(
                id = entryId,
                occurredAtEpochMillis = event.occurredAtEpochMillis,
                title = sampleTitle,
                detail = "Preparing the consumer-owned microphone…",
                active = true,
            ),
        )
        try {
            val capture = handoff.openAudioCapture(this, includeWakeword = includeWakeword)
            check(SampleCaptureController.register(entryId) { capture.close() }) {
                "Another sample capture is already active"
            }
            SampleHistory.update(this, entryId) {
                it.copy(detail = "Listening… Tap Stop when the command is complete.")
            }
            recorderExecutor.execute { readCommand(entryId, capture) }
        } catch (error: Throwable) {
            SampleCaptureController.finish(entryId)
            markFailure(entryId, error)
            captureActive.set(false)
            handoff.close()
        }
    }

    private fun readCommand(
        entryId: String,
        capture: WakewordAudioCapture,
    ) {
        val destination = SampleHistory.newRecording(this, "handoff")
        val writer = Pcm16WavWriter(destination, capture.sampleRateHz, capture.channelCount)
        try {
            val buffer = ByteArray(READ_BYTES)
            var total = 0L
            val deadline = SystemClock.elapsedRealtime() + MAX_CAPTURE_MILLIS
            while (SystemClock.elapsedRealtime() < deadline) {
                val count = capture.read(buffer)
                if (count <= 0) break
                writer.write(buffer, 0, count)
                total += count
            }
            writer.close()
            val durationMillis = total * 1_000 / (capture.sampleRateHz * capture.channelCount * 2L)
            SampleHistory.update(this, entryId) {
                it.copy(
                    detail = buildString {
                        append("Saved ${durationMillis} ms of playable audio.")
                        append(
                            if (includeWakeword) "\nBounded wake-word prefix followed by consumer-microphone audio."
                            else "\nConsumer-microphone audio only; wake-word audio was omitted.",
                        )
                    },
                    recordingName = destination.name,
                    active = false,
                )
            }
        } catch (error: Throwable) {
            writer.abort()
            markFailure(entryId, error)
        } finally {
            SampleCaptureController.finish(entryId)
            capture.close()
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

    private fun claimEvent(eventId: String): Boolean = synchronized(handledEvents) {
        if (handledEvents.containsKey(eventId)) false else {
            handledEvents[eventId] = Unit
            true
        }
    }

    private companion object {
        const val MAX_REMEMBERED_EVENTS = 128
        const val READ_BYTES = 640 // 20 ms of 16 kHz mono PCM16.
        const val MAX_CAPTURE_MILLIS = 30_000L
    }
}
