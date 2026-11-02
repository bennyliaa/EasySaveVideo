package com.easysavevideo.hls

import org.junit.Assert.*
import org.junit.Test

/** All fixtures are original synthetic manifests on reserved .invalid hosts; no real site requests. */
class HlsParserTest {
    private val limits = HlsLimits(
        maxManifestBytes = 2 * 1024 * 1024,
        maxTotalManifestBytes = 8L * 1024 * 1024,
        maxManifestCount = 8,
        maxLineBytes = 16 * 1024,
        maxLines = 100_000,
        maxUriBytes = 8 * 1024,
        maxAttributes = 32,
        maxVariants = 64,
        maxSegments = 20_000,
        maxDurationUs = 24L * 60 * 60 * 1_000_000,
    )
    private fun uri(value: String) = HlsUri.parse(value, limits.maxUriBytes)
    private val defaultUrl = "https://media.invalid/root/video.m3u8?signature=private-manifest"
    private fun location(request: String = defaultUrl, response: String = request) = HlsPlaylistLocation(uri(request), uri(response))
    private fun parse(text: String, at: HlsPlaylistLocation = location(), bounded: HlsLimits = limits): HlsPlaylist =
        HlsParser().parse(text.toByteArray(Charsets.UTF_8), at, HlsPlanningBudget(bounded))
    private fun vod(vararg refs: String, extras: String = "", duration: String = "6", target: String = "6"): String = buildString {
        append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:$target\n")
        append(extras)
        for (ref in refs) append("#EXTINF:$duration,private segment title\n$ref\n")
        append("#EXT-X-ENDLIST\n")
    }
    private fun master(vararg refs: String): String = buildString {
        append("#EXTM3U\n#EXT-X-VERSION:3\n")
        refs.forEachIndexed { index, ref ->
            append("#EXT-X-STREAM-INF:BANDWIDTH=${1_000_000 + index},CODECS=\"avc1.42E01E,mp4a.40.2\",RESOLUTION=640x360\n$ref\n")
        }
    }
    private fun rejects(code: HlsError, block: () -> Unit) {
        try { block(); fail("Expected technical rejection: $code") }
        catch (failure: HlsFailure) {
            assertEquals(code, failure.code)
            assertEquals(code.name, failure.message)
            assertNull(failure.cause)
        }
    }

    @Test fun parsesStaticMediaAndPreservesOrderedSequencesWithoutDeduplicatingUris() {
        val playlist = parse(vod("same.ts", "same.ts", extras = "#EXT-X-PLAYLIST-TYPE:VOD\n#EXT-X-MEDIA-SEQUENCE:50\n")) as HlsMediaPlaylist
        assertEquals(50L, playlist.mediaSequence)
        assertEquals(listOf(50L, 51L), playlist.segments.map { it.sequence })
        assertEquals(listOf(0L, 6_000_000L), playlist.segments.map { it.startUs })
        assertEquals(12_000_000L, playlist.totalDurationUs)
        assertEquals(playlist.segments[0].uri, playlist.segments[1].uri)
        assertNotEquals(playlist.segments[0].identity, playlist.segments[1].identity)
    }

    @Test fun directMediaSelectionRemainsUnvalidatedAndOnlyDurableMetadataIsExposed() {
        val playlist = parse(vod("one.ts?token=private-segment")) as HlsMediaPlaylist
        val plan = HlsPlanBuilder.build(HlsSelection.directMedia(playlist.location.requestedUri), playlist)
        assertEquals(HlsMediaValidation.REQUIRED_NOT_PERFORMED, plan.mediaValidation)
        assertEquals(HlsMediaProfile.TS_STATIC_VOD_AVC_BASELINE_AAC_LC, plan.profile)
        assertEquals(plan.segments[0].uri.identity, plan.durable.segments[0].uriIdentity)
        assertEquals(plan.media.manifestByteLength, plan.durable.manifestByteLength)
        assertEquals(plan.identity, plan.durable.identity)
        assertEquals(HlsMediaValidation.REQUIRED_NOT_PERFORMED, plan.durable.mediaValidation)
        plan.durable.requireSameIdentity(plan)
    }

