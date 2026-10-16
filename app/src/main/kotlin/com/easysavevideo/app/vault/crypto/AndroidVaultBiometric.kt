package com.easysavevideo.app.vault.crypto

import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.security.keystore.KeyPermanentlyInvalidatedException
import com.easysavevideo.app.vault.*
import com.google.crypto.tink.Aead
import com.google.crypto.tink.BinaryKeysetReader
import com.google.crypto.tink.KeysetHandle
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.concurrent.Executor
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher

/** Optional slot leaf. Not registered/enabled by the password-only V1 composition. */
internal class AndroidVaultBiometric(
    private val androidContext: Context,
    private val keystore: AndroidVaultKeystore,
    private val executor: Executor,
    private val scheduler: ScheduledExecutorService,
    private val clock: VaultMonotonicClock,
) {
    enum class Failure { CANCELLED, TIMEOUT, TEMPORARY_LOCKOUT, PERMANENT_LOCKOUT, SLOT_UNAVAILABLE, REJECTED }
    sealed interface Outcome<out T> {
        class Completed<T>(val value: T) : Outcome<T>
        class Failed(val failure: Failure) : Outcome<Nothing>
    }
    class Pending internal constructor(private val cancelAction: () -> Unit,
        private val platformTerminal: AtomicBoolean) : AutoCloseable {
        /** false after cancellation/timeout until the actual platform callback returns. */
        fun isPlatformTerminal(): Boolean = platformTerminal.get()
        override fun close() = cancelAction()
    }

    fun wrapRoot(identity: VaultPersistentIdentity, slotId: String, generation: Long, root: KeysetHandle, title: CharSequence,
        cancelText: CharSequence, context: VaultOperationContext, completed: (Outcome<VaultCiphertext>) -> Unit): Pending {
        require(generation > 0) { "slot-generation" }
        val immutableAad = VaultCryptoWire.aad(identity, VaultAadPurpose.BIOMETRIC_ROOT, VaultSlotKind.BIOMETRIC, slotId, generation)
        val cipher = keystore.biometricEncryptCipher(identity.biometricKeyAlias)
        cipher.updateAAD(immutableAad)
        return authenticate(cipher, title, cancelText, context, completed) {
            val once = AtomicBoolean(false)
            val aead = object : Aead {
                override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray {
                    require(plaintext.size <= 16_384 - 28 && MessageDigest.isEqual(immutableAad, associatedData) &&
                        once.compareAndSet(false, true)) { "biometric-operation-binding" }
                    return cipher.iv + cipher.doFinal(plaintext)
                }
                override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray =
                    throw GeneralSecurityException("biometric-direction")
            }
            VaultCiphertext(VaultTinkKeysets.wrap(root, aead, immutableAad))
        }
    }

    fun unwrapRoot(identity: VaultPersistentIdentity, slotId: String, generation: Long, persistedEnvelope: VaultCiphertext,
        title: CharSequence, cancelText: CharSequence, context: VaultOperationContext,
        completed: (Outcome<KeysetHandle>) -> Unit): Pending {
        require(persistedEnvelope.size <= 16_384 && generation > 0) { "envelope-size" }
        val envelope = persistedEnvelope.copyBytes()
        VaultTinkKeysets.requireCanonicalEncrypted(envelope)
        val immutableAad = VaultCryptoWire.aad(identity, VaultAadPurpose.BIOMETRIC_ROOT, VaultSlotKind.BIOMETRIC, slotId, generation)
        // This bounded outer parse supplies only untrusted IV/ciphertext to Cipher.init.
        // KeysetInfo is never an authority; Tink re-parses/authenticates the same immutable bytes.
        val ciphertext = BinaryKeysetReader.withBytes(envelope).readEncrypted().encryptedKeyset.toByteArray()
        require(ciphertext.size in 28..16_384) { "envelope-size" }
        val cipher = keystore.biometricDecryptCipher(identity.biometricKeyAlias, ciphertext)
        cipher.updateAAD(immutableAad)
        return authenticate(cipher, title, cancelText, context, completed) {
            val once = AtomicBoolean(false)
            val aead = object : Aead {
                override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray =
                    throw GeneralSecurityException("biometric-direction")
                override fun decrypt(input: ByteArray, associatedData: ByteArray): ByteArray {
                    require(MessageDigest.isEqual(ciphertext, input) && MessageDigest.isEqual(immutableAad, associatedData) &&
                        once.compareAndSet(false, true)) { "biometric-operation-binding" }
                    return cipher.doFinal(input, 12, input.size - 12)
                }
            }
            VaultTinkKeysets.unwrap(envelope, aead, immutableAad)
        }
    }

    private fun <T> authenticate(cipher: Cipher, title: CharSequence, cancelText: CharSequence,
        context: VaultOperationContext, completed: (Outcome<T>) -> Unit, operation: () -> T): Pending {
        val bounded = context.bounded(VaultRuntimeLimits.PROMPT_MILLIS)
        val cancellation = CancellationSignal()
        val resultSent = AtomicBoolean(false)
        val platformTerminal = AtomicBoolean(false)
        val callbackClaimed = AtomicBoolean(false)
        val submitted = BiometricPrompt.CryptoObject(cipher)
        fun fail(failure: Failure) {
            if (resultSent.compareAndSet(false, true)) completed(Outcome.Failed(failure))
        }
        val prompt = BiometricPrompt.Builder(androidContext).setTitle(title)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButton(cancelText, executor) { _, _ ->
                fail(Failure.CANCELLED); cancellation.cancel()
            }.build()
        val delay = bounded.deadlineElapsedMillis - clock.elapsedMillis()
        if (delay <= 0) throw VaultOperationException(VaultFailure.DEADLINE)
        val timer = scheduler.schedule({ fail(Failure.TIMEOUT); cancellation.cancel() }, delay, TimeUnit.MILLISECONDS)
        val pending = Pending({ fail(Failure.CANCELLED); cancellation.cancel() }, platformTerminal)
        try {
            bounded.checkActive()
            prompt.authenticate(submitted, cancellation, executor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (!callbackClaimed.compareAndSet(false, true)) return
                    try {
                        if (resultSent.get()) return
                        bounded.checkActive()
                        if (result.cryptoObject !== submitted || result.cryptoObject?.cipher !== cipher) {
                            fail(Failure.REJECTED); return
                        }
                        val value = operation() // Actual current Cipher.doFinal; platform success alone is insufficient.
                        bounded.checkActive()
                        if (resultSent.compareAndSet(false, true)) completed(Outcome.Completed(value))
                    } catch (_: KeyPermanentlyInvalidatedException) { fail(Failure.SLOT_UNAVAILABLE) }
                    catch (_: VaultOperationException) { fail(Failure.CANCELLED) }
                    catch (_: GeneralSecurityException) { fail(Failure.REJECTED) }
                    catch (_: IllegalArgumentException) { fail(Failure.REJECTED) }
                    finally { timer.cancel(false); platformTerminal.set(true) }
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    timer.cancel(false)
                    if (callbackClaimed.compareAndSet(false, true)) platformTerminal.set(true)
                    fail(when (errorCode) {
                        BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT -> Failure.TEMPORARY_LOCKOUT
                        BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT_PERMANENT -> Failure.PERMANENT_LOCKOUT
                        BiometricPrompt.BIOMETRIC_ERROR_CANCELED, BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED -> Failure.CANCELLED
                        BiometricPrompt.BIOMETRIC_ERROR_TIMEOUT -> Failure.TIMEOUT
                        else -> Failure.SLOT_UNAVAILABLE
                    }) // Never log platform error text; failure is local to this slot.
                }
                override fun onAuthenticationFailed() { /* Platform retains this same attempt. */ }
            })
        } catch (_: Exception) {
            timer.cancel(false); platformTerminal.set(true); fail(Failure.SLOT_UNAVAILABLE)
        }
        return pending
    }
}
