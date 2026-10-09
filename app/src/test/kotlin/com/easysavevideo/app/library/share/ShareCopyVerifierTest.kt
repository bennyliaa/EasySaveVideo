package com.easysavevideo.app.library.share

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

class ShareCopyVerifierTest {
    private val bytes = ByteArray(150_123) { (it % 251).toByte() }
    private val digest get() = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    @Test fun exactCopyRequiresFinalEof() {
        var eof = false
        val input = object : ByteArrayInputStream(bytes) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it == -1) eof = true }
        }
        ShareCopyVerifier.verify(input, bytes.size.toLong(), digest)
        assertTrue(eof)
    }
    @Test fun shortCopyFails() = fails { ShareCopyVerifier.verify(ByteArrayInputStream(bytes.copyOf(bytes.size - 1)), bytes.size.toLong(), digest) }
    @Test fun trailingCopyFails() = fails { ShareCopyVerifier.verify(ByteArrayInputStream(bytes + byteArrayOf(0)), bytes.size.toLong(), digest) }
    @Test fun corruptionFails() = fails { ShareCopyVerifier.verify(ByteArrayInputStream(bytes.clone().apply { this[1] = 99 }), bytes.size.toLong(), digest) }
    @Test fun zeroReadIsBoundedFailure() = fails {
        ShareCopyVerifier.verify(object : InputStream() { override fun read() = 0; override fun read(b: ByteArray, off: Int, len: Int) = 0 }, bytes.size.toLong(), digest)
    }
    @Test fun cancellationBeforeAndAfterRead() {
        fails { ShareCopyVerifier.verify(ByteArrayInputStream(bytes), bytes.size.toLong(), digest) { true } }
        var calls = 0
        fails { ShareCopyVerifier.verify(ByteArrayInputStream(bytes), bytes.size.toLong(), digest) { ++calls >= 2 } }
    }
    private fun fails(block: () -> Unit) { try { block(); fail("Expected bounded verification failure") } catch (_: IOException) { } }
}
