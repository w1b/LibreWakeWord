package org.librewakeword.client

import android.net.Uri
import android.os.Parcelable
import androidx.annotation.IntDef
import kotlinx.parcelize.Parcelize

@IntDef(WakewordEvent.STAGE_CANDIDATE, WakewordEvent.STAGE_CONFIRMED)
@Retention(AnnotationRetention.SOURCE)
annotation class WakewordStage

/** An immutable, versioned detection event delivered by LibreWakeWord. */
@Parcelize
data class WakewordEvent(
    val apiVersion: Int = WakewordContract.API_VERSION,
    val eventId: String,
    val detectionId: String,
    @param:WakewordStage @get:WakewordStage val stage: Int,
    val occurredAtElapsedRealtimeNanos: Long,
    val occurredAtEpochMillis: Long,
    val phrase: String? = null,
    val localeTag: String? = null,
    val audio: WakewordAudio? = null,
) : Parcelable {
    companion object {
        const val STAGE_CANDIDATE = 1
        const val STAGE_CONFIRMED = 2
    }
}

/** A bounded, read-only clip. LibreWakeWord never streams ongoing microphone audio. */
@Parcelize
data class WakewordAudio(
    val uri: Uri,
    val mimeType: String,
    val sampleRateHz: Int,
    val channelCount: Int,
    val encoding: Int,
    val frameCount: Long,
    val expiresAtEpochMillis: Long,
    /** Boot-time timestamp of the first frame, when supplied by the detector. */
    val startTimeElapsedRealtimeNanos: Long? = null,
) : Parcelable
