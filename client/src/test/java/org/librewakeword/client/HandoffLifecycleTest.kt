package org.librewakeword.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandoffLifecycleTest {
    @Test
    fun claimIsIdempotentAndNotifiesOnce() {
        val lifecycle = HandoffLifecycle()
        var claims = 0
        lifecycle.invokeOnClaim { claims++ }

        assertTrue(lifecycle.claim())
        assertTrue(lifecycle.claim())
        assertTrue(lifecycle.isClaimed)
        assertEquals(1, claims)
    }

    @Test
    fun closedHandoffCannotBeClaimed() {
        val lifecycle = HandoffLifecycle()
        var claims = 0
        lifecycle.invokeOnClaim { claims++ }

        lifecycle.close()

        assertFalse(lifecycle.claim())
        assertFalse(lifecycle.isClaimed)
        assertEquals(0, claims)
    }

    @Test
    fun lateListenersObserveExistingState() {
        val lifecycle = HandoffLifecycle()
        lifecycle.claim()
        lifecycle.close()
        var claims = 0
        var closes = 0

        lifecycle.invokeOnClaim { claims++ }
        lifecycle.invokeOnClose { closes++ }

        assertEquals(1, claims)
        assertEquals(1, closes)
    }
}
