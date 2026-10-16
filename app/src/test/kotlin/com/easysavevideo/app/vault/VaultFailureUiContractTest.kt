package com.easysavevideo.app.vault

import com.easysavevideo.app.R
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

/** Source/resource contracts plus the actual finite resource mapper. No protected-pixels or real Android claim. */
class VaultFailureUiContractTest {
    private val root: File get() = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
    private fun source(name: String) = File(root, "kotlin/com/easysavevideo/app/vault/$name.kt").readText()
    private fun resources(locale: String): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(root, "res/$locale/vault_runtime.xml"))
        val strings = doc.getElementsByTagName("string")
        return (0 until strings.length).associate { index -> strings.item(index).let {
            it.attributes.getNamedItem("name").nodeValue to it.textContent
        } }
    }
    @Test fun everySafeReasonMapsToAnExplicitResourceAndUnknownIsNotGuessed() {
        assertEquals(R.string.vault_failure_device_key, vaultFailureMessage(VaultFailureReason.DEVICE_KEY_UNAVAILABLE))
        assertEquals(R.string.vault_failure_installation, vaultFailureMessage(VaultFailureReason.INSTALLATION_MISMATCH_OR_CORRUPT))
        assertEquals(R.string.vault_failure_retry_ledger, vaultFailureMessage(VaultFailureReason.RETRY_LEDGER_UNAVAILABLE_OR_CORRUPT))
        assertEquals(R.string.vault_failure_store_unreadable, vaultFailureMessage(VaultFailureReason.STORE_UNREADABLE))
        assertEquals(R.string.vault_failure_store_incomplete, vaultFailureMessage(VaultFailureReason.STORE_INCOMPLETE))
        assertEquals(R.string.vault_failure_unknown, vaultFailureMessage(VaultFailureReason.UNKNOWN))
        listOf(VaultFailure.UNAVAILABLE, VaultFailure.CORRUPT, VaultFailure.UNSUPPORTED, VaultFailure.CONFLICT,
            VaultFailure.CANCELLED, VaultFailure.DEADLINE, VaultFailure.REJECTED, VaultFailure.RELEASE_UNKNOWN).forEach {
            assertEquals(VaultFailureReason.UNKNOWN, it.safeReason()); assertFalse(it.confirmedPrimaryFailure())
        }
    }
    @Test fun englishAndChineseExplainPreservationAndNoKeyLossOrCrossInstallationRepair() {
        val en = resources("values"); val zh = resources("values-zh-rCN")
        assertEquals(en.keys, zh.keys)
        val keys = en.keys.filter { it.startsWith("vault_failure_") }
        assertEquals(8, keys.size); keys.forEach { assertTrue(en.getValue(it).isNotBlank()); assertTrue(zh.getValue(it).isNotBlank()) }
        assertTrue(en.getValue("vault_failure_ciphertext_preserved").contains("Encrypted vault data is preserved"))
        assertTrue(zh.getValue("vault_failure_ciphertext_preserved").contains("保留加密数据"))
        assertTrue(en.getValue("vault_failure_recovery_limits").contains("no supported repair"))
        assertTrue(en.getValue("vault_failure_recovery_limits").contains("uninstalling or moving to another device is not supported"))
        assertTrue(zh.getValue("vault_failure_recovery_limits").contains("恢复码不能绕过这些检查"))
        assertTrue(zh.getValue("vault_failure_recovery_limits").contains("卸载后或换设备恢复也不受支持"))
        assertTrue(en.getValue("vault_failure_unknown").contains("cause is unknown"))
    }
    @Test fun runtimeReasonReachesExistingProtectedPageAndNeverCreatesRepairOrAuthenticationAuthority() {
        val ui = source("VaultRuntimeUi"); val owner = source("AndroidVaultPresentationOwner"); val runtime = source("VaultRuntime")
        assertTrue(ui.contains("val failureReason: VaultFailureReason? = null"))
        assertTrue(ui.contains("state.status in setOf(VaultRuntimeStatus.UNAVAILABLE, VaultRuntimeStatus.RECOVERY_REQUIRED)"))
        assertTrue(ui.contains("vaultFailureMessage(state.failureReason ?: VaultFailureReason.UNKNOWN)"))
        assertTrue(ui.contains("R.string.vault_failure_ciphertext_preserved")); assertTrue(ui.contains("R.string.vault_failure_recovery_limits"))
        val failureUi = ui.substringAfter("state.status in setOf(VaultRuntimeStatus.UNAVAILABLE").substringBefore("VaultPlaybackControls")
        assertFalse(failureUi.contains("EsvButton")); assertFalse(failureUi.contains("host.beginCredential"))
        val show = runtime.substringAfter("private fun showStatus()").substringBefore("fun hostCleanupComplete")
        assertTrue(show.contains("if (generation != presentationGeneration || readLease != null) return@execute"))
        assertTrue(show.contains("if (failureSticky) presentation.presentFailure(failureReason ?: VaultFailureReason.UNKNOWN)"))
        val delivery = owner.substringAfter("override fun presentFailure").substringBefore("override fun presentStatus")
        assertTrue(delivery.contains("status = VaultRuntimeStatus.UNAVAILABLE")); assertTrue(delivery.contains("failureReason = reason"))
        assertTrue(delivery.contains("items = emptyList()")); assertTrue(delivery.contains("revision = null"))
        val cover = owner.substringAfter("override fun cover").substringBefore("fun retireOrdinarySource")
        assertTrue(cover.contains("if (latest.coverGeneration != generation) return@main"))
        assertTrue(cover.contains("rendered = latest; host")); assertFalse(cover.contains("rendered = value"))
        assertTrue(source("AndroidVaultRuntimeEntry").contains("const val ENABLED: Boolean = false"))
    }
    @Test fun trustedLeafDoesNotParseExceptionMessagesOrRecreateKeysAndKeepsPreCredentialLedgerChecks() {
        val auth = source("crypto/AndroidVaultAuthentication"); val keys = source("crypto/AndroidVaultKeystore")
        val ledger = auth.substringAfter("override fun authenticateLedger").substringBefore("override fun sealLedger")
        assertFalse(ledger.contains(".message")); assertFalse(ledger.contains("createDeviceKey"))
        assertFalse(ledger.contains("freshAes256Gcm")); assertFalse(ledger.contains("SecureRandom"))
        assertTrue(ledger.contains("KeyPermanentlyInvalidatedException")); assertTrue(ledger.contains("AEADBadTagException"))
        val existing = keys.substringAfter("private fun existing").substringBefore("private fun keyInfo")
        assertTrue(existing.contains("if (biometric) VaultFailure.UNAVAILABLE else VaultFailure.DEVICE_KEY_UNAVAILABLE"))
        assertFalse(existing.contains("createFresh")); assertFalse(existing.contains("generateKey"))
        val device = keys.substringAfter("fun deviceAead").substringBefore("Only the platform-prompt leaf")
        assertEquals(3, Regex("deviceKeyOperation \\{").findAll(device).count())
        val password = auth.substringAfter("private fun unwrapPasswordSlot").substringBefore("private fun verifyCatalog")
        assertTrue(password.indexOf("authenticateLedger(snapshot, context)") < password.indexOf("passwordAead(input"))
        val recovery = auth.substringAfter("private fun unwrapRecovery").substringBefore("private fun wrapPassword")
        assertTrue(recovery.indexOf("authenticateLedger(snapshot, context)") < recovery.indexOf("input.copyCharacters()"))
    }
}
