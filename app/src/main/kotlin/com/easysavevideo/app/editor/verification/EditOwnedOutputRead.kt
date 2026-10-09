package com.easysavevideo.app.editor.verification

import com.easysavevideo.library.edit.EditOutputRef
import java.io.FileInputStream

/**
 * Private staging or closed-recording capability supplied by its storage owner after the writer actually closes.
 * It must pin one immutable owned inode/extent, deny publication/deletion/replacement while open,
 * and never resolve a caller-supplied path or URL. No ordinary catalog item is opened by this API. Closed raw recording verification uses a separate owner, not a Save receipt.
 */
interface EditOwnedOutputRead {
    val output: EditOutputRef
    val expectedBytes: Long
    val input: FileInputStream

    /** Close the descriptor and release the storage read fence. Called after native release only. */
    fun closeAfterNativeRelease()

    /** Retain the descriptor and storage fence until process recovery. Must not close anything. */
    fun retainForProcessRecovery()
}
