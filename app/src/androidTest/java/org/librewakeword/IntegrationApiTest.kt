package org.librewakeword

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.SharedMemory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.librewakeword.integration.AudioAssetStore
import org.librewakeword.integration.IntegrationStore
import org.librewakeword.integration.EventJournal
import org.librewakeword.client.WakewordEvent
import java.nio.ByteBuffer

@RunWith(AndroidJUnit4::class)
class IntegrationApiTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun discoversAndEnablesDeclaredConsumersWithExclusiveHandler() {
        val store = IntegrationStore(context)
        val sample = store.discoverConsumers().filter {
            it.component.packageName == SAMPLE_PACKAGE
        }
        assertEquals(3, sample.size)
        assertEquals(2, sample.count { it.handler })
        assertEquals(1, sample.count { !it.handler })

        sample.forEach { store.setConsumerEnabled(it.component, true) }
        val enabled = store.enabledConsumers().filter { it.component.packageName == SAMPLE_PACKAGE }
        assertEquals(2, enabled.size)
        assertEquals(1, enabled.count { it.handler })
    }

    @Test
    fun audioStoreCreatesBoundedReadableWav() {
        val pcm = ByteArray(3200) { it.toByte() }
        val asset = AudioAssetStore(context).create(pcm, 16_000, 1)
        assertNotNull(asset)
        assertEquals(1600, asset!!.frameCount)
        context.contentResolver.openInputStream(asset.uri)!!.use { input ->
            val bytes = input.readBytes()
            assertEquals(3244, bytes.size)
            assertEquals("RIFF", bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII))
            assertEquals("WAVE", bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII))
        }
    }

    @Test
    fun audioProviderSupportsNarrowUriGrants() {
        val provider = context.packageManager.getProviderInfo(
            ComponentName(context, org.librewakeword.integration.WakewordAudioProvider::class.java),
            PackageManager.ComponentInfoFlags.of(0),
        )
        assertFalse(provider.exported)
        assertTrue(provider.grantUriPermissions)
    }

    @Test
    fun syntheticDetectionRoutesToEnabledSampleObserver() {
        val application = ApplicationProvider.getApplicationContext<HotwordApplication>()
        val store = application.detectionCoordinator.integrationStore
        val sampleConsumers = store.discoverConsumers().filter {
            it.component.packageName == SAMPLE_PACKAGE
        }
        assertEquals(3, sampleConsumers.size)
        sampleConsumers.forEach { store.setConsumerEnabled(it.component, true) }
        application.detectionCoordinator.refreshIntegrations()

        val detectionId = "instrumented-${System.nanoTime()}"
        application.detectionCoordinator.onCandidate(
            detectionId,
            android.os.SystemClock.elapsedRealtimeNanos(),
            System.currentTimeMillis(),
        )
        val audio = ByteArray(6400) { index -> (index and 0xff).toByte() }
        val memory = SharedMemory.create("instrumented-audio", audio.size)
        val buffer: ByteBuffer = memory.mapReadWrite()
        buffer.put(audio)
        SharedMemory.unmap(buffer)
        var finished = false
        application.detectionCoordinator.processCompletion(
            detectionId,
            true,
            16_000,
            1,
            audio.size,
            memory,
        ) { finished = true }
        repeat(50) {
            if (finished) return@repeat
            Thread.sleep(100)
        }
        assertTrue(finished)
        Thread.sleep(1000)
    }

    @Test
    fun pendingDeliveriesRoundTripAndAcknowledge() {
        val journal = EventJournal(context)
        val event = WakewordEvent(
            eventId = "journal-${System.nanoTime()}",
            detectionId = "detection",
            stage = WakewordEvent.STAGE_CONFIRMED,
            occurredAtElapsedRealtimeNanos = 123,
            occurredAtEpochMillis = 456,
            phrase = "Hello world",
            localeTag = "en-US",
        )
        val component = "example.package/.WakewordService"
        journal.queueDelivery(component, event)
        val restored = journal.pendingDeliveries().single { it.second.eventId == event.eventId }
        assertEquals(component, restored.first)
        assertEquals(event, restored.second)
        journal.acknowledgeDelivery(component, event.eventId)
        assertFalse(journal.pendingDeliveries().any { it.second.eventId == event.eventId })
    }

    private companion object {
        const val SAMPLE_PACKAGE = "org.librewakeword.sample"
    }
}
