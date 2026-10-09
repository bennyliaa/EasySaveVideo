import com.easysavevideo.app.storage.DiscoveryCheckpointCodec
import com.easysavevideo.discovery.*
import java.io.File
fun main(args: Array<String>) {
    val original = DiscoveryCheckpointCodec.decode(File(args[0]).readBytes())
    val entry = original.pool.entries.single()
    val rights = ResultRights(listOf(RightsSnapshot("source", "Example", "example", "1", "https://example.com/video",
        sourcePageRevisionId = 12, retrievedAtEpochMillis = 400, author = "作者", credit = "Credit", customAttribution = "Custom",
        licenseName = "License", licenseUrl = "https://example.com/license", usageTerms = "Terms", copyrighted = null,
        attributionRequired = true, nonFree = true, restrictions = listOf("Restricted"), deletionReason = "Review",
        review = RightsReview.FLAGGED_REQUIRES_REVIEW,
        provenance = listOf(RightsFieldProvenance("Artist", "source", htmlConvertedToText = true)),
        metadataTermsUrl = "https://example.com/metadata")), setOf(RightsWarning.CONFLICTING_REPORTS))
    val checkpoint = original.copy(pool = original.pool.copy(entries = listOf(entry.copy(result = entry.result.copy(rights = rights)))))
    val bytes = DiscoveryCheckpointCodec.encode(checkpoint)
    check(java.nio.ByteBuffer.wrap(bytes, 4, 4).int == 2)
    check(DiscoveryCheckpointCodec.decode(bytes) == checkpoint)
    check(DiscoveryCheckpointCodec.encode(DiscoveryCheckpointCodec.decode(bytes)).contentEquals(bytes))
    File(args[1]).writeBytes(bytes)
    println("Frozen genuine v2 fixture: ${bytes.size} bytes; unchanged 35dfacf codec round trip and byte identity verified")
}
