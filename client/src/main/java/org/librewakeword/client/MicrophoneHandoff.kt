package org.librewakeword.client

import android.annotation.SuppressLint
import android.content.Context
import androidx.annotation.AnyThread
import androidx.annotation.WorkerThread
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A scoped lease for the consumer's post-wake microphone foreground capability.
 *
 * Use [openAudioCapture] for a ready-to-read stream containing the optional wake-word prefix
 * followed by live microphone audio. The recorder and ongoing audio remain in the consumer
 * process. Advanced consumers may instead construct and start their own recorder, then call
 * [claim] immediately. Use [awaitWakewordPrefix] to obtain the
 * lookback audio. Close this lease when recording ends.
 */
class MicrophoneHandoff internal constructor(
    val eventId: String,
    private val lifecycle: HandoffLifecycle,
) : AutoCloseable {
    val isClaimed: Boolean
        get() = lifecycle.isClaimed

    val isClosed: Boolean
        get() = lifecycle.isClosed

    /**
     * Confirms that a consumer-owned recorder has been activated.
     *
     * Prepare the recorder while armed when low latency matters, or construct it in the callback.
     * Call `AudioRecord.startRecording()`, verify that it entered the recording state, then call
     * this immediately. LibreWakeWord uses the claim to finalize and release its bounded bridge.
     * The operation is idempotent.
     */
    @AnyThread
    fun claim(): Boolean = lifecycle.claim()

    /**
     * Waits for LibreWakeWord's bounded PCM prefix without starting an SDK AudioRecord.
     *
     * This is intended for advanced handlers that claim with a custom recorder. Most handlers
     * should use [openAudioCapture], which performs the prefix-to-live splice itself.
     */
    @WorkerThread
    @JvmOverloads
    fun awaitWakewordPrefix(timeoutMillis: Long = PREFIX_WAIT_MILLIS): WakewordAudioPrefix? {
        require(timeoutMillis >= 0L) { "timeoutMillis must not be negative" }
        return lifecycle.awaitPrefix(timeoutMillis)
            ?.takeIf { it.pcm.isNotEmpty() }
            ?.let { WakewordAudioPrefix(it.pcm) }
    }

    @SuppressLint("MissingPermission")
    @WorkerThread
    @JvmOverloads
    fun openAudioCapture(
        context: Context,
        includeWakeword: Boolean = true,
        bufferSizeInBytes: Int = WakewordAudioCapture.defaultBufferSizeInBytes(),
    ): WakewordAudioCapture =
        WakewordAudioCapture.open(
            context.applicationContext,
            lifecycle,
            includeWakeword,
            bufferSizeInBytes,
        )

    @AnyThread
    override fun close() {
        lifecycle.close()
    }

    private companion object {
        const val PREFIX_WAIT_MILLIS = 2_000L
    }
}

/** Claim and close listeners coordinate the two services in the consumer process. */
internal class HandoffLifecycle {
    private val stateLock = Object()
    private val closed = AtomicBoolean()
    private val claimed = AtomicBoolean()
    private val claimListeners = ArrayList<() -> Unit>()
    private val closeListeners = ArrayList<() -> Unit>()
    private var capture: WakewordAudioCapture? = null
    private var prefix: WakewordAudioCapture.Prefix? = null
    private var prefixFinalized = false

    val isClaimed: Boolean
        get() = claimed.get()

    val isClosed: Boolean
        get() = closed.get()

    fun claim(): Boolean {
        val pending = synchronized(stateLock) {
            if (closed.get()) return false
            if (claimed.compareAndSet(false, true)) {
                claimListeners.toList().also { claimListeners.clear() }
            } else {
                emptyList()
            }
        }
        pending.forEach { it() }
        return !closed.get()
    }

    fun close() {
        val pending = synchronized(stateLock) {
            if (!closed.compareAndSet(false, true)) return
            claimListeners.clear()
            stateLock.notifyAll()
            closeListeners.toList().also { closeListeners.clear() }
        }
        pending.forEach { it() }
    }

    fun invokeOnClaim(listener: () -> Unit) {
        val runImmediately = synchronized(stateLock) {
            if (claimed.get()) true else if (closed.get()) false else {
                claimListeners += listener
                false
            }
        }
        if (runImmediately) listener()
    }

    fun invokeOnClose(listener: () -> Unit) {
        val runImmediately = synchronized(stateLock) {
            if (closed.get()) true else {
                closeListeners += listener
                false
            }
        }
        if (runImmediately) listener()
    }

    fun attachCapture(value: WakewordAudioCapture) {
        val readyPrefix = synchronized(stateLock) {
            check(!closed.get()) { "Microphone handoff is already closed" }
            check(capture == null) { "Only one audio capture can be opened per handoff" }
            capture = value
            prefix.takeIf { prefixFinalized }
        }
        readyPrefix?.let(value::offerPrefix)
    }

    fun awaitPrefix(timeoutMillis: Long): WakewordAudioCapture.Prefix? =
        synchronized(stateLock) {
            val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMillis
            while (!prefixFinalized && !closed.get()) {
                val remaining = deadline - android.os.SystemClock.elapsedRealtime()
                if (remaining <= 0L) break
                stateLock.wait(remaining)
            }
            prefix.takeIf { prefixFinalized }
        }

    private fun finalizePrefix(value: WakewordAudioCapture.Prefix) {
        val target = synchronized(stateLock) {
            if (prefixFinalized) return
            prefix = value
            prefixFinalized = true
            stateLock.notifyAll()
            capture
        }
        target?.offerPrefix(value)
    }

    fun deliverPrefix(
        context: Context,
        audio: WakewordAudio?,
        completed: () -> Unit = {},
    ) {
        Thread(
            {
                try {
                    val prefix = runCatching {
                        WakewordAudioCapture.readPrefix(
                            context,
                            audio,
                        )
                    }.getOrElse { error ->
                        android.util.Log.e(
                            "WakewordHandoff",
                            "Could not load finalized wake-word prefix",
                            error,
                        )
                        WakewordAudioCapture.Prefix(ByteArray(0))
                    }
                    finalizePrefix(prefix)
                } finally {
                    completed()
                }
            },
            "wakeword-prefix-loader",
        ).start()
    }
}
