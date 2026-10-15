package com.easysavevideo.app.discovery.thumbnail

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import coil3.BitmapImage
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.request.allowRgb565
import coil3.request.bitmapConfig
import coil3.size.Precision
import coil3.size.Scale
import com.easysavevideo.app.BuildConfig
import com.easysavevideo.commons.CommonsRetryAfter
import com.easysavevideo.commons.CommonsUrlPolicy
import com.easysavevideo.discovery.DiscoveryCancellation
import com.easysavevideo.core.model.DiscoverySettings
import com.easysavevideo.discovery.thumbnail.DiscoveryThumbnailRead
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Proxy
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request

/** Preview resource policy, not an image-format validator or a native peak-memory claim. */
internal object CommonsThumbnailPolicy {
    const val MAX_ENCODED = 1_048_576
    const val WIDTH = 256
    const val HEIGHT = 144
    const val MAX_FRAME = 147_456L
    const val MAX_RETAINED = 2_359_296L
    const val MAX_VISIBLE = 16
    val hosts = setOf("upload.wikimedia.org", "thumb.wikimedia.org")
    val mimeTypes = setOf("image/jpeg", "image/png", "image/webp")
    fun sourceDimensions(width: Int, height: Int): Boolean = width in 1..8192 && height in 1..8192 &&
        width.toLong() * height.toLong() <= 16_777_216L
    fun output(width: Int, height: Int, bytes: Long, software: Boolean): Boolean = software &&
        width in 1..WIDTH && height in 1..HEIGHT && bytes in 1..MAX_FRAME

    /** Suppress unrelated settings notifications; this affects revocation only, not business state. */
    fun settingsInputChanged(previous: AtomicReference<DiscoverySettings>, value: DiscoverySettings): Boolean =
        previous.getAndSet(value) != value

    /** Capture generation first; pending UI intent cannot authorize an unapplied old page. */
    fun publicationGeneration(capturedGeneration: Long, requestedIntent: Long, appliedIntent: Long): Long =
        if (requestedIntent == appliedIntent) capturedGeneration else -1L

    /** A UI snapshot cannot borrow a newer withdrawal epoch before it is published. */
    fun <T> issueAtGeneration(expected: Long, current: () -> Long, issue: () -> T?): T? =
        if (expected == current()) issue() else null

    /** Read through true EOF, including the one-byte probe at the exact cap. */
    fun readBody(input: InputStream, declaredLength: Long?, cancelled: () -> Boolean): ByteArray {
        require(declaredLength == null || declaredLength in 0..MAX_ENCODED.toLong())
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            check(!cancelled())
            val count = input.read(buffer, 0, minOf(buffer.size, MAX_ENCODED - out.size() + 1))
            check(!cancelled())
            if (count < 0) break
            check(count > 0 && count <= MAX_ENCODED - out.size())
            out.write(buffer, 0, count)
        }
        check(declaredLength == null || declaredLength == out.size().toLong())
        return out.toByteArray()
    }
}

internal fun anonymousCommonsImageClient(): OkHttpClient = OkHttpClient.Builder()
    .cookieJar(CookieJar.NO_COOKIES)
    .authenticator(Authenticator.NONE)
    .proxyAuthenticator(Authenticator.NONE)
    .proxy(Proxy.NO_PROXY)
    .cache(null)
    .followRedirects(false)
    .followSslRedirects(false)
    .retryOnConnectionFailure(false)
    .dns(object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            check(hostname in CommonsThumbnailPolicy.hosts)
            return Dns.SYSTEM.lookup(hostname).also { answers ->
                check(answers.isNotEmpty() && answers.all(CommonsUrlPolicy::isPublicAddress))
            }
        }
    })
    .connectTimeout(5, TimeUnit.SECONDS)
    .readTimeout(8, TimeUnit.SECONDS)
    .callTimeout(8, TimeUnit.SECONDS)
    .build()

/** One process-scoped public-image client/loader; no browser state or API contact is consulted. */
internal class CommonsThumbnailReader(context: Context) {
    private val app = context.applicationContext
    private val http = anonymousCommonsImageClient()
    private val loader = ImageLoader.Builder(app).memoryCache(null).diskCache(null).build()
    private val retryNotBefore = AtomicLong(0)

    fun read(url: String, cancellation: DiscoveryCancellation): DiscoveryThumbnailRead {
        if (cancellation.isCancelled || CommonsUrlPolicy.thumbnail(url) != url ||
            System.currentTimeMillis() < retryNotBefore.get()) return DiscoveryThumbnailRead.Released(null)
        val call = http.newCall(Request.Builder().url(url)
            .header("User-Agent", "EasySaveVideo/${BuildConfig.VERSION_NAME}")
            .header("Accept", "image/jpeg, image/png, image/webp")
            .header("Accept-Encoding", "identity").build())
        val decoding = Job()
        val cancel = cancellation.onCancel { call.cancel(); decoding.cancel() }
        return try {
            if (cancellation.isCancelled) return DiscoveryThumbnailRead.Released(null)
            val bytes = call.execute().use { response ->
                if (response.code == 429 || response.code == 503) {
                    val retryAt = CommonsRetryAfter.retryAt(response.header("Retry-After"), System.currentTimeMillis())
                    retryNotBefore.accumulateAndGet(retryAt) { previous, next -> maxOf(previous, next) }
                }
                check(response.code == 200 && !cancellation.isCancelled)
                check(response.request.url.toString() == url)
                val mime = response.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase()
                check(mime in CommonsThumbnailPolicy.mimeTypes)
                check(response.header("Content-Encoding")?.let { it.equals("identity", true) } != false)
                val rawLength = response.header("Content-Length")
                val declared = rawLength?.toLongOrNull()
                check(rawLength == null || declared != null)
                CommonsThumbnailPolicy.readBody(checkNotNull(response.body).byteStream(), declared) { cancellation.isCancelled }
            }
            check(!cancellation.isCancelled)
            // Platform header-only inspection. No pixel decode or container parser is duplicated.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            check(CommonsThumbnailPolicy.sourceDimensions(bounds.outWidth, bounds.outHeight))
            check(!cancellation.isCancelled)
            // ByteArrayFetcher -> BitmapFactoryDecoder in Coil 3.3.0; structured execute does not
            // release this physical reader while a cancelled platform decode is still returning.
            val result = runBlocking(decoding) {
                loader.execute(ImageRequest.Builder(app).data(bytes)
                    .size(CommonsThumbnailPolicy.WIDTH, CommonsThumbnailPolicy.HEIGHT)
                    .scale(Scale.FIT).precision(Precision.EXACT)
                    .allowHardware(false).allowRgb565(false).bitmapConfig(Bitmap.Config.ARGB_8888).build())
            }
            val bitmap = ((result as? SuccessResult)?.image as? BitmapImage)?.bitmap
            if (cancellation.isCancelled || bitmap == null || !CommonsThumbnailPolicy.output(bitmap.width, bitmap.height,
                    bitmap.allocationByteCount.toLong(), bitmap.config != Bitmap.Config.HARDWARE))
                DiscoveryThumbnailRead.Released(null)
            else DiscoveryThumbnailRead.Released(AndroidDiscoveryThumbnailFrame(bitmap))
        } catch (_: Exception) {
            // Includes ordinary HTTP/TLS/decode/cancellation failure. Never log response or exception text.
            DiscoveryThumbnailRead.Released(null)
        } finally {
            cancel.cancel()
            // runBlocking already waited for its structured work; cancel the otherwise-active parent.
            decoding.cancel()
            runBlocking { decoding.join() }
        }
    }
}
