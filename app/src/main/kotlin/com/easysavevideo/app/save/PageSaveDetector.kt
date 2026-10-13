package com.easysavevideo.app.save

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import com.easysavevideo.core.model.BrowserContextRef
import com.easysavevideo.save.SourceUrl
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URI
import java.util.UUID

/** Short-lived, private functional values, never diagnostic payloads. */
data class PageSaveCandidate(
    val id: String,
    val url: SourceUrl,
    val label: String,
    val width: Int?,
    val height: Int?,
    val durationMillis: Long?,
    val expectedAudio: Boolean,
    val protected: Boolean,
    val supported: Boolean,
    val method: com.easysavevideo.core.model.TaskMethod = com.easysavevideo.core.model.TaskMethod.DIRECT,
) {
    override fun toString() = "PageSaveCandidate([redacted])"
}
class PageSaveSelection(
    val context: BrowserContextRef,
    val epoch: Long,
    val pageUrl: String,
    val candidates: List<PageSaveCandidate>,
    val expiresAtMillis: Long,
    private val stillCurrent: () -> Boolean,
) {
    fun isCurrent() = System.currentTimeMillis() < expiresAtMillis && stillCurrent()
    override fun toString() = "PageSaveSelection([redacted])"
}
sealed interface PageSaveDetection {
    data class Found(val selection: PageSaveSelection) : PageSaveDetection
    data class Unavailable(val reason: Reason) : PageSaveDetection
    enum class Reason { STALE_PAGE, NO_MEDIA, UNSUPPORTED, JAVASCRIPT_DISABLED, TIMED_OUT, INVALID_RESPONSE }
}

