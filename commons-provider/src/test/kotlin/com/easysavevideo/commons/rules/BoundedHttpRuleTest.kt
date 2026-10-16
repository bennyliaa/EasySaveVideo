package com.easysavevideo.commons.rules

import com.easysavevideo.commons.Json
import com.easysavevideo.commons.StrictJson
import com.easysavevideo.core.model.EpochClock
import com.easysavevideo.discovery.DiscoveryError
import com.easysavevideo.discovery.DiscoveryFailure
import com.easysavevideo.discovery.DiscoveryLimits
import com.easysavevideo.discovery.ProviderDescriptor
import org.junit.Assert.*
import org.junit.Test

class BoundedHttpRuleTest {
    @Test fun explicitKind2AcceptsBothFiniteFormatsWithoutChangingKind1Grammar() {
        for (format in RuleResponseFormat.entries) {
            val endpoint = HttpRuleFixtures.endpoint(format)
            val rule = BoundedHttpRule.compile(HttpRuleFixtures.bytes(endpoint), endpoint)
            assertEquals(1L, rule.ruleVersion)
            assertEquals("BoundedHttpRule([redacted])", rule.toString())
            assertEquals(format == RuleResponseFormat.JSON, rule.response is BoundedHttpRule.Response.Json)
            RuleFixtures.fails(DiscoveryError.SOURCE_CONFIGURATION) { DeclarativeJsonRule.compile(HttpRuleFixtures.bytes(endpoint)) }
            RuleFixtures.fails(DiscoveryError.SOURCE_CONFIGURATION) { BoundedHttpRule.compile(RuleFixtures.bytes(), endpoint) }
        }
    }

    @Test fun refusesMissingAndUnknownFieldsAtEveryRequestResponseBoundary() {
        val endpoint = HttpRuleFixtures.endpoint()
        for (key in listOf("schemaVersion", "ruleVersion", "request", "response")) {
            fails(endpoint, HttpRuleFixtures.change(endpoint) { it.remove(key) })
        }
        for (key in listOf("script", "regex", "sourceName", "providerId", "rights", "limits", "videoConfirmed", "headers")) {
            fails(endpoint, HttpRuleFixtures.change(endpoint) { it[key] = Json.Str("private-value") })
            for (section in listOf("request", "response")) {
                fails(endpoint, HttpRuleFixtures.change(endpoint) {
                    it[section] = Json.Obj((it.getValue(section) as Json.Obj).fields + (key to Json.Str("private-value")))
                })
            }
        }
        for (key in listOf("endpointId", "path", "query")) {
            fails(endpoint, HttpRuleFixtures.changeRequest(endpoint) { it.remove(key) })
        }
    }

    @Test fun rejectsDuplicateKeysMalformedUtf8UnicodeAndTrailingInput() {
        val endpoint = HttpRuleFixtures.endpoint()
        val rule = String(HttpRuleFixtures.bytes(endpoint))
        for (bytes in listOf(byteArrayOf(), byteArrayOf(0xc3.toByte(), 0x28), "[]".toByteArray(),
            (rule + " trailing").toByteArray(), rule.replace("\"ruleVersion\":1", "\"ruleVersion\":1,\"ruleVersion\":1").toByteArray(),
            rule.replace("\"input\":\"query\"", "\"input\":\"query\",\"input\":\"query\"").toByteArray(),
            rule.replace("\"literal\":\"5\"", "\"literal\":\"\\ud800\"").toByteArray())) fails(endpoint, bytes)
    }

    @Test fun versionsRequireCanonicalPositiveIntegerAndSchemaOne() {
        val endpoint = HttpRuleFixtures.endpoint()
        for (value in listOf("0", "-1", "1.0", "1e0", "true", "null", "\"1\"", "9223372036854775808")) {
            fails(endpoint, HttpRuleFixtures.change(endpoint) { it["ruleVersion"] = StrictJson.read(value.toByteArray()) })
        }
        for (value in listOf("0", "2", "1.0", "1e0", "null", "\"1\"")) {
            fails(endpoint, HttpRuleFixtures.change(endpoint) { it["schemaVersion"] = StrictJson.read(value.toByteArray()) })
        }
        assertEquals(Long.MAX_VALUE, BoundedHttpRule.compile(HttpRuleFixtures.change(endpoint) {
            it["ruleVersion"] = Json.Num(Long.MAX_VALUE.toString())
        }, endpoint).ruleVersion)
    }