    @Test fun masterSupportsQuotedCommaCodecListAndExplicitImmutableSelection() {
        val root = parse(master("low.m3u8", "high.m3u8")) as HlsMasterPlaylist
        assertEquals(2, root.variants.size)
        assertTrue(root.variants[1].metadata.codecs!!.declaresAacLc)
        assertEquals(0x42E01E, root.variants[0].metadata.codecs!!.avcProfileLevel)
        val selected = root.selectVariant(root.variants[1].identity)
        assertEquals(root.variants[1].playlistUri, selected.playlistUri)
        assertEquals(root.contentIdentity, selected.masterContentIdentity)
        val media = parse(vod("x.ts"), location(selected.playlistUri.value))
        assertEquals(selected.identity, HlsPlanBuilder.build(selected, media).selection.identity)
        rejects(HlsError.SELECTION_MISMATCH) { root.selectVariant("not-a-variant") }
        rejects(HlsError.SELECTION_MISMATCH) { HlsPlanBuilder.build(root.selectVariant(root.variants[0].identity), media) }
    }

    @Test fun optionalCodecDeclarationIsAHintAndNoImplicitAudioIsInvented() {
        val root = parse("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=123,AVERAGE-BANDWIDTH=100,FRAME-RATE=29.970,CLOSED-CAPTIONS=NONE,HDCP-LEVEL=NONE\na.m3u8\n") as HlsMasterPlaylist
        assertNull(root.variants.single().metadata.codecs)
        assertEquals(29_970L, root.variants.single().metadata.frameRateMilli)
        assertEquals(100L, root.variants.single().metadata.averageBandwidth)
    }

    @Test fun resolvesAgainstFinalResponseUrlAndDoesNotInheritManifestSignatures() {
        val playlist = parse(vod("../piece.ts?sig=a%2Fb%2bc&z=2&z=1"),
            location("https://origin.invalid/start.m3u8?token=origin", "https://cdn.invalid/deep/path/list.m3u8?token=cdn")) as HlsMediaPlaylist
        assertEquals("https://cdn.invalid/deep/piece.ts?sig=a%2Fb%2bc&z=2&z=1", playlist.segments[0].uri.value)
    }

    @Test fun relativeQueryPreservesFinalFilenameAndExactEscaping() {
        val playlist = parse(vod("?sig=new%2FSignature&z=2&z=1")) as HlsMediaPlaylist
        assertEquals("https://media.invalid/root/video.m3u8?sig=new%2FSignature&z=2&z=1", playlist.segments[0].uri.value)
    }

    @Test fun networkRelativeAndRootRelativeUrisResolveNormally() {
        val playlist = parse(vod("//other.invalid/p.ts", "/absolute/second.ts")) as HlsMediaPlaylist
        assertEquals("https://other.invalid/p.ts", playlist.segments[0].uri.value)
        assertEquals("https://media.invalid/absolute/second.ts", playlist.segments[1].uri.value)
    }

    @Test fun relativeAndBaseEmptyPathSegmentsAreNotCollapsed() {
        val expected = listOf(
            "a//b.ts?sig=one" to "https://media.invalid/root/a//b.ts?sig=one",
            "../a//b.ts?sig=one" to "https://media.invalid/a//b.ts?sig=one",
            "/a//b.ts?sig=one" to "https://media.invalid/a//b.ts?sig=one",
            "a///b.ts" to "https://media.invalid/root/a///b.ts",
        )
        for ((reference, result) in expected) {
            assertEquals(result, (parse(vod(reference)) as HlsMediaPlaylist).segments.single().uri.value)
        }
        val base = location(response = "https://media.invalid/a//video.m3u8?token=old")
        assertEquals("https://media.invalid/a//seg.ts?sig=one",
            (parse(vod("seg.ts?sig=one"), base) as HlsMediaPlaylist).segments.single().uri.value)
        assertEquals("https://media.invalid/a//video.m3u8?sig=one",
            (parse(vod("?sig=one"), base) as HlsMediaPlaylist).segments.single().uri.value)
    }

