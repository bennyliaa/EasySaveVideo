package com.easysavevideo.app.discovery

import com.easysavevideo.commons.maintenance.ProviderRepository
import com.easysavevideo.commons.maintenance.ProviderRepositoryError
import com.easysavevideo.commons.maintenance.ProviderRuleSelfTestVector
import com.easysavevideo.commons.maintenance.ProviderTarget
import com.easysavevideo.commons.maintenance.ProviderRuleHttpSelfTestInput
import com.easysavevideo.commons.rules.RuleHttpEndpoint
import com.easysavevideo.commons.rules.RuleResponseFormat
import com.easysavevideo.commons.rules.RuleQueryAdmission
import com.easysavevideo.commons.rules.RuleInputSlot
import com.easysavevideo.discovery.CandidateUrl
import com.easysavevideo.discovery.SourceReportedCandidateMetadata
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProviderRepositoryOwnerTargetsTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun target(id: String) = ProviderTarget.declarativeJson(id, "1", id, listOf(
        ProviderRuleSelfTestVector("{\"items\":[]}".toByteArray(),
            listOf(CandidateUrl("https://example.org/one", SourceReportedCandidateMetadata(sourceName = id))))))

    @Test fun fixedTargetsUseDistinctDirectoriesAndKeepLegacyEntryPoint() {
        val a = target("fixture-alpha"); val b = target("fixture-beta")
        val seen = mutableListOf<Pair<String, ProviderTarget>>()
        val owner = ProviderRepositoryOwner(listOf(ProviderRepositoryAdmission(a, "fixed-alpha"), ProviderRepositoryAdmission(b, "fixed-beta"))) { directory, target ->
            seen += directory.name to target
            ProviderRepository(directory, target)
        }
        val root = temporary.newFolder()
        val legacy = owner.open(root.resolve("provider-maintenance"))!!
        val first = owner.openTarget(root, a.providerId)!!
        val second = owner.openTarget(root, b.providerId)!!
        try {
            assertSame(legacy, owner.current); assertSame(legacy, owner.open(temporary.newFolder()))
            assertSame(first, owner.openTarget(temporary.newFolder(), a.providerId))
            assertSame(second, owner.openTarget(root, b.providerId))
            assertFalse(first.snapshot().policy.enabled); assertFalse(second.snapshot().policy.enabled)
            assertEquals(listOf("provider-maintenance", "fixed-alpha", "fixed-beta"), seen.map { it.first })
            assertEquals(listOf(ProviderTarget.LEGACY, a, b), seen.map { it.second })
        } finally { legacy.close(); first.close(); second.close() }
    }

    @Test fun unknownTargetsCannotCreateOrRetryDirectories() {
        var calls = 0
        val owner = ProviderRepositoryOwner(emptyList()) { directory, target -> calls++; ProviderRepository(directory, target) }
        val root = temporary.newFolder()
        assertNull(owner.openTarget(root, "../not-admitted")); assertEquals(0, calls)
        assertTrue(root.listFiles()!!.isEmpty())
    }

    @Test fun failedAndBusyTargetOpensAreRetainedWithoutResettingOtherTarget() {
        val a = target("fixture-alpha"); val b = target("fixture-beta")
        val root = temporary.newFolder()
        val blocker = ProviderRepository(root.resolve("fixed-beta"), b)
        val calls = mutableMapOf<String, Int>()
        val owner = ProviderRepositoryOwner(listOf(ProviderRepositoryAdmission(a, "fixed-alpha"), ProviderRepositoryAdmission(b, "fixed-beta"))) { directory, target ->
            calls[target.providerId] = (calls[target.providerId] ?: 0) + 1
            if (target === a) throw IllegalStateException("PRIVATE")
            ProviderRepository(directory, target)
        }
        assertNull(owner.openTarget(root, a.providerId))
        val failed = owner.openTarget(root, b.providerId)!!
        blocker.close()
        try {
            repeat(3) { assertNull(owner.openTarget(root, a.providerId)); assertSame(failed, owner.openTarget(root, b.providerId)) }
            assertEquals(mapOf(a.providerId to 1, b.providerId to 1), calls)
            assertEquals(ProviderRepositoryError.BUSY, failed.snapshot().error)
        } finally { failed.close() }
    }

    @Test fun targetCountAndDirectoryMappingAreClosedAndBounded() {
        val a = target("a"); val b = target("b"); val c = target("c")
        fun rejects(block: () -> Unit) { try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) { } }
        rejects { ProviderRepositoryAdmission(a, "../escape") }
        rejects { ProviderRepositoryAdmission(a, "provider-maintenance") }
        rejects { ProviderRepositoryAdmission(ProviderTarget.LEGACY, "other") }
        rejects { ProviderRepositoryOwner(listOf(ProviderRepositoryAdmission(a, "a"), ProviderRepositoryAdmission(b, "b"), ProviderRepositoryAdmission(c, "c"))) { directory, target -> ProviderRepository(directory, target) } }
        rejects { ProviderRepositoryOwner(listOf(ProviderRepositoryAdmission(a, "same"), ProviderRepositoryAdmission(b, "same"))) { directory, target -> ProviderRepository(directory, target) } }
        rejects { ProviderRepositoryOwner(listOf(ProviderRepositoryAdmission(a, "first"), ProviderRepositoryAdmission(a, "second"))) { directory, target -> ProviderRepository(directory, target) } }
    }
    @Test fun oneExistingOwnerAdmitsBothExplicitCandidateKindsWithoutProductionDefaults() {
        val json = target("bytes-json")
        val endpoint = RuleHttpEndpoint.https("fixture.example.org", "/search", RuleResponseFormat.HTML,
            setOf("und"), linkedMapOf("q" to RuleQueryAdmission.Input(RuleInputSlot.QUERY),
                "after" to RuleQueryAdmission.Input(RuleInputSlot.CURSOR)))
        val http = ProviderTarget.httpCandidates("http-html", "1", "HTML fixture", endpoint, listOf(
            ProviderRuleSelfTestVector("<main></main>".toByteArray(), listOf(CandidateUrl(
                "https://example.org/one", SourceReportedCandidateMetadata(sourceName = "HTML fixture"))),
                httpRequest = ProviderRuleHttpSelfTestInput("self", "und", expectedEncodedPathAndQuery = "/search?q=self")),
            ProviderRuleSelfTestVector("<main></main>".toByteArray(), emptyList(),
                httpRequest = ProviderRuleHttpSelfTestInput("self", "und", "next", "/search?q=self&after=next"))))
        val owner = ProviderRepositoryOwner(listOf(ProviderRepositoryAdmission(json, "json"),
            ProviderRepositoryAdmission(http, "http"))) { directory, target -> ProviderRepository(directory, target) }
        val root = temporary.newFolder()
        val first = owner.openTarget(root, json.providerId)!!
        val second = owner.openTarget(root, http.providerId)!!
        try {
            assertSame(first, owner.openTarget(root, json.providerId)); assertSame(second, owner.openTarget(root, http.providerId))
            assertSame(json, first.target); assertSame(http, second.target)
            assertFalse(first.snapshot().policy.enabled); assertFalse(second.snapshot().policy.enabled)
            assertNull(owner.openTarget(root, "not-configured")); assertNull(owner.current)
        } finally { first.close(); second.close() }
    }

}
