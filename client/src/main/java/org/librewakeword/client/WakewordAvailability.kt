package org.librewakeword.client

import android.os.Parcelable
import androidx.annotation.IntDef
import kotlinx.parcelize.Parcelize

@IntDef(WakewordAvailability.STATE_INACTIVE, WakewordAvailability.STATE_ARMED)
@Retention(AnnotationRetention.SOURCE)
annotation class WakewordAvailabilityState

/** Advisory detector state delivered to integrations that request low-latency binding. */
@Parcelize
data class WakewordAvailability(
    val apiVersion: Int = WakewordContract.API_VERSION,
    @param:WakewordAvailabilityState @get:WakewordAvailabilityState val state: Int,
    val phrase: String? = null,
) : Parcelable {
    companion object {
        const val STATE_INACTIVE = 0
        const val STATE_ARMED = 1
    }
}
