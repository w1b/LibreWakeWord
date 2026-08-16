package org.librewakeword;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class WakewordLiveInputTest {
    @Test
    public void activationClockCorrection_removesTimestampedFramesFromFirstReadWait() {
        long correction = WakewordLiveInput.activationClockCorrectionNanos(
                576_320_615_930_344L,
                576_320_681_813_259L,
                0L,
                318L,
                16_000);

        assertEquals(46_007_915L, correction);
    }

    @Test
    public void activationClockCorrection_neverMovesClockBackward() {
        assertEquals(0L, WakewordLiveInput.activationClockCorrectionNanos(
                1_000L, 2_000L, 0L, 320L, 16_000));
        assertEquals(0L, WakewordLiveInput.activationClockCorrectionNanos(
                2_000L, 1_000L, 0L, 0L, 16_000));
    }
}
