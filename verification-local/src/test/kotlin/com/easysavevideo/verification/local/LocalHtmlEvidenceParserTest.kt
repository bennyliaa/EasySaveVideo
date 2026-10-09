package com.easysavevideo.verification.local

import org.junit.Assert.*
import org.junit.Test

class LocalHtmlEvidenceParserTest {
    private val parser=LocalHtmlEvidenceParser()
    private fun parse(html:String)=parser.parse(html.toByteArray(),"https://fixture.example.org/page")
    @Test fun realJsoupPrimaryVideoEvidenceNotPlaybackOrDownload() {
        val evidence=parse("<main><h1>Owned fixture</h1><video data-esv-primary='true' src='/media.mp4'></video></main>")!!
        assertEquals("https://fixture.example.org/media.mp4",evidence.sourceCandidate)
        assertEquals("Owned fixture",evidence.raw.title)
        assertTrue(evidence.raw.videoConfirmed)
    }
    @Test fun wrappedAndClassChangedTargetRetainsEvidence() {
        assertNotNull(parse("<main><section><article><video data-esv-primary='true' id='revised' class='v2'><source src='/media.mp4'></video></article></section></main>"))
    }
    @Test fun deletedTargetAndSimilarFilenameNeverConfirm() {
        assertNull(parse("<main><a id='player' href='/media.mp4'>Decoy</a></main>"))
    }
    @Test fun structuredDeclarationsAndScriptsAloneRemainUnknown() {
        assertNull(parse("<main><script type='application/ld+json'>{\"@type\":\"VideoObject\",\"contentUrl\":\"/media.mp4\"}</script></main>"))
        assertNull(parse("<main><script>document.write('<video src=/media.mp4>')</script></main>"))
    }
    @Test fun ambiguityHiddenMissingAndUnsafeSourcesRemainUnknown() {
        for (body in listOf("<video src='/a.mp4'></video><video src='/b.mp4'></video>",
            "<video hidden src='/a.mp4'></video>","<video></video>",
            "<video src='javascript:alert(1)'></video>","<video src='https://user:pass@example.org/a.mp4'></video>",
            "<video src='/a.mp4'><source src='/b.mp4'></video>","<iframe src='/player'></iframe>"))
            assertNull(parse("<main>${body.replace("<video ","<video data-esv-primary='true' ").replace("<video>","<video data-esv-primary='true'>")}</main>"))
    }
    @Test fun malformedUtf8AndFiniteSize() {
        assertNull(parser.parse(byteArrayOf(0xc3.toByte(),0x28),"https://fixture.example.org/page"))
        assertNull(parser.parse(ByteArray(262145),"https://fixture.example.org/page"))
    }
    @Test fun evidenceIsRedactedAndDoesNotInventMetadata() {
        val result=parse("<main><video data-esv-primary='true' src='/media.mp4'></video></main>")!!
        assertNull(result.raw.title);assertNull(result.raw.thumbnailUrl);assertNull(result.raw.durationMs)
        assertFalse(result.toString().contains("media.mp4"))
    }
    @Test fun articleWithoutPrimaryMarkerRemainsUnknown() {
        assertNull(parse("<main><article><video src='/related.mp4'></video></article></main>"))
    }
}
