package com.easysavevideo.commons.maintenance

import com.easysavevideo.commons.rules.RuleResponseCall
import com.easysavevideo.commons.rules.RuleResponseSource
import com.easysavevideo.commons.rules.SignedJsonVideoSearchProvider
import com.easysavevideo.commons.rules.BoundedHttpRule
import com.easysavevideo.commons.rules.HttpRuleFixtures
import com.easysavevideo.commons.rules.RuleHttpEndpoint
import com.easysavevideo.commons.rules.RuleResponseFormat
import com.easysavevideo.commons.CommonsLimits
import com.easysavevideo.core.model.EpochClock
import com.easysavevideo.core.model.OperationContext
import com.easysavevideo.discovery.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

/** Signed offline fixtures exercise the original repository; no production roots or network calls. */
class ProviderRulesPackageCompatibilityTest {
    private val packages = ProviderPackageFixtures
    private val policies = RepositoryPolicyFixtures
    private val response = """{"items":[{"url":"https://example.org/rules-one","title":"One"}]}""".toByteArray()
    private val rules = """{"schemaVersion":1,"ruleVersion":1,"itemsPath":["items"],"fields":{"pageUrl":["url"],"title":["title"]}}""".toByteArray()
    private class Clock(var now: Long = ProviderPackageFixtures.NOW) : EpochClock {
        override fun nowMillis(): Long = now
    }
    private fun candidate(name: String) = CandidateUrl("https://example.org/rules-one",
        SourceReportedCandidateMetadata(title = "One", sourceName = name))
    private fun target(id: String = "rules-alpha", name: String = "Rules Alpha") = ProviderTarget.declarativeJson(
        id, "1.0", name, listOf(ProviderRuleSelfTestVector(response, listOf(candidate(name)))))
    private fun httpVectors(format: RuleResponseFormat, name: String = "HTTP Alpha"): List<ProviderRuleSelfTestVector> {
        val positive = if (format == RuleResponseFormat.JSON)
            """{"items":[{"url":"https://example.org/http-one","title":"One"}],"next":"cursor-one"}"""
        else """<main id="results"><article class="result"><a class="page" href="https://example.org/http-one"></a><h2>One</h2></article><a id="next" data-cursor="cursor-one"></a></main>"""
        val empty = if (format == RuleResponseFormat.JSON) """{"items":[]}""" else """<main id="results"></main>"""
        return listOf(ProviderRuleSelfTestVector(positive.toByteArray(), listOf(CandidateUrl("https://example.org/http-one",
            SourceReportedCandidateMetadata(title = "One", sourceName = name))), "cursor-one", false,
            ProviderRuleHttpSelfTestInput("fixed", "und", expectedEncodedPathAndQuery = "/search?q=fixed&lang=und&limit=5")),
            ProviderRuleSelfTestVector(empty.toByteArray(), emptyList(), httpRequest = ProviderRuleHttpSelfTestInput(
                "fixed", "und", "cursor-one", "/search?q=fixed&lang=und&after=cursor-one&limit=5")))
    }
    private fun httpTarget(format: RuleResponseFormat = RuleResponseFormat.JSON,
        endpoint: RuleHttpEndpoint = HttpRuleFixtures.endpoint(format), vectors: List<ProviderRuleSelfTestVector> = httpVectors(format),
        limits: CommonsLimits = CommonsLimits(pageSize = 5, maxResponseBytes = 65_536)) =
        ProviderTarget.httpCandidates("http-alpha", "1.0", "HTTP Alpha", endpoint, vectors, limits)
    private fun repository(directory: File, target: ProviderTarget, clock: Clock = Clock()) = ProviderRepository(
        directory, target, packages.verifier(target), clock, policies.verifier(target))
    private fun verified(bytes: ByteArray, target: ProviderTarget): SignedProviderPackage {
        val result = packages.verifier(target).verify(bytes, packages.NOW)
        assertTrue(result.toString(), result is ProviderPackageResult.Verified)
        return (result as ProviderPackageResult.Verified).packageValue
    }
    private fun rejected(bytes: ByteArray, target: ProviderTarget, error: ProviderPackageError) {
        assertEquals(ProviderPackageResult.Rejected(error), packages.verifier(target).verify(bytes, packages.NOW))
    }
    private fun success(result: ProviderRepositoryResult): ProviderRepositorySnapshot {
        assertTrue(result.toString(), result is ProviderRepositoryResult.Success)
        return (result as ProviderRepositoryResult.Success).snapshot
    }
    private fun failure(error: ProviderRepositoryError, result: ProviderRepositoryResult) {
        assertEquals(ProviderRepositoryResult.Failure(error), result)
    }
    private fun temp(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("esv-rules-package-test").toFile()
        try { block(directory) } finally { directory.deleteRecursively() }
    }
    private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun signedPayload(payload: ByteArray) = packages.envelope(payload, format = ProviderPackageProtocol.FORMAT_V2)
    private fun operation(clock: Clock): DiscoveryOperation {
        val cancellation = DiscoveryCancellation()
        return DiscoveryOperation(OperationContext("rules-compatibility", clock.now + 15_000, cancellation), cancellation)
    }
    /** Health evidence comes from actual signed-plan execution, never a synthetic confirmHealthy call. */
    private fun execute(repo: ProviderRepository, clock: Clock): ProviderAuthorityView {
        val provider = SignedJsonVideoSearchProvider(repo, RuleResponseSource {
            object : RuleResponseCall {
                override fun execute() = ByteArrayInputStream(response)
                override fun cancel() = Unit
            }
        }, clock)
        val request = SearchRequest(SessionStamp("rules-compatibility", 1),
            SearchProfile("rules-profile", "owned fixture", filters = SearchFilters(safeSearch = false)),
            null, provider.descriptor)
        val operation = operation(clock)
        val lease = provider.acquire(request, operation)
        return lease.use {
            val page = it.search(request, operation)
            assertTrue(page.results.isEmpty())
            assertEquals(listOf(candidate(repo.target.sourceName)), page.candidates)
            assertTrue(it.authority.isCurrent(clock.now))
            it.authority
        }
    }
    private fun promote(repo: ProviderRepository, clock: Clock) {
        val generation = repo.snapshot().generation
        repeat(ProviderHealthLimits.REQUIRED_SUCCESSES) { execute(repo, clock) }
        assertEquals(ProviderHealthPhase.HEALTHY, repo.snapshot().health.phase)
        assertNull(repo.snapshot().pendingVersion)
        assertEquals(generation, repo.snapshot().generation)
    }

