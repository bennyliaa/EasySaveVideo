package com.easysavevideo.app.vault

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException

/** New isolated fixed-data fixture, intentionally not wired into old probes or run automatically.
 * Requires a real Android runtime. Compiling it is not evidence that these assertions ran.
 */
internal object VaultPlaybackDataSourceFixedFixture {
    fun runAssertions(): Int {
        var assertions = 0
        fun verify(value: Boolean) { check(value) { "VAULT_FIXED_DATASOURCE_ASSERTION" }; assertions++ }
        val read = FixedRead()
        val factory = VaultMedia3DataSource.Factory(read)
        val source = factory.createDataSource()
        var initialized = 0; var started = 0; var transferred = 0; var ended = 0
        source.addTransferListener(object : TransferListener {
            override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) { verify(!isNetwork); initialized++ }
            override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) { verify(!isNetwork); started++ }
            override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) { verify(!isNetwork); transferred += bytesTransferred }
            override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) { verify(!isNetwork); ended++ }
        })
        fun spec(position: Long = 0, length: Long = C.LENGTH_UNSET.toLong()) = DataSpec.Builder().setUri(Uri.EMPTY)
            .setPosition(position).setLength(length).build()
        verify(source.uri == null)
        verify(source.open(spec(8, 8)) == 8L)
        verify(source.uri == Uri.EMPTY && source.responseHeaders.isEmpty())
        val buffer = ByteArray(8) { 99 }
        verify(source.read(buffer, 0, 8) == 2 && buffer[0] == 8.toByte() && buffer[1] == 9.toByte() && buffer[2] == 99.toByte())
        verify(source.read(buffer, 0, 8) == C.RESULT_END_OF_INPUT)
        source.close(); source.close(); verify(source.uri == null)
        verify(initialized == 1 && started == 1 && transferred == 2 && ended == 1)
        verify(source.open(spec(10)) == 0L); verify(source.read(buffer, 0, 0) == 0); verify(source.read(buffer, 0, 1) == C.RESULT_END_OF_INPUT); source.close()
        verify(source.open(spec(8, Long.MAX_VALUE)) == Long.MAX_VALUE); source.close()
        try { source.open(spec(11)); error("expected range error") }
        catch (error: IOException) { verify(DataSourceException.isCausedByPositionOutOfRange(error)); verify(error.cause == null) }
        finally { source.close() }
        val progressive = DataSpec.Builder().setUri(Uri.EMPTY).setHttpRequestHeaders(mapOf("Icy-MetaData" to "1"))
            .setFlags(DataSpec.FLAG_DONT_CACHE_IF_LENGTH_UNKNOWN or DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION).build()
        verify(source.open(progressive) == 10L); source.close()
        val badSpecs = listOf(
            DataSpec.Builder().setUri(Uri.parse("https://example.invalid/fixed")).build(),
            DataSpec.Builder().setUri(Uri.parse("file:///fixed")).build(),
            DataSpec.Builder().setUri(Uri.parse("content://fixed/item")).build(),
            DataSpec.Builder().setUri(Uri.EMPTY).setHttpMethod(DataSpec.HTTP_METHOD_POST).build(),
            DataSpec.Builder().setUri(Uri.EMPTY).setHttpBody(byteArrayOf(1)).build(),
            DataSpec.Builder().setUri(Uri.EMPTY).setKey("FIXED_SECRET_NAME").build(),
            DataSpec.Builder().setUri(Uri.EMPTY).setCustomData(Any()).build(),
            DataSpec.Builder().setUri(Uri.EMPTY).setUriPositionOffset(1).build(),
            DataSpec.Builder().setUri(Uri.EMPTY).setHttpRequestHeaders(mapOf("X-Fixed" to "secret")).build())
        val beforeBad = read.opens
        badSpecs.forEach { bad ->
            try { source.open(bad); error("expected rejection") }
            catch (error: IOException) { verify(error.message == "VAULT_SPEC_REJECTED" && error.cause == null) }
            finally { source.close() }
        }
        verify(read.opens == beforeBad)
        verify(source.open(spec()) == 10L)
        val second = factory.createDataSource()
        try { second.open(spec()); error("expected concurrent rejection") } catch (_: IOException) { verify(read.open == 1) }
        finally { second.close() }
        source.close(); verify(second.open(spec(5)) == 5L); second.close(); verify(read.open == 0)
        // Failure after actual open must keep transport ownership until mandatory close.
        val partial = factory.createDataSource()
        partial.addTransferListener(object : TransferListener {
            override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
            override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) { throw IllegalStateException("FIXED_SECRET_CAUSE") }
            override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) = Unit
            override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
        })
        try { partial.open(spec()); error("expected listener failure") }
        catch (error: IOException) { verify(error.message == "VAULT_READ_FAILED" && error.cause == null); verify(read.open == 1) }
        partial.close(); partial.close(); verify(read.open == 0)
        verify(source.open(spec()) == 10L); read.current = false
        try { source.read(buffer,0,1); error("expected revoked read") } catch (error: IOException) { verify(error.cause == null) }
        source.close(); verify(read.open == 0)
        return assertions
    }

    private class FixedRead : VaultPlaybackReadPort {
        override val authenticatedSize = 10L
        var open = 0; var opens = 0; var current = true
        override fun requireCurrent() { if (!current) throw IOException("FIXED_SECRET_CAUSE") }
        override fun readAt(position: Long, destination: ByteArray, offset: Int, length: Int): Int {
            requireCurrent(); val count = minOf(length.toLong(), authenticatedSize - position).toInt()
            repeat(count) { destination[offset + it] = (position + it).toByte() }; return count
        }
        override fun transportOpened() { requireCurrent(); if (open != 0) throw IOException("FIXED_BUSY"); open++; opens++ }
        override fun transportClosed() { check(open == 1); open-- }
    }
}
