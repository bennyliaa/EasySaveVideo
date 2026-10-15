package com.easysavevideo.app.discovery.thumbnail

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Source wiring only; does not initialize Android, Coil, Handler, Bitmap or the process singleton. */
class CommonsThumbnailWiringContractTest {
    private fun source(name: String): String {
        val root = listOf(File("."), File("app")).first { File(it, "src/main/kotlin").isDirectory }
        return File(root, "src/main/kotlin/com/easysavevideo/app/discovery/thumbnail/$name").readText()
    }

    private fun assertBefore(source: String, first: String, second: String) {
        val firstIndex = source.indexOf(first)
        val secondIndex = source.indexOf(second)
        assertTrue("Missing first operation: $first", firstIndex >= 0)
        assertTrue("Missing second operation: $second", secondIndex >= 0)
        assertTrue("Expected $first before $second", firstIndex < secondIndex)
    }

    @Test fun coilReceivesOnlyTheBoundedBodyAndHasNoMemoryDiskOrHardwarePath() {
        val reader = source("CommonsThumbnailReader.kt")
        for (required in listOf("ImageLoader.Builder(app).memoryCache(null).diskCache(null).build()",
            "CommonsThumbnailPolicy.readBody(", "val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }",
            "CommonsThumbnailPolicy.sourceDimensions(bounds.outWidth, bounds.outHeight)",
            "ImageRequest.Builder(app).data(bytes)", ".scale(Scale.FIT).precision(Precision.EXACT)",
            ".allowHardware(false).allowRgb565(false).bitmapConfig(Bitmap.Config.ARGB_8888)",
            "bitmap.allocationByteCount.toLong()", "bitmap.config != Bitmap.Config.HARDWARE"))
            assertTrue(required, reader.contains(required))
        assertBefore(reader, "CommonsThumbnailPolicy.readBody(", "BitmapFactory.decodeByteArray")
        assertBefore(reader, "CommonsThumbnailPolicy.sourceDimensions(bounds.outWidth", "loader.execute(")
        assertFalse(reader.contains(".data(url)"))
        for (forbidden in listOf("CookieManager", "WebView", "COMMONS_CONTACT", "android.util.Log", "HttpLoggingInterceptor"))
            assertFalse(forbidden, reader.contains(forbidden))
    }

    @Test fun responsePolicyChecksIdentityMimeEncodingLengthAndCancellationBeforeDecode() {
        val reader = source("CommonsThumbnailReader.kt")
        for (required in listOf("CommonsUrlPolicy.thumbnail(url) != url", "response.code == 200",
            "response.request.url.toString() == url", "mime in CommonsThumbnailPolicy.mimeTypes",
            "response.header(\"Content-Encoding\")", "it.equals(\"identity\", true)",
            "rawLength?.toLongOrNull()", "rawLength == null || declared != null",
            ".header(\"Accept-Encoding\", \"identity\")", "call.cancel(); decoding.cancel()",
            "runBlocking(decoding)", "runBlocking { decoding.join() }"))
            assertTrue(required, reader.contains(required))
        assertBefore(reader, "response.request.url.toString() == url", "CommonsThumbnailPolicy.readBody(")
        assertBefore(reader, "rawLength == null || declared != null", "CommonsThumbnailPolicy.readBody(")
        assertBefore(reader, "CommonsThumbnailPolicy.readBody(", "BitmapFactory.decodeByteArray")
    }

    @Test fun laneHandlerReaderAndCooldownRemainProcessScopedAcrossControllerReplacement() {
        val adapter = source("AndroidDiscoveryThumbnails.kt")
        val process = adapter.substringAfter("private object CommonsImageProcess {")
            .substringBefore("internal data class DiscoveryThumbnailImageState")
        for (required in listOf("Handler(Looper.getMainLooper())", "val lane = CommonsThumbnailLane(",
            "private var reader: CommonsThumbnailReader? = null", "@Synchronized fun reader(context: Context)",
            "CommonsThumbnailReader(context.applicationContext).also { reader = it }"))
            assertTrue(required, process.contains(required))
        val owner = adapter.substringAfter("internal class AndroidDiscoveryThumbnails : AutoCloseable")
        assertTrue(owner.contains("CommonsImageProcess.lane, dispatcher, CommonsImageProcess.lane::poison"))
        assertTrue(owner.contains("CommonsImageProcess.reader(context).read(url, cancellation)"))
        assertFalse(owner.contains("CommonsThumbnailLane("))
        assertFalse(owner.contains("CommonsThumbnailReader("))
        assertFalse(owner.contains("shutdownNow("))
        assertFalse(owner.contains("removeCallbacksAndMessages("))
        val reader = source("CommonsThumbnailReader.kt")
        assertTrue(reader.contains("private val retryNotBefore = AtomicLong(0)"))
        assertTrue(reader.contains("retryNotBefore.accumulateAndGet(retryAt)"))
    }

    @Test fun adapterUsesThePublishedEpochWithoutBorrowingANewerGenerationDuringIssuance() {
        val adapter = source("AndroidDiscoveryThumbnails.kt")
        val update = adapter.substringAfter("fun update(").substringBefore("fun state(")
        for (required in listOf("expectedGeneration: Long",
            "if (closed.get() || expectedGeneration != generation.get()) return",
            "val epoch = expectedGeneration", "CommonsThumbnailPolicy.issueAtGeneration(epoch, generation::get)",
            "issue(binding, result, epoch)")) assertTrue(required, update.contains(required))
        assertBefore(update, "expectedGeneration != generation.get()", "val epoch = expectedGeneration")
        assertBefore(update, "CommonsThumbnailPolicy.issueAtGeneration(epoch, generation::get)", "issue(binding, result, epoch)")
        assertFalse(update.contains("val epoch = generation.get()"))
        assertFalse(update.contains("issue(binding, result, generation.get())"))
    }

    @Test fun withdrawalRevokesCapabilitiesBeforeUiCleanupAndRenderingChecksGeneration() {
        val adapter = source("AndroidDiscoveryThumbnails.kt")
        val withdraw = adapter.substringAfter("fun withdraw() {").substringBefore("fun update(")
        assertBefore(withdraw, "generation.incrementAndGet()", "CommonsImageProcess.main.post")
        assertBefore(withdraw, "capabilities.values.forEach { it.close() }", "CommonsImageProcess.main.post")
        assertTrue(withdraw.contains("generation.get() == next"))
        assertTrue(adapter.contains("appliedGeneration == generation.get()"))
        assertTrue(adapter.contains("capabilities[binding.resultId]?.binding == binding"))
        assertTrue(adapter.contains("page == null || closed.get() || appliedGeneration != generation.get()"))
        assertTrue(adapter.contains("bytes <= CommonsThumbnailPolicy.MAX_RETAINED"))
        assertTrue(adapter.contains("laneSubscription.close()"))
    }
}
