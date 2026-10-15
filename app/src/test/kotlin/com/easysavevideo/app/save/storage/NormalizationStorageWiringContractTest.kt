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
    @Test fun sqlActiveGateUsesCurrentPolicyWhileHistoricalBindingAndTerminalStayStructural() {
        // Source wiring only. Host protocol tests execute the ownership path with a fake journal.
        val sql = File("src/main/kotlin/com/easysavevideo/app/save/storage/AndroidNormalizedCommitJournal.kt").readText()
        val active = sql.substringAfter("private fun active(").substringBefore("private fun binding(")
        val historical = sql.substringAfter("private fun binding(")
        assertTrue(active.contains("!proof.isCurrentPolicy()"))
        assertTrue(active.contains("expectedAudio == true && proof.conservation.audio == null"))
        assertTrue(active.contains("evidence.isValidFor"))
        assertFalse(historical.contains("isCurrentPolicy"))
        assertFalse(historical.contains("policyRevision"))
        val runtime = File("src/main/kotlin/com/easysavevideo/app/save/AndroidSaveRuntime.kt").readText()
        assertTrue(runtime.contains("movAdmissionEnabled = false"))
    }
    @Test fun convertedHistoricalCleanupExemptsOnlyTerminalAuthorityAndKeepsActiveFences() {
        val authority = source.substringAfter("internal fun normalizationAuthority(").substringBefore("private inner class NormalizationJournal")
        assertTrue(authority.contains("val terminal = task.task.state.isTerminal"))
        assertTrue(authority.contains("(!terminal && !conversion.isCurrentPolicy())"))
        assertTrue(authority.contains("row.proofSha256 != conversion.digest()"))
        assertTrue(authority.contains("row.sourceTransferSha256 != sourceTransferFingerprint(transfer)"))
        val journalAuthority = source.substringAfter("private fun requireNormalizationAuthority(").substringBefore("private fun normalizationSequences(")
        assertTrue(journalAuthority.contains("(!task.task.state.isTerminal && !proof.isCurrentPolicy())"))
        assertTrue(journalAuthority.contains("expectedAudio == true && proof.expectation.audio == null"))
        val sql = File("src/main/kotlin/com/easysavevideo/app/save/storage/AndroidNormalizedCommitJournal.kt").readText()
        val terminal = sql.substringAfter("private fun conversionTerminal(").substringBefore("private fun conversionPlan(")
        assertFalse(terminal.contains("isCurrentPolicy"))
        assertTrue(terminal.contains("TaskState.SUCCEEDED, TaskState.FAILED, TaskState.CANCELLED"))
        assertTrue(terminal.contains("proof.digest() != row.proofSha256"))
        assertTrue(terminal.contains("source.expectedAudio"))
        val active = sql.substringAfter("private fun active(").substringBefore("private fun binding(")
        assertTrue(active.contains("!output.conversionProof.isCurrentPolicy()"))
        val workspace = File("src/main/kotlin/com/easysavevideo/app/save/storage/NormalizationWorkspaceProtocol.kt").readText()
        val proof = workspace.substringAfter("private fun requireProof(row: NormalizationWorkspace, proof: DirectConversionProof)")
            .substringBefore("private fun boundAuthority(")
        assertTrue(proof.contains("!proof.isCurrentPolicy()"))
        val controlled = File("src/main/kotlin/com/easysavevideo/app/save/storage/AndroidControlledDirectStorage.kt").readText()
        val cleanup = controlled.substringAfter("override fun cleanupTerminal(").substringBefore("private fun history(")
        assertTrue(cleanup.contains("committer.cleanup(")); assertFalse(cleanup.contains("conversionProofMatches("))
        val runtime = File("src/main/kotlin/com/easysavevideo/app/save/AndroidSaveRuntime.kt").readText()
        assertTrue(runtime.contains("conversionExecutionEnabled = false"))
    }

}
