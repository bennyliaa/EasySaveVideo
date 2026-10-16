package com.easysavevideo.app.vault

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

/** Source contracts only; these do not establish Android Window/IME/privacy acceptance. */
class VaultCredentialUiContractTest {
    private val root: File get() = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
    private fun source(name: String) = File(root, "kotlin/com/easysavevideo/app/vault/$name.kt").readText()
    private fun resources(locale: String): Map<String, String> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(root, "res/$locale/vault_runtime.xml"))
        val nodes = document.getElementsByTagName("string")
        return (0 until nodes.length).associate { index ->
            val node = nodes.item(index)
            node.attributes.getNamedItem("name").nodeValue to node.textContent
        }
    }

    @Test fun productionEntryRemainsDisabledAndNoRawOwnerOrReceiptRemainsInUi() {
        assertTrue(source("AndroidVaultRuntimeEntry").contains("const val ENABLED: Boolean = false"))
        val page = source("VaultRuntimeUi")
        listOf("class AndroidVaultPresentationOwner", "attachSurface(", "retireSurface(", "coveredCompositionApplied(",
            "registerFrameCommitCallback", "runtime.close()", "observePresence(").forEach { assertFalse(it, page.contains(it)) }
        assertTrue(page.contains("VaultRuntimePage(host: VaultProtectedHost)"))
        assertTrue(page.contains("host.presentation.rendered"))
        assertTrue(page.contains("VaultRuntimeAvailability(onOpen: (() -> Unit)? = null)"))
        assertTrue(page.contains("if (!AndroidVaultRuntimeEntry.ENABLED)"))
        assertTrue(page.contains("if (AndroidVaultRuntimeEntry.ENABLED) onOpen()"))
    }
    @Test fun exactDialogRequestAndExistingPrivateBoundaryAreRequired() {
        val ui = source("VaultCredentialUi")
        assertTrue(ui.contains("PrivateAppDialog(requestIdentity = request"))
        assertTrue(ui.contains("isRequestCurrent = { host.requestCurrent(request) }"))
        assertTrue(ui.contains("val inputAdmission = LocalPrivateInputAdmission.current"))
        assertTrue(ui.contains("inputAdmission() && host.requestCurrent(request) && !draft.retired"))
        assertFalse(ui.contains("PrivateAppDialogEnvironment("))
        assertFalse(ui.contains("Dialog(" + "onDismissRequest"))
        assertFalse(ui.contains("isPrivateMode"))
    }
    @Test fun retirementSynchronouslyClearsAndDisposalIsIndependent() {
        val ui = source("VaultCredentialUi")
        assertTrue(ui.contains("val clear: () -> Unit = { request.clearDisplayInput(); draft.close(); revision.intValue++ }"))
        assertTrue(ui.contains("request.onRetired(clear)"))
        assertTrue(ui.contains("onDispose { retirement.close(); request.clearDisplayInput(); draft.close() }"))
        assertTrue(ui.contains("onDismissRequest = { clear(); host.cancelCredential(request) }"))
    }
    @Test fun setupUnlockConsumeHostAndNeverDeclareSuccess() {
        val ui = source("VaultCredentialUi")
        assertTrue(ui.contains("host.submitCredential(request, submission.input, submission.recoveryChoice)"))
        assertTrue(ui.contains("submission.input.close(); clear(); host.cancelCredential(request)"))
        val page = source("VaultRuntimeUi")
        listOf("host.captureAction()", "inputAdmission() && capturedAction()", "host.beginCredential(VaultCredentialMode.SETUP)",
            "host.beginCredential(VaultCredentialMode.UNLOCK)", "if (accepts()) host.revealCatalog()", "if (accepts()) host.lock()")
            .forEach { assertTrue(it, page.contains(it)) }
        listOf("runtime.setupPassword(", "runtime.unlockPassword(", "presentEmpty(", "READY_TO_REVEAL)")
            .forEach { assertFalse(it, ui.contains(it)) }
    }
    @Test fun optionalRecoveryStartsUnselectedAndBothChoicesAreExplicit() {
        val draft = source("VaultCredentialDraft"); val ui = source("VaultCredentialUi")
        assertTrue(draft.contains("var recoveryChoice: VaultRecoveryChoice? = null"))
        assertTrue(draft.contains("choose && recoveryChoice == null"))
        assertTrue(ui.contains("draft.chooseRecovery(VaultRecoveryChoice.OPTED_IN)"))
        assertTrue(ui.contains("draft.chooseRecovery(VaultRecoveryChoice.DECLINED)"))
        assertTrue(ui.contains("enabled = !setup || draft.recoveryChoice in setOf(VaultRecoveryChoice.DECLINED, VaultRecoveryChoice.OPTED_IN)"))
        assertFalse(ui.contains("vault_recovery_enable_unavailable), onClick = {}, enabled = false"))
    }
    @Test fun passwordPolicyUsesCryptoOwnerAndHasNoSecretStatePersistenceOrClipboardAccess() {
        val draft = source("VaultCredentialDraft"); val ui = source("VaultCredentialUi")
        assertTrue(draft.contains("VaultCryptoProfile.normalizeCredential(copy, VaultCryptoProfile.CredentialKind.PASSWORD)"))
        assertTrue(draft.contains("finally { normalized.fill('\\u0000') }"))
        assertTrue(draft.contains("finally { copy.fill('\\u0000') }"))
        assertTrue(ui.contains("PasswordVisualTransformation()")); assertTrue(ui.contains("autoCorrectEnabled = false"))
        assertTrue(ui.contains("LocalClipboardManager provides VaultCredentialClipboard"))
        assertTrue(ui.contains("LocalClipboard provides VaultCredentialClipboard"))
        assertTrue(ui.contains("override fun getText(): AnnotatedString? = null"))
        assertTrue(ui.contains("override suspend fun getClipEntry(): ClipEntry? = null"))
        assertTrue(ui.contains("LocalTextToolbar provides VaultCredentialTextToolbar"))
        listOf("rememberSaveable", "SavedStateHandle", "SharedPreferences", "Log.", "println(", "deriveWrappingKey(",
            "SecureRandom", "getSystemService", "KEYCODE_COPY", "setPrimaryClip").forEach {
            assertFalse(it, draft.contains(it) || ui.contains(it))
        }
    }
    @Test fun ownedDraftArraysSharePortBoundsAndCheckBeforeCopy() {
        val draft = source("VaultCredentialDraft")
        val ports = source("VaultRuntimePorts")
        assertTrue(ports.contains("const val MAX_PASSWORD_CHARS = 256"))
        assertTrue(ports.contains("const val MAX_PIN_CHARS = 12"))
        assertTrue(ports.contains("init { require(chars.size in 1..if (kind == VaultCredentialKind.PIN) MAX_PIN_CHARS else MAX_PASSWORD_CHARS) }"))
        assertTrue(draft.contains("passwordOverLimit = value.length > VaultCredentialInput.MAX_PASSWORD_CHARS"))
        assertTrue(draft.contains("confirmationOverLimit = value.length > VaultCredentialInput.MAX_PASSWORD_CHARS"))
        assertTrue(draft.contains("if (!passwordOverLimit) password = value.toCharArray()"))
        assertTrue(draft.contains("if (!confirmationOverLimit) confirmation = value.toCharArray()"))
    }
    @Test fun translationsHaveIdenticalKeysAndNoUnavailableRecoverySuccessClaim() {
        val english = resources("values"); val chinese = resources("values-zh-rCN")
        assertEquals(english.keys, chinese.keys)
        assertTrue(english.values.none { it.isBlank() }); assertTrue(chinese.values.none { it.isBlank() })
        assertTrue(english.getValue("vault_recovery_unavailable_explanation").contains("cancel"))
        assertTrue(chinese.getValue("vault_recovery_unavailable_explanation").contains("取消"))
        assertTrue(english.getValue("vault_recovery_choice_required").contains("No recovery choice"))
        assertTrue(chinese.getValue("vault_recovery_choice_required").contains("尚未选择"))
    }
    @Test fun recoveryDisplayNeverTransfersHolderDuringSpeculativeComposition() {
        val ui = source("VaultCredentialUi"); val request = source("VaultCredentialRequest")
        assertFalse(ui.contains("takeDisplayInput")); assertFalse(ui.contains("remember(request) { request.take"))
        assertTrue(ui.contains("request.copyDisplayCharacters()"))
        assertTrue(ui.contains("finally { chars?.fill('\\u0000') }"))
        assertTrue(request.contains("displayInput?.close(); displayInput = null"))
        assertFalse(source("AndroidVaultPresentationOwner").contains("VaultRecoveryInput"))
    }
    @Test fun recoveryPathsUseProtectedHostAndSeparateNewPasswordConfirmation() {
        val host = source("VaultProtectedHost"); val ui = source("VaultCredentialUi")
        listOf("runtime.unlockForRecovery", "runtime.confirmRecovery", "runtime.resetPassword")
            .forEach { assertTrue(it, host.contains(it)) }
        assertTrue(ui.contains("draft.prepareRecoveryConfirmation()"))
        assertTrue(ui.contains("draft.prepareRecoveryReset()"))
        assertTrue(ui.contains("host.continueRecoveryDisplay(request)"))
        assertTrue(ui.contains("draft.updateConfirmation"))
        val page = source("VaultRuntimeUi")
        assertTrue(page.contains("VaultCredentialMode.RECOVERY_ENROLL"))
        assertTrue(page.contains("VaultCredentialMode.RECOVERY_RESET"))
    }

    @Test fun directoryIdsAreBoundedBeforeOneUtf8Allocation() {
        val adapter = source("storage/AndroidVaultPhoneFiles")
        val start = adapter.indexOf("override fun children(tree: String): List<String>")
        val end = adapter.indexOf("override fun isChild(tree: String, child: String)", start)
        assertTrue(start >= 0 && end > start)
        val body = adapter.substring(start, end)
        val bound = body.indexOf("require(id.length <= VaultPhoneFilesProfile.MAX_ID_BYTES)")
        val encode = body.indexOf("val idBytes = id.toByteArray(Charsets.UTF_8).size")
        val byteBound = body.indexOf("require(idBytes <= VaultPhoneFilesProfile.MAX_ID_BYTES)")
        val accumulate = body.indexOf("bytes += idBytes")
        val totalBound = body.indexOf("require(bytes <= VaultCopyBatchLimits.MAX_DIRECTORY_ID_BYTES)")
        assertTrue(bound >= 0 && encode > bound && byteBound > encode && accumulate > byteBound && totalBound > accumulate)
        assertEquals(1, "id.toByteArray".toRegex(RegexOption.LITERAL).findAll(body).count())
    }
    @Test fun batchFolderLabelIsQuantityNeutralForOneOrMany() {
        val template = resources("values").getValue("vault_batch_choose_folder")
        assertEquals("Choose one folder (selected: 1)", String.format(java.util.Locale.ROOT, template, 1))
        assertEquals("Choose one folder (selected: 8)", String.format(java.util.Locale.ROOT, template, 8))
        assertEquals("为 1 项选择一个文件夹", String.format(java.util.Locale.ROOT,
            resources("values-zh-rCN").getValue("vault_batch_choose_folder"), 1))
    }

}