    @Test fun literalDotNavigationHonorsRootAndPreservesEmptySegments() {
        val expected = listOf(
            "../../../seg.ts" to "/seg.ts",
            "/./seg.ts" to "/seg.ts",
            "/a/../seg.ts" to "/seg.ts",
            "/a//../seg.ts" to "/a/seg.ts",
            "/a//./seg.ts" to "/a//seg.ts",
            "/a/../../seg.ts" to "/seg.ts",
            "./seg.ts" to "/root/seg.ts",
            "a/.." to "/root/",
            "." to "/root/",
            ".." to "/",
            "../.." to "/",
            "a/." to "/root/a/",
            "a//.." to "/root/a/",
        )
        for ((reference, path) in expected) {
            assertEquals("https://media.invalid$path", (parse(vod(reference)) as HlsMediaPlaylist).segments.single().uri.value)
        }
        assertEquals("https://other.invalid/a//seg.ts?sig=one",
            (parse(vod("https://other.invalid/x/../a//seg.ts?sig=one")) as HlsMediaPlaylist).segments.single().uri.value)
        assertEquals("https://other.invalid/a//seg.ts?sig=one",
            (parse(vod("//other.invalid/x/../a//seg.ts?sig=one")) as HlsMediaPlaylist).segments.single().uri.value)
    }

    @Test fun encodedDotsSlashesAndQueryNavigationAreNotReinterpreted() {
        val reference = "%2e/%2E%2e/segment%2fpart.ts?sig=a%2Fb&path=x/../y//z&v=2&v=1"
        assertEquals("https://media.invalid/root/$reference", (parse(vod(reference)) as HlsMediaPlaylist).segments.single().uri.value)
        assertEquals("https://empty.invalid/seg.ts",
            (parse(vod("seg.ts"), location(response = "https://empty.invalid")) as HlsMediaPlaylist).segments.single().uri.value)
        assertEquals("https://media.invalid/root/video.m3u8?",
            (parse(vod("?")) as HlsMediaPlaylist).segments.single().uri.value)
    }

    @Test fun emptyNetworkAuthorityNeverInheritsTheBaseHost() {
        for (reference in listOf("///a.ts", "////a.ts", "//?q=1", "//")) {
            rejects(HlsError.INVALID_URI) { parse(vod(reference)) }
        }
    }

    @Test fun unsignedRelativeUriDoesNotCopyBaseQuery() {
        val playlist = parse(vod("plain.ts")) as HlsMediaPlaylist
        assertEquals("https://media.invalid/root/plain.ts", playlist.segments.single().uri.value)
    }

    @Test fun httpsIsRequiredAndOpaqueCredentialsFragmentsWhitespaceAndBadPortsAreRejected() {
        rejects(HlsError.INSECURE_URI) { parse(vod("http://insecure.invalid/a.ts")) }
        for (value in listOf("https://u:secret@media.invalid/a.ts", "https://media.invalid/a.ts#part", "https://media.invalid:70000/a.ts", "https://media.invalid:0/a.ts", "a file.ts", "broken%xy.ts", "https:opaque")) {
            rejects(HlsError.INVALID_URI) { parse(vod(value)) }
        }
    }

    @Test fun externalUriStringsRejectUnpairedSurrogatesWithoutHashReplacement() {
        for (suffix in listOf("\uD800", "\uDC00", "\uD800x", "x\uDC00")) {
            rejects(HlsError.INVALID_URI) { uri("https://media.invalid/x$suffix") }
        }
        val paired = uri("https://media.invalid/x\uD83D\uDE00")
        val question = uri("https://media.invalid/x?")
        assertEquals("https://media.invalid/x\uD83D\uDE00", paired.value)
        assertNotEquals(question.identity, paired.identity)
        assertEquals(paired.identity, uri(paired.value).identity)
    }

    @Test fun fractionalDurationUsesExactMicrosecondsAndRfcTargetRounding() {
        val playlist = parse(vod("one.ts", duration = "6.499999")) as HlsMediaPlaylist
        assertEquals(6_499_999L, playlist.segments.single().durationUs)
        assertEquals(1L, (parse(vod("one.ts", duration = "0.000001")) as HlsMediaPlaylist).totalDurationUs)
        assertEquals(1_250_000L, (parse(vod("one.ts", duration = "1.2500000")) as HlsMediaPlaylist).totalDurationUs)
        rejects(HlsError.INVALID_PLAYLIST) { parse(vod("one.ts", duration = "6.5")) }
    }

