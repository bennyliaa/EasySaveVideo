package com.easysavevideo.app.library.share

import com.easysavevideo.app.diagnostics.InitializationDiagnostic
import com.easysavevideo.app.diagnostics.InitializationProbe
import com.easysavevideo.app.diagnostics.InitializationStage
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.file.Files

/** Same fail-closed filesystem guards as the owner, isolated for local fault-injection tests. */
internal fun initializeShareDirectory(directory: File, probe: InitializationProbe) {
    probe.at(InitializationStage.SHARE_DIRECTORY_CHECK) { require(!Files.isSymbolicLink(directory.toPath())) }
    probe.at(InitializationStage.SHARE_DIRECTORY_CREATE) { require(directory.isDirectory || directory.mkdirs()) }
    probe.at(InitializationStage.SHARE_DIRECTORY_CANONICAL) { require(directory.canonicalFile == directory) }
}

internal class ShareOwnerLease(val file: RandomAccessFile, val lock: FileLock)

/** Retain the unsafe owner and its first bounded failure across subsequent denied attempts. */
internal class UnconfirmedShareOwner(val file: RandomAccessFile, val diagnostic: InitializationDiagnostic?) {
    fun failure() = ShareFailure(ShareError.RELEASE_UNCONFIRMED, diagnostic)
}

internal fun acquireShareOwner(
    directory: File,
    probe: InitializationProbe,
    openOwner: (File) -> RandomAccessFile = { RandomAccessFile(it, "rw") },
    retainUnconfirmedOwner: (RandomAccessFile) -> Unit,
): ShareOwnerLease {
    val lease = File(directory, ".owner")
    probe.at(InitializationStage.SHARE_OWNER_CHECK) { require(!Files.isSymbolicLink(lease.toPath())) }
    val file = probe.at(InitializationStage.SHARE_OWNER_OPEN) { openOwner(lease) }
    val lock = try {
        probe.at(InitializationStage.SHARE_OWNER_LOCK) { file.channel.tryLock() ?: throw IOException("SHARE_BUSY") }
    } catch (failure: Exception) {
        try { file.close() } catch (_: Exception) { retainUnconfirmedOwner(file) }
        throw failure
    }
    return ShareOwnerLease(file, lock)
}
