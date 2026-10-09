package com.easysavevideo.app.dependencies

import com.google.crypto.tink.AccessesPartialKey
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.streamingaead.AesGcmHkdfStreamingKey
import com.google.crypto.tink.streamingaead.PredefinedStreamingAeadParameters
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import com.google.crypto.tink.util.SecretBytes
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Host dependency smoke only. Public fixed test bytes; never use this key for real data. */
class TinkAdmissionSmokeTest {
    @AccessesPartialKey
    private fun primitive(): StreamingAead {
        StreamingAeadConfig.register()
        val publicTestKey = ByteArray(32) { it.toByte() }
        val key = AesGcmHkdfStreamingKey.create(
            PredefinedStreamingAeadParameters.AES256_GCM_HKDF_1MB,
            SecretBytes.copyFrom(publicTestKey, InsecureSecretKeyAccess.get()),
        )
        return KeysetHandle.newBuilder()
            .addEntry(KeysetHandle.importKey(key).withFixedId(1234).makePrimary()).build()
            .getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java)
    }

    @Test fun reviewedPublicApiEncryptsAndAuthenticatesAcrossOneMiBSegment() {
        val aead = primitive()
        val plain = ByteArray(1_048_577) { (it % 251).toByte() }
        val aad = "ESV-PUBLIC-HOST-DEPENDENCY-FIXTURE".toByteArray()
        val sink = ByteArrayOutputStream()
        aead.newEncryptingStream(sink, aad).use { it.write(plain) }
        val ciphertext = sink.toByteArray()
        val recovered = aead.newDecryptingStream(ByteArrayInputStream(ciphertext), aad).use { it.readBytes() }
        assertArrayEquals(plain, recovered)
        assertTrue(ciphertext.size > plain.size)
        try {
            aead.newDecryptingStream(ByteArrayInputStream(ciphertext), byteArrayOf(0)).use { it.readBytes() }
            fail("Wrong AAD must fail")
        } catch (_: IOException) { /* Constant failure only; never log exception data. */ }
    }
}