    @Test fun rejectsNonfiniteNegativeExponentZeroSubmicroAndOverflowDurations() {
        for (duration in listOf("NaN", "Infinity", "-1", "+1", "1e3", "0", "0.0000001", "9223372036854.775808", "99999999999999999999999999999999999999999", ".5", "1.")) {
            rejects(HlsError.INVALID_NUMBER) { parse(vod("one.ts", duration = duration)) }
        }
    }

    @Test fun longBoundaryConversionAndSumNeverOverflow() {
        val huge = limits.copy(maxDurationUs = Long.MAX_VALUE)
        val playlist = parse(vod("one.ts", duration = "9223372036854.775807", target = "9223372036855"), bounded = huge) as HlsMediaPlaylist
        assertEquals(Long.MAX_VALUE, playlist.totalDurationUs)
        rejects(HlsError.LIMIT_EXCEEDED) {
            parse(vod("one.ts", "two.ts", duration = "9223372036854.775807", target = "9223372036855"), bounded = huge)
        }
    }

    @Test fun defaultSequenceZeroAndLastSignedLongSequenceAreValid() {
        assertEquals(0L, (parse(vod("one.ts")) as HlsMediaPlaylist).mediaSequence)
        val one = parse(vod("one.ts", extras = "#EXT-X-MEDIA-SEQUENCE:${Long.MAX_VALUE}\n")) as HlsMediaPlaylist
        assertEquals(Long.MAX_VALUE, one.segments.single().sequence)
        rejects(HlsError.INVALID_NUMBER) { parse(vod("one.ts", "two.ts", extras = "#EXT-X-MEDIA-SEQUENCE:${Long.MAX_VALUE}\n")) }
        for (n in listOf("-1", "18446744073709551615", "9223372036854775808")) {
            rejects(HlsError.INVALID_NUMBER) { parse(vod("one.ts", extras = "#EXT-X-MEDIA-SEQUENCE:$n\n")) }
        }
    }

    @Test fun versionIsBoundedAndFractionalDurationsNeedVersionThree() {
        val legacy = vod("one.ts").replace("#EXT-X-VERSION:3\n", "")
        assertEquals(1, parse(legacy).version)
        rejects(HlsError.UNSUPPORTED_VERSION) { parse(legacy.replace("#EXTINF:6,", "#EXTINF:6.0,")) }
        for (version in listOf("8", "999")) {
            rejects(HlsError.UNSUPPORTED_VERSION) { parse(vod("one.ts").replace("VERSION:3", "VERSION:$version")) }
        }
        rejects(HlsError.INVALID_PLAYLIST) { parse(vod("one.ts", extras = "#EXT-X-VERSION:3\n")) }
    }

    @Test fun methodNoneIsAcceptedButNeverWithKeyData() {
        assertEquals(1, (parse(vod("one.ts", extras = "#EXT-X-KEY:METHOD=NONE\n")) as HlsMediaPlaylist).segments.size)
        rejects(HlsError.INVALID_ATTRIBUTE) { parse(vod("one.ts", extras = "#EXT-X-KEY:METHOD=NONE,URI=\"https://secret.invalid/key\"\n")) }
        rejects(HlsError.INVALID_ATTRIBUTE) { parse(vod("one.ts", extras = "#EXT-X-KEY:METHOD=\"NONE\"\n")) }
    }

    @Test fun allEncryptionIsRejectedWithoutAnyKeyRequestOrStoredKeyUri() {
        for (method in listOf("AES-128", "SAMPLE-AES", "SAMPLE-AES-CTR", "UNKNOWN")) {
            rejects(HlsError.PROTECTED_MEDIA) { parse(vod("one.ts", extras = "#EXT-X-KEY:METHOD=$method,URI=\"https://secret.invalid/key?token=private\"\n")) }
        }
        rejects(HlsError.PROTECTED_MEDIA) { parse(vod("one.ts", extras = "#EXT-X-SESSION-KEY:METHOD=NONE\n")) }
    }

