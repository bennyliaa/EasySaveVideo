package com.easysavevideo.app.browser.bookmarks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDirection
import com.easysavevideo.app.R
import com.easysavevideo.app.theme.*

/** Text-only local rows. Browser owns one standard list state across dialog/sidebar hosts. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BookmarkPanel(runtime: AndroidBookmarkRuntime, listState: LazyListState,
    onOpen: (BrowserBookmark) -> Unit, onClose: () -> Unit, canAct: () -> Boolean = { true }) {
    val v = LocalEsvVisuals.current
    val state = runtime.state
    val status = bookmarkPanelStatus(state)
    BoxWithConstraints(Modifier.fillMaxSize().background(v.colors.surface).padding(v.spacingMedium)) {
      val headerMaxHeight = (maxHeight * 0.45f).coerceAtMost(280.dp)
      Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(v.spacingMedium)) {
        // Feedback stays in the visible panel instead of behind its dialog or above distant rows.
        Column(Modifier.fillMaxWidth().heightIn(max = headerMaxHeight)
            .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                EsvText(stringResource(R.string.bookmark_heading, state.items.size, BookmarkPolicy.MAX_ITEMS),
                    style = v.titleTextStyle, modifier = Modifier.weight(1f))
                EsvButton(stringResource(R.string.bookmark_done), onClick = { if (canAct()) onClose() })
            }
            state.notice?.let { notice -> BookmarkNoticeRow(notice, runtime::dismissNotice, canAct = canAct) }
            status.message?.let { message -> EsvText(stringResource(message), style = v.captionTextStyle) }
            if (status.retry) EsvButton(stringResource(R.string.bookmark_retry), onClick = { if (canAct()) runtime.load() })
            EsvText(stringResource(R.string.bookmark_privacy), style = v.captionTextStyle)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState,
            verticalArrangement = Arrangement.spacedBy(v.spacingMedium)) {
        items(state.items, key = { it.id }) { item ->
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                EsvText(item.title, maxLines = 2)
                EsvText(item.address, style = v.captionTextStyle.copy(textDirection = TextDirection.Ltr),
                    color = v.colors.muted, maxLines = 3)
                val openDescription = stringResource(R.string.bookmark_open_description, item.title)
                val removeDescription = stringResource(R.string.bookmark_remove_description, item.title)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(v.spacingSmall),
                    verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                    EsvButton(stringResource(R.string.bookmark_open), onClick = { if (canAct()) onOpen(item) }, enabled = status.rowsEnabled,
                        modifier = Modifier.semantics { contentDescription = openDescription })
                    EsvButton(stringResource(R.string.bookmark_remove), onClick = { if (canAct()) runtime.remove(item) }, enabled = status.rowsEnabled,
                        modifier = Modifier.semantics { contentDescription = removeDescription })
                }
            }
        }
        }
      }
    }
}

@Composable
internal fun BookmarkNoticeRow(notice: BookmarkNotice, onDismiss: () -> Unit, modifier: Modifier = Modifier,
    canAct: () -> Boolean = { true }) {
    val v = LocalEsvVisuals.current
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
        EsvText(bookmarkNoticeText(notice), modifier = Modifier.weight(1f), style = v.captionTextStyle)
        EsvButton(stringResource(R.string.bookmark_dismiss), onClick = { if (canAct()) onDismiss() })
    }
}

@Composable
fun bookmarkNoticeText(notice: BookmarkNotice): String = if (notice == BookmarkNotice.FULL)
    stringResource(bookmarkNoticeResource(notice), BookmarkPolicy.MAX_ITEMS)
else stringResource(bookmarkNoticeResource(notice))