    @Test fun validatesEndpointIdentityAndFixedPathAtCompileTime() {
        val endpoint = HttpRuleFixtures.endpoint()
        for (id in listOf("0".repeat(64), endpoint.endpointId.uppercase(), "unknown", endpoint.endpointId + "0")) {
            fails(endpoint, HttpRuleFixtures.changeRequest(endpoint) { it["endpointId"] = Json.Str(id) })
        }
        for (path in listOf("/other", "//other.invalid/search", "https://other.invalid/search", "/./search", "/../search",
            "/%2e%2e/search", "/search?q=x", "/search#x", "/search\\x", "/${"x".repeat(256)}", "/搜索")) {
            fails(endpoint, HttpRuleFixtures.changeRequest(endpoint) { it["path"] = Json.Str(path) })
        }
        val other = HttpRuleFixtures.endpoint(host = "other-fixture.invalid")
        fails(other, HttpRuleFixtures.bytes(endpoint))
    }

    @Test fun queryEntriesAreUniqueFiniteAndExactlyOneApprovedSlotOrLiteral() {
        val endpoint = HttpRuleFixtures.endpoint()
        for (query in listOf("[]", "null", "{}", "[{}]",
            "[{\"name\":\"q\",\"input\":\"query\",\"literal\":\"5\"}]",
            "[{\"name\":\"q\",\"input\":\"locale\"}]", "[{\"name\":\"q\",\"literal\":\"5\"}]",
            "[{\"name\":\"q\",\"input\":\"query + cursor\"}]", "[{\"name\":\"token\",\"input\":\"query\"}]",
            "[{\"name\":\"q\",\"input\":\"query\"},{\"name\":\"q\",\"input\":\"query\"}]",
            "[{\"name\":\"q&x\",\"input\":\"query\"}]", "[{\"name\":\"limit\",\"literal\":\"6\"}]",
            "[{\"name\":\"limit\",\"literal\":\"\"}]", "[{\"name\":\"limit\",\"literal\":5}]",
            "[{\"name\":\"limit\",\"literal\":\"${"x".repeat(129)}\"}]")) {
            fails(endpoint, HttpRuleFixtures.changeRequest(endpoint) { it["query"] = StrictJson.read(query.toByteArray()) })
        }
    }

    @Test fun responseFormatIsSingleAndBoundToHostCatalog() {
        val json = HttpRuleFixtures.endpoint()
        val html = HttpRuleFixtures.endpoint(RuleResponseFormat.HTML)
        for (format in listOf("HTML", "xml", "javascript", "", "json|html")) {
            fails(json, HttpRuleFixtures.changeResponse(json) { it["format"] = Json.Str(format) })
        }
        fails(json, HttpRuleFixtures.bytes(json, response = HttpRuleFixtures.htmlResponse))
        fails(html, HttpRuleFixtures.bytes(html, response = HttpRuleFixtures.jsonResponse))
    }

