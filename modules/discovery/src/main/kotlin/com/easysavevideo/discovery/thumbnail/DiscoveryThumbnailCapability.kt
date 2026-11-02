package com.easysavevideo.discovery.thumbnail

import com.easysavevideo.discovery.*
import java.nio.ByteBuffer
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch

/** Exact observation identity, never a URL-derived read authority. */
class DiscoveryThumbnailBinding private constructor(
    val session: SessionStamp,
    val pageRevision: Long,
    val resultId: String,
    private val observation: String,
) {
    override fun equals(other: Any?): Boolean = other is DiscoveryThumbnailBinding &&
        session == other.session && pageRevision == other.pageRevision && resultId == other.resultId && observation == other.observation
    override fun hashCode(): Int = arrayOf(session, pageRevision, resultId, observation).contentHashCode()
    override fun toString(): String = "DiscoveryThumbnailBinding([redacted])"
    companion object {
        private fun optional(value: String?): String = if (value == null) "null" else framed("present", value)
        fun of(session: SessionStamp, pageRevision: Long, result: VideoSearchResult): DiscoveryThumbnailBinding {
            require(pageRevision > 0)
            // Match existing normalized-result and pool ceilings before hashing untrusted metadata.
            require(result.fingerprint.length in 1..128 && result.canonicalPageUrl.length <= 8192)
            require(result.thumbnailUrl == null || result.thumbnailUrl.length <= 8192)
            require(result.title == null || result.title.length <= 512)
            require(result.sourceName.length <= 128 && (result.sourceNamespace == null || result.sourceNamespace.length <= 128))
            require(result.sourceVideoId == null || result.sourceVideoId.length <= 512)
            require(result.description == null || result.description.length <= 4096)
            require(result.provenance.size <= 32 && result.provenance.all { it.length <= 128 })
            require(result.identityAliases.size <= 128 && result.identityAliases.all { it.length <= 128 })
            require((listOfNotNull(result.fingerprint, result.canonicalPageUrl, result.thumbnailUrl, result.title,
                result.sourceName, result.sourceNamespace, result.sourceVideoId, result.description) +
                result.provenance + result.identityAliases).all(::isWellFormedUnicode))
            // Encode before retaining: caller-owned mutable rights/provenance collections cannot alter a grant.
            val rights = Base64.getEncoder().encodeToString(RightsCodec.encode(result.rights))
            val observation = digest(framed(result.fingerprint, result.canonicalPageUrl, optional(result.thumbnailUrl),
                optional(result.title), result.sourceName, optional(result.sourceNamespace), optional(result.sourceVideoId),
                result.durationMs.toString(), optional(result.description), result.publishedAtEpochMillis.toString(),
                result.identityVersion.toString(), framed(*result.provenance.sorted().toTypedArray()),
                framed(*result.identityAliases.sorted().toTypedArray()), rights))
            return DiscoveryThumbnailBinding(session, pageRevision, result.resultId, observation)
        }
    }
}

/** Explicit integration budgets, with ceilings no larger than the existing Library thumbnail lane.
 * No product defaults are introduced. Exactly one read can own resources at a time.
 */
data class DiscoveryThumbnailBudget(
    val width: Int, val height: Int, val maxFrameBytes: Long,
    val maxVisible: Int, val maxQueued: Int, val timeoutMillis: Long,
) {
    init {
        require(width in 1..256 && height in 1..256)
        require(maxFrameBytes in 1..262_144)
        require(maxVisible in 1..16 && maxQueued in 1..16 && timeoutMillis in 1..10_000)
    }
}

/** Borrowed only during onFrame. close is idempotent and must not throw. Never retain or draw after close. */
interface DiscoveryThumbnailFrame : AutoCloseable {
    val width: Int
    val height: Int
    val allocationBytes: Long
    override fun close()
}

/** Released certifies all reader/native resources are released, except the returned frame. */
sealed interface DiscoveryThumbnailRead {
    data class Released(val frame: DiscoveryThumbnailFrame?) : DiscoveryThumbnailRead
    data object ReleaseUnconfirmed : DiscoveryThumbnailRead
}
internal fun interface DiscoveryThumbnailReader {
    fun read(cancelled: () -> Boolean): DiscoveryThumbnailRead
}

/** Possession is an explicit reviewed reader grant, not evidence inferred from ResultRights or thumbnailUrl.
 * Fixtures remain local. The scoped Commons registration SPI supplies the separately reviewed
 * production reader without exposing URLs, Android types or codecs to the feature model.
 */
