package com.easysavevideo.app.ui.shell

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory

/** Source/resource checks supplement fixed JVM admission tests; no Android rendering claim. */
class VaultAutoLockSettingsContractTest {
    private fun path(relative: String): Path = Path.of(relative).takeIf(Files::exists) ?: Path.of("app", relative)
    private fun read(relative: String) = String(Files.readAllBytes(path(relative)), Charsets.UTF_8)
    @Test fun fourOptionsHaveExactLocaleParityAndConfirmedSemantics() {
        fun parse(locale: String): Map<String, String> {
            val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(path("src/main/res/$locale/vault_auto_lock_settings.xml").toFile()).getElementsByTagName("string")
            return (0 until nodes.length).associate { nodes.item(it).let { node ->
                node.attributes.getNamedItem("name").nodeValue to node.textContent } }
        }
        val en = parse("values"); val zh = parse("values-zh-rCN")
        assertEquals(8, en.size); assertEquals(en.keys, zh.keys)
        for (value in en.values + zh.values) assertTrue(value.isNotBlank())
        assertEquals(listOf("Immediately", "1 minute", "5 minutes", "15 minutes"),
            listOf("immediate", "one_minute", "five_minutes", "fifteen_minutes").map { en.getValue("vault_auto_lock_$it") })
        assertTrue(en.getValue("vault_auto_lock_scope").contains("leave Vault or put the app in the background"))
        assertTrue(en.getValue("vault_auto_lock_scope").contains("Restarting the app always requires unlocking"))
    }
    @Test fun compactSectionReusesVisualsSemanticsAndParentScroll() {
        val ui = read("src/main/kotlin/com/easysavevideo/app/ui/shell/VaultAutoLockSettingsDetail.kt")
        for (needed in listOf("LocalEsvVisuals.current", "heightIn(min = 48.dp)", "selectableGroup()",
            "Role.RadioButton", "onClick = null", "enabled = enabled && available", "ui_vault_disabled")) assertTrue(needed, ui.contains(needed))
        for (bad in listOf("verticalScroll", "LazyColumn", "rememberUpdatedState", "getOrCreate", "setupPassword",
            "unlockPassword", "revealEmptyCatalog", "inspect(", "Keystore", "SurfaceVisible", "LifecycleObserved", "AutoLockWindowStarted"))
            assertFalse(bad, ui.contains(bad))
    }
    @Test fun mainCapturesAndDisposesExactSectionOwnerAndDispatchesAgainstLatestSettings() {
        val main = read("src/main/kotlin/com/easysavevideo/app/MainActivity.kt")
        val section = main.substringAfter("privacy = {").substringBefore("onSources = ::openProviderManagement")
        for (needed in listOf("val policyBinding = vaultBinding", "CapturedPrivateActions(privateMode, rootInputAdmission)",
            "vaultBinding === policyBinding", "currentBinding() === policyBinding", "privateRouteIdentity === actionRouteIdentity",
            "privatePresentationOwner.inputEpoch == inputEpoch", "isCurrentSource(presentation)", "canUsePrivacy()",
            "settingsReady && persistence.settingsLoaded", "onDispose { policyActions.close() }", "policyActions.run",
            "settings = settings.copy(vaultAutoLockPolicy = accepted)")) assertTrue(needed, section.contains(needed))
        assertFalse(section.contains("rememberUpdatedState")); assertFalse(section.contains("getOrCreate"))
        assertTrue(section.contains("runtime.changePolicy(policy)"))
        assertTrue(section.contains("persistence.saveSettings(settings)"))
        assertTrue(section.indexOf("runtime.changePolicy(policy)") < section.indexOf("persistence.saveSettings(settings)"))
        assertTrue(main.contains("initializeAutoLockPolicy(loaded.vaultAutoLockPolicy)"))
    }
    @Test fun productionGateIsFalseAndReadOnlyLookupDoesNotConstructOwner() {
        val entry = read("src/main/kotlin/com/easysavevideo/app/vault/AndroidVaultRuntimeEntry.kt")
        assertTrue(entry.contains("const val ENABLED: Boolean = false"))
        val getter = entry.substringAfter("fun currentBinding()").substringBefore("@Synchronized fun getOrCreate")
        assertTrue(getter.contains("if (ENABLED) owner else null"))
        assertFalse(getter.contains("VaultRuntime(")); assertFalse(getter.contains("AndroidVaultStore("))
    }
    @Test fun liveResolverAndCheckedCommitReplaceNoUnknownKeyOrStickyWarning() {
        val store = read("src/main/kotlin/com/easysavevideo/app/storage/AndroidStores.kt")
        assertTrue(store.contains("loadedSuccessfully = false")); assertTrue(store.contains("also { loadedSuccessfully = true }"))
        assertTrue(store.contains("if (loadedSuccessfully) acceptedVaultPolicy() else null"))
        assertTrue(store.contains("if (livePolicy != null) putString(\"vault_auto_lock_policy\", livePolicy.key)"))
        assertFalse(store.contains("settings.vaultAutoLockPolicy.key"))
        val boundary = read("src/main/kotlin/com/easysavevideo/app/storage/SettingsWriteBoundary.kt")
        assertTrue(boundary.contains("synchronized(gate)")); assertTrue(boundary.contains("check(write(policy))"))
        assertTrue(boundary.contains("acceptedPolicy()"))
        assertTrue(boundary.indexOf("acceptedPolicy()") < boundary.indexOf("check(write(policy))"))
        val persistence = read("src/main/kotlin/com/easysavevideo/app/storage/BrowserPersistence.kt")
        assertTrue(persistence.contains("value.includeVaultPolicy"))
        assertTrue(persistence.contains("failureNotified.compareAndSet(false, true)"))
        assertTrue(read("src/main/res/values/settings_discovery.xml").contains("this warning does not confirm that a later write succeeded"))
    }
    @Test fun buildRegistersEveryNewSourceAndResourceContractInput() {
        val build = read("build.gradle.kts")
        for (input in listOf("vault_auto_lock_settings.xml", "VaultAutoLockSettingsDetail.kt", "SettingsWriteBoundary.kt",
            "Settings.kt", "VaultSessionContract.kt")) assertTrue(input, build.contains(input))
    }
}