    @Test fun htmlSelectorsAreStructuredFiniteStepsAndNeverCssOrRegex() {
        val endpoint = HttpRuleFixtures.endpoint(RuleResponseFormat.HTML)
        for (path in listOf("null", "\"main article\"", "\"a:matches(.*)\"", "[]", "[{}]",
            "[{\"axis\":\"parent\",\"tag\":\"main\"}]", "[{\"axis\":\"descendant\",\"tag\":\"*\"}]",
            "[{\"axis\":\"descendant\",\"tag\":\"script\"}]", "[{\"axis\":\"descendant\",\"tag\":\"main,div\"}]",
            "[{\"axis\":\"descendant\",\"tag\":\"main\",\"regex\":\".*\"}]",
            "[{\"axis\":\"descendant\",\"tag\":\"main\",\"id\":\"a b\"}]",
            "[{\"axis\":\"descendant\",\"tag\":\"main\",\"class\":\"${"x".repeat(65)}\"}]",
            "[${List(5) { "{\"axis\":\"child\",\"tag\":\"div\"}" }.joinToString(",")}]")) {
            fails(endpoint, HttpRuleFixtures.changeResponse(endpoint) { it["container"] = StrictJson.read(path.toByteArray()) })
        }
        for (predicate in listOf("null", "{\"name\":\"style\",\"value\":\"x\"}",
            "{\"name\":\"data-title\",\"value\":\"x\",\"regex\":true}",
            "{\"name\":\"data-title\",\"value\":\"${"x".repeat(129)}\"}")) {
            fails(endpoint, HttpRuleFixtures.changeResponse(endpoint) {
                it["container"] = StrictJson.read("[{\"axis\":\"descendant\",\"tag\":\"main\",\"attribute\":$predicate}]".toByteArray())
            })
        }
    }

    @Test fun htmlFieldExtractionOnlyAdmitsItsOwnFiniteAttributeAndScope() {
        val endpoint = HttpRuleFixtures.endpoint(RuleResponseFormat.HTML)
        for (field in listOf("pageUrl", "title", "description", "reportedDurationMs", "reportedPublishedAtEpochMillis")) {
            for (extractor in listOf("{\"path\":[],\"attribute\":\"onclick\"}", "{\"path\":[],\"text\":false}",
                "{\"path\":[],\"text\":true,\"attribute\":\"href\"}", "{\"path\":[],\"href\":true}")) {
                fails(endpoint, HttpRuleFixtures.changeResponse(endpoint) {
                    it["fields"] = Json.Obj((it.getValue("fields") as Json.Obj).fields + (field to StrictJson.read(extractor.toByteArray())))
                })
            }
        }
        fails(endpoint, HttpRuleFixtures.changeResponse(endpoint) {
            it["fields"] = StrictJson.read("{\"pageUrl\":{\"path\":[],\"text\":true}}".toByteArray())
        })
        fails(endpoint, HttpRuleFixtures.changeResponse(endpoint) {
            it["nextCursor"] = StrictJson.read("{\"path\":[],\"attribute\":\"href\"}".toByteArray())
        })
        BoundedHttpRule.compile(HttpRuleFixtures.changeResponse(endpoint) {
            it["fields"] = StrictJson.read("{\"pageUrl\":{\"path\":[],\"attribute\":\"href\"}}".toByteArray())
            it["nextCursor"] = StrictJson.read("{\"path\":[],\"attribute\":\"data-cursor\"}".toByteArray())
        }, endpoint)
    }

    @Test fun newHttpJsonUsesSameFiniteMappingsAndSourceReportedProjection() {
        val endpoint = HttpRuleFixtures.endpoint()
        val plan = BoundedHttpRule.compile(HttpRuleFixtures.bytes(endpoint), endpoint)
        val page = HttpRuleFixtures.project(plan, endpoint, """{"items":[{"url":"https://example.org/one","title":"One"}],"next":"opaque"}""".toByteArray())
        assertTrue(page.results.isEmpty())
        assertEquals("https://example.org/one", page.candidates.single().pageUrl)
        assertEquals("Fixture HTTP", page.candidates.single().sourceReported!!.sourceName)
        assertEquals("opaque", page.nextCursor)
        assertFalse(page.exhausted)
        assertNull(page.cursorExpiresAtEpochMillis)
        RuleFixtures.fails(DiscoveryError.INVALID_RESULT) {
            HttpRuleFixtures.project(plan, endpoint, """{"items":{}}""".toByteArray())
        }
    }

    @Test fun pureProjectionRequiresItsExactFormatBindingAndNeverAddsAuthority() {
        val endpoint = HttpRuleFixtures.endpoint()
        val plan = BoundedHttpRule.compile(HttpRuleFixtures.bytes(endpoint), endpoint)
        val bytes = """{"items":[]}""".toByteArray()
        RuleFixtures.fails(DiscoveryError.SOURCE_CONFIGURATION) { projectCandidateRule(plan, bytes, null,
            ProviderDescriptor("fixture-http", "1"), "Fixture HTTP", DiscoveryLimits(), EpochClock { 1000 }, RuleFixtures.operation()) {} }
        val other = HttpRuleFixtures.endpoint(host = "other.invalid")
        val otherPlan = BoundedHttpRule.compile(HttpRuleFixtures.bytes(other), other)
        val otherBinding = otherPlan.bind(other, "fixture", "und", null) {}
        RuleFixtures.fails(DiscoveryError.SOURCE_CONFIGURATION) { projectCandidateRule(plan, bytes, otherBinding,
            ProviderDescriptor("fixture-http", "1"), "Fixture HTTP", DiscoveryLimits(), EpochClock { 1000 }, RuleFixtures.operation()) {} }
        RuleFixtures.fails(DiscoveryError.RESPONSE_TOO_LARGE) {
            HttpRuleFixtures.project(plan, endpoint, ByteArray(65_537))
        }
    }

    @Test fun ruleByteBudgetIsCheckedBeforeCopyAndCompiledPlanIsDetached() {
        val endpoint = HttpRuleFixtures.endpoint()
        val bytes = HttpRuleFixtures.bytes(endpoint)
        val exact = bytes + ByteArray(16_384 - bytes.size) { 32 }
        val plan = BoundedHttpRule.compile(exact, endpoint)
        fails(endpoint, exact + byteArrayOf(32))
        exact.fill(0); bytes.fill(0)
        assertTrue(HttpRuleFixtures.project(plan, endpoint, """{"items":[]}""".toByteArray()).exhausted)
    }

    @Test fun compilePreservesCancellationDeadlineAndSanitizesOtherFailures() {
        val endpoint = HttpRuleFixtures.endpoint()
        for (at in listOf(1, 4, 12)) {
            var checks = 0
            RuleFixtures.fails(DiscoveryError.CANCELLED) {
                BoundedHttpRule.compile(HttpRuleFixtures.bytes(endpoint), endpoint) {
                    if (++checks == at) throw DiscoveryFailure(DiscoveryError.CANCELLED)
                }
            }
            assertEquals(at, checks)
        }
        RuleFixtures.fails(DiscoveryError.DEADLINE_EXCEEDED) {
            BoundedHttpRule.compile(HttpRuleFixtures.bytes(endpoint), endpoint) { throw DiscoveryFailure(DiscoveryError.DEADLINE_EXCEEDED) }
        }
        RuleFixtures.fails(DiscoveryError.CANCELLED) {
            BoundedHttpRule.compile(HttpRuleFixtures.bytes(endpoint), endpoint) { error("private input") }
        }
        try {
            Thread.currentThread().interrupt()
            RuleFixtures.fails(DiscoveryError.CANCELLED) { BoundedHttpRule.compile(HttpRuleFixtures.bytes(endpoint), endpoint) }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }

    private fun fails(endpoint: RuleHttpEndpoint, bytes: ByteArray) =
        RuleFixtures.fails(DiscoveryError.SOURCE_CONFIGURATION) { BoundedHttpRule.compile(bytes, endpoint) }
}

