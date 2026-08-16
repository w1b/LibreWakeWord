package org.librewakeword.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.librewakeword.client.WakewordEvent

class WebhookWorkerTest {
    @Test
    fun cloudEventEscapesConsumerControlledText() {
        val payload = WebhookWorker.cloudEvent(
            WakewordEvent(
                eventId = "event-1",
                detectionId = "detection-1",
                stage = WakewordEvent.STAGE_CONFIRMED,
                occurredAtElapsedRealtimeNanos = 42,
                occurredAtEpochMillis = 0,
                phrase = "hello\"\nworld",
                localeTag = "en-US",
            ),
        )

        assertTrue(payload.contains("\"specversion\":\"1.0\""))
        assertTrue(payload.contains("hello\\\"\\nworld"))
        assertFalse(payload.contains("hello\"\nworld"))
    }

    @Test
    fun hmacMatchesPublishedSha256Vector() {
        assertEquals(
            "f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8",
            WebhookWorker.hmac("key", "The quick brown fox jumps over the lazy dog"),
        )
    }
}
