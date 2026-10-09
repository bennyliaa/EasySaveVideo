package com.easysavevideo.app.library.destination

/** Disjoint from recording's 41320/41321 and AndroidX's >=65536 request codes. No wraparound. */
internal object FolderPickerRequestCodes {
    const val FIRST = 42_000
    const val LAST = 65_534
    fun owns(code: Int): Boolean = code in FIRST..LAST
    fun consume(next: Int, persistNext: (Int) -> Boolean): Int {
        if (next == LAST + 1) throw FolderPickerFailure(FolderPickerError.EXHAUSTED)
        if (!owns(next)) throw FolderPickerFailure(FolderPickerError.INVALID_STATE)
        if (!persistNext(next + 1)) throw FolderPickerFailure(FolderPickerError.STORAGE) // Acknowledgment loss consumes a gap; never launch or reuse it.
        return next
    }
}

internal enum class FolderPickerError { EXHAUSTED, PENDING, INVALID_STATE, STORAGE }
internal class FolderPickerFailure(val code: FolderPickerError) : IllegalStateException(code.name)