    /** Owned temporary fixture only: remove the new nonce to reconstruct the exact former layout. */
    private fun legacyRulesRecord(directory: File): ByteArray {
        val file = File(directory, "provider-state.bin")
        val current = file.readBytes()
        assertEquals(4, ByteBuffer.wrap(current).getInt(4))
        val payload = current.copyOfRange(0, 16) + current.copyOfRange(32, current.size - 32)
        ByteBuffer.wrap(payload).putInt(4, 3)
        val old = payload + MessageDigest.getInstance("SHA-256").digest(payload)
        file.writeBytes(old)
        return old
    }
    private fun legacyIdentity(target: ProviderTarget, encoded: ByteArray, ruleBytes: ByteArray, generation: Long): String {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            fun text(value: String) { val bytes = value.toByteArray(Charsets.UTF_8); out.writeInt(bytes.size); out.write(bytes) }
            text("esv-signed-json-authority"); out.writeInt(1)
            text(target.providerId); text(target.providerVersion)
            out.write(MessageDigest.getInstance("SHA-256").digest(encoded))
            out.write(MessageDigest.getInstance("SHA-256").digest(ruleBytes))
            out.writeBoolean(false); out.writeLong(generation)
        }
        return MessageDigest.getInstance("SHA-256").digest(buffer.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    @Test fun legacyPayloadByteGoldensAndDefaultWireDomainsAreUnchanged() {
        assertArrayEquals(hex("000000010000001177696b696d656469612d636f6d6d6f6e7300000005302e312e300000000000000001000001a3185c4c18000001a3185d3a60010000000500000000002000000000000000003a9800000000000493e000000010"), packages.payload())
        assertArrayEquals(hex("000000010000001177696b696d656469612d636f6d6d6f6e7300000005302e312e300000000000000001000001a3185c4c18000001a3185d3a60010000000000000000000000000000000000"), policies.payload())
        val oldPackage = packages.signed()
        val oldPolicy = policies.signed()
        assertEquals(1, ByteBuffer.wrap(oldPackage).getInt(4))
        assertEquals(1, ByteBuffer.wrap(oldPolicy).getInt(4))
        val value = verified(oldPackage, ProviderTarget.LEGACY)
        assertEquals(ProviderPackageKind.COMMONS_PARAMETERS_V1, value.kind)
        assertNull(value.compiledRule); assertNull(value.ruleDigest)
        assertTrue(policies.verifier().verify(oldPolicy, policies.NOW) is RepositoryPolicyResult.Verified)
        rejected(packages.envelope(format = 2), ProviderTarget.LEGACY, ProviderPackageError.UNSUPPORTED_FORMAT)
        assertEquals(RepositoryPolicyResult.Rejected(RepositoryPolicyError.UNSUPPORTED_FORMAT),
            policies.verifier().verify(policies.envelope(format = 2), policies.NOW))
        assertEquals(8192, ProviderTarget.LEGACY.maxPackageBytes)
        assertEquals(4096, ProviderTarget.LEGACY.maxPackagePayloadBytes)
        assertEquals(8192, ProviderTarget.LEGACY.maxPolicyBytes)
        assertEquals(4096, ProviderTarget.LEGACY.maxPolicyPayloadBytes)
    }

    @Test fun completeSignedRulesAndAllExportedBytesAreDetached() {
        val target = target()
        val encoded = packages.signedRules(target, rules)
        val expectedEncoded = encoded.copyOf()
        val value = verified(encoded, target)
        encoded.fill(0)
        value.encodedBytes().fill(0)
        value.ruleDigest!!.fill(0)
        assertArrayEquals(expectedEncoded, value.encodedBytes())
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(rules), value.ruleDigest)
        assertEquals(ProviderPackageKind.DECLARATIVE_JSON_CANDIDATES_V1, value.kind)
        assertEquals(1L, value.compiledRule!!.ruleVersion)
        assertSame(target, value.policy.target)
        assertEquals(target.providerId, value.policy.providerId)
        assertEquals(target.providerVersion, value.policy.providerVersion)
    }

    @Test fun hostVectorsAreDetachedAndCannotBecomePackageSuppliedAssertions() {
        val bytes = response.copyOf()
        val expected = mutableListOf(candidate("Rules Alpha"))
        val vector = ProviderRuleSelfTestVector(bytes, expected)
        val supplied = mutableListOf(vector)
        val target = ProviderTarget.declarativeJson("rules-alpha", "1.0", "Rules Alpha", supplied)
        bytes.fill(0); expected.clear(); supplied.clear(); vector.responseBytes.fill(0)
        assertArrayEquals(response, vector.responseBytes)
        assertEquals(listOf(candidate("Rules Alpha")), vector.expectedCandidates)
        assertEquals(listOf(vector), target.selfTestVectors)
        try { (target.selfTestVectors as MutableList<*>).clear(); fail("mutable vectors") }
        catch (_: UnsupportedOperationException) { }
        try { (vector.expectedCandidates as MutableList<*>).clear(); fail("mutable expected values") }
        catch (_: UnsupportedOperationException) { }
    }

    @Test fun signatureIsCheckedBeforeMalformedRulesAndEveryPayloadByteIsAuthenticated() {
        val target = target()
        val invalidRules = "not-json".toByteArray()
        val correctlySignedInvalid = packages.signedRules(target, invalidRules)
        rejected(correctlySignedInvalid, target, ProviderPackageError.INVALID_RULES)
        val tampered = correctlySignedInvalid.copyOf().also { bytes ->
            val payloadOffset = 16 + packages.KEY_ID.length
            val payloadLength = ByteBuffer.wrap(bytes).getInt(payloadOffset - 4)
            val at = payloadOffset + payloadLength - 1
            bytes[at] = (bytes[at].toInt() xor 1).toByte()
        }
        rejected(tampered, target, ProviderPackageError.INVALID_SIGNATURE)
        val valid = packages.signedRules(target, rules)
        val payloadOffset = 16 + packages.KEY_ID.length
        val payloadLength = ByteBuffer.wrap(valid).getInt(payloadOffset - 4)
        for (offset in listOf(payloadOffset, payloadOffset + 4, payloadOffset + 12, payloadOffset + payloadLength - 1)) {
            val changed = valid.copyOf().also { it[offset] = (it[offset].toInt() xor 1).toByte() }
            rejected(changed, target, ProviderPackageError.INVALID_SIGNATURE)
        }
    }

