package com.easysavevideo.commons.rules

import com.easysavevideo.commons.Json
import com.easysavevideo.commons.StrictJson
import com.easysavevideo.core.model.EpochClock
import com.easysavevideo.core.model.OperationContext
import com.easysavevideo.core.model.ResourceBudget
import com.easysavevideo.discovery.DiscoveryCancellation
import com.easysavevideo.discovery.DiscoveryError
import com.easysavevideo.discovery.DiscoveryFailure
import com.easysavevideo.discovery.DiscoveryLimits
import com.easysavevideo.discovery.DiscoveryOperation
import com.easysavevideo.discovery.ProviderDescriptor
import org.junit.Assert.*
import org.junit.Test

class DeclarativeJsonRuleTest {
    @Test fun acceptsFiniteSchemaAndPositiveRuleVersion() {
        val rule = DeclarativeJsonRule.compile(RuleFixtures.bytes())
        assertEquals(1L, rule.ruleVersion)
        assertEquals(Long.MAX_VALUE, DeclarativeJsonRule.compile(
            RuleFixtures.bytes(version = Long.MAX_VALUE.toString())).ruleVersion)
    }

    @Test fun rejectsEveryUnknownRootOrFieldKeyIncludingAuthorityAndCode() {
        for (key in listOf("script", "regex", "request", "headers", "sourceName", "providerId", "limits", "rights", "videoConfirmed")) {
            RuleFixtures.fails(DiscoveryError.SOURCE_CONFIGURATION) {
                DeclarativeJsonRule.compile(RuleFixtures.changeRule { it[key] = Json.Str("private-value") })
            }
            RuleFixtures.fails(DiscoveryError.SOURCE_CONFIGURATION) {
                DeclarativeJsonRule.compile(RuleFixtures.bytes(fields = "\"pageUrl\":[\"url\"],\"$key\":[\"value\"]"))
            }
        }
    }

    @Test fun rejectsMissingRequiredSchemaProperties() {
        for (key in listOf("schemaVersion", "ruleVersion", "itemsPath", "fields")) {
            RuleFixtures.fails(DiscoveryError.SOURCE_CONFIGURATION) {
                DeclarativeJsonRule.compile(RuleFixtures.changeRule { it.remove(key) })
            }
        }
        RuleFixtures.fails(DiscoveryError.SOURCE_CONFIGURATION) {
            DeclarativeJsonRule.compile(RuleFixtures.bytes(fields = "\"title\":[\"title\"]"))
        }
    }

    @Test fun rejectsUnsupportedAndNonIntegralVersionsWithoutCoercion() {
        for (value in listOf("0", "2", "1.0", "1e0", "\"1\"", "null", "true")) {
            RuleFixtures.fails(DiscoveryError.SOURCE_CONFIGURATION) {
                DeclarativeJsonRule.compile(RuleFixtures.changeRule {
                    it["schemaVersion"] = StrictJson.read(value.toByteArray())
                })
            }
        }
        for (version in listOf("0", "-1", "1.0", "1e0", "\"1\"", "9223372036854775808", "null", "true")) {
            RuleFixtures.fails(DiscoveryError.SOURCE_CONFIGURATION) {
                DeclarativeJsonRule.compile(RuleFixtures.bytes(version = version))
            }
        }
    }

    @Test fun rejectsNonArrayPathsAndNonLiteralSegments() {
        for (path in listOf("null", "\"$.items[*]\"", "{}", "[true]", "[null]", "[{}]", "[[]]",
            "[-1]", "[-0]", "[0.0]", "[1e0]", "[100]", "[99999999999999999999]", "[\"\"]", "[\"a\\nb\"]")) {
            RuleFixtures.fails(DiscoveryError.SOURCE_CONFIGURATION) {
                DeclarativeJsonRule.compile(RuleFixtures.bytes(itemsPath = path))
            }
        }
    }