/** No bridges, resource interception, observers, timers in the page, iframe traversal or network IO. */
class PageSaveDetector {
    private val handler = Handler(Looper.getMainLooper())
    private var generation = 0L
    private var pendingTimeout: Runnable? = null
    fun cancel() {
        check(Looper.myLooper() == Looper.getMainLooper())
        generation++; pendingTimeout?.let(handler::removeCallbacks); pendingTimeout = null
    }
    /** Called only by the native Save button. The caller fences tab/session/epoch/View identity. */
    fun inspectFromUserClick(view: WebView, context: BrowserContextRef, epoch: Long, pageUrl: String,
        stillCurrent: () -> Boolean, callback: (PageSaveDetection) -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        cancel()
        if (!view.settings.javaScriptEnabled) { callback(PageSaveDetection.Unavailable(PageSaveDetection.Reason.JAVASCRIPT_DISABLED)); return }
        if (!stillCurrent() || view.url != pageUrl) { callback(PageSaveDetection.Unavailable(PageSaveDetection.Reason.STALE_PAGE)); return }
        val token = generation
        var delivered = false
        fun deliver(result: PageSaveDetection) {
            if (token != generation || delivered) return
            delivered = true; pendingTimeout?.let(handler::removeCallbacks); pendingTimeout = null
            callback(result)
        }
        pendingTimeout = Runnable { deliver(PageSaveDetection.Unavailable(PageSaveDetection.Reason.TIMED_OUT)) }.also {
            handler.postDelayed(it, 5_000)
        }
        try {
            view.evaluateJavascript(SCRIPT) { response ->
                if (token != generation || delivered) return@evaluateJavascript
                if (!stillCurrent() || view.url != pageUrl) {
                    deliver(PageSaveDetection.Unavailable(PageSaveDetection.Reason.STALE_PAGE)); return@evaluateJavascript
                }
                val result = try {
                    if (response == null || response.length > 256 * 1024) throw IllegalArgumentException()
                    val decoded = JSONTokener(response).nextValue() as? String ?: throw IllegalArgumentException()
                    if (decoded.length > 192 * 1024) throw IllegalArgumentException()
                    val root = JSONObject(decoded)
                    if (root.optString("page") != pageUrl) throw IllegalArgumentException()
                    val array = root.getJSONArray("items")
                    if (array.length() > 32) throw IllegalArgumentException()
                    val candidates = ArrayList<PageSaveCandidate>()
                    val seen = HashSet<String>()
                    repeat(array.length()) { i ->
                        val item = array.getJSONObject(i)
                        val raw = item.optString("url")
                        if (raw.length !in 1..8192) return@repeat
                        val uri = URI(raw)
                        if (!uri.scheme.equals("https", true) || uri.host == null || uri.rawUserInfo != null) return@repeat
                        // Preserve query bytes; never rebuild a signed URL through decoded URI parts.
                        val source = SourceUrl(raw.substringBefore('#'))
                        val width = item.optInt("width").takeIf { it in 1..8192 }
                        val height = item.optInt("height").takeIf { it in 1..8192 }
                        val seconds = item.optDouble("duration", Double.NaN)
                        val duration = (seconds * 1000).takeIf { it.isFinite() && it > 0 && it <= Long.MAX_VALUE.toDouble() }?.toLong()
                        val mime = item.optString("type").lowercase().substringBefore(';')
                        val path = uri.path.orEmpty().lowercase()
                        val hls = mime in setOf("application/vnd.apple.mpegurl", "application/x-mpegurl", "audio/mpegurl", "audio/x-mpegurl") || path.endsWith(".m3u8")
                        val mp4 = mime == "video/mp4" || mime == "application/mp4" || path.endsWith(".mp4") ||
                            (mime.isBlank() && !path.endsWith(".m3u8") && !path.endsWith(".mpd") && !path.endsWith(".webm") && !path.endsWith(".mov"))
                        if (!seen.add(source.value)) {
                            // currentSrc has no MIME in WebView; a later matching <source> can carry
                            // the explicit HLS type even when the signed endpoint has no extension.
                            if (hls) {
                                val index = candidates.indexOfFirst { it.url == source }
                                if (index >= 0) candidates[index] = candidates[index].copy(supported = true,
                                    method = com.easysavevideo.core.model.TaskMethod.STREAM)
                            }
                            return@repeat
                        }
                        candidates.add(PageSaveCandidate(UUID.randomUUID().toString(), source,
                            "视频 ${item.optInt("video", i + 1).coerceIn(1, 32)} · 资源 ${i + 1}", width, height, duration,
                            item.optBoolean("audio"), item.optBoolean("protected"), mp4 || hls,
                            if (hls) com.easysavevideo.core.model.TaskMethod.STREAM else com.easysavevideo.core.model.TaskMethod.DIRECT))
                    }
                    if (candidates.isEmpty()) PageSaveDetection.Unavailable(if (root.optBoolean("unsupported"))
                        PageSaveDetection.Reason.UNSUPPORTED else PageSaveDetection.Reason.NO_MEDIA)
                    else PageSaveDetection.Found(PageSaveSelection(context, epoch, pageUrl, candidates,
                        System.currentTimeMillis() + 60_000, stillCurrent))
                } catch (_: Exception) { PageSaveDetection.Unavailable(PageSaveDetection.Reason.INVALID_RESPONSE) }
                deliver(result)
            }
        } catch (_: RuntimeException) { deliver(PageSaveDetection.Unavailable(PageSaveDetection.Reason.INVALID_RESPONSE)) }
    }
    companion object {
        // Trusted fixed script. Page-supplied strings are data and are never interpolated as source code.
        private val SCRIPT = """
            (function() {
              'use strict';
              var items = [], unsupported = false, videos = document.getElementsByTagName('video');
              function add(v, url, type, index) {
                if (!url || typeof url !== 'string' || url.length > 8192 || items.length >= 32) return;
                if (url.indexOf('https://') !== 0) { unsupported = true; return; }
                items.push({url:url, type:(typeof type === 'string' ? type.substring(0,128) : ''),
                  video:index, width:v.videoWidth || 0, height:v.videoHeight || 0,
                  duration:Number.isFinite(v.duration) ? v.duration : null,
                  audio:!!(v.webkitAudioDecodedByteCount > 0 || v.mozHasAudio === true || (v.audioTracks && v.audioTracks.length > 0)),
                  protected:!!v.mediaKeys});
              }
              for (var i=0; i<videos.length && i<32 && items.length<32; i++) {
                var v=videos[i]; add(v,v.currentSrc,'',i+1); add(v,v.src,v.type,i+1);
                var children=v.getElementsByTagName('source');
                for(var j=0;j<children.length && j<32 && items.length<32;j++) add(v,children[j].src,children[j].type,i+1);
              }
              return JSON.stringify({page:String(location.href).substring(0,8192),items:items,unsupported:unsupported});
            })();
        """.trimIndent()
    }
}
