package com.easysavevideo.app.licenses

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.easysavevideo.app.theme.*

/** Bundled, offline text. No website, remote license fetch, or product-agreement acceptance. */
@Composable
fun ThirdPartyNotices(onClose: () -> Unit) {
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
                    text.chunked(4_096)
                }
            } catch (_: Exception) { null }
            main.post { if (active) { sections = result.orEmpty(); unavailable = result == null } }
        }, "esv-offline-notices").apply { isDaemon = true; start() }
        onDispose { active = false; worker.interrupt() }
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(visuals.spacingMedium)) {
        EsvText("第三方软件许可", style = visuals.titleTextStyle)
        EsvButton("返回设置", onClick = onClose)
        if (unavailable) EsvText("许可文本无法读取，请勿分发此构建。", color = visuals.colors.error)
        else if (sections.isEmpty()) EsvText("正在读取随应用提供的许可文本…")
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(visuals.spacingSmall)) {
            items(sections.size) { index -> EsvText(sections[index], style = visuals.captionTextStyle) }
        }
    }
}