    @Test fun acceptsRootSelectionAndExactPathBounds() {
        DeclarativeJsonRule.compile(RuleFixtures.bytes(itemsPath = "[]"))
        DeclarativeJsonRule.compile(RuleFixtures.bytes(itemsPath = "[\"${"x".repeat(128)}\",0,1,2,3,4,5,99]"))
        RuleFixtures.fails(DiscoveryError.SOURCE_CONFIGURATION) {
            DeclarativeJsonRule.compile(RuleFixtures.bytes(itemsPath = "[\"${"x".repeat(129)}\"]"))
        }
        RuleFixtures.fails(DiscoveryError.SOURCE_CONFIGURATION) {
            DeclarativeJsonRule.compile(RuleFixtures.bytes(itemsPath = "[0,1,2,3,4,5,6,7,8]"))
        }
    }

    @Test fun validatesEveryOptionalPathInsteadOfIgnoringMalformedMappings() {
        for (field in listOf("title", "description", "reportedDurationMs", "reportedPublishedAtEpochMillis")) {
            RuleFixtures.fails(DiscoveryError.SOURCE_CONFIGURATION) {
                DeclarativeJsonRule.compile(RuleFixtures.bytes(fields = "\"pageUrl\":[\"url\"],\"$field\":null"))
            }
        }
        RuleFixtures.fails(DiscoveryError.SOURCE_CONFIGURATION) {
            DeclarativeJsonRule.compile(RuleFixtures.bytes(cursorPath = "null"))
        }
    }

    @Test fun permitsLiteralPunctuationKeysWithoutInterpretingExpressions() {
        val bytes = RuleFixtures.bytes(itemsPath = "[\"items.*[0]\"]", fields = "\"pageUrl\":[\"a.b\"]")
        val page = RuleFixtures.executor(DeclarativeJsonRule.compile(bytes)).execute(
            """{"items.*[0]":[{"a.b":"https://example.org/one"}]}""".toByteArray(), RuleFixtures.operation())
        assertEquals("https://example.org/one", page.candidates.single().pageUrl)
    }

    @Test fun enforcesRuleByteBudgetBeforeCopyOrParsing() {
        val bytes = RuleFixtures.bytes()
        val exact = bytes + ByteArray(DeclarativeJsonRule.MAX_RULE_BYTES - bytes.size) { ' '.code.toByte() }
        DeclarativeJsonRule.compile(exact)
        RuleFixtures.fails(DiscoveryError.SOURCE_CONFIGURATION) { DeclarativeJsonRule.compile(exact + byteArrayOf(32)) }
    }

    @Test fun rejectsDuplicateKeysAtRootAndInFieldMap() {
        val original = String(RuleFixtures.bytes())
        for (value in listOf(original.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1"),
            original.replace("\"pageUrl\":[\"url\"]", "\"pageUrl\":[\"url\"],\"pageUrl\":[\"other\"]"))) {
            RuleFixtures.fails(DiscoveryError.SOURCE_CONFIGURATION) { DeclarativeJsonRule.compile(value.toByteArray()) }
        }
    }

    @Test fun rejectsMalformedJsonUtf8AndUnicodeWithoutPayloadInErrors() {
        for (bytes in listOf(byteArrayOf(0xc3.toByte(), 0x28), byteArrayOf(), "{} trailing".toByteArray(),
            "[]".toByteArray(), "{\"private\":\"\\ud800\"}".toByteArray(),
            ("[".repeat(18) + "0" + "]".repeat(18)).toByteArray())) {
            RuleFixtures.fails(DiscoveryError.SOURCE_CONFIGURATION) { DeclarativeJsonRule.compile(bytes) }
        }
    }

    @Test fun compiledRuleDoesNotRetainInputBytes() {
        val bytes = RuleFixtures.bytes()
        val rule = DeclarativeJsonRule.compile(bytes)
        bytes.fill(0)
        assertEquals(1, RuleFixtures.executor(rule).execute(RuleFixtures.response(), RuleFixtures.operation()).candidates.size)
    }