class DiscoveryThumbnailCapability internal constructor(
    val binding: DiscoveryThumbnailBinding,
    private val reader: DiscoveryThumbnailReader,
    private val sourceCurrent: () -> Boolean = { true },
    private val registeredReader: ((DiscoveryCancellation) -> DiscoveryThumbnailRead)? = null,
) : AutoCloseable {
    private val revoked = AtomicBoolean()
    internal val isRevoked: Boolean get() = revoked.get() || try { !sourceCurrent() } catch (_: Exception) { true }
    private val active = AtomicReference<DiscoveryCancellation?>()
    internal fun read(cancellation: DiscoveryCancellation): DiscoveryThumbnailRead {
        if (isRevoked || cancellation.isCancelled) return DiscoveryThumbnailRead.Released(null)
        check(active.compareAndSet(null, cancellation))
        val drained = CountDownLatch(1)
        cancellation.drained = { drained.countDown() }
        if (cancellation.isDrained) drained.countDown()
        var readerReturned = false
        try {
            if (isRevoked) cancellation.cancel()
            val result = if (cancellation.isCancelled) DiscoveryThumbnailRead.Released(null)
                else registeredReader?.invoke(cancellation) ?: reader.read { revoked.get() || cancellation.isCancelled }
            if (result == DiscoveryThumbnailRead.ReleaseUnconfirmed) cancellation.cancel()
            readerReturned = true
            return result
        } finally {
            // A cancelled transport hook can still be closing resources after read returns.
            // Keep the physical lane occupied until both the reader and those hooks have exited.
            if (!readerReturned) cancellation.cancel()
            // Atomically retire unstarted hooks, or wait if cancellation already claimed them.
            // Checking isCancelled alone would race cancel immediately before active is cleared.
            val completedWithoutCancellation = cancellation.releaseCompletedAttempt()
            var interrupted = false
            while (!completedWithoutCancellation && !cancellation.isDrained) {
                try { drained.await() } catch (_: InterruptedException) { interrupted = true }
            }
            active.compareAndSet(cancellation, null)
            if (interrupted) Thread.currentThread().interrupt()
        }
    }
    override fun close() { revoked.set(true); active.get()?.cancel() }
    override fun toString(): String = "DiscoveryThumbnailCapability([redacted])"

    companion object {
        /** Trusted composition-root SPI, solely used by the reviewed Commons registration.
         * No URL is accepted here. The producer must bind exact current metadata/configuration;
         * this Kotlin API does not sandbox other trusted application code.
         */
        fun fromCommonsRegistration(binding: DiscoveryThumbnailBinding, sourceCurrent: () -> Boolean,
            read: (DiscoveryCancellation) -> DiscoveryThumbnailRead): DiscoveryThumbnailCapability =
            DiscoveryThumbnailCapability(binding, DiscoveryThumbnailReader { DiscoveryThumbnailRead.Released(null) }, sourceCurrent) { cancellation ->
                if (!sourceCurrent() || cancellation.isCancelled) DiscoveryThumbnailRead.Released(null)
                else read(cancellation)
            }

        /** Caller affirms these fixture pixels are owned/local. Copies input; no URL is dereferenced. */
        fun ownedRgbaFixture(binding: DiscoveryThumbnailBinding, width: Int, height: Int, pixels: ByteArray): DiscoveryThumbnailCapability {
            require(width in 1..256 && height in 1..256 && pixels.size == width * height * 4)
            val owned = pixels.copyOf()
            return DiscoveryThumbnailCapability(binding, DiscoveryThumbnailReader { cancelled ->
                DiscoveryThumbnailRead.Released(if (cancelled()) null else RgbaFixtureFrame(width, height, owned.copyOf()))
            })
        }
    }
}

/** Pixel inspection is scoped to the borrowed callback. Read-only view becomes invalid at close. */
class RgbaFixtureFrame internal constructor(
    override val width: Int, override val height: Int, private var pixels: ByteArray?,
) : DiscoveryThumbnailFrame {
    override val allocationBytes: Long = width.toLong() * height * 4
    fun pixels(): ByteBuffer = ByteBuffer.wrap(checkNotNull(pixels)).asReadOnlyBuffer()
    override fun close() { pixels?.fill(0); pixels = null }
}
