package com.easysavevideo.app.discovery

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Resource and call-site evidence only; native rendering/accessibility acceptance remains separate. */
class DiscoveryPresentationResourceContractTest {
    private fun source(path: String) = File(discoveryPresentationSourceRoot(), path).readText()
    private val adapter get() = source("ui/DiscoveryPresentationResources.kt")

    @Test fun bothResourceSetsHaveTheSameCompleteKeysAndNumberedPlaceholderContracts() {
        val en = discoveryPresentationResourceStrings("values")
        val zh = discoveryPresentationResourceStrings("values-zh-rCN")
        val wired = Regex("""stringResource\(R.string.(\w+)\)""").findAll(adapter).map { it.groupValues[1] }.toList()
        assertEquals(67, en.size)
        assertEquals(en.keys, zh.keys)
        assertEquals(en.keys, wired.toSet())
        assertEquals(wired.toSet().size, wired.size)
        val placeholders = Regex("%[0-9]+\\$(?:s|d|02d)")
        for (key in en.keys) {
            assertEquals(key, placeholders.findAll(en.getValue(key)).map { it.value }.toList(),
                placeholders.findAll(zh.getValue(key)).map { it.value }.toList())
            for (strings in listOf(en, zh)) {
                assertFalse(key, strings.getValue(key).contains('\uFFFD'))
                assertFalse(key, placeholders.replace(strings.getValue(key), "").contains('%'))
            }
            assertFalse(key, en.getValue(key).any { it in '\u4e00'..'\u9fff' })
        }
        assertEquals(" · Has a committed result page", en.getValue("discovery_presentation_committed_page"))
        assertEquals("; ", en.getValue("discovery_rights_restriction_separator"))
    }

    @Test fun bothActualAndroidAdaptersExplicitlyOverrideEveryCopyFieldAndCurrentLocale() {
        for ((function, type) in listOf("discoveryPresentationCopy" to DiscoveryPresentationCopy::class.java,
            "rightsPresentationCopy" to RightsPresentationCopy::class.java)) {
            val body = adapter.substringAfter("internal fun $function():").substringBefore("\n}")
            val fields = type.declaredFields.filter { it.type == String::class.java }.map { it.name }.toSet()
            val mapped = Regex("""(\w+) = stringResource\(R.string.\w+\)""").findAll(body).map { it.groupValues[1] }.toList()
            assertEquals(function, fields, mapped.toSet())
            assertEquals(function, fields.size, mapped.size)
            assertTrue(body.contains("val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()"))
            assertTrue(body.contains("locale = locale,"))
            assertTrue(body.contains("localizedCounts = true,"))
        }
    }

    @Test fun everyVisiblePolicyCallExplicitlySuppliesCurrentResourceCopy() {
        val screen = source("ui/DiscoveryScreen.kt")
        assertEquals(1, Regex("val presentationCopy = discoveryPresentationCopy\\(\\)").findAll(screen).count())
        assertEquals(1, Regex("val rightsCopy = rightsPresentationCopy\\(\\)").findAll(screen).count())
        for (call in listOf(
            "DiscoveryPresentationPolicy.sortLabel(order, presentationCopy)",
            "DiscoveryPresentationPolicy.statisticsText(stringResource(R.string.discovery_copy_45), statistics.currentProfile, presentationCopy)",
            "DiscoveryPresentationPolicy.statisticsText(stringResource(R.string.discovery_copy_46), statistics.allProfiles, presentationCopy)",
            "DiscoveryPresentationPolicy.retentionNotice(copy = presentationCopy)",
            "DiscoveryPresentationPolicy.providerProgressText(progress, presentationCopy)",
            "RightsPresentationPolicy.summary(result.rights, rightsCopy)",
            "RightsPresentationPolicy.details(snapshot, rightsCopy)")) assertTrue(call, screen.contains(call))
        assertEquals(7, Regex("""(?:DiscoveryPresentationPolicy\.(?:sortLabel|statisticsText|retentionNotice|providerProgressText)|RightsPresentationPolicy\.(?:summary|details))\(""")
            .findAll(screen).count())
        // The existing movable panel reads the latest content lambda, including its current copy.
        assertTrue(screen.contains("val optionsContentState = rememberUpdatedState(optionsContent)"))
        assertTrue(screen.contains("optionsContentState.value(modifier, presentation)"))
    }

