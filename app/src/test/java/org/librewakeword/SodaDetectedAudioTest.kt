package org.librewakeword

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class SodaDetectedAudioTest {
    @Test
    fun selectsTheHotwordChannelAdvertisedBySoda() {
        val audio = SodaHotwordVerifier.DetectedAudio(
            byteArrayOf(1, 0, 11, 0, 2, 0, 12, 0),
            16_000,
            2,
            1,
            123_456L,
            300,
            900,
            32_640L,
        )

        assertArrayEquals(byteArrayOf(11, 0, 12, 0), audio.selectedChannelPcm16())
        org.junit.Assert.assertEquals(27_840L, audio.hotwordEndSourceFrame())
    }
}
