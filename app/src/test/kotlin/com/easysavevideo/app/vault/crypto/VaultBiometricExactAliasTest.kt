package com.easysavevideo.app.vault.crypto

import com.easysavevideo.app.vault.*
import org.junit.Assert.*
import org.junit.Test

/** Fixed fake exact-alias IO only. Authentication registry authority is tested by its owner tests. */
class VaultBiometricExactAliasTest {
    private val identity = VaultPersistentIdentity("public-vault", "public-install", "public-init",
        "esv.vault.v1.public-init.device", "esv.vault.v1.public-init.biometric")
    private class Permission : VaultBiometricAliasRetirement { override fun close() {} }
    private class Alias : AndroidVaultKeystore.BiometricAliasAccess {
        val deleted = mutableListOf<String>()
        val read = mutableListOf<String>()
        var present = true
        var deletionThrows = false
        var readThrows = false
        var keepPresent = false
        override fun containsExact(alias: String): Boolean {
            read += alias
            if (readThrows) throw IllegalStateException("fixed-read")
            return present
        }
        override fun deleteExact(alias: String) {
            deleted += alias
            if (deletionThrows) throw IllegalStateException("fixed-delete")
            if (!keepPresent) present = false
        }
    }
    @Test fun readOnlyAbsenceCheckUsesExactBiometricAliasAndNeverDeletes() {
        val io = Alias(); val leaf = AndroidVaultKeystore(io)
        assertTrue(leaf.hasBiometricAlias(identity)); io.present = false
        assertFalse(leaf.hasBiometricAlias(identity))
        assertEquals(listOf(identity.biometricKeyAlias, identity.biometricKeyAlias), io.read)
        assertTrue(io.deleted.isEmpty())
    }
    @Test fun validatorConsumesExactCapabilityBeforeDeleteAndAbsenceReadback() {
        val io = Alias(); val leaf = AndroidVaultKeystore(io); val permission = Permission()
        var consumed = false
        val validator: (VaultBiometricAliasRetirement) -> VaultPersistentIdentity = {
            assertSame(permission, it); check(!consumed); consumed = true
            assertTrue(io.deleted.isEmpty()); identity
        }
        assertEquals(AndroidVaultKeystore.AliasRetirementResult.ABSENT, leaf.deleteBiometricKey(permission, validator))
        assertEquals(listOf(identity.biometricKeyAlias), io.deleted)
        assertEquals(listOf(identity.biometricKeyAlias), io.read)
        try { leaf.deleteBiometricKey(permission, validator); fail("reused capability") }
        catch (_: IllegalStateException) { }
        assertEquals(1, io.deleted.size)
    }
    @Test fun missingAuthorityRejectsBeforeAnyAliasIo() {
        val io = Alias(); val leaf = AndroidVaultKeystore(io)
        try {
            leaf.deleteBiometricKey(Permission()) { throw VaultOperationException(VaultFailure.REJECTED) }
            fail("unregistered permission")
        } catch (expected: VaultOperationException) { assertEquals(VaultFailure.REJECTED, expected.category) }
        assertTrue(io.read.isEmpty()); assertTrue(io.deleted.isEmpty())
    }
    @Test fun unknownDeleteOrReadbackRetainsUnknownAndNeverCallsCreate() {
        for (case in 0..2) {
            val io = Alias()
            io.deletionThrows = case == 0; io.readThrows = case == 1; io.keepPresent = case == 2
            val leaf = AndroidVaultKeystore(io)
            assertEquals(AndroidVaultKeystore.AliasRetirementResult.UNKNOWN,
                leaf.deleteBiometricKey(Permission()) { identity })
            assertEquals(listOf(identity.biometricKeyAlias), io.deleted)
            assertFalse(io.deleted.contains(identity.deviceKeyAlias))
        }
    }
    @Test fun orphanNoneNeedsNoInventedGenerationAndDoesNotModifyIdentity() {
        val io = Alias(); val leaf = AndroidVaultKeystore(io)
        // The same-owner ORPHAN_NONE validator is allowed to return identity without a fabricated slot.
        assertEquals(AndroidVaultKeystore.AliasRetirementResult.ABSENT,
            leaf.deleteBiometricKey(Permission()) { identity })
        assertEquals("esv.vault.v1.public-init.biometric", identity.biometricKeyAlias)
        assertEquals(listOf(identity.biometricKeyAlias), io.deleted)
    }
}
