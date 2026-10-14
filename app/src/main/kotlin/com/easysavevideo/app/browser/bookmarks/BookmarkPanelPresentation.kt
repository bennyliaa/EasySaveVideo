package com.easysavevideo.app.browser.bookmarks

import com.easysavevideo.app.R

/** Presentation of the existing controller state, with no optimistic operation result. */
internal data class BookmarkPanelStatus(val message: Int?, val retry: Boolean, val rowsEnabled: Boolean)
internal fun bookmarkPanelStatus(state: BookmarkState): BookmarkPanelStatus = BookmarkPanelStatus(
    message = when {
        state.busy -> if (state.notice == BookmarkNotice.SLOW) null else R.string.bookmark_loading
        !state.ready -> if (state.notice == BookmarkNotice.UNAVAILABLE) null else R.string.bookmark_unavailable
        state.items.isEmpty() -> R.string.bookmark_empty
        else -> null
    },
    retry = !state.ready && !state.busy,
    rowsEnabled = state.ready && !state.busy,
)
internal fun bookmarkNoticeResource(notice: BookmarkNotice): Int = when (notice) {
    BookmarkNotice.ADDED -> R.string.bookmark_added
    BookmarkNotice.DUPLICATE -> R.string.bookmark_duplicate
    BookmarkNotice.REMOVED -> R.string.bookmark_removed
    BookmarkNotice.FULL -> R.string.bookmark_full
    BookmarkNotice.UNAVAILABLE -> R.string.bookmark_unavailable
    BookmarkNotice.SLOW -> R.string.bookmark_slow
    BookmarkNotice.STALE_PAGE -> R.string.bookmark_stale_page
}
