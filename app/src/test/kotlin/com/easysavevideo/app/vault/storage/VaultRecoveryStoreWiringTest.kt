package com.easysavevideo.app.vault.storage

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Structural connection regression checks, not execution of Android/SQLite or authentication. */
class VaultRecoveryStoreWiringTest {
    private fun source(name: String): String {
        val root = listOf(File("src/main/kotlin"), File("app/src/main/kotlin")).first { it.isDirectory }
        return root.resolve("com/easysavevideo/app/vault/$name.kt").readText()
    }
    @Test fun bothCredentialAdaptersAskInjectedSameAuthRegistryBeforeWriting() {
        val source = source("storage/AndroidVaultStore")
        val stage = source.substringAfter("override fun compareAndSwapCredentials(").substringBefore("override fun compareAndSwapCredentialReset(")
        val reset = source.substringAfter("override fun compareAndSwapCredentialReset(").substringBefore("private fun writeCredential(")
        listOf(stage, reset).forEach {
            assertTrue(it.indexOf("authentication.validateCredentialMutation(before, mutation, context)") >= 0)
            assertTrue(it.indexOf("authentication.validateCredentialMutation") < it.indexOf("writeCredential("))
            assertTrue(it.contains("VaultCredentialMutationValidation.requireValid"))
            assertFalse(it.contains("mutation.requireMatches"))
        }
        assertTrue(stage.contains("proposal.kind != VaultCredentialMutationKind.ACTIVATE_PASSWORD_RESET"))
        assertTrue(reset.contains("write.kind == VaultRetryWriteKind.RESET"))
        assertTrue(reset.contains("proposal.attempt == write.identity.attempt"))
    }
    @Test fun finalResetValidatesBothLedgersAndFreezesOneCredentialPlusLedgerRecord() {
        val reset = source("storage/AndroidVaultStore").substringAfter("override fun compareAndSwapCredentialReset(").substringBefore("private fun writeCredential(")
        assertTrue(reset.contains("VaultRetryMutationValidation.requireValid(data.retryLedger, write, confirmedReservation)"))
        assertTrue(reset.contains("authentication.authenticateLedger(before, context)"))
        assertTrue(reset.contains("authentication.authenticateLedger(after, context)"))
        assertTrue(reset.contains("VaultSnapshotCodec.freeze(credentialAfter.copy"))
        assertTrue(reset.contains("retryLedger = write.proposedLedger, retryLedgerEnvelope = authenticatedLedger"))
        assertEquals(1, "val committed = writeCredential(".toRegex(RegexOption.LITERAL).findAll(reset).count())
        assertFalse(reset.contains("sealLedger("))
        assertFalse(reset.contains("compareAndSwapRetry("))
    }
    @Test fun credentialsReuseExistingSingleDatabaseCasAndActualByteReadback() {
        val store = source("storage/AndroidVaultStore")
        assertEquals(1, "AndroidVaultDatabase(context)".toRegex(RegexOption.LITERAL).findAll(store).count())
        val credentialWrite = store.substringAfter("private fun writeCredential(").substringBefore("private fun write(before:")
        assertTrue(credentialWrite.contains("VaultCredentialWriteReceipt.admit"))
        assertTrue(credentialWrite.contains("val committed = write(before, after, operation, bounded, request)"))
        val accept = store.substringAfter("private fun accept(").substringBefore("private fun readBounded")
        assertTrue(accept.contains("val actual = database.readExisting()"))
        assertTrue(accept.contains("!actual.sameBytes(mutation.proposed)"))
        assertTrue(accept.contains("original.checkActive()"))
        assertTrue(accept.contains("return decode(actual)"))
    }
    @Test fun lastProofRetirementAndSqlBudgetRemainBoundedAndProductionStaysOff() {
        val store = source("storage/AndroidVaultStore")
        val helper = source("storage/VaultCredentialMutationValidation")
        assertTrue(helper.contains("if (hasPendingMutation) throw VaultOperationException(VaultFailure.INCOMPLETE)"))
        assertTrue(helper.contains("previous?.retire()"))
        assertTrue(helper.contains("previous.context.checkActive()"))
        assertTrue(store.substringAfter("override fun close()").contains("credentialWrite?.retire()"))
        assertFalse(helper.contains("HashMap"))
        assertTrue(source("AndroidVaultRuntimeEntry").contains("const val ENABLED: Boolean = false"))
    }
    @Test fun inspectOnlyRetiresConfirmedOldExactCredentialOperationBeforeClearingPending() {
        val store = source("storage/AndroidVaultStore")
        val branch = store.substringAfter("VaultSqliteWriteResult.CONFIRMED_OLD -> {").substringBefore("else -> throw")
        assertTrue(branch.contains("credentialWrite?.retireConfirmedOld(unknown, VaultSqliteWriteResult.CONFIRMED_OLD) == true"))
        assertTrue(branch.indexOf("retireConfirmedOld") < branch.indexOf("pending = null; pendingContext = null"))
        val helper = source("storage/VaultCredentialMutationValidation").substringAfter("fun retireConfirmedOld(").substringBefore("fun retire()")
        assertTrue(helper.contains("result != VaultSqliteWriteResult.CONFIRMED_OLD"))
        assertTrue(helper.contains("!expected.sameBytes(value.expected)"))
        assertTrue(helper.contains("!proposed.sameBytes(value.proposed)"))
        assertTrue(helper.contains("context.checkActive()"))
        assertTrue(helper.contains("VaultFailure.CANCELLED"))
        assertTrue(helper.contains("VaultFailure.DEADLINE"))
        assertTrue(helper.indexOf("retire()") < helper.indexOf("return true"))
    }

}
