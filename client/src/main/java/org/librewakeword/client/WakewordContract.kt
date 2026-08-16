package org.librewakeword.client

/** Stable discovery and metadata constants for LibreWakeWord consumers. */
object WakewordContract {
    const val API_VERSION = 1
    const val LIBREWAKEWORD_PACKAGE = "org.librewakeword"
    const val PERMISSION_BIND_CONSUMER = "org.librewakeword.permission.BIND_CONSUMER"
    const val ACTION_CONSUMER = "org.librewakeword.action.WAKEWORD_CONSUMER"
    const val ACTION_REQUEST_ACCESS = "org.librewakeword.action.REQUEST_ACCESS"
    const val ACTION_BEGIN_MICROPHONE_HANDOFF =
        "org.librewakeword.action.BEGIN_MICROPHONE_HANDOFF"
    const val MICROPHONE_HANDOFF_SERVICE =
        "org.librewakeword.client.MicrophoneHandoffService"
    const val EXTRA_HANDOFF_TOKEN = "org.librewakeword.extra.HANDOFF_TOKEN"
    const val EXTRA_EVENT_ID = "org.librewakeword.extra.EVENT_ID"
    const val EXTRA_HANDOFF_STATUS = "org.librewakeword.extra.HANDOFF_STATUS"

    const val META_API_VERSION = "org.librewakeword.consumer.API_VERSION"
    const val META_EVENTS = "org.librewakeword.consumer.EVENTS"
    const val META_AUDIO = "org.librewakeword.consumer.AUDIO"
    const val META_ROLE = "org.librewakeword.consumer.ROLE"
    const val META_LATENCY = "org.librewakeword.consumer.LATENCY"

    const val EVENTS_CANDIDATE = "candidate"
    const val EVENTS_CONFIRMED = "confirmed"
    const val EVENTS_BOTH = "candidate,confirmed"
    const val AUDIO_NONE = "none"
    const val AUDIO_PREFIX = "prefix"
    const val ROLE_OBSERVER = "observer"
    const val ROLE_HANDLER = "handler"
    const val LATENCY_NORMAL = "normal"
    const val LATENCY_LOW = "low"
}
