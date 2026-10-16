package com.easysavevideo.app.vault.crypto

import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.text.Normalizer
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Fixed R2 wire profile. Changes require a new profile and review, never calibration. */
internal object VaultCryptoProfile {
    const val PROFILE_VERSION = 1
    const val SCHEMA_VERSION = 1
    const val ROOT_VERSION = 1
    const val PBKDF2_ITERATIONS = 600_000
    const val SALT_BYTES = 16
    const val KEY_BYTES = 32
    const val IV_BYTES = 12
    const val TAG_BYTES = 16
    const val MAX_ENVELOPE_BYTES = 16 * 1024
    const val MAX_CATALOG_BYTES = 64 * 1024
    const val MAX_PASSWORD_UTF8_BYTES = 512
    const val MAX_PASSWORD_CODE_POINTS = 128
    private const val MAX_RAW_PASSWORD_CHARS = 1024

    enum class CredentialKind { PIN, PASSWORD }

    fun normalizeCredential(input: CharArray, kind: CredentialKind): CharArray {
        require(input.size <= MAX_RAW_PASSWORD_CHARS) { "credential-size" }
        rejectMalformedAndLineBreaks(input)
        if (kind == CredentialKind.PIN) {
            require(input.size in 6..12 && input.all { it in '0'..'9' }) { "credential-policy" }
            return input.copyOf()
        }
        // java.text.Normalizer requires an immutable String. JVM/provider/IME copies cannot
        // be proven erased; neither the temporary String nor input is saved or logged.
        val normalized = Normalizer.normalize(CharBuffer.wrap(input), Normalizer.Form.NFC)
        val count = normalized.codePointCount(0, normalized.length)
        require(count in 15..MAX_PASSWORD_CODE_POINTS) { "credential-policy" }
        val chars = normalized.toCharArray()
        try {
            rejectMalformedAndLineBreaks(chars)
            val utf8 = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(chars))
            try { require(utf8.remaining() <= MAX_PASSWORD_UTF8_BYTES) { "credential-size" } }
            finally { if (utf8.hasArray()) utf8.array().fill(0) }
            return chars
        } catch (failure: Exception) {
            chars.fill('\u0000')
            throw failure
        }
    }

    fun requireKdfProfile(profile: Int, iterations: Int, saltSize: Int, keySize: Int) {
        require(profile == PROFILE_VERSION && iterations == PBKDF2_ITERATIONS &&
            saltSize == SALT_BYTES && keySize == KEY_BYTES) { "unsupported-crypto-profile" }
    }

    /** The caller owns returned bytes and must clear them after constructing its leaf AEAD. */
    fun deriveWrappingKey(input: CharArray, kind: CredentialKind, salt: ByteArray,
        profile: Int = PROFILE_VERSION, iterations: Int = PBKDF2_ITERATIONS): ByteArray {
        requireKdfProfile(profile, iterations, salt.size, KEY_BYTES)
        val normalized = normalizeCredential(input, kind)
        val spec = PBEKeySpec(normalized, salt, PBKDF2_ITERATIONS, KEY_BYTES * 8)
        try {
            val result = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            if (result.size != KEY_BYTES) { result.fill(0); throw IllegalArgumentException("provider-profile-mismatch") }
            return result
        } finally {
            spec.clearPassword()
            normalized.fill('\u0000')
        }
    }

    /** Strict canonical RFC4648 base64url without padding; no trim or recovery-code KDF. */
    fun decodeRecovery(input: CharArray): ByteArray {
        require(input.size == 43 && input.all { it in 'A'..'Z' || it in 'a'..'z' ||
            it in '0'..'9' || it == '-' || it == '_' }) { "recovery-format" }
        val ascii = ByteArray(input.size) { input[it].code.toByte() }
        var decoded: ByteArray? = null
        try {
            decoded = Base64.getUrlDecoder().decode(ascii)
            require(decoded.size == KEY_BYTES) { "recovery-format" }
            val canonical = Base64.getUrlEncoder().withoutPadding().encode(decoded)
            try { require(canonical.contentEquals(ascii)) { "recovery-format" } }
            finally { canonical.fill(0) }
            return decoded
        } catch (failure: Exception) {
            decoded?.fill(0)
            throw IllegalArgumentException("recovery-format")
        } finally { ascii.fill(0) }
    }

    private fun rejectMalformedAndLineBreaks(input: CharArray) {
        var i = 0
        while (i < input.size) {
            val c = input[i]
            require(c !in charArrayOf('\u0000', '\n', '\r', '\u000B', '\u000C', '\u0085', '\u2028', '\u2029')) {
                "credential-policy"
            }
            when {
                Character.isHighSurrogate(c) -> {
                    require(i + 1 < input.size && Character.isLowSurrogate(input[i + 1])) { "credential-encoding" }
                    i += 2
                }
                Character.isLowSurrogate(c) -> throw IllegalArgumentException("credential-encoding")
                else -> i++
            }
        }
    }
}