    @Test fun staticEndlistRequiredAndEventRemainsUnsupportedEvenWhenEnded() {
        rejects(HlsError.NOT_STATIC_VOD) { parse(vod("one.ts").replace("#EXT-X-ENDLIST\n", "")) }
        rejects(HlsError.NOT_STATIC_VOD) { parse(vod("one.ts", extras = "#EXT-X-PLAYLIST-TYPE:EVENT\n")) }
        rejects(HlsError.INVALID_PLAYLIST) { parse(vod("one.ts") + "#EXTINF:1,\ntwo.ts\n") }
        rejects(HlsError.INVALID_PLAYLIST) { parse(vod("one.ts") + "#EXT-X-ENDLIST\n") }
    }

    @Test fun containerAndTimelineExtensionsFailClosed() {
        for (tag in listOf("#EXT-X-MAP:URI=\"init.mp4\"", "#EXT-X-BYTERANGE:100@0")) {
            rejects(HlsError.UNSUPPORTED_CONTAINER) { parse(vod("one.ts", extras = "$tag\n")) }
        }
        for (tag in listOf("#EXT-X-DISCONTINUITY", "#EXT-X-DISCONTINUITY-SEQUENCE:0", "#EXT-X-DISCONTINUITY-SEQUENCE:9", "#EXT-X-GAP")) {
            rejects(HlsError.UNSUPPORTED_TIMELINE) { parse(vod("one.ts", extras = "$tag\n")) }
        }
    }

    @Test fun lowLatencyStartDateRangeVariablesAndUnknownTagsFailClosed() {
        val tags = listOf("#EXT-X-PART:DURATION=1,URI=\"a\"", "#EXT-X-PART-INF:PART-TARGET=1", "#EXT-X-PRELOAD-HINT:TYPE=PART,URI=\"a\"", "#EXT-X-SERVER-CONTROL:CAN-BLOCK-RELOAD=YES", "#EXT-X-SKIP:SKIPPED-SEGMENTS=1", "#EXT-X-RENDITION-REPORT:URI=\"a\"", "#EXT-X-START:TIME-OFFSET=1", "#EXT-X-DATERANGE:ID=\"ad\"", "#EXT-X-PROGRAM-DATE-TIME:2026-10-06T00:00:00Z", "#EXT-X-DEFINE:NAME=\"x\",VALUE=\"secret\"", "#EXT-X-CONTENT-STEERING:SERVER-URI=\"a\"", "#EXT-X-I-FRAMES-ONLY", "#EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=1,URI=\"a\"", "#EXT-X-FUTURE-SEMANTICS:YES", "#EXTFOO")
        for (tag in tags) rejects(HlsError.UNSUPPORTED_TAG) { parse(vod("one.ts", extras = "$tag\n")) }
    }

    @Test fun groupsAndSeparateAudioRejectTheEntireInitialMasterProfile() {
        for (group in listOf("AUDIO", "VIDEO", "SUBTITLES")) {
            rejects(HlsError.UNSUPPORTED_RENDITION) { parse(master("low.m3u8").replace("BANDWIDTH=1000000", "BANDWIDTH=1000000,$group=\"group1\"")) }
        }
        rejects(HlsError.UNSUPPORTED_RENDITION) {
            parse("#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",NAME=\"audio\",URI=\"audio.m3u8\"\n" + master("low.m3u8").substringAfter('\n'))
        }
        rejects(HlsError.UNSUPPORTED_RENDITION) {
            parse(master("low.m3u8").replace("BANDWIDTH=1000000", "BANDWIDTH=1000000,CLOSED-CAPTIONS=group1"))
        }
    }

