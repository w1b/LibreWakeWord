package org.librewakeword.sample

import org.librewakeword.client.WakewordConsumerService
import org.librewakeword.client.WakewordEvent
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Demonstrates durable candidate/confirmed observation and bounded audio attachments. */
class SampleObserverService : WakewordConsumerService() {
    override fun onWakeword(event: WakewordEvent) {
        val entryId = "observer-${event.eventId}"
        val stage = when (event.stage) {
            WakewordEvent.STAGE_CANDIDATE -> "Raw DSP candidate"
            WakewordEvent.STAGE_CONFIRMED -> "Confirmed wake word"
            else -> "Unknown event"
        }
        SampleHistory.put(
            this,
            SampleEntry(
                id = entryId,
                occurredAtEpochMillis = event.occurredAtEpochMillis,
                title = stage,
                detail = buildString {
                    append("Detection ${event.detectionId.shortId()} · event ${event.eventId.shortId()}")
                    event.phrase?.let { append("\nPhrase: $it") }
                    append(
                        if (event.audio == null) "\nNo audio attached to this delivery."
                        else "\nCopying the expiring audio attachment…",
                    )
                },
            ),
        )
        val audio = event.audio ?: return
        attachmentExecutor.execute {
            runCatching {
                val destination = SampleHistory.newRecording(this, "observed-${event.stage}")
                val temporary = destination.resolveSibling("${destination.name}.part")
                try {
                    contentResolver.openInputStream(audio.uri)?.use { input ->
                        FileOutputStream(temporary).use { output ->
                            val buffer = ByteArray(16 * 1024)
                            var total = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                if (total > MAX_ATTACHMENT_BYTES) {
                                    throw IOException("Audio attachment exceeds sample limit")
                                }
                                output.write(buffer, 0, count)
                            }
                            output.fd.sync()
                        }
                    } ?: throw IOException("Audio attachment could not be opened")
                    check(temporary.renameTo(destination)) {
                        "Could not save ${destination.name}"
                    }
                } catch (error: Throwable) {
                    temporary.delete()
                    throw error
                }
                destination
            }.onSuccess { destination ->
                SampleHistory.update(this, entryId) { entry ->
                    entry.copy(
                        detail = entry.detail.substringBefore("\nCopying") +
                            "\nSaved ${audio.frameCount} frames at ${audio.sampleRateHz} Hz.",
                        recordingName = destination.name,
                    )
                }
            }.onFailure { error ->
                SampleHistory.update(this, entryId) { entry ->
                    entry.copy(
                        detail = entry.detail.substringBefore("\nCopying") +
                            "\nAudio was unavailable: ${error.message ?: error.javaClass.simpleName}",
                    )
                }
            }
        }
    }

    private fun String.shortId(): String = take(8)

    private companion object {
        const val MAX_ATTACHMENT_BYTES = 2L * 1024 * 1024
        val attachmentExecutor = ThreadPoolExecutor(
            0,
            1,
            10L,
            TimeUnit.SECONDS,
            LinkedBlockingQueue(),
        )
    }
}