    @Test fun compileChecksCancellationBeforeAndDuringParsing() {
        for (at in listOf(1, 4)) {
            var checks = 0
            RuleFixtures.fails(DiscoveryError.CANCELLED) {
                DeclarativeJsonRule.compile(RuleFixtures.bytes()) {
                    if (++checks == at) throw DiscoveryFailure(DiscoveryError.CANCELLED)
                }
            }
            assertEquals(at, checks)
        }
    }

    @Test fun compilePreservesDeadlineAndSanitizesUnexpectedCancellationFailure() {
        RuleFixtures.fails(DiscoveryError.DEADLINE_EXCEEDED) {
            DeclarativeJsonRule.compile(RuleFixtures.bytes()) { throw DiscoveryFailure(DiscoveryError.DEADLINE_EXCEEDED) }
        }
        RuleFixtures.fails(DiscoveryError.CANCELLED) {
            DeclarativeJsonRule.compile(RuleFixtures.bytes()) { throw IllegalStateException("private-rule-data") }
        }
    }

    @Test fun compileHonorsThreadInterruptionWithoutClearingIt() {
        try {
            Thread.currentThread().interrupt()
            RuleFixtures.fails(DiscoveryError.CANCELLED) { DeclarativeJsonRule.compile(RuleFixtures.bytes()) }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }

    @Test fun descriptionsNeverContainRuleOrSourceData() {
        val rule = DeclarativeJsonRule.compile(RuleFixtures.bytes(itemsPath = "[\"private-field-name\"]"))
        assertEquals("DeclarativeJsonRule([redacted])", rule.toString())
        assertEquals("DeclarativeJsonRuleExecutor([redacted])", RuleFixtures.executor(rule).toString())
    }
}

internal object RuleFixtures {
    fun bytes(
        itemsPath: String = "[\"items\"]",
        fields: String = "\"pageUrl\":[\"url\"],\"title\":[\"title\"],\"description\":[\"description\"]," +
            "\"reportedDurationMs\":[\"duration\"],\"reportedPublishedAtEpochMillis\":[\"published\"]",
        cursorPath: String? = "[\"next\"]",
        version: String = "1",
    ): ByteArray = ("{\"schemaVersion\":1,\"ruleVersion\":$version,\"itemsPath\":$itemsPath,\"fields\":{$fields}" +
        (cursorPath?.let { ",\"nextCursorPath\":$it" } ?: "") + "}").toByteArray()

    fun changeRule(change: (MutableMap<String, Json>) -> Unit): ByteArray {
        val values = (StrictJson.read(bytes()) as Json.Obj).fields.toMutableMap()
        change(values)
        return StrictJson.write(Json.Obj(values)).toByteArray()
    }

    fun response(row: String = """{"url":"https://example.org/one","title":"One"}""", next: String? = null): ByteArray =
        ("{\"items\":[$row]" + (next?.let { ",\"next\":$it" } ?: "") + "}").toByteArray()

    fun operation(maxBytes: Long? = null, deadline: Long = 2000,
        cancellation: DiscoveryCancellation = DiscoveryCancellation()): DiscoveryOperation = DiscoveryOperation(
        OperationContext("declarative-rule-test", deadline, cancellation, resourceBudget = ResourceBudget(maxBytes = maxBytes)), cancellation)

    fun executor(rule: DeclarativeJsonRule = DeclarativeJsonRule.compile(bytes()),
        limits: DiscoveryLimits = DiscoveryLimits(), clock: EpochClock = EpochClock { 1000 },
        id: String = "fixture-alpha", name: String = "Fixture Alpha"): DeclarativeJsonRuleExecutor =
        DeclarativeJsonRuleExecutor(rule, ProviderDescriptor(id, "1"), name, limits, clock)

    fun fails(code: DiscoveryError, action: () -> Unit) {
        try { action(); fail("Expected $code") }
        catch (failure: DiscoveryFailure) {
            assertEquals(code, failure.code)
            assertEquals(code.name, failure.message)
            assertNull(failure.cause)
        }
    }
}