    @Test fun codecDeclarationsOutsideBaselineAndOptionalAacLcReject() {
        for (codecs in listOf("avc1.64001f,mp4a.40.2", "hvc1.1.6.L93.B0", "avc1.42E01E,mp4a.40.5", "mp4a.40.2", "avc1.42E01E,avc1.42E01E", "avc1.42E01E, mp4a.40.2")) {
            rejects(HlsError.UNSUPPORTED_CODEC_DECLARATION) { parse(master("a.m3u8").replace("avc1.42E01E,mp4a.40.2", codecs)) }
        }
        rejects(HlsError.PROTECTED_MEDIA) { parse(master("a.m3u8").replace("BANDWIDTH=1000000", "BANDWIDTH=1000000,HDCP-LEVEL=TYPE-0")) }
    }

    @Test fun noClosedCaptionsMustBeDeclaredConsistentlyAcrossMasterVariants() {
        val mixed = master("a.m3u8", "b.m3u8").replace("BANDWIDTH=1000000", "BANDWIDTH=1000000,CLOSED-CAPTIONS=NONE")
        rejects(HlsError.INVALID_PLAYLIST) { parse(mixed) }
        val consistent = mixed.replace("BANDWIDTH=1000001", "BANDWIDTH=1000001,CLOSED-CAPTIONS=NONE")
        assertTrue((parse(consistent) as HlsMasterPlaylist).variants.all { it.metadata.declaresNoClosedCaptions })
    }

    @Test fun malformedAndDuplicateAttributesRejectRatherThanLastValueWins() {
        val attrs = listOf("BANDWIDTH=1,BANDWIDTH=2", "BANDWIDTH=1,", "BANDWIDTH=1, CODECS=\"avc1.42E01E\"", "BANDWIDTH=\"1\"", "BANDWIDTH=1,CODECS=\"avc1.42E01E", "BANDWIDTH=1,CODECS=\"avc1.42E01E\"garbage", "BANDWIDTH=1,,CODECS=\"avc1.42E01E\"", "BANDWIDTH=1,CODECS=avc1.42E01E", "bandwidth=1", "BANDWIDTH=1,FOO=")
        for (attr in attrs) rejects(HlsError.INVALID_ATTRIBUTE) { parse("#EXTM3U\n#EXT-X-STREAM-INF:$attr\na.m3u8\n") }
        rejects(HlsError.INVALID_ATTRIBUTE) { parse("#EXTM3U\n#EXT-X-STREAM-INF:CODECS=\"avc1.42E01E\"\na.m3u8\n") }
        rejects(HlsError.UNSUPPORTED_TAG) { parse("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1,FUTURE=YES\na.m3u8\n") }
    }

    @Test fun mixedMasterAndMediaNeverParseAsOneOrTheOther() {
        rejects(HlsError.MIXED_PLAYLIST_TYPES) { parse(master("a.m3u8") + "#EXT-X-TARGETDURATION:6\n") }
        rejects(HlsError.MIXED_PLAYLIST_TYPES) { parse("#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXT-X-STREAM-INF:BANDWIDTH=1\na.m3u8\n") }
    }

    @Test fun malformedOrderingDuplicatesMissingUriDurationAndEmptyPlaylistReject() {
        val cases = listOf("", "#EXTM3U\n", "\n#EXTM3U\n", "#extm3u\n", "#EXTM3U\n#EXTM3U\n", "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\n", "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\n#EXT-X-VERSION:3\na.m3u8\n", "#EXTM3U\n#EXT-X-TARGETDURATION:6\none.ts\n#EXT-X-ENDLIST\n", "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\n#EXT-X-ENDLIST\n", vod("one.ts", extras = "#EXT-X-TARGETDURATION:6\n"), vod("one.ts").replace("#EXTINF:6,", "#EXTINF:6\n#comment"), vod("one.ts") + "#EXT-X-MEDIA-SEQUENCE:1\n")
        for (text in cases) rejects(HlsError.INVALID_PLAYLIST) { parse(text) }
    }

