package com.easysavevideo.app.licenses
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.io.StringReader

class NoticeSectionsTest {
    @Test fun emptyTextHasNoInventedContent() {assertTrue(noticeSections("").isEmpty())}
    @Test fun newlinePreferenceDoesNotDropWhitespaceOrChangeAttribution() {
        val text="Heading\r\n\r\n  attribution line\nCopyright\n";val pieces=noticeSections(text,12)
        assertEquals(text,pieces.joinToString(""));assertTrue(pieces.all{it.length<=12})
    }
    @Test fun surrogatePairCrossingTheDefaultBoundaryIsNeverSplit() {
        val text="a".repeat(4095)+"\uD83D\uDE00"+"z".repeat(4096);val pieces=noticeSections(text)
        assertEquals(text,pieces.joinToString(""));assertFalse(pieces.dropLast(1).any{it.last().isHighSurrogate()})
        assertFalse(pieces.drop(1).any{it.first().isLowSurrogate()})
    }
    @Test fun crlfIsNotSplitAtBoundary() {
        val text="a".repeat(4095)+"\r\nEND";val pieces=noticeSections(text)
        assertEquals(text,pieces.joinToString(""));assertFalse(pieces.dropLast(1).any{it.last()=='\r'})
    }
    @Test fun longUnbrokenLineIsStillBoundedAndLossless() {
        val text="x".repeat(900003);val pieces=noticeSections(text)
        assertEquals(text,pieces.joinToString(""));assertTrue(pieces.all{it.isNotEmpty() && it.length<=4096})
    }
    @Test fun minimumChunkBoundHandlesPairsAndLineEndingsWithoutStalling() {
        val text="\uD83D\uDE00\r\nA\nB";val pieces=noticeSections(text,2)
        assertEquals(text,pieces.joinToString(""));assertTrue(pieces.all{it.length in 1..2})
    }
    @Test fun invalidLimitIsRejectedInsteadOfSplittingUnicode() {
        for(limit in listOf(0,1,-1))try{noticeSections("x",limit);fail()}catch(_:IllegalArgumentException){}
    }
    @Test fun actualBundledNoticeReconstructsExactlyIncludingLastAttribution() {
        val relative="src/main/assets/third-party-notices.txt";val path=Path.of(relative).takeIf{Files.exists(it)} ?: Path.of("app",relative)
        val original=String(Files.readAllBytes(path),Charsets.UTF_8)
        val read=readNoticeText(StringReader(original));assertEquals(original,noticeSections(read).joinToString(""))
    }
    @Test fun maximumReadableTextHasNoContentLossAcrossDisplayChunks() {
        val text="x".repeat(1024*1024);assertEquals(text,noticeSections(readNoticeText(StringReader(text))).joinToString(""))
    }
    @Test fun missingOrEmptyContentIsUnavailableInsteadOfPermanentlyLoading() {
        assertTrue(noticeLoadUnavailable(null));assertTrue(noticeLoadUnavailable(emptyList()))
        assertFalse(noticeLoadUnavailable(listOf("license")))
    }

    @Test fun loadingAndFailureNeverMeasureAnEmptyListOrReplaceRetainedPosition() {
        assertFalse(noticeContentVisible(false,emptyList()));assertFalse(noticeContentVisible(true,emptyList()))
        assertFalse(noticeContentVisible(true,listOf("old")));assertTrue(noticeContentVisible(false,listOf("ready")))
    }

}
