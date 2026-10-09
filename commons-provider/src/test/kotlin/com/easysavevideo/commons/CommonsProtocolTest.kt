package com.easysavevideo.commons

import java.net.InetAddress
import java.net.URI
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class CommonsProtocolTest {
    @Test fun malformedUtf16CannotCollapseDifferentQueriesIntoOneKey() {
        for (bad in listOf("\uD800", "\uD801", "\uDC00", "x\uD800y", "\uDC00\uD800")) {
            try { CommonsSearchRequest(bad); fail("Malformed UTF-16 accepted") } catch (_: IllegalArgumentException) { }
        }
        val first = CommonsSearchRequest("猫😺", safeSearchRequired = false)
        val second = CommonsSearchRequest("猫😸", safeSearchRequired = false)
        assertNotEquals(CommonsCursor.queryKey(first, 5), CommonsCursor.queryKey(second, 5))
        assertNull(CommonsUrlPolicy.page("https://commons.wikimedia.org/wiki/File:\uD800.webm"))
    }
    @Test fun recordingsAreExactBytesFromThreeActualPublicResponses() {
        val digests = mapOf(
            "big-buck-bunny-live.json" to "084ca46cc974272f8f9b4f0682667e950974de87216990c797794eadaec70add",
            "search-live-1.json" to "4f174d74e770e3fcdc5df56adb6cd1a7bd1735bd0468e0ab54647aabe53af32e",
            "search-live-2.json" to "906450951431ffe141ec465fee50787517b3cd9c6b0f9d33f490fcf428a4f138",
        )
        digests.forEach { (name, expected) ->
            val actual = MessageDigest.getInstance("SHA-256").digest(fixture(name)).joinToString("") { "%02x".format(it) }
            assertEquals(expected, actual)
        }
    }
    @Test fun endpointPolicyIsExactHttpsHostPathAndNoCredentials() {
        CommonsUrlPolicy.requireApi(URI("https://commons.wikimedia.org/w/api.php?action=query"))
        for (url in listOf(
            "http://commons.wikimedia.org/w/api.php?action=query",
            "https://commons.wikimedia.org.evil.invalid/w/api.php?action=query",
            "https://commons.wikimedia.org@evil.invalid/w/api.php?action=query",
            "https://user@commons.wikimedia.org/w/api.php?action=query",
            "https://commons.wikimedia.org:444/w/api.php?action=query",
            "https://commons.wikimedia.org/w/../api.php?action=query",
            "https://commons.wikimedia.org/w/api.php?action=query#fragment",
            "https://commons.wikimedia.org/w/api.php",
            "https://upload.wikimedia.org/w/api.php?action=query",
        )) fails(CommonsError.UNSAFE_URL) { CommonsUrlPolicy.requireApi(URI(url)) }
    }
    @Test fun onlyKnownRasterThumbnailHostsAndTrackingParametersAreAccepted() {
        val path = "/wikipedia/commons/thumb/c/c0/A.webm/320px-A.webm.jpg"
        assertNotNull(CommonsUrlPolicy.thumbnail("https://thumb.wikimedia.org$path"))
        assertNotNull(CommonsUrlPolicy.thumbnail("https://upload.wikimedia.org$path?utm_source=commons.wikimedia.org&utm_campaign=imageinfo"))
        for (url in listOf(
            "https://thumb.wikimedia.org$path?url=https://evil.invalid/",
            "https://thumb.wikimedia.org$path?utm_source=commons.wikimedia.org&utm_campaign=imageinfo&x=1",
            "https://evil.invalid$path", "//upload.wikimedia.org$path", "http://upload.wikimedia.org$path",
            "https://upload.wikimedia.org/wikipedia/commons/a/a0/A.webm",
            "https://upload.wikimedia.org/wikipedia/commons/thumb/x/image.svg",
            "https://upload.wikimedia.org/wikipedia/commons/thumb/%2fetc/x.jpg",
            "https://upload.wikimedia.org/wikipedia/commons/thumb/%0ax.jpg",
            "https://upload.wikimedia.org/wikipedia/commons/thumb/%2e%2e/x.jpg",
            "https://upload.wikimedia.org/wikipedia/commons/thumb/%252e%252e/x.jpg",
        )) assertNull(url, CommonsUrlPolicy.thumbnail(url))
    }
    @Test fun licenseLinksAreSafeDisplayOnlyAndDoNotAllowJavascriptOrUnknownHosts() {
        assertNotNull(CommonsUrlPolicy.license("https://creativecommons.org/licenses/by/3.0"))
        for (url in listOf("javascript:alert(1)", "data:text/html,test", "https://evil.invalid/license",
            "https://creativecommons.org.evil.invalid/licenses/by/3.0", "https://creativecommons.org/licenses/by/3.0?x=1"))
            assertNull(CommonsUrlPolicy.license(url))
    }
    @Test fun publicAddressCheckRejectsPrivateReservedAndTransitionRanges() {
        for (address in listOf("127.0.0.1", "10.1.1.1", "172.16.0.1", "192.168.0.1", "169.254.169.254", "100.64.0.1",
            "192.0.2.1", "198.51.100.1", "203.0.113.1", "198.18.0.1", "224.1.1.1", "0.0.0.0",
            "::1", "fc00::1", "fe80::1", "2001:20::1", "3fff::1", "192.88.99.1", "2001:db8::1", "2002:7f00:1::", "2001::1"))
            assertFalse(address, CommonsUrlPolicy.isPublicAddress(InetAddress.getByName(address)))
        assertTrue(CommonsUrlPolicy.isPublicAddress(InetAddress.getByName("208.80.154.224")))
        assertTrue(CommonsUrlPolicy.isPublicAddress(InetAddress.getByName("2620:0:861:ed1a::1")))
    }
    @Test fun htmlIsConvertedToPlainTextWithoutExecutingOrFollowingLinks() {
        val input = "<a href='javascript:bad()'>Author &amp; team</a><script>alert('bad')</script><img src='https://evil.invalid'>"
        val result = CommonsText.plain(input)
        assertEquals("Author & team", result.text); assertTrue(result.changed)
        assertFalse(result.text!!.contains("alert")); assertFalse(result.text.contains("evil"))
        assertEquals("A B", CommonsText.plain("A<span title='>'>B</span>").text)
        assertEquals("© Name", CommonsText.plain("&#169; Name").text)
        assertEquals("A B", CommonsText.plain("A\u202eB").text)
        assertEquals("Safe", CommonsText.plain("Safe <broken").text)
        assertEquals("&lt;script&gt;", CommonsText.plain("&amp;lt;script&amp;gt;").text)
        assertTrue(CommonsText.plain("x".repeat(3000)).truncated)
    }
    @Test fun missingOrUnreliableRightsNeverBecomeDownloadPermission() {
        val missing = mutate { root -> changeFirstInfo(root) { info ->
            info["extmetadata"] = Json.Obj(mapOf("LicenseShortName" to Json.Obj(mapOf("value" to Json.Str("CC BY 4.0")))))
        } }
        val result = provider(TestTransport(Reply(missing))).search(request, operation()).results.single()
        assertNull(result.rights.author); assertNull(result.rights.licenseUrl)
        assertFalse(result.rights.hasDisplayAttribution); assertFalse(result.rights.downloadAuthorized)
        assertEquals(CommonsRightsReview.INCOMPLETE, result.rights.review)
        val flagged = mutate { root -> changeFirstInfo(root) { info ->
            val metadata = info.getValue("extmetadata").objectValue().toMutableMap()
            metadata["Restrictions"] = Json.Obj(mapOf("value" to Json.Arr(listOf(Json.Str("personality"), Json.Str("trademark")))))
            metadata["NonFree"] = Json.Obj(mapOf("value" to Json.Str("true")))
            info["extmetadata"] = Json.Obj(metadata)
        } }
        val rights = provider(TestTransport(Reply(flagged))).search(request, operation()).results.single().rights
        assertEquals(listOf("personality", "trademark"), rights.restrictions)
        assertEquals(CommonsRightsReview.FLAGGED_REQUIRES_REVIEW, rights.review); assertFalse(rights.downloadAuthorized)
    }
    @Test fun unknownOrInvalidDurationIsNullRatherThanZero() {
        for (value in listOf<Json?>(null, Json.Str("634.553"), Json.Num("-1"), Json.Num("0"), Json.Num("1e99"))) {
            val bytes = mutate { root -> changeFirstInfo(root) { info ->
                if (value == null) info.remove("duration") else info["duration"] = value
            } }
            assertNull(provider(TestTransport(Reply(bytes))).search(request, operation()).results.single().durationMs)
        }
    }
    @Test fun nonVideoNamespaceMimeAndMediaTypeDoNotProduceCandidates() {
        for ((key, value) in listOf("mime" to "image/jpeg", "mediatype" to "AUDIO")) {
            val bytes = mutate { root -> changeFirstInfo(root) { info -> info[key] = Json.Str(value) } }
            assertTrue(provider(TestTransport(Reply(bytes))).search(request, operation()).results.isEmpty())
        }
        val bytes = mutate { root ->
            val query = root.getValue("query").objectValue().toMutableMap()
            val page = (query.getValue("pages") as Json.Arr).values.single().objectValue().toMutableMap()
            page["ns"] = Json.Num("0"); query["pages"] = Json.Arr(listOf(Json.Obj(page))); root["query"] = Json.Obj(query)
        }
        assertTrue(provider(TestTransport(Reply(bytes))).search(request, operation()).results.isEmpty())
    }
    @Test fun malformedAndAmbiguousJsonFailsClosed() {
        for (input in listOf("{\"query\":1,\"query\":2}", "[1,]", "{\"a\":01}", "{\"a\":1e}",
            "{\"a\":\"\\uD800\"}", "{}junk", "[".repeat(18) + "0" + "]".repeat(18)))
            fails(CommonsError.INVALID_RESPONSE) { StrictJson.read(input.toByteArray()) }
        fails(CommonsError.INVALID_RESPONSE) { StrictJson.read(byteArrayOf(0xc3.toByte(), 0x28)) }
        fails(CommonsError.INVALID_RESPONSE) { provider(TestTransport(Reply("{}".toByteArray()))).search(request, operation()) }
    }
    @Test fun noResultsAndMissingDurationAreLegitimateStates() {
        val page = provider(TestTransport(Reply("""{"batchcomplete":true}""".toByteArray()))).search(request, operation())
        assertTrue(page.results.isEmpty()); assertTrue(page.exhausted); assertNull(page.nextCursor)
    }
    @Test fun unsafePageAndThumbnailAreNotExposed() {
        val bytes = mutate { root -> changeFirstInfo(root) { info -> info["descriptionurl"] = Json.Str("https://evil.invalid/a") } }
        assertTrue(provider(TestTransport(Reply(bytes))).search(request, operation()).results.isEmpty())
        val thumb = mutate { root -> changeFirstInfo(root) { info -> info["thumburl"] = Json.Str("javascript:bad()") } }
        assertNull(provider(TestTransport(Reply(thumb))).search(request, operation()).results.single().thumbnailUrl)
    }
    @Test fun entityDecodedControlsUseTheSameSanitizerAsRawCharacters() {
        for (point in listOf(0, 1, 31, 127, 128, 159, 0x061c, 0x200e, 0x200f, 0x202a, 0x202e, 0x2066, 0x2069)) {
            val numeric = "A&#x${point.toString(16)};B"
            assertEquals("A B", CommonsText.plain(numeric).text)
            assertEquals(CommonsText.plain("A${point.toChar()}B").text, CommonsText.plain(numeric).text)
        }
        assertEquals("A😃B", CommonsText.plain("A&#x1f603;B").text)
    }

}
