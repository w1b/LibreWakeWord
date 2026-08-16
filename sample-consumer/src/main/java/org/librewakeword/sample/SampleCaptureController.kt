package org.librewakeword.sample

/** Process-local control for the one microphone handler that LibreWakeWord permits at a time. */
object SampleCaptureController {
    private val lock = Any()
    private var active: ActiveCapture? = null

    fun register(entryId: String, stop: () -> Unit): Boolean = synchronized(lock) {
        if (active != null) return false
        active = ActiveCapture(entryId, stop)
        true
    }

    fun finish(entryId: String) {
        synchronized(lock) {
            if (active?.entryId == entryId) active = null
        }
    }

    fun stop(entryId: String): Boolean {
        val action = synchronized(lock) {
            active?.takeIf { it.entryId == entryId }?.stop
        } ?: return false
        action()
        return true
    }

    private data class ActiveCapture(
        val entryId: String,
        val stop: () -> Unit,
    )
}