    @Test fun signedHashVersionSchemaRuntimeAndLimitsMismatchesFailClosed() {
        val target = target()
        fun check(payload: ByteArray, error: ProviderPackageError) = rejected(signedPayload(payload), target, error)
        check(packages.rulesPayload(target, rules, ruleDigest = ByteArray(32)), ProviderPackageError.INVALID_RULES)
        check(packages.rulesPayload(target, rules, ruleVersion = 2), ProviderPackageError.INVALID_RULES)
        check(packages.rulesPayload(target, rules, ruleVersion = 0), ProviderPackageError.INVALID_RULES)
        check(packages.rulesPayload(target, rules, ruleSchemaVersion = 2), ProviderPackageError.UNSUPPORTED_SCHEMA)
        check(packages.rulesPayload(target, rules, schema = 1), ProviderPackageError.UNSUPPORTED_SCHEMA)
        check(packages.rulesPayload(target, rules, kind = 2), ProviderPackageError.UNSUPPORTED_SCHEMA)
        check(packages.rulesPayload(target, rules, specVersion = 2), ProviderPackageError.INCOMPATIBLE_RUNTIME)
        check(packages.rulesPayload(target, rules, runtimeVersion = 2), ProviderPackageError.INCOMPATIBLE_RUNTIME)
        for (minimum in listOf(0, 2)) check(packages.rulesPayload(target, rules, minimumAppVersionCode = minimum),
            ProviderPackageError.INCOMPATIBLE_RUNTIME)
        check(packages.rulesPayload(target, rules, responseBytes = 262_145), ProviderPackageError.INVALID_POLICY)
        check(packages.rulesPayload(target, rules, pageSize = 6), ProviderPackageError.INVALID_POLICY)
        check(packages.rulesPayload(target, rules, enabledByte = 2), ProviderPackageError.MALFORMED_PACKAGE)
        check(packages.rulesPayload(target, rules) + byteArrayOf(0), ProviderPackageError.MALFORMED_PACKAGE)
    }

