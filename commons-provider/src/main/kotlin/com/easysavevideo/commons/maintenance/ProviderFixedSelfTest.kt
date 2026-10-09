package com.easysavevideo.commons.maintenance

import com.easysavevideo.commons.CommonsError
import com.easysavevideo.commons.CommonsFailure
import com.easysavevideo.commons.CommonsIdentity
import com.easysavevideo.commons.CommonsResponseParser
import com.easysavevideo.commons.CommonsRightsReview

/**
 * Compiled synthetic vectors only: no requests, user data, downloaded rules or executable input.
 * This proves the admitted limits can accommodate a tiny known parser exercise, not compatibility
 * with a live site. The parser has its own depth/node bounds; these fixed inputs total < 4 KiB.
 * Cancellation is propagated, never converted to an incompatibility/quarantine verdict.
 */
internal object ProviderFixedSelfTest {
    private const val NOW = 1_000L
    private const val KEY = "0000000000000000000000000000000000000000000000000000000000000000"
    private const val MAX_CHECKS = 2_048
    private val page = CommonsIdentity.API.substringBefore("/w/") + "/wiki/File:ESV_self_test.webm"
    private val sample = """{"batchcomplete":true,"query":{"pages":[{"pageid":1,"ns":6,"title":"File:ESV_self_test.webm","videoinfo":[{"mime":"video/webm","mediatype":"VIDEO","descriptionurl":"$page","duration":1.25,"width":320,"height":180}]}]}}""".toByteArray()
    private val empty = """{"batchcomplete":true,"query":{"pages":[]}}""".toByteArray()
    private val continued = """{"continue":{"continue":"gsroffset||","gsroffset":1},"query":{"pages":[]}}""".toByteArray()
    private val invalid = listOf(
        """{"query":{"pages":"not-an-array"}}""",
        """{"query":{"pages":[]},"continue":{"action":"edit"}}""",
        """{"batchcomplete":true,"batchcomplete":false}""",
        """{"query":{"pages":[{"pageid":1,"ns":6},{"pageid":1,"ns":6}]}}""",
    ).map { it.toByteArray() }

    fun run(policy: ProviderPolicy, checkCancelled: () -> Unit): Boolean {
        checkCancelled()
        if (!policy.enabled) return true
        val positives = listOf(sample, empty, continued)
        if (positives.any { it.size > policy.limits.maxResponseBytes }) return false
        var checks = 0
        fun check() {
            checkCancelled()
            check(++checks <= MAX_CHECKS) { "SELF_TEST_RESOURCE_LIMIT" }
        }
        val parser = CommonsResponseParser(policy.limits.pageSize)
        val parsed = parser.parse(sample, KEY, NOW, NOW + 1_000, ::check)
        val video = parsed.results.singleOrNull() ?: return false
        if (!parsed.exhausted || parsed.nextCursor != null || video.pageId != 1L || video.durationMs != 1_250L ||
            video.width != 320 || video.height != 180 || video.rights.downloadAuthorized ||
            video.rights.review != CommonsRightsReview.INCOMPLETE) return false
        val blank = parser.parse(empty, KEY, NOW, NOW + 1_000, ::check)
        if (!blank.exhausted || blank.results.isNotEmpty()) return false
        val next = parser.parse(continued, KEY, NOW, NOW + 1_000, ::check)
        if (next.exhausted || next.nextCursor == null || next.results.isNotEmpty()) return false
        for (bytes in invalid) {
            check()
            try {
                parser.parse(bytes, KEY, NOW, NOW + 1_000, ::check)
                return false
            } catch (failure: CommonsFailure) {
                if (failure.code != CommonsError.INVALID_RESPONSE) return false
            }
        }
        checkCancelled()
        return true
    }
}
