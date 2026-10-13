package com.easysavevideo.app.storage

import com.easysavevideo.core.model.RepeatPolicy
import com.easysavevideo.discovery.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

/** Current writer/reader contracts, with an independent wire oracle from frozen historical bytes. */
class DiscoveryCheckpointCurrentContractTest {
    private val configurationKey = "0123456789abcdef".repeat(4)
    private val fingerprint = "a".repeat(64)

    private fun fixture(version: Int): ByteArray {
        val bytes = javaClass.getResourceAsStream("/discovery-checkpoint-v$version.bin")!!.use { it.readBytes() }
        val expectedHash = when (version) {
            1 -> "a58309da3e846d6af207f3726d6745ab8a2b7e82a40943a120b7471b60616bd5"
            2 -> "a0d25e3c6d1b3c1e2f4165f2ff88b75e1b985442500061e089d98b037b65d0e0"
            else -> error("Only frozen historical fixtures are supported")
        }
        assertEquals(expectedHash, MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) })
        assertEquals(version, ByteBuffer.wrap(bytes).getInt(4))
        return bytes
    }

    private fun historicalCheckpoint(version: Int): DiscoveryCheckpoint {
        val stamp = SessionStamp("legacy-session", 9)
        val rights = if (version == 1) ResultRights() else ResultRights(
            snapshots = listOf(RightsSnapshot(
                providerId = "source", sourceName = "Example", sourceNamespace = "example", sourceItemId = "1",
                sourcePageUrl = "https://example.com/video", sourcePageRevisionId = 12, retrievedAtEpochMillis = 400,
                author = "作者", credit = "Credit", customAttribution = "Custom", licenseName = "License",
                licenseUrl = "https://example.com/license", usageTerms = "Terms", copyrighted = null,
                attributionRequired = true, nonFree = true, restrictions = listOf("Restricted"), deletionReason = "Review",
                review = RightsReview.FLAGGED_REQUIRES_REVIEW,
                provenance = listOf(RightsFieldProvenance("Artist", "source", htmlConvertedToText = true)),
                metadataTermsUrl = "https://example.com/metadata",
            )), warnings = setOf(RightsWarning.CONFLICTING_REPORTS),
        )
        val result = VideoSearchResult(
            fingerprint, "https://example.com/video", "旧标题", "Example", "example", "42", null, 65_000,
            null, null, setOf("provider"), setOf(fingerprint), rights = rights,
        )
        return DiscoveryCheckpoint(
            stamp, SearchProfile("legacy-profile", "旧查询", "zh",
                SearchFilters(setOf("example"), maximumDurationMs = 90_000), RepeatPolicy.HIDE_SHOWN),
            PoolState(listOf(PoolEntry(result, 1, 0)), listOf(PageSnapshot(stamp, 3, 0, listOf(fingerprint), 20)), 3, 1),
            sourceConfigurationKey = null, queryAuthorized = true,
        )
    }

    private fun framedText(text: String): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            val utf8 = text.toByteArray(Charsets.UTF_8)
            out.writeInt(utf8.size)
            out.write(utf8)
        }
    }.toByteArray()

    /**
     * Frozen v2 ends its profile at offset 112, immediately after HIDE_SHOWN. Its pool,
     * pages and complete nested rights remain opaque here. This is not a v2 payload with
     * only its version patched: v3's sort is inserted before the pool, and v4's two-field
     * authority suffix is added below. Neither current production codec constructs this oracle.
     */
    private fun currentBody(version: Int = 4, sort: ResultSort = ResultSort.NEWEST): ByteArray {
        val legacy = fixture(2)
        val body = legacy.copyOfRange(0, 112) + framedText(sort.name) + legacy.copyOfRange(112, legacy.size)
        ByteBuffer.wrap(body).putInt(4, version)
        return body
    }

    private fun authoritySuffix(key: String?, authorized: Boolean): ByteArray =
        (if (key == null) byteArrayOf(0) else byteArrayOf(1) + framedText(key)) +
            byteArrayOf(if (authorized) 1 else 0)

    private fun independentV4(key: String?, authorized: Boolean, sort: ResultSort = ResultSort.NEWEST): ByteArray =
        currentBody(sort = sort) + authoritySuffix(key, authorized)

    private fun reject(label: String, bytes: ByteArray) {
        try {
            DiscoveryCheckpointCodec.decode(bytes)
            fail("Decoder accepted $label")
        } catch (_: IllegalArgumentException) {
        } catch (_: IOException) {
        }
    }

    @Test fun allFourAuthorityCombinationsPreserveFullPayloadAndExactWriterWire() {
        for (sort in ResultSort.entries) for (key in listOf(null, configurationKey)) for (authorized in listOf(false, true)) {
            val expected = historicalCheckpoint(2).copy(
                profile = historicalCheckpoint(2).profile.copy(sortOrder = sort),
                sourceConfigurationKey = key, queryAuthorized = authorized,
            )
            val version = if (key == null && authorized) 3 else 4
            val oracle = currentBody(version, sort) + if (version == 4) authoritySuffix(key, authorized) else byteArrayOf()
            val encoded = DiscoveryCheckpointCodec.encode(expected)
            assertArrayEquals("The current writer must retain the frozen nonempty pool/pages/rights wire", oracle, encoded)
            assertEquals(expected, DiscoveryCheckpointCodec.decode(encoded))
            assertEquals(expected, DiscoveryCheckpointCodec.decode(oracle))
        }
    }

    @Test fun independentVersionFourAcceptsAllFourCombinationsIncludingHistoricalDefaults() {
        for (key in listOf(null, configurationKey)) for (authorized in listOf(false, true)) {
            val bytes = independentV4(key, authorized)
            val expected = historicalCheckpoint(2).copy(
                profile = historicalCheckpoint(2).profile.copy(sortOrder = ResultSort.NEWEST),
                sourceConfigurationKey = key, queryAuthorized = authorized,
            )
            val restored = DiscoveryCheckpointCodec.decode(bytes)
            assertEquals(expected, restored)
            assertEquals(1, restored.pool.entries.size)
            assertEquals(1, restored.pool.pages.size)
            assertEquals(historicalCheckpoint(2).pool.entries.single().result.rights, restored.pool.entries.single().result.rights)
            // A v4 record with default authority is valid, even though the canonical writer uses v3.
            val rewritten = DiscoveryCheckpointCodec.encode(restored)
            assertEquals(if (key == null && authorized) 3 else 4, ByteBuffer.wrap(rewritten).getInt(4))
            assertEquals(expected, DiscoveryCheckpointCodec.decode(rewritten))
        }
    }

    @Test fun bothVersionFourAuthorityBooleansRejectEveryNonBooleanByte() {
        val suffixStart = currentBody().size
        for (key in listOf(null, configurationKey)) for (authorized in listOf(false, true)) {
            val valid = independentV4(key, authorized)
            for (invalid in 2..255) {
                reject("invalid key-presence flag", valid.copyOf().also { it[suffixStart] = invalid.toByte() })
                reject("invalid query-authority flag", valid.copyOf().also { it[it.lastIndex] = invalid.toByte() })
            }
        }
    }

    @Test fun versionFourRejectsMalformedKeysAndKeyLengthsInsteadOfGrantingAuthority() {
        for (key in listOf("", "a".repeat(63), "a".repeat(65), "A".repeat(64), "g".repeat(64), "界".repeat(64))) {
            for (authorized in listOf(false, true)) reject("malformed configuration key", independentV4(key, authorized))
        }
        val valid = independentV4(configurationKey, true)
        val suffixStart = currentBody().size
        for (invalidLength in listOf(-1, 0, 63, 65, 257, Int.MAX_VALUE)) {
            reject("invalid key length", valid.copyOf().also { ByteBuffer.wrap(it).putInt(suffixStart + 1, invalidLength) })
        }
        reject("malformed key UTF-8", valid.copyOf().also { it[suffixStart + 5] = 0xff.toByte() })
    }

    @Test fun versionFourRejectsMissingSuffixAndEveryTruncatedSuffixPrefix() {
        val suffixStart = currentBody().size
        for (key in listOf(null, configurationKey)) for (authorized in listOf(false, true)) {
            val valid = independentV4(key, authorized)
            assertEquals(key, DiscoveryCheckpointCodec.decode(valid).sourceConfigurationKey)
            for (length in suffixStart until valid.size) {
                reject("v4 authority suffix truncated to ${length - suffixStart} bytes", valid.copyOf(length))
            }
        }
    }

    @Test fun trailingBytesAreRejectedForEverySupportedVersionAndAuthorityCombination() {
        val valid = mutableListOf(fixture(1), fixture(2), currentBody(version = 3))
        for (key in listOf(null, configurationKey)) for (authorized in listOf(false, true)) valid += independentV4(key, authorized)
        for (bytes in valid) {
            DiscoveryCheckpointCodec.decode(bytes)
            for (suffix in listOf(byteArrayOf(0), byteArrayOf(1), byteArrayOf(0, 1), byteArrayOf(1, 2, 3))) {
                reject("trailing bytes", bytes + suffix)
            }
        }
    }

    @Test fun unknownVersionFiveCannotBeInterpretedAsCurrentAuthorityOrHistoricalDefaults() {
        for (bytes in listOf(currentBody(version = 3), independentV4(null, true), independentV4(configurationKey, false))) {
            DiscoveryCheckpointCodec.decode(bytes)
            reject("unknown v5", bytes.copyOf().also { ByteBuffer.wrap(it).putInt(4, 5) })
        }
    }

    @Test fun genuineHistoricalFixturesDefaultAuthorityAndUpgradeWithoutDroppingAnyInformation() {
        for (version in 1..2) {
            val expected = historicalCheckpoint(version)
            val restored = DiscoveryCheckpointCodec.decode(fixture(version))
            assertNull(restored.sourceConfigurationKey)
            assertTrue(restored.queryAuthorized)
            assertEquals(expected, restored)
            val compatible = DiscoveryCheckpointCodec.encode(restored)
            assertEquals(3, ByteBuffer.wrap(compatible).getInt(4))
            assertEquals(expected, DiscoveryCheckpointCodec.decode(compatible))
            for (authorized in listOf(false, true)) {
                val configured = restored.copy(sourceConfigurationKey = configurationKey, queryAuthorized = authorized)
                val upgraded = DiscoveryCheckpointCodec.encode(configured)
                assertEquals(4, ByteBuffer.wrap(upgraded).getInt(4))
                assertEquals(expected.copy(sourceConfigurationKey = configurationKey, queryAuthorized = authorized),
                    DiscoveryCheckpointCodec.decode(upgraded))
            }
        }
    }
}
