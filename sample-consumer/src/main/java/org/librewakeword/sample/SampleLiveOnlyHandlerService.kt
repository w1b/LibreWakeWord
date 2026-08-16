package org.librewakeword.sample

/** Demonstrates a verified handoff that intentionally omits the wake-word prefix. */
class SampleLiveOnlyHandlerService : SampleAssistantService() {
    override val includeWakeword = false
    override val sampleTitle = "Continuous capture: live mic only"
}
