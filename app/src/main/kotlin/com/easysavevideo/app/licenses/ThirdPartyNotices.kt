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
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(visuals.spacingMedium)) {
        EsvText(stringResource(R.string.notice_title), style = visuals.titleTextStyle)
        EsvButton(stringResource(R.string.notice_back_settings), onClick = onClose)
        if (unavailable) EsvText(stringResource(R.string.notice_unavailable), color = visuals.colors.error)
        else if (sections.isEmpty()) EsvText(stringResource(R.string.notice_loading))
        // Do not measure an empty list during reload: it can reset the retained scroll position.
        if (noticeContentVisible(unavailable, sections)) {
            LazyColumn(Modifier.weight(1f), state = listState, verticalArrangement = Arrangement.spacedBy(visuals.spacingSmall)) {
                items(sections.size) { index -> EsvText(sections[index], style = visuals.captionTextStyle) }
            }
        }
    }
}
