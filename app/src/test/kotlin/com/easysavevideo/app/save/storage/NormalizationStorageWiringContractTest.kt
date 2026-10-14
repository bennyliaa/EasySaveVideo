package com.easysavevideo.app.save.storage

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Source wiring checks only; descriptor/syscall tests live in the production-adapter suites. */
class NormalizationStorageWiringContractTest {
    private val source = File("src/main/kotlin/com/easysavevideo/app/save/storage/AndroidSaveStorage.kt").readText()
    private fun section(marker: String): String {
        val declaration = Regex("(?m)^    (?:override |internal |private )?(?:inline )?" + Regex.escape(marker)).find(source)
        assertNotNull("Missing $marker", declaration)
        val start = declaration!!.range.first
        // Function declarations are the stable boundary, not indentation inside a function.
        val next = Regex("\\n    (?:override |internal |private )?(?:inline )?fun ").find(source, start + marker.length)?.range?.first ?: source.length
        return source.substring(start, next)
    }
    @Test fun everyConflictingLegacyEntryChecksDurableOwnershipBeforeItsProtocolAction() {
        val methods = listOf("prepare", "commit", "inspectCommitted", "cleanup", "streamDirectory", "cleanupStream",
            "adoptStreamOutput", "stagingFile", "verificationFile", "availableBytes", "openStaging", "retainNativeWriter", "truncateStaging", "syncStaging")
        for (name in methods) {
            val body = section("fun $name(")
            val guard = body.indexOf("ensureLegacyNormalization")
            val action = body.indexOf("protocol.")
            assertTrue("$name guard precedes all legacy protocol work", guard >= 0 && action > guard)
        }
    }
    @Test fun missingCatalogParentStillChecksPersistentChildIdentity() {
        val artifact = section("fun ensureLegacyNormalizationArtifact(")
        val commit = section("fun ensureLegacyNormalizationCommit(")
        assertTrue(artifact.contains("artifact_id=? OR commit_id=?"))
        assertTrue(commit.contains("commit_id=? OR task_id=?"))
        assertTrue(artifact.contains("NormalizationLookup.Unreadable else NormalizationLookup.Absent"))
        assertTrue(commit.contains("NormalizationLookup.Unreadable else NormalizationLookup.Absent"))
    }
    @Test fun cleanupCannotBypassTheFenceThroughStreamCleanup() {
        val body = section("fun cleanup(")
        assertTrue(body.indexOf("ensureLegacyNormalizationArtifact") < body.indexOf("protocol.cleanupStream"))
        assertTrue(body.indexOf("protocol.cleanupStream") < body.indexOf("protocol.cleanup(artifact"))
    }
    @Test fun genericFenceDoesNotInspectOrDestroyFilesystemMaterial() {
        for (name in listOf("ensureLegacyNormalizationArtifact", "ensureLegacyNormalizationCommit")) {
            val body = section("fun $name(")
            assertTrue(body.contains("NormalizationLegacyFence.requireAbsent"))
            assertFalse(body.contains("protocol.cleanup")); assertFalse(body.contains("delete(")); assertFalse(body.contains("openStaging"))
        }
    }
    @Test fun aggregateReleaseGetterIsMemoryOnlyAndShutdownRetainsUnconfirmedOwner() {
        val getter = section("fun normalizationReleaseUnconfirmed(")
        assertTrue(getter.contains("normalizationFilesOwner?.releaseUnconfirmed() == true"))
        assertFalse(getter.contains("synchronized")); assertFalse(getter.contains("fileCall")); assertFalse(getter.contains("normalizationOwner()"))
        val close = section("fun close(")
        assertTrue(close.indexOf("normalizationFilesOwner?.releaseUnconfirmed()") < close.indexOf("releaseStorageOwner(helper)"))
        assertTrue(close.indexOf("normalizationFilesOwner?.hasAnyLeases()") < close.indexOf("releaseStorageOwner(helper)"))
    }    @Test fun productionRuntimeDoesNotCallTheInactiveNormalizationFactories() {
        val runtime = File("src/main/kotlin/com/easysavevideo/app/save/AndroidSaveRuntime.kt").readText()
        for (name in listOf("prepareMovRemux", "finishMovRemux", "recordNormalizationAdoptionIntent", "adoptNormalization"))
            assertFalse(runtime.contains(name))
        for (name in listOf("prepareMovRemux", "finishMovRemux", "recordNormalizationAdoptionIntent", "adoptNormalization", "cleanupNormalization")) {
            val body = section("fun $name(")
            assertTrue(body.contains("normalizationCall")); assertFalse(body.contains("= fileCall"))
        }
    }

}