internal object HttpRuleFixtures {
    fun endpoint(format: RuleResponseFormat = RuleResponseFormat.JSON, host: String = "json-fixture.invalid"): RuleHttpEndpoint =
        RuleHttpEndpoint.https(host, "/search", format, setOf("en", "und"), linkedMapOf(
            "q" to RuleQueryAdmission.Input(RuleInputSlot.QUERY), "lang" to RuleQueryAdmission.Input(RuleInputSlot.LOCALE),
            "after" to RuleQueryAdmission.Input(RuleInputSlot.CURSOR), "limit" to RuleQueryAdmission.Literals(setOf("5"))))
    const val jsonResponse = """{"format":"json","itemsPath":["items"],"fields":{"pageUrl":["url"],"title":["title"]},"nextCursorPath":["next"]}"""
    const val htmlResponse = """{"format":"html","container":[{"axis":"descendant","tag":"main","id":"results"}],"items":[{"axis":"child","tag":"article","class":"result"}],"fields":{"pageUrl":{"path":[{"axis":"child","tag":"a","class":"page"}],"attribute":"href"},"title":{"path":[{"axis":"child","tag":"h2"}],"text":true},"description":{"path":[{"axis":"child","tag":"p"}],"text":true},"reportedDurationMs":{"path":[],"attribute":"data-duration-ms"},"reportedPublishedAtEpochMillis":{"path":[],"attribute":"data-published-at-ms"}},"nextCursor":{"path":[{"axis":"child","tag":"a","id":"next"}],"attribute":"data-cursor"}}"""
    fun bytes(endpoint: RuleHttpEndpoint = endpoint(), response: String = if (endpoint.format == RuleResponseFormat.JSON) jsonResponse else htmlResponse): ByteArray =
        """{"schemaVersion":1,"ruleVersion":1,"request":{"endpointId":"${endpoint.endpointId}","path":"/search","query":[{"name":"q","input":"query"},{"name":"lang","input":"locale"},{"name":"after","input":"cursor"},{"name":"limit","literal":"5"}]},"response":$response}""".toByteArray()
    fun change(endpoint: RuleHttpEndpoint, update: (MutableMap<String, Json>) -> Unit): ByteArray {
        val values = (StrictJson.read(bytes(endpoint)) as Json.Obj).fields.toMutableMap()
        update(values)
        return StrictJson.write(Json.Obj(values)).toByteArray()
    }
    fun changeRequest(endpoint: RuleHttpEndpoint, update: (MutableMap<String, Json>) -> Unit): ByteArray = change(endpoint) {
        val fields = (it.getValue("request") as Json.Obj).fields.toMutableMap(); update(fields); it["request"] = Json.Obj(fields)
    }
    fun changeResponse(endpoint: RuleHttpEndpoint, update: (MutableMap<String, Json>) -> Unit): ByteArray = change(endpoint) {
        val fields = (it.getValue("response") as Json.Obj).fields.toMutableMap(); update(fields); it["response"] = Json.Obj(fields)
    }
    fun project(plan: BoundedHttpRule, endpoint: RuleHttpEndpoint, bytes: ByteArray,
        limits: DiscoveryLimits = DiscoveryLimits(maxCandidatesPerResponse = 5), structureRecognized: () -> Unit = {}) =
        projectCandidateRule(plan, bytes, plan.bind(endpoint, "fixture", "und", null) {},
            ProviderDescriptor("fixture-http", "1"), "Fixture HTTP", limits, EpochClock { 1000 },
            RuleFixtures.operation(), structureRecognized)
}