    @Test fun explicitlyCompatibleVersionsAndCompleteRuleVersionAreAccepted() {
        val target = ProviderTarget.declarativeJson("rules-compatible", "2.0", "Rules Compatible",
            listOf(ProviderRuleSelfTestVector(response, listOf(candidate("Rules Compatible")))),
            appVersionCode = 10, specVersion = 4, runtimeVersion = 3)
        val ruleSeven = String(rules).replace("\"ruleVersion\":1", "\"ruleVersion\":7").toByteArray()
        val value = verified(signedPayload(packages.rulesPayload(target, ruleSeven,
            minimumAppVersionCode = 9, ruleVersion = 7)), target)
        assertEquals(7L, value.compiledRule!!.ruleVersion)
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(ruleSeven), value.ruleDigest)
    }

    @Test fun sameKeysDoNotPermitCrossTargetOrCrossSignatureDomainUse() {
        val alpha = target()
        val beta = target("rules-beta", "Rules Beta")
        val packageA = packages.signedRules(alpha, rules)
        val policyA = policies.signed(target = alpha)
        rejected(packageA, beta, ProviderPackageError.TARGET_MISMATCH)
        assertEquals(RepositoryPolicyResult.Rejected(RepositoryPolicyError.TARGET_MISMATCH),
            policies.verifier(beta).verify(policyA, policies.NOW))
        val sharedPolicyVerifier = RepositoryPolicyVerifier(mapOf(packages.KEY_ID to packages.keyPair.public), alpha)
        assertEquals(RepositoryPolicyResult.Rejected(RepositoryPolicyError.MALFORMED_POLICY),
            sharedPolicyVerifier.verify(packageA, packages.NOW))
        rejected(policyA, alpha, ProviderPackageError.MALFORMED_PACKAGE)
        assertEquals(ProviderPackageResult.Rejected(ProviderPackageError.UNTRUSTED_KEY),
            ProviderPackageVerifier(target = alpha).verify(packageA, packages.NOW))
        assertEquals(RepositoryPolicyResult.Rejected(RepositoryPolicyError.UNTRUSTED_KEY),
            RepositoryPolicyVerifier(target = alpha).verify(policyA, policies.NOW))
        rejected(packages.signed(), alpha, ProviderPackageError.UNSUPPORTED_FORMAT)
    }

    @Test fun v2BoundsDoNotRelaxLegacyOrPermitOversizedRuleAllocations() {
        val target = target()
        val exact = rules + ByteArray(ProviderPackageProtocol.MAX_RULE_BYTES - rules.size) { 32 }
        val encoded = packages.signedRules(target, exact)
        assertEquals(1L, verified(encoded, target).compiledRule!!.ruleVersion)
        assertTrue(encoded.size > ProviderPackageProtocol.MAX_ENVELOPE_BYTES)
        rejected(encoded, ProviderTarget.LEGACY, ProviderPackageError.PACKAGE_TOO_LARGE)
        rejected(packages.signedRules(target, exact + byteArrayOf(32)), target, ProviderPackageError.MALFORMED_PACKAGE)
        rejected(ByteArray(target.maxPackageBytes + 1), target, ProviderPackageError.PACKAGE_TOO_LARGE)
        rejected(signedPayload(ByteArray(target.maxPackagePayloadBytes + 1)), target, ProviderPackageError.MALFORMED_PACKAGE)
        for (length in listOf(Int.MIN_VALUE, -1, 0, Int.MAX_VALUE)) {
            rejected(signedPayload(packages.rulesPayload(target, rules, ruleByteLength = length)), target,
                ProviderPackageError.MALFORMED_PACKAGE)
        }
        assertEquals(24_576, target.maxPackageBytes)
        assertEquals(20_480, target.maxPackagePayloadBytes)
        assertEquals(8192, target.maxPolicyBytes)
        assertEquals(4096, target.maxPolicyPayloadBytes)
        assertEquals(RepositoryPolicyResult.Rejected(RepositoryPolicyError.POLICY_TOO_LARGE),
            policies.verifier(target).verify(ByteArray(target.maxPolicyBytes + 1), policies.NOW))
        assertEquals(RepositoryPolicyResult.Rejected(RepositoryPolicyError.MALFORMED_POLICY),
            policies.verifier(target).verify(policies.envelope(ByteArray(target.maxPolicyPayloadBytes + 1),
                format = RepositoryPolicyProtocol.FORMAT_V2), policies.NOW))
    }

    @Test fun authenticityRecoveryPreservesHistoricalEvidenceButNotCurrentTimeAuthority() {
        val target = target()
        val verifier = packages.verifier(target)
        val encoded = packages.signedRules(target, rules, expires = packages.NOW + 1)
        assertTrue(verifier.verifyAuthenticity(encoded) is ProviderPackageResult.Verified)
        assertEquals(ProviderPackageResult.Rejected(ProviderPackageError.EXPIRED),
            verifier.verify(encoded, packages.NOW + 1))
        val policy = policies.signed(target = target, expires = policies.NOW + 1)
        assertTrue(policies.verifier(target).verifyAuthenticity(policy) is RepositoryPolicyResult.Verified)
        assertEquals(RepositoryPolicyResult.Rejected(RepositoryPolicyError.EXPIRED),
            policies.verifier(target).verify(policy, policies.NOW + 1))
        assertEquals(ProviderPackageResult.Rejected(ProviderPackageError.CANCELLED), verifier.verify(encoded,
            packages.NOW) { throw IllegalStateException("private cancellation") })
    }

    @Test fun newTargetIsDisabledWithoutSignedPlanAndActualPlanSelfTestRejectsWrongMapping() = temp { dir ->
        val target = target()
        ProviderRepository(File(dir, "no-roots"), target, clock = Clock()).use { repo ->
            assertFalse(repo.snapshot().policy.enabled)
            assertEquals("unavailable", repo.ruleDescriptor().version)
            failure(ProviderRepositoryError.INVALID_PACKAGE, repo.install(packages.signedRules(target, rules)))
        }
        repository(File(dir, "signed"), target).use { repo ->
            val wrong = String(rules).replace("[\"url\"]", "[\"missing\"]").toByteArray()
            failure(ProviderRepositoryError.SELF_TEST_FAILED, repo.install(packages.signedRules(target, wrong)))
            assertEquals(0L, repo.snapshot().highestAcceptedVersion)
            assertEquals(ProviderQuarantineReason.FIXED_SELF_TEST_FAILED, repo.snapshot().quarantinedVersions.single().reason)
            assertEquals("unavailable", repo.ruleDescriptor().version)
        }
    }

    @Test fun actualPlanPromotesWithoutGenerationChangeAndClosedRequestKeepsOriginalView() = temp { dir ->
        val target = target()
        val clock = Clock()
        repository(dir, target, clock).use { repo ->
            val installed = success(repo.install(packages.signedRules(target, rules)))
            val descriptor = repo.ruleDescriptor()
            assertTrue(descriptor.version.matches(Regex("[0-9a-f]{64}")))
            assertEquals(ProviderProgressScope.DURABLE, descriptor.progressScope)
            val view = execute(repo, clock)
            assertTrue(view.isCurrent(clock.now))
            repeat(2) { execute(repo, clock) }
            assertEquals(ProviderHealthPhase.HEALTHY, repo.snapshot().health.phase)
            assertEquals(installed.generation, repo.snapshot().generation)
            assertEquals(descriptor, repo.ruleDescriptor())
            clock.now += 15_001 // An old request's deadline is not the lifetime of pending authority.
            assertTrue(view.isCurrent(clock.now))
            success(repo.installPolicy(policies.signed(target = target, enabled = false)))
            assertFalse(view.isCurrent(clock.now))
            assertEquals("unavailable", repo.ruleDescriptor().version)
        }
    }

    @Test fun pendingRecoveryAndCopiedTargetStateFailClosedWithoutErasingHighWater() = temp { dir ->
        val alpha = target()
        val alphaDir = File(dir, "alpha")
        repository(alphaDir, alpha).use { success(it.install(packages.signedRules(alpha, rules, version = 7))) }
        repository(alphaDir, alpha).use { repo ->
            assertEquals(7L, repo.snapshot().highestAcceptedVersion)
            assertEquals(ProviderRepositoryError.RECOVERY_REQUIRED, repo.snapshot().error)
            assertEquals("unavailable", repo.ruleDescriptor().version)
            success(repo.rejectCandidate(7))
            failure(ProviderRepositoryError.REPLAYED_VERSION, repo.install(packages.signedRules(alpha, rules, version = 7)))
            success(repo.install(packages.signedRules(alpha, rules, version = 8)))
        }
        repository(alphaDir, target("rules-beta", "Rules Beta")).use { repo ->
            assertEquals(ProviderRepositoryError.CORRUPT_STATE, repo.snapshot().error)
            assertFalse(repo.snapshot().policy.enabled)
        }
        repository(alphaDir, alpha).use { repo ->
            assertEquals(8L, repo.snapshot().highestAcceptedVersion)
            assertEquals(ProviderRepositoryError.RECOVERY_REQUIRED, repo.snapshot().error)
        }
    }

    @Test fun exactSignedRollbackPreservesHighWaterAndPolicyFloorAcrossReopen() = temp { dir ->
        val target = target()
        val clock = Clock()
        val first = packages.signedRules(target, rules)
        val second = packages.signedRules(target, rules, version = 2)
        var generation = 0L
        repository(dir, target, clock).use { repo ->
            success(repo.install(first)); promote(repo, clock)
            success(repo.install(second)); promote(repo, clock)
            failure(ProviderRepositoryError.ROLLBACK_NOT_AUTHORIZED, repo.rollback())
            success(repo.installPolicy(policies.signed(target = target, rollbackTargetVersion = 1,
                rollbackTargetDigest = policies.digest(second))))
            failure(ProviderRepositoryError.ROLLBACK_NOT_AUTHORIZED, repo.rollback())
            success(repo.installPolicy(policies.signed(target = target, version = 2, rollbackTargetVersion = 1,
                rollbackTargetDigest = policies.digest(first))))
            val rolled = success(repo.rollback())
            assertEquals(1L, rolled.policy.packageVersion)
            assertEquals(2L, rolled.highestAcceptedVersion)
            generation = rolled.generation
        }
        repository(dir, target, clock).use { repo ->
            assertEquals(generation, repo.snapshot().generation)
            assertEquals(1L, repo.snapshot().policy.packageVersion)
            assertEquals(2L, repo.snapshot().highestAcceptedVersion)
            assertEquals(2L, repo.snapshot().highestAcceptedPolicyVersion)
            failure(ProviderRepositoryError.REPLAYED_VERSION, repo.install(second))
            success(repo.installPolicy(policies.signed(target = target, version = 3,
                minimumSafePackageVersion = 2, revokedPackageVersions = listOf(1))))
            assertFalse(repo.snapshot().policy.enabled)
            assertEquals("unavailable", repo.ruleDescriptor().version)
            failure(ProviderRepositoryError.SECURITY_FLOOR_REGRESSION,
                repo.installPolicy(policies.signed(target = target, version = 4)))
        }
    }

    @Test fun healthyExpiredRecoveryRetainsVersionButCannotIssueRuleLease() = temp { dir ->
        val target = target()
        val clock = Clock()
        repository(dir, target, clock).use { repo ->
            success(repo.install(packages.signedRules(target, rules))); promote(repo, clock)
        }
        clock.now += 60_001
        repository(dir, target, clock).use { repo ->
            assertEquals(1L, repo.snapshot().highestAcceptedVersion)
            assertEquals(ProviderRepositoryError.EXPIRED, repo.snapshot().error)
            assertFalse(repo.snapshot().policy.enabled)
            assertEquals("unavailable", repo.ruleDescriptor().version)
            try { repo.acquireRuleExecution(repo.ruleDescriptor(), operation(clock)); fail("expired lease") }
            catch (failure: DiscoveryFailure) { assertEquals(DiscoveryError.SOURCE_CONFIGURATION, failure.code) }
        }
    }

    @Test fun maximumRuleBytesSurviveMultipleRecordSlotsPromotionAndReopen() = temp { dir ->
        val target = target()
        val clock = Clock()
        val exact = rules + ByteArray(ProviderPackageProtocol.MAX_RULE_BYTES - rules.size) { 32 }
        val first = packages.signedRules(target, exact)
        val second = packages.signedRules(target, exact, version = 2)
        var generation = 0L
        repository(dir, target, clock).use { repo ->
            success(repo.install(first)); promote(repo, clock)
            success(repo.install(second)); promote(repo, clock)
            assertEquals(2L, repo.snapshot().highestAcceptedVersion)
            assertEquals(2L, repo.snapshot().lastKnownGoodVersion)
            generation = repo.snapshot().generation
        }
        val persisted = File(dir, "provider-state.bin").readBytes()
        assertTrue(persisted.size > ProviderRepository.MAX_PACKAGE_BYTES * 5 + ProviderRepository.MAX_POLICY_BYTES)
        assertEquals(4, ByteBuffer.wrap(persisted).getInt(4)) // JSON-only incarnation layout; Commons remains format3.
        repository(dir, target, clock).use { repo ->
            assertEquals(2L, repo.snapshot().highestAcceptedVersion)
            assertEquals(2L, repo.snapshot().lastKnownGoodVersion)
            assertEquals(generation, repo.snapshot().generation)
            assertTrue(repo.snapshot().policy.enabled)
            assertNull(repo.snapshot().error)
            execute(repo, clock)
            failure(ProviderRepositoryError.REPLAYED_VERSION, repo.install(second))
        }
    }

    @Test fun signedDisableBarrierCannotBeUndoneByOlderRuleOrExactRollbackGrant() = temp { dir ->
        val target = target()
        val clock = Clock()
        val first = packages.signedRules(target, rules)
        repository(dir, target, clock).use { repo ->
            success(repo.install(first)); promote(repo, clock)
            val view = execute(repo, clock)
            success(repo.install(packages.signedRules(target, rules, version = 2, enabled = false)))
            assertFalse(view.isCurrent(clock.now))
            assertFalse(repo.snapshot().policy.enabled)
            success(repo.installPolicy(policies.signed(target = target, rollbackTargetVersion = 1,
                rollbackTargetDigest = policies.digest(first))))
            failure(ProviderRepositoryError.DISABLE_BARRIER, repo.rollback())
            failure(ProviderRepositoryError.REPLAYED_VERSION, repo.install(first))
            assertEquals(2L, repo.snapshot().highestAcceptedVersion)
            success(repo.install(packages.signedRules(target, rules, version = 3)))
            assertTrue(repo.snapshot().policy.enabled)
        }
    }

    @Test fun legacyCommonsFormatThreeReopensWithoutMigrationOrByteChanges() = temp { dir ->
        val clock = Clock()
        fun legacy() = ProviderRepository(dir, packages.verifier(), clock, policyVerifier = policies.verifier())
        legacy().use { success(it.install(packages.signed(enabled = false))) }
        val before = File(dir, "provider-state.bin").readBytes()
        assertEquals(3, ByteBuffer.wrap(before).getInt(4))
        legacy().use { repo ->
            assertEquals(1L, repo.snapshot().highestAcceptedVersion)
            assertFalse(repo.snapshot().policy.enabled)
        }
        assertArrayEquals(before, File(dir, "provider-state.bin").readBytes())
    }

    @Test fun largeLegacyJsonRecordMigratesAtomicallyAndDoesNotReuseOldCursorIdentity() = temp { dir ->
        val target = target()
        val clock = Clock()
        val exact = rules + ByteArray(ProviderPackageProtocol.MAX_RULE_BYTES - rules.size) { 32 }
        val first = packages.signedRules(target, exact)
        val second = packages.signedRules(target, exact, version = 2)
        var generation = 0L
        repository(dir, target, clock).use { repo ->
            success(repo.install(first)); promote(repo, clock)
            success(repo.install(second)); promote(repo, clock)
            generation = repo.snapshot().generation
        }
        val former = legacyRulesRecord(dir)
        assertTrue(former.size > ProviderRepository.MAX_RECORD_BYTES)
        val oldToken = legacyIdentity(target, second, exact, generation)
        var stableToken = ""
        repository(dir, target, clock).use { repo ->
            assertEquals(generation, repo.snapshot().generation)
            assertEquals(2L, repo.snapshot().highestAcceptedVersion)
            assertEquals(2L, repo.snapshot().lastKnownGoodVersion)
            assertEquals(4, ByteBuffer.wrap(File(dir, "provider-state.bin").readBytes()).getInt(4))
            assertEquals(ProviderHealthPhase.HEALTHY, repo.snapshot().health.phase)
            assertTrue(repo.snapshot().policy.enabled)
            stableToken = repo.ruleDescriptor().version
            assertNotEquals(oldToken, stableToken)
            assertNull(repo.currentRuleAuthority(ProviderDescriptor(target.providerId, oldToken,
                progressScope = ProviderProgressScope.DURABLE), clock.now))
            assertNotNull(repo.currentRuleAuthority(repo.ruleDescriptor(), clock.now))
            execute(repo, clock)
        }
        val upgraded = File(dir, "provider-state.bin").readBytes()
        assertEquals(former.size + 16, upgraded.size)
        // All signed anchors/health/generation bytes survive; only format+incarnation+checksum change.
        assertArrayEquals(former.copyOfRange(8, 16), upgraded.copyOfRange(8, 16))
        assertArrayEquals(former.copyOfRange(16, former.size - 32), upgraded.copyOfRange(32, upgraded.size - 32))
        repository(dir, target, clock).use { repo ->
            assertEquals(stableToken, repo.ruleDescriptor().version)
            assertEquals(generation, repo.snapshot().generation)
            failure(ProviderRepositoryError.REPLAYED_VERSION, repo.install(second))
        }
    }

    @Test fun pendingAndExpiredLegacyJsonMigrationNeverGrantsExecutableOrProgressView() {
        for (pending in listOf(true, false)) temp { dir ->
            val target = target()
            val clock = Clock()
            repository(dir, target, clock).use { repo ->
                success(repo.install(packages.signedRules(target, rules)))
                if (!pending) promote(repo, clock)
            }
            legacyRulesRecord(dir)
            if (!pending) clock.now += 60_001
            repository(dir, target, clock).use { repo ->
                assertEquals(1L, repo.snapshot().highestAcceptedVersion)
                assertEquals(if (pending) ProviderRepositoryError.RECOVERY_REQUIRED else ProviderRepositoryError.EXPIRED,
                    repo.snapshot().error)
                assertFalse(repo.snapshot().policy.enabled)
                assertEquals("unavailable", repo.ruleDescriptor().version)
                assertNull(repo.currentRuleAuthority(repo.ruleDescriptor(), clock.now))
                assertEquals(4, ByteBuffer.wrap(File(dir, "provider-state.bin").readBytes()).getInt(4))
            }
        }
    }

    @Test fun malformedFormatFourRecordsFailClosedWithoutReplacingEvidence() {
        for (case in listOf("truncated-incarnation", "trailing-payload", "unknown-format")) temp { dir ->
            val target = target()
            val clock = Clock()
            repository(dir, target, clock).use { repo ->
                success(repo.install(packages.signedRules(target, rules))); promote(repo, clock)
            }
            val state = File(dir, "provider-state.bin")
            val initialized = File(dir, "provider-state.initialized")
            val marker = initialized.readBytes()
            val current = state.readBytes()
            assertEquals(case, 4, ByteBuffer.wrap(current).getInt(4))
            val originalPayload = current.copyOfRange(0, current.size - 32)
            val malformedPayload = when (case) {
                // Retain all fields after the nonce; remove only its final byte.
                "truncated-incarnation" -> originalPayload.copyOfRange(0, 31) +
                    originalPayload.copyOfRange(32, originalPayload.size)
                "trailing-payload" -> originalPayload + byteArrayOf(0)
                else -> originalPayload.copyOf().also { ByteBuffer.wrap(it).putInt(4, 5) }
            }
            // Recompute the outer checksum so every case reaches structural decoding.
            val corrupt = malformedPayload + MessageDigest.getInstance("SHA-256").digest(malformedPayload)
            state.writeBytes(corrupt)
            repository(dir, target, clock).use { repo ->
                assertEquals(case, ProviderRepositoryError.CORRUPT_STATE, repo.snapshot().error)
                assertFalse(case, repo.snapshot().policy.enabled)
                assertEquals(case, "unavailable", repo.ruleDescriptor().version)
                assertNull(case, repo.currentRuleAuthority(repo.ruleDescriptor(), clock.now))
            }
            assertArrayEquals(case, corrupt, state.readBytes())
            assertArrayEquals(case, marker, initialized.readBytes())
            assertFalse(case, File(dir, "provider-state.intent").exists())
            assertFalse(case, File(dir, "provider-state.next").exists())
        }
    }

    @Test fun validOuterChecksumCannotMigrateLegacyJsonWithInvalidSignedAnchor() = temp { dir ->
        val target = target()
        val clock = Clock()
        val encoded = packages.signedRules(target, rules)
        repository(dir, target, clock).use { repo ->
            success(repo.install(encoded)); promote(repo, clock)
        }
        val former = legacyRulesRecord(dir)
        val payload = former.copyOfRange(0, former.size - 32)
        val packageOffset = (0..payload.size - encoded.size).first { start ->
            encoded.indices.all { payload[start + it] == encoded[it] }
        }
        val signatureByte = packageOffset + encoded.lastIndex
        payload[signatureByte] = (payload[signatureByte].toInt() xor 1).toByte()
        val corrupt = payload + MessageDigest.getInstance("SHA-256").digest(payload)
        val state = File(dir, "provider-state.bin")
        state.writeBytes(corrupt)
        // The outer record checksum is valid; only the authoritative inner signature is corrupt.
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(payload),
            corrupt.copyOfRange(corrupt.size - 32, corrupt.size))
        repository(dir, target, clock).use { repo ->
            assertEquals(ProviderRepositoryError.CORRUPT_STATE, repo.snapshot().error)
            assertFalse(repo.snapshot().policy.enabled)
            assertEquals("unavailable", repo.ruleDescriptor().version)
            assertNull(repo.currentRuleAuthority(repo.ruleDescriptor(), clock.now))
        }
        assertArrayEquals(corrupt, state.readBytes())
        assertEquals(3, ByteBuffer.wrap(state.readBytes()).getInt(4))
        assertTrue(File(dir, "provider-state.initialized").isFile)
        assertFalse(File(dir, "provider-state.intent").exists())
    }

    @Test fun everyMigrationWriteFaultKeepsEvidenceAndCannotBecomeFreshAuthority() {
        for (stage in ProviderWriteStage.entries) temp { dir ->
            val target = target()
            val clock = Clock()
            repository(dir, target, clock).use { repo ->
                success(repo.install(packages.signedRules(target, rules))); promote(repo, clock)
            }
            legacyRulesRecord(dir)
            var injected = false
            ProviderRepository(dir, packages.verifier(target), clock,
                ProviderPolicy(0, 0, Long.MAX_VALUE, false, target.localLimits, target), policies.verifier(target),
                { observed -> if (observed == stage) { injected = true; throw IOException("owned migration fault") } }).use { repo ->
                assertTrue(injected)
                assertFalse(repo.snapshot().policy.enabled)
                assertNotNull(repo.snapshot().error)
                assertEquals(1L, repo.snapshot().highestAcceptedVersion)
                assertNull(repo.currentRuleAuthority(repo.ruleDescriptor(), clock.now))
            }
            assertTrue(File(dir, "provider-state.bin").isFile)
            assertTrue(File(dir, "provider-state.initialized").isFile)
            assertTrue(File(dir, "provider-state.intent").isFile)
            repository(dir, target, clock).use { repo ->
                assertFalse(repo.snapshot().policy.enabled)
                assertEquals(ProviderRepositoryError.CORRUPT_STATE, repo.snapshot().error)
                assertEquals("unavailable", repo.ruleDescriptor().version)
            }
        }
    }

    @Test fun freshRepositoryIncarnationRejectsSameSignedPackageAndGenerationFromOldDirectory() = temp { dir ->
        val target = target()
        val clock = Clock()
        val encoded = packages.signedRules(target, rules)
        var firstDescriptor: ProviderDescriptor? = null
        var firstGeneration = 0L
        var firstIncarnation = byteArrayOf()
        repository(File(dir, "original"), target, clock).use { repo ->
            success(repo.install(encoded)); promote(repo, clock)
            firstDescriptor = repo.ruleDescriptor()
            firstGeneration = repo.snapshot().generation
        }
        firstIncarnation = File(File(dir, "original"), "provider-state.bin").readBytes().copyOfRange(16, 32)
        repository(File(dir, "fresh"), target, clock).use { repo ->
            success(repo.install(encoded)); promote(repo, clock)
            assertEquals(firstGeneration, repo.snapshot().generation)
            assertNotEquals(firstDescriptor!!.version, repo.ruleDescriptor().version)
            assertNull(repo.currentRuleAuthority(firstDescriptor!!, clock.now))
            val freshIncarnation = File(File(dir, "fresh"), "provider-state.bin").readBytes().copyOfRange(16, 32)
            assertFalse(firstIncarnation.contentEquals(freshIncarnation))
        }
    }

    @Test fun explicitHttpKindHasBothFormatsAndDoesNotMasqueradeAsLegacyJson() {
        for (format in RuleResponseFormat.entries) {
            val target = httpTarget(format)
            val bytes = HttpRuleFixtures.bytes(target.httpEndpoint!!)
            val encoded = packages.signedHttpRules(target, bytes)
            val value = verified(encoded, target)
            assertEquals(2, ByteBuffer.wrap(encoded).getInt(4))
            assertEquals(ProviderPackageKind.BOUNDED_HTTP_CANDIDATES_V1, value.kind)
            assertTrue(value.candidatePlan is BoundedHttpRule)
            assertNull(value.compiledRule)
            assertEquals(1L, value.candidatePlan!!.ruleVersion)
            assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(bytes), value.ruleDigest)
            assertTrue(ProviderFixedSelfTest.run(value) {})
            rejected(packages.signedRules(target, bytes), target, ProviderPackageError.UNSUPPORTED_SCHEMA)
            rejected(encoded, this.target(), ProviderPackageError.UNSUPPORTED_SCHEMA)
        }
        val legacy = verified(packages.signedRules(target(), rules), target())
        assertSame(legacy.compiledRule, legacy.candidatePlan)
    }

    @Test fun httpSignatureHashAndVersionAreVerifiedBeforeClosedRuleCompilation() {
        val target = httpTarget()
        val rule = HttpRuleFixtures.bytes(target.httpEndpoint!!)
        val malformed = packages.signedHttpRules(target, "not-json".toByteArray())
        rejected(malformed, target, ProviderPackageError.INVALID_RULES)
        val changed = malformed.copyOf().also { it[it.size - 1] = (it.last().toInt() xor 1).toByte() }
        rejected(changed, target, ProviderPackageError.INVALID_SIGNATURE)
        fun payload(digest: ByteArray = MessageDigest.getInstance("SHA-256").digest(rule), version: Long = 1) =
            packages.rulesPayload(target, rule, kind = ProviderPackageProtocol.KIND_BOUNDED_HTTP_CANDIDATES_V1,
                ruleDigest = digest, ruleVersion = version)
        rejected(signedPayload(payload(ByteArray(32))), target, ProviderPackageError.INVALID_RULES)
        rejected(signedPayload(payload(version = 2)), target, ProviderPackageError.INVALID_RULES)
        rejected(packages.signedHttpRules(target, rule, limits = CommonsLimits(pageSize = 5, maxResponseBytes = 65_537)),
            target, ProviderPackageError.INVALID_POLICY)
        val copy = packages.signedHttpRules(target, rule)
        val value = verified(copy, target)
        val saved = copy.copyOf(); copy.fill(0); value.encodedBytes().fill(0); value.ruleDigest!!.fill(0)
        assertArrayEquals(saved, value.encodedBytes())
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(rule), value.ruleDigest)
    }

    @Test fun httpEndpointAndFormatMismatchCannotEnterSignedTarget() {
        val json = httpTarget()
        val html = httpTarget(RuleResponseFormat.HTML)
        rejected(packages.signedHttpRules(json, HttpRuleFixtures.bytes(json.httpEndpoint!!, HttpRuleFixtures.htmlResponse)),
            json, ProviderPackageError.INVALID_RULES)
        rejected(packages.signedHttpRules(html, HttpRuleFixtures.bytes(html.httpEndpoint!!, HttpRuleFixtures.jsonResponse)),
            html, ProviderPackageError.INVALID_RULES)
        val moved = httpTarget(endpoint = HttpRuleFixtures.endpoint(host = "other-fixture.invalid"))
        rejected(packages.signedHttpRules(json, HttpRuleFixtures.bytes(json.httpEndpoint!!)), moved, ProviderPackageError.INVALID_RULES)
        val withCode = String(HttpRuleFixtures.bytes(json.httpEndpoint!!)).replace("\"response\":", "\"script\":\"x\",\"response\":")
        rejected(packages.signedHttpRules(json, withCode.toByteArray()), json, ProviderPackageError.INVALID_RULES)
    }

    @Test fun httpHostVectorsRequireInputsPositiveEmptyCursorAndNarrowByteLimit() {
        fun refused(action: () -> Unit) {
            try { action(); fail("Invalid HTTP target admitted") } catch (_: IllegalArgumentException) { }
        }
        val vectors = httpVectors(RuleResponseFormat.JSON)
        refused { httpTarget(vectors = listOf(vectors.first())) }
        refused { httpTarget(vectors = listOf(vectors.last())) }
        refused { httpTarget(vectors = listOf(ProviderRuleSelfTestVector(response, listOf(candidate("HTTP Alpha")))),
            limits = CommonsLimits(pageSize = 5, maxResponseBytes = 65_536)) }
        refused { httpTarget(limits = CommonsLimits(pageSize = 5, maxResponseBytes = 65_537)) }
        refused { ProviderRuleHttpSelfTestInput("x\n", "und", expectedEncodedPathAndQuery = "/search") }
        refused { ProviderRuleHttpSelfTestInput("x", "und", "\ud800", "/search") }
        refused { ProviderRuleHttpSelfTestInput("x", "und", expectedEncodedPathAndQuery = "https://other.invalid/search") }
        assertEquals(65_536L, httpTarget().localLimits.maxResponseBytes)
        assertEquals(24_576, httpTarget().maxPackageBytes)
        assertNull(target().httpEndpoint)
    }

    @Test fun httpSelfTestWrongShapeOrExpectedEncodingDoesNotAdvanceHighWater() = temp { dir ->
        for (format in RuleResponseFormat.entries) {
            val target = httpTarget(format)
            val rule = String(HttpRuleFixtures.bytes(target.httpEndpoint!!))
            val wrong = if (format == RuleResponseFormat.JSON) rule.replace("\"itemsPath\":[\"items\"]", "\"itemsPath\":[\"missing\"]")
                else rule.replace("\"id\":\"results\"", "\"id\":\"missing\"")
            repository(File(dir, format.name), target).use { repo ->
                failure(ProviderRepositoryError.SELF_TEST_FAILED, repo.install(packages.signedHttpRules(target, wrong.toByteArray())))
                assertEquals(0L, repo.snapshot().highestAcceptedVersion)
                assertEquals("unavailable", repo.ruleDescriptor().version)
            }
        }
        val vectors = httpVectors(RuleResponseFormat.JSON)
        val first = vectors.first()
        val wrong = ProviderRuleSelfTestVector(first.responseBytes, first.expectedCandidates, "cursor-one", false,
            ProviderRuleHttpSelfTestInput("fixed", "und", expectedEncodedPathAndQuery = "/search?q=wrong&lang=und&limit=5"))
        val target = httpTarget(vectors = listOf(wrong, vectors.last()))
        repository(File(dir, "wrong-encoding"), target).use { repo ->
            failure(ProviderRepositoryError.SELF_TEST_FAILED,
                repo.install(packages.signedHttpRules(target, HttpRuleFixtures.bytes(target.httpEndpoint!!))))
            assertEquals(0L, repo.snapshot().highestAcceptedVersion)
        }
    }

    @Test fun httpRecordFourCannotBeDowngradedToJsonLegacyRecordThree() = temp { dir ->
        val target = httpTarget()
        repository(dir, target).use { repo ->
            success(repo.install(packages.signedHttpRules(target, HttpRuleFixtures.bytes(target.httpEndpoint!!))))
        }
        assertEquals(4, ByteBuffer.wrap(File(dir, "provider-state.bin").readBytes()).getInt(4))
        val old = legacyRulesRecord(dir)
        repository(dir, target).use { repo ->
            assertEquals(ProviderRepositoryError.CORRUPT_STATE, repo.snapshot().error)
            assertFalse(repo.snapshot().policy.enabled)
            assertEquals("unavailable", repo.ruleDescriptor().version)
        }
        assertArrayEquals(old, File(dir, "provider-state.bin").readBytes())
    }

    @Test fun newHttpAuthorityDomainIsDistinctWhileJsonDefaultFrameIsUnchanged() {
        val packageDigest = ByteArray(32) { 1 }
        val ruleDigest = ByteArray(32) { 2 }
        val incarnation = ByteArray(16) { 3 }
        val implicit = ruleAuthorityDescriptorToken("target", "1", packageDigest, ruleDigest, null, 4, incarnation)
        val json = ruleAuthorityDescriptorToken("target", "1", packageDigest, ruleDigest, null, 4, incarnation,
            ProviderPackageKind.DECLARATIVE_JSON_CANDIDATES_V1)
        val http = ruleAuthorityDescriptorToken("target", "1", packageDigest, ruleDigest, null, 4, incarnation,
            ProviderPackageKind.BOUNDED_HTTP_CANDIDATES_V1)
        assertEquals(implicit, json)
        assertNotEquals(json, http)
        assertEquals(64, http.length)
    }

    @Test fun targetVersionsPolicyAndExecutionViewsRemainIsolated() = temp { dir ->
        val alpha = target()
        val beta = target("rules-beta", "Rules Beta")
        val clock = Clock()
        repository(File(dir, "alpha"), alpha, clock).use { a ->
            repository(File(dir, "beta"), beta, clock).use { b ->
                success(a.install(packages.signedRules(alpha, rules))); promote(a, clock)
                success(b.install(packages.signedRules(beta, rules))); promote(b, clock)
                val av = execute(a, clock); val bv = execute(b, clock)
                val betaGeneration = b.snapshot().generation
                success(a.installPolicy(policies.signed(target = alpha, revokedPackageVersions = listOf(1))))
                assertFalse(av.isCurrent(clock.now))
                assertTrue(bv.isCurrent(clock.now))
                assertEquals(betaGeneration, b.snapshot().generation)
                assertEquals(0L, b.snapshot().highestAcceptedPolicyVersion)
                assertEquals(1L, b.snapshot().highestAcceptedVersion)
                execute(b, clock)
            }
        }
    }
}
