package com.easysavevideo.app.save.storage

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Source contract evidence only; not execution of AndroidSaveStorage or Android SQLite. */
class ConversionProducerWiringContractTest {
    private val root = "src/main/kotlin/com/easysavevideo/app/"
    private val storage get() = File(root + "save/storage/AndroidSaveStorage.kt").readText()
    private val owner get() = File(root + "save/storage/NormalizationAttemptOwner.kt").readText()
    private val attempt get() = File(root + "save/storage/OwnedDirectTranscodeAttempt.kt").readText()

    private fun before(body: String, left: String, right: String) {
        val first = body.indexOf(left); val second = body.indexOf(right)
        assertTrue("Missing $left", first >= 0); assertTrue("Missing $right", second >= 0)
        assertTrue("Expected $left before $right", first < second)
    }

    @Test fun actualSqlCasRequiresConcreteCompletionAndOnlyExactProofAndNullCheckpointProposal() {
        val cas = storage.substringAfter("override fun compareAndSetConversionProof(").substringBefore("override fun listUnfinished(")
        assertTrue(cas.contains("transaction { db ->"))
        assertTrue(cas.contains("completion as? NormalizationConversionProofCompletion"))
        assertTrue(cas.contains("owned.requireOwnedBy(normalizationOwner())"))
        assertTrue(cas.contains("expectedRevision == Long.MAX_VALUE"))
        assertTrue(cas.contains("prior != owned.expectedRecord"))
        assertTrue(cas.contains("prior.task.revision != expectedRevision"))
        assertTrue(cas.contains("next != prior.copy(task = prior.task.copy(revision = expectedRevision + 1),"))
        assertTrue(cas.contains("directConversion = owned.proof, checkpoint = null)"))
        assertTrue(cas.contains("\"task_id=? AND revision=?\""))
        assertFalse(cas.contains("directNormalization =")); assertFalse(cas.contains("delete"))
    }
    @Test fun losingFinishClaimNeverEntersWinnerCleanupAndOldCloseUsesOwnerClaim() {
        val finish = owner.substringAfter("fun finishOwnedTranscode(").substringBefore("private fun requireTranscodeFinishSnapshot(")
        before(finish, "attempt.claimFinish()", "try {")
        val close = attempt.substringAfterLast("override fun close() {").substringBefore("private class LogicalReaderClosed")
        assertTrue(close.contains("owner.claimTranscodeClose(this)"))
        assertFalse(close.contains("requestRevoke"))
        val claim = owner.substringAfter("fun claimTranscodeClose(").substringBefore("/** Release-only:")
        assertTrue(claim.contains("synchronized(ownerLock)"))
        before(claim, "attempt in retiredTranscodes", "reservations.revoke")
        assertTrue(finish.contains("retiredTranscodes.add(attempt)"))
    }
    @Test fun finishKeepsOriginalStopPrecedenceAndAcceptsOnlyTwoWholeLedgerSnapshots() {
        val check = owner.substringAfter("private fun requireTranscodeFinishSnapshot(").substringBefore("internal fun requireConversionCompletion(")
        before(check, "checkOperation(attempt.context)", "requireTranscodeScope(attempt.scope)")
        assertTrue(owner.contains("if (row != attempt.writingRow && row != proposed) fail()"))
        assertTrue(owner.contains("if (row != proposed) fail()"))
        assertTrue(owner.contains("attempt.validateFinishResources(operation)"))
        assertEquals(2, Regex("attempt\\.validateFinishResources\\(operation\\)").findAll(owner).count())
        val predicates = attempt.substringAfter("private fun requireFinishResources()").substringBefore("private fun sameFinishedTransport(")
        before(predicates, "terminalRunFailure?.let", "firstOriginalFailure?.let")
        assertFalse(predicates.contains("requireEntered")); assertFalse(predicates.contains("completedTransportFacts()"))
    }
    @Test fun syntheticTestSuccessSlotsHaveNoProductionInjectionOrAdmissionRoute() {
        assertEquals(1, Regex("actualRunOutcome = returned").findAll(attempt).count())
        assertFalse(attempt.contains("getDeclaredField(")); assertFalse(attempt.contains("Unsafe"))
        val adapter = File(root + "save/storage/AndroidControlledDirectStorage.kt").readText()
        val convert = adapter.substringAfter("override fun convert(").substringBefore("override fun inspectRecovery(")
        before(convert, "owner.prepareTranscode(", "owner.runOwnedTranscode(attempt)")
        before(convert, "owner.runOwnedTranscode(attempt)", "owner.finishOwnedTranscode(attempt)")
        assertTrue(convert.contains("selected.token ?: reject(), selected.scope"))
        assertFalse(convert.contains("actualRunOutcome"))
        val runtime = File(root + "save/AndroidSaveRuntime.kt").readText()
        assertTrue(runtime.contains("movAdmissionEnabled = false"))
        assertFalse(runtime.contains("finishOwnedTranscode("))
        assertTrue(runtime.contains("conversionExecutionEnabled = false"))
        val core = File("../modules/save/src/main/kotlin/com/easysavevideo/save/SaveCoordinator.kt").readText()
        assertTrue(core.contains("if (record.directConversion != null && !pipeline.conversionExecutionEnabled)"))
        val local = core.substringAfter("private fun runControlledLocal(").substringBefore("private fun nextRevision(")
        before(local, "if (record.directConversion != null && !pipeline.conversionExecutionEnabled)", "storage.inspectRecovery(")
        before(local, "if (!pipeline.conversionExecutionEnabled)", "storage.convert(")
        before(local, "storage.convert(", "compareAndSetConversionProof(")
        val pipeline = File("../modules/save/src/main/kotlin/com/easysavevideo/save/ControlledDirectPipeline.kt").readText()
        assertTrue(pipeline.contains("val conversionExecutionEnabled: Boolean = false"))
        assertFalse(pipeline.substringAfter("fun acceptsRawMime(").contains("video/webm"))
    }
}
