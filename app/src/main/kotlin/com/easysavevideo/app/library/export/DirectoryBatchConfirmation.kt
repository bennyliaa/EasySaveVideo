package com.easysavevideo.app.library.export

import com.easysavevideo.app.library.destination.DirectoryPickerLifecycle

/** Opaque, process-local consent identity. It carries no URI, media or persisted authority. */
class DirectoryBatchConfirmation internal constructor() {
    override fun toString() = "DirectoryBatchConfirmation([opaque])"
}

internal enum class DirectoryConfirmationDecision { IGNORED, STALE, CONFIRMED }

/** Check identity and pending phase before evaluating proof: a replay cannot cancel live work. */
internal fun consumeDirectoryBatchConfirmation(
    expected: DirectoryBatchConfirmation,
    current: DirectoryBatchConfirmation,
    lifecycle: DirectoryPickerLifecycle,
    isCurrent: () -> Boolean,
): DirectoryConfirmationDecision {
    if (expected !== current || !lifecycle.canConfirm(true)) return DirectoryConfirmationDecision.IGNORED
    if (!isCurrent()) return DirectoryConfirmationDecision.STALE
    return if (lifecycle.confirm(true)) DirectoryConfirmationDecision.CONFIRMED else DirectoryConfirmationDecision.IGNORED
}
