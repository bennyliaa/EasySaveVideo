package com.easysavevideo.app.ui

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.easysavevideo.app.discovery.DiscoveryReadingAnchor
import com.easysavevideo.app.discovery.DiscoveryReadingAnchorPolicy
import com.easysavevideo.app.discovery.DiscoveryReadingPageKey
import com.easysavevideo.app.discovery.DiscoveryReadingRestorationClaim
import com.easysavevideo.discovery.SessionStamp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect

private val discoveryReadingAnchorSaver = Saver<DiscoveryReadingAnchor?, List<Any>>(
    save = { anchor -> anchor?.let(DiscoveryReadingAnchorPolicy::encode) ?: emptyList() },
    restore = DiscoveryReadingAnchorPolicy::decode,
)

/** UI restoration only: no page demand, history, search or background-work authority. */
@Composable
internal fun PreserveDiscoveryReadingAnchor(
    grid: LazyGridState,
    pageKey: DiscoveryReadingPageKey?,
    resultIds: List<String>,
    restoredFromStamp: SessionStamp?,
    visible: Boolean,
    currentPageKey: () -> DiscoveryReadingPageKey?,
) {
    var anchor by rememberSaveable(stateSaver = discoveryReadingAnchorSaver) {
        mutableStateOf<DiscoveryReadingAnchor?>(null)
    }
    val latestCurrentPageKey by rememberUpdatedState(currentPageKey)
    val latestVisible by rememberUpdatedState(visible)
    val claim = remember(pageKey, visible, resultIds, restoredFromStamp) {
        pageKey?.takeIf { visible }?.let(::DiscoveryReadingRestorationClaim)
    }
    DisposableEffect(claim) {
        onDispose { claim?.retire() }
    }
    LaunchedEffect(claim) {
        if (claim == null || pageKey == null) return@LaunchedEffect
        fun current() = latestVisible && claim.isCurrent(latestCurrentPageKey())
        if (!current()) return@LaunchedEffect
        val position = DiscoveryReadingAnchorPolicy.resolve(anchor, pageKey, resultIds, restoredFromStamp)
        // A same-page return restores its stable result, while genuinely new page/query keys reset.
        restoreDiscoveryReadingPosition { grid.scrollToItem(position.index, position.scrollOffset) }
        if (!current()) return@LaunchedEffect
        snapshotFlow { grid.firstVisibleItemIndex to grid.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                // A cancelled/retired restore or a newer controller page cannot overwrite the anchor.
                if (current()) {
                    DiscoveryReadingAnchorPolicy.capture(pageKey, resultIds, index, offset)?.let { anchor = it }
                }
            }
    }
}

/** A user drag can cancel only the inner scroll mutation; keep observing without fighting it.
 * Parent effect cancellation still propagates, so an obsolete page never starts capture. */
internal suspend fun restoreDiscoveryReadingPosition(restore: suspend () -> Unit) {
    try {
        restore()
    } catch (_: CancellationException) {
        currentCoroutineContext().ensureActive()
    }
}
