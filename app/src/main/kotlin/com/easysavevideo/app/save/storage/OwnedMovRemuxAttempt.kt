package com.easysavevideo.app.save.storage

import com.easysavevideo.app.save.normalization.MovRemuxFiles
import com.easysavevideo.app.save.normalization.MovRemuxInput
import com.easysavevideo.app.save.normalization.MovRemuxOutput
import com.easysavevideo.core.model.OperationContext
import com.easysavevideo.save.SaveError
import com.easysavevideo.save.SaveFailure
import java.io.IOException
import java.nio.ByteBuffer

/** A fresh, non-copyable, synchronous worker capability. close() never deletes or rewinds its row. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class OwnedMovRemuxAttempt internal constructor(
    private val owner: NormalizationAttemptOwner,
    private val files: AndroidNormalizationFiles,
    internal val writingRow: NormalizationWorkspace,
    internal val context: OperationContext,
    internal val raw: NormalizationBoundFile,
    private val emptyOutput: NormalizationFileIdentity,
    internal val outputMaximum: Long,
    internal val sourceIdentity: String,
    internal val sourceTransferSha256: String,
    private val thread: Thread,
) : MovRemuxFiles, AutoCloseable {
    val key: NormalizationKey get() = writingRow.key
    private var revoked = false
    private var failed = false
    private var consumed = false
    private var finishing = false
    private var calls = 0
    private var sourceTried = false
    private var outputTried = false
    private var readerTried = false
    private var source: Input? = null
    private var sink: Output? = null
    private var reader: Input? = null
    internal var finalizedIdentity: NormalizationFileIdentity? = null; private set
    private val lock get() = files.ownerLock

    private fun check() = synchronized(lock) {
        if (Thread.currentThread() !== thread || revoked || failed || consumed) fail()
        owner.check(this)
    }
    private fun <T> operation(block: () -> T): T {
        synchronized(lock) { check(); calls++ }
        try { return block() }
        catch (failure: Throwable) {
            synchronized(lock) { failed = true }
            // A failed positional syscall has unknown exceptional progress. Do not retry or
            // count bytes; preserve cancellation/deadline without masking release uncertainty.
            if (failure is IOException) owner.checkStop(context)
            throw failure
        }
        finally { synchronized(lock) { calls-- }; owner.retire(this) }
    }
    override fun openSource(): MovRemuxInput = operation { synchronized(lock) {
        if (sourceTried || outputTried || readerTried) fail()
        sourceTried = true
        val descriptor = files.open(key, NormalizationRole.PRIMARY, NormalizationDescriptorIo.Open.READ, raw.identity, ::check, ::released)
        Input(NormalizationRole.PRIMARY, raw.identity, descriptor).also { source = it }
    } }
    override fun openEmptyOutput(): MovRemuxOutput = operation { synchronized(lock) {
        if (outputTried || readerTried || source == null || source!!.releaseConfirmed) fail()
        outputTried = true // An attempted open consumes the only grant, including open failure.
        files.validate(key, NormalizationRole.OUTPUT, emptyOutput)
        if (emptyOutput.stat.size != 0L) fail()
        val descriptor = files.open(key, NormalizationRole.OUTPUT, NormalizationDescriptorIo.Open.WRITE_EXISTING, emptyOutput, ::check, ::released)
        Output(descriptor).also { sink = it }
    } }
    override fun openFinalizedOutput(): MovRemuxInput = operation { synchronized(lock) {
        val output = sink ?: fail()
        if (readerTried || !output.releaseConfirmed) fail()
        readerTried = true
        val identity = files.capture(key, NormalizationRole.OUTPUT, outputMaximum)
        if (identity.stat != output.expected || !identity.stat.sameInode(emptyOutput.stat)) fail()
        val descriptor = files.open(key, NormalizationRole.OUTPUT, NormalizationDescriptorIo.Open.READ, identity, ::check, ::released)
        Input(NormalizationRole.OUTPUT, identity, descriptor).also { reader = it; finalizedIdentity = identity }
    } }
    override fun availableBytes(): Long = operation { files.availableBytes() }

    internal fun consumeForFinish() = synchronized(lock) {
        check()
        if (calls != 0 || source == null || sink == null || reader == null ||
            source?.releaseConfirmed != true || sink?.releaseConfirmed != true || reader?.releaseConfirmed != true || finalizedIdentity == null) fail()
        consumed = true; finishing = true; revoked = true
    }
    internal fun rejectFinish() = synchronized(lock) { failed = true; revoked = true }
    internal fun finishEnded() = synchronized(lock) { finishing = false }
    internal fun isRetirable(): Boolean = synchronized(lock) {
        revoked && !finishing && calls == 0 && !files.hasLeases(key) && listOfNotNull(source, reader).all { it.releaseConfirmed } && (sink?.releaseConfirmed != false)
    }
    private fun released() { owner.retire(this) }
    override fun close() {
        val handles = synchronized(lock) {
            revoked = true
            listOfNotNull<AutoCloseable>(reader, sink, source)
        }
        var uncertain = false
        for (handle in handles) try { handle.close() } catch (_: Exception) { uncertain = true }
        owner.retire(this)
        if (uncertain || synchronized(lock) { calls != 0 || handles.any {
                when (it) { is Input -> !it.releaseConfirmed; is Output -> !it.releaseConfirmed; else -> true }
            } }) throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
    }

    private inner class Input(
        private val role: NormalizationRole,
        private val identity: NormalizationFileIdentity,
        private val descriptor: AndroidNormalizationFiles.Descriptor,
    ) : MovRemuxInput {
        override val length: Long get() = identity.stat.size
        override val releaseConfirmed: Boolean get() = descriptor.releaseConfirmed
        override fun validateIdentity() = operation { descriptor.call {
            check(); files.validate(key, role, identity, descriptor)
        } }
        override fun readAt(offset: Long, target: ByteArray, targetOffset: Int, length: Int) = operation {
            if (offset < 0 || offset > this.length || length < 0 || length.toLong() > this.length - offset ||
                targetOffset < 0 || targetOffset > target.size || length > target.size - targetOffset) throw IOException("NORMALIZATION_READ_RANGE")
            descriptor.call {
                check(); files.validate(key, role, identity, descriptor)
                var done = 0
                while (done < length) {
                    check(); files.validate(key, role, identity, descriptor)
                    val requested = minOf(CHUNK, length - done)
                    // EINTR/InterruptedIOException are bounded failures. Never infer partial progress.
                    val count = files.io.pread(descriptor.handle, target, targetOffset + done, requested, offset + done)
                    if (count <= 0 || count > requested) throw IOException("NORMALIZATION_READ_PROGRESS")
                    done += count
                    check(); files.validate(key, role, identity, descriptor)
                }
            }
        }
        override fun close() = descriptor.close()
    }

    private inner class Output(private val descriptor: AndroidNormalizationFiles.Descriptor) : MovRemuxOutput {
        private var position = 0L
        internal var expected = emptyOutput.stat; private set
        override val releaseConfirmed: Boolean get() = descriptor.releaseConfirmed
        override fun isOpen(): Boolean = !descriptor.closeRequested && !descriptor.releaseConfirmed
        private fun validate() { check(); files.validate(key, NormalizationRole.OUTPUT, emptyOutput, descriptor, expected) }
        private fun extent(value: Long) {
            if (value !in 0..outputMaximum) throw SaveFailure(SaveError.BYTE_LIMIT_EXCEEDED)
        }
        private fun updated(size: Long) {
            val after = files.io.fstat(descriptor.handle)
            if (!after.sameInode(emptyOutput.stat) || after.links != 1L || after.size != size || after.size > outputMaximum) fail()
            files.validate(key, NormalizationRole.OUTPUT, emptyOutput, descriptor, after)
            expected = after
        }
        override fun getPosition(): Long = operation { descriptor.call { validate(); position } }
        override fun setPosition(position: Long) = operation { descriptor.call {
            validate(); extent(position); this.position = position
        } }
        override fun getSize(): Long = operation { descriptor.call { validate(); expected.size } }
        override fun truncate(size: Long) = operation { descriptor.call {
            validate(); extent(size)
            // SeekableByteChannel semantics: truncate never extends; position is clamped only on shrink.
            if (size < expected.size) {
                files.io.truncate(descriptor.handle, size)
                updated(size)
                if (position > size) position = size
                check()
            }
        } }
        override fun write(source: ByteBuffer): Int = operation { descriptor.call {
            validate()
            val requested = source.remaining()
            if (requested.toLong() > outputMaximum - position) throw SaveFailure(SaveError.BYTE_LIMIT_EXCEEDED)
            val bytes = ByteArray(minOf(CHUNK, requested))
            var done = 0
            while (done < requested) {
                validate()
                val count = minOf(bytes.size, requested - done)
                val end = position + count // Safe after subtraction bounds above.
                val growth = (end - expected.size).coerceAtLeast(0)
                // Charge sparse gap as well as bytes; overwrites still require chunk headroom.
                if (files.availableBytes() < maxOf(growth, count.toLong())) throw SaveFailure(SaveError.STORAGE_FULL)
                val copy = source.duplicate(); copy.limit(copy.position() + count); copy.get(bytes, 0, count)
                val written = files.io.pwrite(descriptor.handle, bytes, 0, count, position)
                if (written <= 0 || written > count) throw IOException("NORMALIZATION_WRITE_PROGRESS")
                position += written
                source.position(source.position() + written)
                done += written
                updated(maxOf(expected.size, position))
                check()
            }
            requested
        } }
        override fun close() = descriptor.close()
    }
    private fun fail(): Nothing = throw NormalizationRecovery()
    private companion object { const val CHUNK = 64 * 1024 }
}
