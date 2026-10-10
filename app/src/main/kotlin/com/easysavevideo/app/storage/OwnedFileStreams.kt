package com.easysavevideo.app.storage

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * FileChannel.close calls its parent stream's close. Closing the channel first lets that nested
 * stream close finish the descriptor before ownership is checked. Checking after super.close
 * alone can run inside its channel callback, throw while the descriptor is still valid, and
 * prevent the outer close from ever closing it. No lock is held across either platform close.
 */
internal class OwnedFileStreamRelease(
    private val ownershipLock: Any,
    private val onReleased: () -> Unit,
    private val onUnconfirmed: () -> Nothing,
) {
    private var released = false
    private val depth = ThreadLocal.withInitial { 0 }

    fun close(closeChannel: () -> Unit, closeStream: () -> Unit, descriptorClosed: () -> Boolean) {
        val priorDepth=depth.get()
        depth.set(priorDepth+1)
        try {
            closeChannel()
            closeStream()
        } finally {
            if(priorDepth==0) depth.remove() else depth.set(priorDepth)
            synchronized(ownershipLock) {
                val confirmed = try { descriptorClosed() } catch (_: IOException) { false }
                if (confirmed && !released) {
                    released = true
                    onReleased()
                }
            }
        }
        // Windows descriptor.closeAll invokes attached stream.close before closing its native
        // handle. That nested callback must return so the outer close can establish FD evidence.
        // It neither publishes release nor stands in for an outer successful close.
        if (priorDepth==0 && !synchronized(ownershipLock) { released }) onUnconfirmed()
    }
}

internal class OwnedFileInputStream(
    file: File,
    ownershipLock: Any,
    onReleased: () -> Unit,
    onUnconfirmed: () -> Nothing,
) : FileInputStream(file) {
    private val release = OwnedFileStreamRelease(ownershipLock, onReleased, onUnconfirmed)
    override fun close() = release.close({ channel.close() }, { super.close() }, { !fd.valid() })
}

internal class OwnedFileOutputStream(
    file: File,
    append: Boolean = false,
    ownershipLock: Any,
    onReleased: () -> Unit,
    onUnconfirmed: () -> Nothing,
) : FileOutputStream(file, append) {
    private val release = OwnedFileStreamRelease(ownershipLock, onReleased, onUnconfirmed)
    override fun close() = release.close({ channel.close() }, { super.close() }, { !fd.valid() })
}
