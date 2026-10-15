package com.easysavevideo.app.editor.engine

import com.easysavevideo.app.media.transform.*
import com.easysavevideo.library.EditInputRef
import com.easysavevideo.library.edit.EditError
import com.easysavevideo.library.edit.EditFailure
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal class EditorTransformerSource(private val inputs: EditorInputPort, private val reference: EditInputRef) : TransformerOwnedSource {
    override fun retainNativeReader(): AutoCloseable = inputs.retainTyped(reference)
    override fun openReader(): TransformerReadHandle = EditorTransformerRead(inputs.openTyped(reference))
}
internal class EditorTransformerRead(val input: EditorOwnedInput) : TransformerReadHandle {
    override val extent: Long = input.sizeBytes
    private val closed = AtomicBoolean(false)
    @Volatile private var confirmed = false
    override val releaseConfirmed: Boolean get() = confirmed
    override fun verifyIdentity() {
        if (closed.get() || !input.stream.fd.valid() || input.stream.channel.size() != extent) throw EditFailure(EditError.STALE_SOURCE)
    }
    override fun readAt(position: Long, target: ByteArray, offset: Int, length: Int): Int {
        verifyIdentity()
        if (position < 0 || position > extent || length < 0 || length.toLong() > extent - position ||
            offset < 0 || offset > target.size || length > target.size - offset) throw EditFailure(EditError.STALE_SOURCE)
        return input.stream.channel.read(ByteBuffer.wrap(target, offset, length), position).also { verifyIdentity() }
    }
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            input.close()
            if (input.stream.fd.valid() || input.stream.channel.isOpen) throw EditFailure(EditError.RELEASE_UNCONFIRMED)
            confirmed = true
        }
    }
}

/** Converts the already issued Editor target; the Transformer path is only a random match token. */
internal class EditorTransformerTarget(private val output: EditorOwnedOutput) : TransformerOwnedTarget {
    override val muxerToken = "esv-transform-output-${UUID.randomUUID()}"
    override fun verifyIdentity() = output.verifyIdentity()
    override fun sizeBytes(): Long = output.file.length()
    override fun availableBytes(): Long = output.availableBytes()
    override fun retainNativeWriter(): AutoCloseable = output.retainNativeWriter()
    override fun openEmptyWriter(): TransformerWriter {
        verifyIdentity()
        if (sizeBytes() != 0L) throw EditFailure(EditError.INVALID_STATE)
        return Writer(output.openWriter())
    }
    @androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
    private class Writer(private val stream: FileOutputStream) : TransformerWriter {
        private val channel = stream.channel
        private val closed = AtomicBoolean(false)
        @Volatile private var confirmed = false
        override val releaseConfirmed: Boolean get() = confirmed
        override fun beginFinalization() = Unit // Shared bounded policy controls cancellation/finalization.
        override fun write(source: ByteBuffer): Int = channel.write(source)
        override fun getPosition(): Long = channel.position()
        override fun setPosition(position: Long) { channel.position(position) }
        override fun getSize(): Long = channel.size()
        override fun truncate(size: Long) { channel.truncate(size) }
        override fun isOpen(): Boolean = channel.isOpen
        override fun force() = channel.force(true)
        override fun close() {
            if (closed.compareAndSet(false, true)) {
                stream.close()
                if (stream.fd.valid() || channel.isOpen) throw EditFailure(EditError.RELEASE_UNCONFIRMED)
                confirmed = true
            }
        }
    }
}
