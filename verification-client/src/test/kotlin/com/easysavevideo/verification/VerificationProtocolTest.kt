package com.easysavevideo.verification

import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class VerificationProtocolTest {
    private fun parse(json: String = responseJson(), dynamic: Boolean = false): VerificationResult =
        VerificationProtocol.response(json.toByteArray(), REQUEST_ID, GENERATION, CANDIDATE, dynamic) {}
    private fun mutate(name: String, value: Json): String {
        val fields = StrictJson.read(responseJson().toByteArray()).objectValue().toMutableMap()
        fields[name] = value
        return StrictJson.write(Json.Obj(fields))
    }
    @Test fun requestIsExactAndCarriesNoSourceMetadata() {
        val bytes = VerificationProtocol.request(REQUEST_ID, GENERATION, CANDIDATE, 1234, false)
        val fields = StrictJson.read(bytes).objectValue()
        assertEquals(setOf("version", "requestId", "generation", "candidateUrl", "budgetMs", "dynamicAllowed"), fields.keys)
        assertEquals(CANDIDATE, fields["candidateUrl"]?.stringOrNull())
        assertEquals(1234L, fields["budgetMs"]?.longOrNull())
        assertEquals(Json.Bool(false), fields["dynamicAllowed"])
    }
    @Test fun positiveResponseHasPageEvidenceOnly() {
        val result = parse()
        assertEquals(VerificationOutcome.PAGE_EVIDENCE, result.outcome)
        assertFalse(result.playbackVerified); assertFalse(result.downloadable)
        val raw = result.toRaw()!!
        assertTrue(raw.videoConfirmed); assertEquals("Verified title", raw.title)
        assertEquals(12000L, raw.durationMs)
        assertNull(raw.sourceNamespace); assertNull(raw.sourceVideoId); assertNull(raw.description)
        assertEquals(com.easysavevideo.discovery.ResultRights(), raw.rights)
    }
    @Test fun unknownPositiveMetadataStaysNull() {
        val result = parse(responseJson(title = Json.Nil, thumbnail = Json.Nil, duration = Json.Nil))
        assertNotNull(result.toRaw()); assertNull(result.title); assertNull(result.thumbnailUrl); assertNull(result.durationMs)
    }
    @Test fun everyNegativeOutcomeCannotPromoteToRaw() {
        for (outcome in VerificationOutcome.entries.filter { it != VerificationOutcome.PAGE_EVIDENCE }) {
            val result = parse(responseJson(outcome = outcome.name, evidence = "NONE", title = Json.Nil, thumbnail = Json.Nil, duration = Json.Nil))
            assertEquals(outcome, result.outcome); assertNull(result.toRaw())
        }
    }
    @Test fun dynamicEvidenceRequiresClientOptIn() {
        val json = responseJson(evidence = "DYNAMIC_VIDEO_XHR")
        protocolFails { parse(json) }
        assertEquals(VerificationEvidence.DYNAMIC_VIDEO_XHR, parse(json, true).evidence)
    }
    @Test fun everyIdentityMismatchIsRejected() {
        for (key in listOf("requestId", "generation", "candidateUrl", "pageUrl"))
            protocolFails { parse(mutate(key, Json.Str("mismatch"))) }
    }
    @Test fun strictVersionAndDurationIntegerTokens() {
        for (value in listOf("1.0", "1e0", "-1", "9223372036854775808", "1e99999999999999999999"))
            protocolFails { parse(mutate("version", Json.Num(value))) }
        for (value in listOf("0", "-1", "86400001", "1.0", "1e3", "9223372036854775808"))
            protocolFails { parse(mutate("durationMs", Json.Num(value))) }
        protocolFails { parse(mutate("durationMs", Json.Str("1000"))) }
        assertEquals(86400000L, parse(mutate("durationMs", Json.Num("86400000"))).durationMs)
    }
    @Test fun missingUnknownDuplicateFieldsRejected() {
        val fields = StrictJson.read(responseJson().toByteArray()).objectValue()
        for (key in fields.keys) protocolFails { parse(StrictJson.write(Json.Obj(fields - key))) }
        protocolFails { parse(mutate("extra", Json.Nil)) }
        protocolFails { parse(responseJson().dropLast(1) + ",\"title\":null}") }
        protocolFails { parse(responseJson().replace("\"version\":1", "\"version\":1,\"\\u0076ersion\":1")) }
    }
    @Test fun falseFlagsCannotBeCoercedOrPromoted() {
        for (key in listOf("playbackVerified", "downloadable")) for (value in listOf(Json.Bool(true), Json.Nil, Json.Num("0"), Json.Str("false")))
            protocolFails { parse(mutate(key, value)) }
    }
    @Test fun outcomeEvidenceAndMetadataMustAgree() {
        protocolFails { parse(mutate("outcome", Json.Str("SUCCESS"))) }
        protocolFails { parse(mutate("evidence", Json.Str("JSON_LD"))) }
        protocolFails { parse(mutate("evidence", Json.Str("NONE"))) }
        protocolFails { parse(mutate("outcome", Json.Str("INSUFFICIENT"))) }
        for (key in listOf("title", "thumbnailUrl", "durationMs")) {
            val fields = StrictJson.read(responseJson(outcome = "INSUFFICIENT", evidence = "NONE", title = Json.Nil,
                thumbnail = Json.Nil, duration = Json.Nil).toByteArray()).objectValue().toMutableMap()
            fields[key] = if (key == "durationMs") Json.Num("1") else Json.Str("unexpected")
            protocolFails { parse(StrictJson.write(Json.Obj(fields))) }
        }
    }
    @Test fun titleBoundsControlsAndTypes() {
        assertEquals(512, parse(mutate("title", Json.Str("字".repeat(512)))).title!!.length)
        protocolFails { parse(mutate("title", Json.Str("字".repeat(513)))) }
        protocolFails { parse(mutate("title", Json.Str("😀".repeat(257)))) }
        for (char in listOf('\u0000', '\n', '\t', '\u007f', '\u009f'))
            protocolFails { parse(mutate("title", Json.Str("title$char"))) }
        protocolFails { parse(mutate("title", Json.Bool(false))) }
    }
    @Test fun malformedUtf8SurrogatesUnicodeEscapesAndStructureRejected() {
        val bad = listOf("\"\\uD800\"", "\"\\uDC00\"", "\"\\uD800\\u0041\"", "\"\\uＦＦＦＦ\"", "\"\\u１２３４\"",
            "{\"a\":1,}", "[".repeat(6) + "0" + "]".repeat(6), "01", "+1", "NaN", "true false", "\ufeff{}")
        for (json in bad) protocolFails { StrictJson.read(json.toByteArray()) }
        protocolFails { StrictJson.read(byteArrayOf(0xc3.toByte(), 0x28)) }
        protocolFails(VerificationError.RESPONSE_TOO_LARGE) { StrictJson.read(ByteArray(32769)) }
        assertEquals("😀", StrictJson.read("\"\\ud83d\\ude00\"".toByteArray()).stringOrNull())
    }
    @Test fun requestIdentityBudgetAndSizeAreValidated() {
        for (budget in listOf(0L, 10001L)) protocolFails(VerificationError.INVALID_REQUEST) {
            VerificationProtocol.request(REQUEST_ID, GENERATION, CANDIDATE, budget, false)
        }
        protocolFails(VerificationError.INVALID_REQUEST) { VerificationProtocol.request(REQUEST_ID.uppercase(), GENERATION, CANDIDATE, 1, false) }
        protocolFails(VerificationError.INVALID_REQUEST) { VerificationProtocol.request(REQUEST_ID, GENERATION, "https://127.0.0.1/", 1, false) }
    }
    @Test fun endpointAllowsOnlyExactPublicHttps443Api() {
        assertNotNull(endpoint()); assertNotNull(endpoint("https://verification.example.org:443/v1/verify"))
        for (url in listOf("http://verification.example.org/v1/verify", "https://verification.example.org:444/v1/verify",
            "https://verification.example.org:/v1/verify", "https://verification.example.org:0443/v1/verify",
            "https://verification.example.org/v1/verify/", "https://verification.example.org/verify", "https://verification.example.org/v1/verify?q=x",
            "https://verification.example.org/v1/verify#x", "https://user@verification.example.org/v1/verify", "https://127.0.0.1/v1/verify",
            "https://localhost/v1/verify", "https://verification.local/v1/verify", "https://verification.example.org./v1/verify")) {
            try { endpoint(url); fail("Expected endpoint denial") } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun candidatePolicyPreservesExactPublicHttpAndHttpsIdentity() {
        for (url in listOf(CANDIDATE, "http://videos.example.org/watch", "https://videos.example.org:443/watch", "http://videos.example.org:80/watch",
            "https://videos.example.org/视频?q=书")) assertEquals(url, VerificationUrlPolicy.candidate(url))
        for (url in listOf("file:///etc/passwd", "https://127.0.0.1/watch", "https://10.0.0.1/watch", "https://[::1]/watch",
            "https://user:pass@videos.example.org/watch", "https://videos.example.org:8000/watch", "https://videos.local/watch",
            "https://videos.example.org/a/../watch", "https://videos.example.org/%2e%2e/watch", "https://videos.example.org/%5cwatch",
            "https://videos.example.org/watch#secret", "https://videos.example.org/%00", "https://videos.example.org/" + "字".repeat(1400)))
            assertNull(VerificationUrlPolicy.candidate(url))
    }
    @Test fun thumbnailMustBePublicHttps() {
        for (url in listOf("http://images.example.org/a.jpg", "https://127.0.0.1/a.jpg", "data:image/png;base64,AA", "https://images.example.org:444/a.jpg"))
            protocolFails { parse(mutate("thumbnailUrl", Json.Str(url))) }
    }
    @Test fun publicDnsAnswerPolicyRejectsReservedRanges() {
        for (ip in listOf("0.0.0.0", "10.0.0.1", "127.0.0.1", "169.254.1.1", "100.64.0.1", "192.0.2.1", "198.51.100.1",
            "203.0.113.1", "198.18.0.1", "224.0.0.1", "::1", "fc00::1", "fe80::1", "2001:db8::1", "2002:808:808::1"))
            assertFalse(VerificationUrlPolicy.isPublicAddress(InetAddress.getByName(ip)))
        assertTrue(VerificationUrlPolicy.isPublicAddress(InetAddress.getByName("8.8.8.8")))
        assertTrue(VerificationUrlPolicy.isPublicAddress(InetAddress.getByName("2606:4700:4700::1111")))
    }
    @Test fun functionalObjectsAreRedacted() {
        assertFalse(endpoint().toString().contains("example.org"))
        assertFalse(parse().toString().contains("Verified title"))
        assertFalse(parse().toString().contains(GENERATION))
    }
}
