package org.librewakeword;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class SodaDspInputTest {
    @Test
    public void pcmRingPreservesChronologicalBytesAcrossWrap() {
        SodaDspInput.PcmRingBuffer ring = new SodaDspInput.PcmRingBuffer(6);

        ring.append(new byte[]{1, 2, 3, 4}, 0, 4);
        ring.append(new byte[]{5, 6, 7, 8}, 0, 4);

        assertArrayEquals(new byte[]{3, 4, 5, 6, 7, 8}, ring.snapshot());
    }

    @Test
    public void pcmRingKeepsTailWhenSingleAppendExceedsCapacity() {
        SodaDspInput.PcmRingBuffer ring = new SodaDspInput.PcmRingBuffer(4);

        ring.append(new byte[]{1, 2, 3, 4, 5, 6}, 0, 6);

        assertArrayEquals(new byte[]{3, 4, 5, 6}, ring.snapshot());
    }

    @Test
    public void selectedChannelDeinterleavesPcm16AndPreservesClock() {
        SodaDspInput.Capture stereo = new SodaDspInput.Capture(
                new byte[]{1, 0, 11, 0, 2, 0, 12, 0, 3, 0, 13, 0},
                123L, 456L, 2);

        SodaDspInput.Capture right = stereo.selectedChannel(1);

        assertArrayEquals(new byte[]{11, 0, 12, 0, 13, 0}, right.pcm);
        assertEquals(123L, right.startElapsedRealtimeNanos);
        assertEquals(456L, right.endElapsedRealtimeNanos);
        assertEquals(1, right.channelCount);
    }

    @Test
    public void clockSpliceReplacesOverlappingDspTailWithOrdinaryLiveAudio() {
        SodaDspInput.Capture history = new SodaDspInput.Capture(
                new byte[]{1, 0, 2, 0, 3, 0, 4, 0},
                0L, 1_000_000_000L);
        WakewordLiveInput.Capture live = new WakewordLiveInput.Capture(
                new byte[]{11, 0, 12, 0, 13, 0},
                500_000_000L, 1_250_000_000L);

        SodaDspInput.Capture composed = SodaDspInput.composeWithLive(history, live, 4);

        assertArrayEquals(new byte[]{1, 0, 2, 0, 11, 0, 12, 0, 13, 0}, composed.pcm);
        assertEquals(0L, composed.startElapsedRealtimeNanos);
        assertEquals(1_250_000_000L, composed.endElapsedRealtimeNanos);
    }

    @Test
    public void clockSpliceRepresentsARealGapAsSilence() {
        SodaDspInput.Capture history = new SodaDspInput.Capture(
                new byte[]{1, 0, 2, 0},
                0L, 500_000_000L);
        WakewordLiveInput.Capture live = new WakewordLiveInput.Capture(
                new byte[]{11, 0, 12, 0},
                1_000_000_000L, 1_500_000_000L);

        SodaDspInput.Capture composed = SodaDspInput.composeWithLive(history, live, 4);

        assertArrayEquals(
                new byte[]{1, 0, 2, 0, 0, 0, 0, 0, 11, 0, 12, 0}, composed.pcm);
        assertEquals(1_500_000_000L, composed.endElapsedRealtimeNanos);
    }
}
