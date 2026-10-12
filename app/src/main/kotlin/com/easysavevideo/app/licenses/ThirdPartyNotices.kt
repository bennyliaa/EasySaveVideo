package com.easysavevideo.app.licenses

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.easysavevideo.app.theme.*
import androidx.compose.ui.res.stringResource
import com.easysavevideo.app.R

/** Bundled, offline text. No website, remote license fetch, or product-agreement acceptance. */
@Composable
fun ThirdPartyNotices(onClose: () -> Unit) {
    ThirdPartyNotices(listState = rememberLazyListState(), onClose = onClose)
}

@Composable
fun ThirdPartyNotices(listState: LazyListState, onClose: () -> Unit) {
    val visuals = LocalEsvVisuals.current
    val context = LocalContext.current.applicationContext
    var sections by remember { mutableStateOf<List<String>>(emptyList()) }
    var unavailable by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        var active = true
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val worker = Thread({
            val result = try {
                context.assets.open("third-party-notices.txt").bufferedReader().use { reader ->
                    val text = readNoticeText(reader)
                    noticeSections(text)
                }
            } catch (_: Exception) { null }
            main.post { if (active) { sections = result.orEmpty(); unavailable = noticeLoadUnavailable(result) } }
        }, "esv-offline-notices").apply { isDaemon = true; start() }
        onDispose { active = false; worker.interrupt() }
    }
    // Loading/error must not measure the retained content state with a shorter item set.
    // All controls and text share one finite viewport: large headers cannot starve the body.
    val transientState = rememberLazyListState()
    val contentVisible = noticeContentVisible(unavailable, sections)
    LazyColumn(Modifier.fillMaxSize(), state = if (contentVisible) listState else transientState,
        verticalArrangement = Arrangement.spacedBy(visuals.spacingSmall)) {
        item(key = "notice-title") { EsvText(stringResource(R.string.notice_title), style = visuals.titleTextStyle) }
        item(key = "notice-back") { EsvButton(stringResource(R.string.notice_back_settings), onClick = onClose) }
        if (unavailable) item(key = "notice-unavailable") { EsvText(stringResource(R.string.notice_unavailable), color = visuals.colors.error) }
        else if (!contentVisible) item(key = "notice-loading") { EsvText(stringResource(R.string.notice_loading)) }
        if (contentVisible) {
            items(sections.size, key = { index -> "notice-section-$index" }) { index ->
                EsvText(sections[index], style = visuals.captionTextStyle)
            }
        }
    }
}