    @Test fun copyResolutionHasNoRememberedControllerResultOrCommandSideEffects() {
        for (text in listOf(adapter, source("discovery/DiscoveryPresentationCopy.kt"))) {
            for (forbidden in listOf("remember(", "rememberSaveable", "LaunchedEffect", "SideEffect", "DisposableEffect",
                "AndroidDiscoveryController", "recordSeen(", "onOpen(", "onLink(", "updateSort(", "updateFilterDraft(",
                "submitFilterDraft(", "clearHistory(", "resetProgress(", "stopRecording(", "java.net.", "android.content.res.Resources")) {
                assertFalse(forbidden, text.contains(forbidden))
            }
        }
        val copy = source("discovery/DiscoveryPresentationCopy.kt")
        assertFalse(Regex("\\bvar\\b").containsMatchIn(copy))
        assertTrue(copy.contains("NumberFormat.getIntegerInstance(locale).format(value)"))
        assertFalse(copy.contains("NumberFormat.getIntegerInstance()"))
        val rights = source("discovery/RightsPresentationPolicy.kt")
        assertFalse(rights.any { it in '\u4e00'..'\u9fff' })
        val policy = source("discovery/DiscoveryPresentationPolicy.kt")
        assertFalse(policy.substringAfter("    fun retentionNotice(").any { it in '\u4e00'..'\u9fff' })
        // Resource templates are always the format source; external strings are arguments only.
        for (text in listOf(rights, policy.substringAfter("    fun retentionNotice("))) {
            val formats = Regex("""copy\.format\(([^,\n]+)""").findAll(text).map { it.groupValues[1] }.toList()
            assertTrue(formats.isNotEmpty())
            assertTrue(formats.all { it.startsWith("copy.") })
        }
    }

    @Test fun localizedDisplayKeepsExistingCardNavigationPrivacyAndControlOwners() {
        val screen = source("ui/DiscoveryScreen.kt")
        for (required in listOf(
            "active && canClearQuery() && !(options && !paneLayout.split) && localControlConfirm == null &&",
            "safeDisableConfirm == null && rightsDetails == null",
            "val openRequest = discoveryCardOpenRequest(state, result, cardNavigationLifecycle)",
            "if (currentQueryClearInteractive() && !controller.filterControlsBusy && openRequest != null)",
            "cardOpenRejected = !onOpen(openRequest)",
            "val openEnabled = active && openRequest != null && !controller.filterControlsBusy && !optionsIsModal &&",
            "localControlConfirm == null && safeDisableConfirm == null && rightsDetails == null",
            "RightsPresentationPolicy.safeLink(url)?.let { safe ->",
            "onClick = { rightsDetails = null; onLink(safe) }",
            "EsvText(discoveryPrivacyControlTitle(request)",
            "EsvText(discoveryPrivacyControlConsequence(request)",
            "onClick = { localControlActions.confirm(prompt) }, enabled = valid")) {
            assertTrue(required, screen.contains(required))
        }
    }

    @Test fun originalFiftyFourResourceNamesAndValuesRemainByteExactAfterDecoding() {
        val added = setOf(
        "discovery_notice_source_configuration_checking",
        "discovery_notice_verification_not_started",
        "discovery_notice_view_settings_changed",
        "discovery_notice_invalid_query",
        "discovery_notice_sort_changed",
        "discovery_notice_storage_retry_unavailable",
        "discovery_notice_storage_recovery_unavailable",
        "discovery_notice_control_stale",
        "discovery_notice_control_failed",
        "discovery_notice_control_current_history_cleared",
        "discovery_notice_control_all_history_cleared",
        "discovery_notice_control_provider_progress_reset",
        "discovery_presentation_duration",
        )
        val digests = mapOf(
            "values" to "7b5cef1f5b1d2c321ca848d921740d9403a6401a77b557cde35cf44932147edd",
            "values-zh-rCN" to "7a4565af23a7033be9a265ae3436ba81cfae161b3e86c553c0c955e6e4f2b828",
        )
        for ((folder, digest) in digests) {
            val all = discoveryPresentationResourceStrings(folder)
            assertTrue(all.keys.containsAll(added))
            val original = all.filterKeys { it !in added }.toSortedMap()
            assertEquals(54, original.size)
            val canonical = original.entries.joinToString("") { "${it.key}\u0000${it.value}\u0000" }
            val actual = java.security.MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            assertEquals(folder, digest, actual)
        }
    }

    @Test fun bothNoticesAndKnownDurationResolveOnlyAtTheExistingDisplaySites() {
        val screen = source("ui/DiscoveryScreen.kt")
        assertEquals(2, Regex(Regex.escape("state.notice?.let { EsvText(DiscoveryPresentationPolicy.noticeText(it, presentationCopy), style = v.captionTextStyle) }")).findAll(screen).count())
        assertEquals(1, Regex(Regex.escape("DiscoveryPresentationPolicy.durationText(it, presentationCopy)")).findAll(screen).count())
        assertTrue(screen.contains("result.durationMs?.let { DiscoveryPresentationPolicy.durationText(it, presentationCopy) } ?: stringResource(R.string.flow_duration_unknown)"))
        assertFalse(screen.contains("state.notice?.let { EsvText(it,"))
        for (effect in Regex("""(?:LaunchedEffect|DisposableEffect)\(([^)]*)\)""").findAll(screen)) {
            for (displayOnly in listOf("presentationCopy", "notice", "locale")) {
                assertFalse(effect.value, effect.groupValues[1].contains(displayOnly))
            }
        }
    }
}
