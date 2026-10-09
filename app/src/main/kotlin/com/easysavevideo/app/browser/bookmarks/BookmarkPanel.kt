package com.easysavevideo.app.browser.bookmarks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDirection
import com.easysavevideo.app.theme.*

/** Text-only local rows: no thumbnails, favicons, WebViews, links or background requests. */
@Composable
fun BookmarkPanel(runtime: AndroidBookmarkRuntime, onOpen: (BrowserBookmark) -> Unit, onClose: () -> Unit) {
    val v = LocalEsvVisuals.current
    val state = runtime.state
    LazyColumn(Modifier.fillMaxSize().background(v.colors.surface).padding(v.spacingMedium),
        verticalArrangement = Arrangement.spacedBy(v.spacingMedium)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                EsvText("收藏（${state.items.size}/${BookmarkPolicy.MAX_ITEMS}）", style = v.titleTextStyle, modifier = Modifier.weight(1f))
                EsvButton("完成", onClick = onClose)
            }
        }
        item { EsvText("收藏只保存在本机普通浏览空间。点击“打开”后才访问网页；清除 Cookie 或关闭标签恢复不会删除收藏。", style = v.captionTextStyle) }
        if (state.busy) item { EsvText(if (state.notice == BookmarkNotice.SLOW) "存储响应较慢，正在等待本次操作完成。" else "正在读取或保存…", style = v.captionTextStyle) }
        if (!state.ready && !state.busy) item {
            Column(verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                EsvText("收藏暂不可用，本次结果尚未确认。请检查存储空间后重新读取。", style = v.captionTextStyle)
                EsvButton("重新读取", onClick = runtime::load)
            }
        }
        if (state.ready && state.items.isEmpty()) item { EsvText("还没有收藏。打开网页后，点“收藏此页”。", style = v.captionTextStyle) }
        items(state.items, key = { it.id }) { item ->
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                EsvText(item.title, maxLines = 2)
                EsvText(item.address, style = v.captionTextStyle.copy(textDirection = TextDirection.Ltr),
                    color = v.colors.muted, maxLines = 3)
                Row(horizontalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                    EsvButton("打开", onClick = { onOpen(item) }, enabled = state.ready && !state.busy,
                        modifier = Modifier.semantics { contentDescription = "打开收藏：${item.title}" })
                    EsvButton("删除", onClick = { runtime.remove(item) }, enabled = state.ready && !state.busy,
                        modifier = Modifier.semantics { contentDescription = "删除收藏：${item.title}" })
                }
            }
        }
    }
}

fun bookmarkNoticeText(notice: BookmarkNotice): String = when (notice) {
    BookmarkNotice.ADDED -> "已收藏此网页。"
    BookmarkNotice.DUPLICATE -> "此地址已在收藏中。"
    BookmarkNotice.REMOVED -> "已删除这条收藏。"
    BookmarkNotice.FULL -> "已达到 200 条收藏上限。请先删除不再需要的收藏。"
    BookmarkNotice.UNAVAILABLE -> "收藏读取或保存未完成，未确认成功。打开收藏列表，检查存储空间后重新读取。"
    BookmarkNotice.SLOW -> "收藏存储响应较慢，正在等待本次操作完成。"
    BookmarkNotice.STALE_PAGE -> "网页已变化或尚未加载完成，请在当前网页重新点“收藏此页”。"
}
