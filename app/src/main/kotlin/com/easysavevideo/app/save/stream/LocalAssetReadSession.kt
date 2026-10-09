package com.easysavevideo.app.save.stream

import java.io.IOException
import java.security.MessageDigest

/** Immutable, already-approved local input. No arbitrary URI/path or caller-provided final flag. */
internal class AssetResource(val bytes: Long, val sha256: String, val verifyIdentity: () -> Unit) {
    init { require(bytes > 0 && bytes % 188 == 0L && sha256.matches(Regex("[0-9a-f]{64}"))) }
    override fun toString() = "AssetResource([private])"
}

/** One extraction pass, one generation, one ordered full read per immutable resource.
 * Construct separate sessions for sample inspection and export. A failed session is never reusable.
 * SHA-256 binds received bytes to preflight; it does not prove upstream author intent.
 */
internal class LocalAssetReadSession(
    resources: List<AssetResource>,
    val evidence: CompatTsProfileEvidence,
    private val checkpoint: () -> Unit,
) {
    private val resources = resources.toList()
    private var nextIndex = 0
    private var current: Permit? = null
    private var invalid = false
    private var extractionClaimed = false
    private var complete = false

    internal class Permit internal constructor(
        internal val resourceIndex: Int,
        internal val resource: AssetResource,
    ) {
        internal val digest = MessageDigest.getInstance("SHA-256")
        internal var received = 0L
        internal var verified = false
        internal var input: Any? = null
        internal var lastPosition = 0L
        internal var extractorEof = false
        override fun toString() = "LocalAssetPermit([private])"
    }

    init {
        guard(resources.isNotEmpty() && resources.size <= 20_000)
        guard(evidence.videoAccessUnitCount > 0 && evidence.audioFrameCount >= 0)
        guard(evidence.segments.size == resources.size)
    }

    @Synchronized fun check() {
        guard(!invalid)
        try { checkpoint() } catch (failure: Throwable) { invalid = true; throw failure }
    }

    @Synchronized fun claimExtraction() {
        check(); guard(!extractionClaimed && nextIndex == 0 && !complete)
        extractionClaimed = true
    }

    /** Called only after the exact allowlisted DataSource's full-file open succeeds. */
    @Synchronized fun open(index: Int, position: Long, requestedLength: Long, openedLength: Long): Permit {
        check(); guard(!complete && current == null && index == nextIndex && index in resources.indices)
        val resource = resources[index]
        guard(position == 0L && (requestedLength == -1L || requestedLength == resource.bytes))
        guard(openedLength == resource.bytes)
        verifyIdentity(resource)
        return Permit(index, resource).also { current = it }
    }

    @Synchronized fun received(permit: Permit, bytes: ByteArray, offset: Int, length: Int) {
        valid(permit)
        guard(!permit.extractorEof && length > 0 && offset >= 0 && offset <= bytes.size - length)
        guard(length <= permit.resource.bytes - permit.received)
        permit.digest.update(bytes, offset, length)
        permit.received += length
        if (permit.received == permit.resource.bytes) {
            verifyIdentity(permit.resource)
            val actual = permit.digest.digest().joinToString("") { "%02x".format(it) }
            guard(actual == permit.resource.sha256)
            permit.verified = true
        }
    }

    @Synchronized fun sourceEof(permit: Permit) {
        valid(permit); guard(permit.received == permit.resource.bytes && permit.verified)
    }

    /** An object-identity binding prevents another/recreated input from borrowing this permit. */
    @Synchronized fun bind(input: Any, position: Long, length: Long): Permit {
        val permit = current ?: reject()
        valid(permit); guard(extractionClaimed && length == permit.resource.bytes)
        guard(position in permit.lastPosition..permit.resource.bytes)
        permit.lastPosition = position
        if (permit.input == null) {
            guard(position == 0L)
            permit.input = input
        } else guard(permit.input === input)
        return permit
    }

    /** Intermediate EOF is observation only. Exactly the immutable final index authorizes finish. */
    @Synchronized fun extractorEof(permit: Permit, input: Any, position: Long): Boolean {
        valid(permit); guard(permit.input === input && permit.verified && position == permit.resource.bytes)
        verifyIdentity(permit.resource)
        permit.extractorEof = true
        return permit.resourceIndex == resources.lastIndex
    }

    @Synchronized fun finishAsset(permit: Permit) {
        valid(permit)
        guard(permit.extractorEof && permit.verified && permit.resourceIndex == resources.lastIndex)
        complete = true
    }

    @Synchronized fun close(permit: Permit) {
        valid(permit); guard(permit.verified && permit.extractorEof)
        guard(permit.resourceIndex != resources.lastIndex || complete)
        current = null
        nextIndex++
    }

    /** Mark failure before closing resources; cannot be undone by a later EOF/retry. */
    @Synchronized fun revoke() { invalid = true }
    @Synchronized fun verifyComplete() { check(); guard(complete && current == null && nextIndex == resources.size) }
    @Synchronized fun activeIndex(): Int { check(); return current?.resourceIndex ?: reject() }

    private fun verifyIdentity(resource: AssetResource) {
        try { resource.verifyIdentity() } catch (failure: Throwable) { invalid = true; throw failure }
    }
    private fun valid(permit: Permit) { check(); guard(current === permit) }
    private fun guard(ok: Boolean) { if (!ok) reject() }
    private fun reject(): Nothing { invalid = true; throw IOException("LOCAL_ASSET_REJECTED") }
}