    @Test fun utf8IsStrictBomBareCrControlsAndNonNfcAreRejected() {
        val parser = HlsParser()
        for (invalid in listOf(byteArrayOf(0xC3.toByte(), 0x28), byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()))) {
            rejects(HlsError.INVALID_UTF8) { parser.parse(invalid, location(), HlsPlanningBudget(limits)) }
        }
        for (text in listOf("\uFEFF" + vod("one.ts"), vod("one.ts").replace('\n', '\r'), vod("one.ts", extras = "#comment\tcontrol\n"), vod("one.ts", extras = "#comment\u0085control\n"), vod("one.ts", extras = "#caf\u0065\u0301\n"))) {
            rejects(HlsError.INVALID_PLAYLIST) { parse(text) }
        }
        assertEquals(1, (parse(vod("one.ts").replace("\n", "\r\n")) as HlsMediaPlaylist).segments.size)
        assertEquals(1, (parse(vod("one.ts", extras = "#café\n\n")) as HlsMediaPlaylist).segments.size)
    }

    @Test fun independentSegmentsDoesNotClaimValidationAndDuplicateTagRejects() {
        val playlist = parse(vod("one.ts", extras = "#EXT-X-INDEPENDENT-SEGMENTS\n"))
        assertTrue(playlist.declaresIndependentSegments)
        assertEquals(HlsMediaValidation.REQUIRED_NOT_PERFORMED, HlsPlanBuilder.build(HlsSelection.directMedia(playlist.location.requestedUri), playlist).mediaValidation)
        rejects(HlsError.INVALID_PLAYLIST) { parse(vod("one.ts", extras = "#EXT-X-INDEPENDENT-SEGMENTS\n#EXT-X-INDEPENDENT-SEGMENTS\n")) }
    }

    @Test fun loopsAndNestedMasterCannotTurnIntoRecursiveFetchPlans() {
        rejects(HlsError.PLAYLIST_LOOP) { parse(master(defaultUrl)) }
        val outer = parse(master("child.m3u8")) as HlsMasterPlaylist
        val selected = outer.selectVariant(outer.variants.single().identity)
        val nested = parse(master("grandchild.m3u8"), location(selected.playlistUri.value))
        rejects(HlsError.NESTED_MASTER) { HlsPlanBuilder.build(selected, nested) }
        rejects(HlsError.NESTED_MASTER) { HlsPlanBuilder.build(HlsSelection.directMedia(outer.location.requestedUri), outer) }
    }

    @Test fun budgetsCountFailedAttemptsAndNeverRenewAcrossRetries() {
        val text = vod("one.ts").toByteArray()
        val budget = HlsPlanningBudget(limits.copy(maxManifestCount = 2))
        val parser = HlsParser()
        rejects(HlsError.INVALID_PLAYLIST) { parser.parse("bad".toByteArray(), location(), budget) }
        parser.parse(text, location(), budget)
        assertEquals(HlsPlanningUsage(2, 3L + text.size), budget.usage())
        rejects(HlsError.LIMIT_EXCEEDED) { parser.parse(text, location(), budget) }
        assertEquals(2, budget.usage().manifestsCharged)
    }

    @Test fun cumulativeBytesAndPerManifestBytesAreBothBounded() {
        val text = vod("one.ts").toByteArray()
        val custom = limits.copy(maxManifestBytes = text.size, maxTotalManifestBytes = text.size.toLong(), maxLineBytes = 100, maxUriBytes = 100)
        val budget = HlsPlanningBudget(custom)
        HlsParser().parse(text, location(), budget)
        rejects(HlsError.LIMIT_EXCEEDED) { HlsParser().parse(text, location(), budget) }
        val one = HlsPlanningBudget(custom.copy(maxTotalManifestBytes = text.size * 3L))
        rejects(HlsError.LIMIT_EXCEEDED) { HlsParser().parse(text + byteArrayOf(10), location(), one) }
        assertEquals(text.size + 1L, one.usage().manifestBytesCharged)
    }

    @Test fun linesByteLengthUriAttrsVariantsSegmentsAndTotalDurationAreBounded() {
        rejects(HlsError.LIMIT_EXCEEDED) { parse(vod("one.ts", extras = "#" + "a".repeat(100) + "\n"), bounded = limits.copy(maxLineBytes = 100, maxUriBytes = 100)) }
        rejects(HlsError.LIMIT_EXCEEDED) { parse(vod("one.ts", extras = "#" + "é".repeat(50) + "\n"), bounded = limits.copy(maxLineBytes = 100, maxUriBytes = 100)) }
        rejects(HlsError.LIMIT_EXCEEDED) { parse(vod("one.ts"), bounded = limits.copy(maxLines = 3)) }
        rejects(HlsError.LIMIT_EXCEEDED) { parse(vod("https://media.invalid/" + "a".repeat(90)), bounded = limits.copy(maxUriBytes = 100)) }
        rejects(HlsError.LIMIT_EXCEEDED) { parse(master("a.m3u8"), bounded = limits.copy(maxAttributes = 1)) }
        rejects(HlsError.LIMIT_EXCEEDED) { parse(master("a.m3u8", "b.m3u8"), bounded = limits.copy(maxVariants = 1)) }
        rejects(HlsError.LIMIT_EXCEEDED) { parse(vod("a.ts", "b.ts"), bounded = limits.copy(maxSegments = 1)) }
        rejects(HlsError.LIMIT_EXCEEDED) { parse(vod("a.ts", "b.ts"), bounded = limits.copy(maxDurationUs = 6_000_000)) }
    }

    @Test fun normalManySegmentVodIsNotLimitedByDiscoveryPageBudget() {
        val playlist = parse(vod(*Array(2_000) { "segment-$it.ts" })) as HlsMediaPlaylist
        assertEquals(2_000, playlist.segments.size)
        assertEquals(1_999L, playlist.segments.last().sequence)
    }

    @Test fun listsAreActuallyImmutableAcrossJavaAndKotlinCallers() {
        val media = parse(vod("one.ts")) as HlsMediaPlaylist
        val root = parse(master("one.m3u8")) as HlsMasterPlaylist
        val plan = HlsPlanBuilder.build(HlsSelection.directMedia(media.location.requestedUri), media)
        for (list in listOf(media.segments, root.variants, plan.durable.segments)) {
            try { (list as MutableList<*>).clear(); fail("Expected immutable snapshot") }
            catch (_: UnsupportedOperationException) { }
        }
    }

    @Test fun refetchMustMatchManifestBaseSelectionAndSegmentIdentities() {
        val body = vod("one.ts?token=original")
        val old = parse(body)
        val selection = HlsSelection.directMedia(old.location.requestedUri)
        val original = HlsPlanBuilder.build(selection, old)
        original.durable.requireSameIdentity(HlsPlanBuilder.build(selection, parse(body)))
        val changedBody = HlsPlanBuilder.build(selection, parse(body.replace("token=original", "token=rotated")))
        rejects(HlsError.IDENTITY_MISMATCH) { original.durable.requireSameIdentity(changedBody) }
        val changedBase = HlsPlanBuilder.build(selection, parse(body, location(defaultUrl, "https://other.invalid/video.m3u8")))
        rejects(HlsError.IDENTITY_MISMATCH) { original.durable.requireSameIdentity(changedBase) }
    }

    @Test fun everyProducedToStringAndFailureIsSafeForDiagnostics() {
        val root = parse(master("child.m3u8?token=private-variant")) as HlsMasterPlaylist
        val selection = root.selectVariant(root.variants.single().identity)
        val media = parse(vod("one.ts?token=private-segment"), location(selection.playlistUri.value)) as HlsMediaPlaylist
        val plan = HlsPlanBuilder.build(selection, media)
        val values = listOf(root, root.location, root.location.requestedUri, root.variants.single(), selection, media, media.segments.single(), media.segments.single().uri, plan, plan.durable, plan.durable.segments.single())
        for (value in values) {
            val text = value.toString()
            for (forbidden in listOf("https://", "private-manifest", "private-variant", "private-segment", "private segment title", "token=", "signature=")) assertFalse("Unsafe rendering", text.contains(forbidden))
        }
        rejects(HlsError.INVALID_URI) { parse(vod("https://private-user:private-password@media.invalid/x.ts")) }
        // The durable view has only scalar/hash/segment metadata; a raw URI cannot be recovered.
        assertFalse(HlsDurablePlan::class.java.declaredFields.any { it.type == HlsUri::class.java || it.type == HlsPlaylistLocation::class.java || it.type == HlsSelection::class.java })
    }
}
