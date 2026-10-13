package com.easysavevideo.app.library

import com.easysavevideo.app.library.destination.DirectoryPickerLifecycle

/** Process-local navigation continuity, never a grant or permission to create a copy. */
internal class LibraryPickerPageReturn internal constructor(
    private val library: AndroidLibraryRuntime,
    internal val selection: LibraryBatchSelection,
    internal val intentEpoch: Long,
    internal val lifecycle: DirectoryPickerLifecycle,
    internal val related: () -> Boolean,
    internal val onStale: () -> Unit,
) : AutoCloseable {
    internal var refreshStarted = false
    internal var revalidated = false
    internal var retired = false

    fun isCurrent(): Boolean = library.pickerReturnIsCurrent(this)
    override fun close() = library.retirePickerReturn(this)
    override fun toString() = "LibraryPickerPageReturn([opaque])"
}
