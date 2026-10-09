package com.easysavevideo.app.storage

import com.easysavevideo.discovery.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class DiscoveryConfigurationCheckpointTest {
    private fun checkpoint(key: String? = "a".repeat(64), authorized: Boolean = false) =
        DiscoveryCheckpoint(SessionStamp("session", 1), SearchProfile("profile", "query"),
            sourceConfigurationKey = key, queryAuthorized = authorized)
    @Test fun sourceConfigurationAndExplicitSearchFenceSurviveVersionFourRoundTrip() {
        val value = checkpoint(); val bytes = DiscoveryCheckpointCodec.encode(value)
        assertEquals(4, ByteBuffer.wrap(bytes, 4, 4).int)
        assertEquals(value, DiscoveryCheckpointCodec.decode(bytes))
    }
    @Test fun confirmedSearchPermissionAndConfiguredSourceKeyRoundTrip() {
        val value = checkpoint(authorized = true)
        assertEquals(value, DiscoveryCheckpointCodec.decode(DiscoveryCheckpointCodec.encode(value)))
    }
    @Test fun sourceFreeHistoricalEncodingStaysVersionThree() {
        val value = checkpoint(null, true); val bytes = DiscoveryCheckpointCodec.encode(value)
        assertEquals(3, ByteBuffer.wrap(bytes, 4, 4).int); assertEquals(value, DiscoveryCheckpointCodec.decode(bytes))
    }
    @Test fun missingConfigurationStillPersistsExplicitSearchRequirement() {
        val value = checkpoint(null, false)
        assertEquals(value, DiscoveryCheckpointCodec.decode(DiscoveryCheckpointCodec.encode(value)))
    }
    @Test fun malformedKeyAndTruncatedFenceAreRejected() {
        try { checkpoint("bad"); fail() } catch (_: IllegalArgumentException) { }
        val bytes = DiscoveryCheckpointCodec.encode(checkpoint())
        try { DiscoveryCheckpointCodec.decode(bytes.copyOf(bytes.size - 1)); fail() } catch (_: Exception) { }
        try { DiscoveryCheckpointCodec.decode(bytes.copyOf().also { it[it.lastIndex] = 2 }); fail() } catch (_: Exception) { }
    }
}
