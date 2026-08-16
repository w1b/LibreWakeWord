package org.librewakeword.sample

import org.librewakeword.client.WakewordEvent

/** Demonstrates taking microphone ownership immediately at the raw DSP boundary. */
class SampleCandidateHandlerService : SampleAssistantService() {
    override val triggerStage = WakewordEvent.STAGE_CANDIDATE
    override val includeWakeword = false
    override val sampleTitle = "Continuous capture: raw DSP + live mic"
}
