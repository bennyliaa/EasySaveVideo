package com.easysavevideo.app.vault.crypto

import com.easysavevideo.app.vault.*
import com.easysavevideo.core.model.VaultAttemptIdentity
import com.easysavevideo.core.model.VaultRetryLedger
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.IdentityHashMap

/** Real production adapter, instantiated only behind the separately closed entry gate.
 * One serial worker calls operations; registry revocation never waits on KDF or platform work.
 * Setup executes persistent-key creation and therefore requires the protected authorized flow.
 */
internal class AndroidVaultAuthentication(
    private val keystore: AndroidVaultKeystore = AndroidVaultKeystore(),
) : VaultAuthenticationPort {
    private val operationLock = Any()
    private val registryLock = Any()
    private var closed = false
    private val roots = IdentityHashMap<VaultRootCapability, Root>()
    private val proofs = IdentityHashMap<VaultStagedVerification, Proof>()
    private val mutations = IdentityHashMap<VaultCredentialMutation, Mutation>()

    /** Ciphertext-only registry entry. The original operation remains authoritative across
     * narrower worker/SQL deadlines; a new operation cannot resurrect a cancelled proof.
     */
    private inner class Mutation(val fingerprint: ByteArray, val proposal: VaultCredentialMutationProposal,
        val operation: VaultOperationContext) : VaultCredentialMutation {
        override fun close() { synchronized(registryLock) { mutations.remove(this); fingerprint.fill(0) } }
        override fun toString() = "VaultCredentialMutation(REDACTED)"
    }

    private inner class Root(
        var keyset: KeysetHandle?, val identity: VaultPersistentIdentity,
        val attempt: VaultAttemptIdentity, val catalogBinding: ByteArray,
    ) : VaultRootCapability {
        override fun close() { synchronized(registryLock) { roots.remove(this); keyset = null; catalogBinding.fill(0) } }
        override fun toString() = "VaultRootCapability(REDACTED)"
    }
    private inner class Proof(val fingerprint: ByteArray) : VaultStagedVerification {
        override fun requireMatches(snapshot: VaultStoredSnapshot) = validateStagedVerification(snapshot, this)
        override fun close() { synchronized(registryLock) { proofs.remove(this); fingerprint.fill(0) } }
        override fun toString() = "VaultStagedVerification(REDACTED)"
    }

    override fun hasExistingVaultAliases(context: VaultOperationContext): Boolean = serial(context) {
        keystore.hasExistingVaultAliases().also { context.checkActive() }
    }

    override fun validatePasswordInput(input: VaultCredentialInput) {
        val chars = input.copyCharacters()
        try { VaultCryptoProfile.normalizeCredential(chars, profileKind(input.kind)).fill('\u0000') }
        catch (_: IllegalArgumentException) { throw VaultOperationException(VaultFailure.REJECTED) }
        finally { chars.fill('\u0000') }
    }

    override fun preparePasswordSetup(identity: VaultPersistentIdentity, input: VaultCredentialInput,
        context: VaultOperationContext): VaultStagedData = serial(context) {
        VaultCryptoWire.requireIdentity(identity)
        // Validate before any entropy or KeyStore side effect. Values never enter a DTO or log.
        val chars = input.copyCharacters()
        try { VaultCryptoProfile.normalizeCredential(chars, profileKind(input.kind)).fill('\u0000') }
        finally { chars.fill('\u0000') }
        context.checkActive()
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        passwordAead(input, salt, context).use { password ->
            context.checkActive()
            keystore.createDeviceKey(identity.deviceKeyAlias, requireHardware = input.kind == VaultCredentialKind.PIN)
            context.checkActive()
            val device = keystore.deviceAead(identity.deviceKeyAlias, requireHardware = input.kind == VaultCredentialKind.PIN)
            val root = VaultTinkKeysets.freshAes256Gcm()
            context.checkActive()
            val catalog = VaultTinkKeysets.freshAes256Gcm()
            val kind = slotKind(input.kind)
            val slotAad = passwordAad(identity, kind, salt)
            val outerAad = passwordAad(identity, kind, salt, VaultAadPurpose.DEVICE_PASSWORD_OUTER)
            val encryptedRoot = VaultTinkKeysets.wrap(root, nestedPassword(password, device, outerAad), slotAad)
            val slot = VaultSlotRecord(VaultCryptoWire.PASSWORD_SLOT, 1, kind,
                VaultCiphertext(VaultCryptoWire.passwordEnvelope(salt, encryptedRoot)), required = true)
            val encryptedCatalogKey = VaultTinkKeysets.wrap(catalog, VaultTinkKeysets.primitive(root),
                VaultCryptoWire.aad(identity, VaultAadPurpose.ROOT_CATALOG_KEYSET, revision = 1))
            val catalogPlain = VaultCryptoWire.emptyCatalog(identity, slot, 1)
            val catalogCipher = try { VaultTinkKeysets.primitive(catalog).encrypt(catalogPlain,
                VaultCryptoWire.aad(identity, VaultAadPurpose.CATALOG, revision = 1)) }
                finally { catalogPlain.fill(0) }
            val initialLedger = VaultRetryLedger(0, 0, -1, null)
            val marker = device.encrypt(VaultCryptoWire.marker(identity),
                VaultCryptoWire.aad(identity, VaultAadPurpose.INSTALLATION_MARKER))
            val result = VaultStagedData(listOf(slot), VaultCiphertext(encryptedCatalogKey),
                VaultCiphertext(catalogCipher, VaultRuntimeLimits.EMPTY_CATALOG_BYTES), 1,
                VaultCiphertext(marker), initialLedger, sealLedger(identity, initialLedger, context))
            context.checkActive()
            result // Setup never publishes or registers a root capability.
        }
    }

    override fun verifyStaged(snapshot: VaultStoredSnapshot, input: VaultCredentialInput,
        context: VaultOperationContext): VaultStagedVerification = serial(context) {
        if (snapshot.phase != VaultStorePhase.STAGED) throw VaultOperationException(VaultFailure.INCOMPLETE)
        val root = unwrapPassword(snapshot, input, context)
        verifyCatalog(root, snapshot, context)
        val fingerprint = VaultCryptoWire.snapshotFingerprint(snapshot)
        synchronized(registryLock) {
            requireOpen(); context.checkActive()
            Proof(fingerprint).also { proofs[it] = it }
        }
    }

    override fun validateStagedVerification(snapshot: VaultStoredSnapshot, verification: VaultStagedVerification) {
        synchronized(registryLock) {
            requireOpen()
            val proof = proofs[verification] ?: throw VaultOperationException(VaultFailure.REJECTED)
            if (snapshot.phase != VaultStorePhase.STAGED ||
                !MessageDigest.isEqual(proof.fingerprint, VaultCryptoWire.snapshotFingerprint(snapshot)))
                throw VaultOperationException(VaultFailure.CONFLICT)
        }
    }

    override fun authenticateLedger(snapshot: VaultStoredSnapshot, context: VaultOperationContext): VaultRetryLedger = serial(context) {
        val data = requireData(snapshot)
        val device = keystore.deviceAead(snapshot.identity.deviceKeyAlias)
        val marker = device.decrypt(data.installationMarker.copyBytes(),
            VaultCryptoWire.aad(snapshot.identity, VaultAadPurpose.INSTALLATION_MARKER))
        try { require(MessageDigest.isEqual(marker, VaultCryptoWire.marker(snapshot.identity))) { "marker-integrity" } }
        finally { marker.fill(0) }
        val expected = VaultCryptoWire.ledger(data.retryLedger)
        val actual = device.decrypt(data.retryLedgerEnvelope.copyBytes(),
            VaultCryptoWire.aad(snapshot.identity, VaultAadPurpose.RETRY_LEDGER, revision = data.retryLedger.ledgerRevision))
        try { require(MessageDigest.isEqual(actual, expected)) { "ledger-integrity" } }
        finally { expected.fill(0); actual.fill(0) }
        context.checkActive()
        data.retryLedger
    }

    override fun sealLedger(identity: VaultPersistentIdentity, ledger: VaultRetryLedger,
        context: VaultOperationContext): VaultCiphertext = serial(context) {
        val plain = VaultCryptoWire.ledger(ledger)
        try { VaultCiphertext(keystore.deviceAead(identity.deviceKeyAlias).encrypt(plain,
            VaultCryptoWire.aad(identity, VaultAadPurpose.RETRY_LEDGER, revision = ledger.ledgerRevision))) }
        finally { plain.fill(0); context.checkActive() }
    }

    override fun unlockPassword(snapshot: VaultStoredSnapshot, input: VaultCredentialInput,
        attempt: VaultAttemptIdentity, context: VaultOperationContext): VaultRootCapability = serial(context) {
        if (snapshot.phase != VaultStorePhase.READY || attempt.processInstance <= 0 || attempt.attemptId <= 0 || attempt.authEpoch < 0)
            throw VaultOperationException(VaultFailure.REJECTED)
        val ledger = authenticateLedger(snapshot, context)
        if (ledger.reservedAttemptCount <= 0 || ledger.outstandingReservationId == null)
            throw VaultOperationException(VaultFailure.REJECTED)
        val root = unwrapPassword(snapshot, input, context)
        verifyCatalog(root, snapshot, context) // No key scope exists before real persisted catalog authentication.
        val binding = VaultCryptoWire.catalogFingerprint(snapshot)
        synchronized(registryLock) {
            requireOpen(); context.checkActive()
            Root(root, snapshot.identity, attempt, binding).also { roots[it] = it }
        }
    }

    override fun readEmptyCatalog(root: VaultRootCapability, snapshot: VaultStoredSnapshot,
        context: VaultOperationContext): VaultEmptyCatalog = serial(context) {
        val handle = synchronized(registryLock) {
            requireOpen()
            val owned = roots[root] ?: throw VaultOperationException(VaultFailure.REJECTED)
            if (snapshot.phase != VaultStorePhase.READY || owned.identity != snapshot.identity ||
                !MessageDigest.isEqual(owned.catalogBinding, VaultCryptoWire.catalogFingerprint(snapshot)))
                throw VaultOperationException(VaultFailure.CONFLICT)
            owned.keyset ?: throw VaultOperationException(VaultFailure.REJECTED)
        }
        val result = verifyCatalog(handle, snapshot, context)
        synchronized(registryLock) {
            requireOpen(); context.checkActive()
            if (roots[root]?.keyset !== handle) throw VaultOperationException(VaultFailure.CANCELLED)
            result
        }
    }

    override fun prepareRecoveryEnrollment(root: VaultRootCapability, snapshot: VaultStoredSnapshot,
        context: VaultOperationContext): VaultRecoveryEnrollment = serial(context) {
        val handle = ownedRoot(root, snapshot)
        val data = requireReadyData(snapshot)
        if (data.recoveryState == VaultRecoveryState.ACTIVE || data.pendingPassword != null)
            throw VaultOperationException(VaultFailure.REJECTED)
        authenticateLedger(snapshot, context)
        verifyCatalog(handle, snapshot, context)
        val revision = nextCatalogRevision(snapshot)
        val generated = VaultRecoverySlot.generateForProtectedDisplay(context)
        var holder: VaultRecoveryInput? = null
        var mutation: VaultCredentialMutation? = null
        try {
            val recovery = VaultSlotRecord(VaultCryptoWire.RECOVERY_SLOT, revision, VaultSlotKind.RECOVERY,
                VaultRecoverySlot.wrap(handle, snapshot.identity, VaultCryptoWire.RECOVERY_SLOT, revision, generated, context), true)
            val proposed = resealCatalog(handle, snapshot, data.copy(slots = listOf(passwordSlot(data), recovery),
                catalogRevision = revision, recoveryState = VaultRecoveryState.PENDING), context)
            // Revocation may race with work but never waits on entropy, encryption or KDF.
            mutation = synchronized(registryLock) {
                requireOpen(); context.checkActive()
                if (roots[root]?.keyset !== handle) throw VaultOperationException(VaultFailure.CANCELLED)
                registerMutation(snapshot, proposed, VaultCredentialMutationKind.STAGE_RECOVERY, null, context)
            }
            holder = VaultRecoveryInput(generated)
            VaultRecoveryEnrollment(mutation, holder)
        } catch (failure: Exception) {
            holder?.close(); mutation?.close(); throw failure
        } finally { generated.fill('\u0000') }
    }

    override fun verifyPendingRecovery(snapshot: VaultStoredSnapshot, input: VaultRecoveryInput,
        context: VaultOperationContext): Unit = serial(context) {
        // The generated holder is tested only against actual durable ciphertext. This check
        // deliberately issues no activation proof and retains neither root nor display code.
        unwrapRecovery(snapshot, input, VaultRecoveryState.PENDING, context)
        Unit
    }

    override fun prepareRecoveryActivation(snapshot: VaultStoredSnapshot, input: VaultRecoveryInput,
        context: VaultOperationContext): VaultCredentialMutation = serial(context) {
        val handle = unwrapRecovery(snapshot, input, VaultRecoveryState.PENDING, context)
        val data = requireReadyData(snapshot)
        val proposed = resealCatalog(handle, snapshot, data.copy(catalogRevision = nextCatalogRevision(snapshot),
            recoveryState = VaultRecoveryState.ACTIVE), context)
        registerMutation(snapshot, proposed, VaultCredentialMutationKind.ACTIVATE_RECOVERY, null, context)
    }

    override fun preparePasswordReset(snapshot: VaultStoredSnapshot, input: VaultRecoveryInput,
        password: VaultCredentialInput, attempt: VaultAttemptIdentity,
        context: VaultOperationContext): VaultCredentialMutation = serial(context) {
        if (password.kind != VaultCredentialKind.PASSWORD) throw VaultOperationException(VaultFailure.REJECTED)
        validatePasswordInput(password) // Independent password policy before any new wrapping material.
        val data = requireReadyData(snapshot)
        requireReservedAttempt(data, attempt)
        val handle = unwrapRecovery(snapshot, input, VaultRecoveryState.ACTIVE, context)
        val revision = nextCatalogRevision(snapshot)
        val replacement = wrapPassword(handle, snapshot.identity, password, revision, context)
        val pending = VaultPendingPassword(replacement, attempt, data.retryLedger)
        val proposed = resealCatalog(handle, snapshot, data.copy(catalogRevision = revision, pendingPassword = pending), context)
        registerMutation(snapshot, proposed, VaultCredentialMutationKind.STAGE_PASSWORD_RESET, attempt, context)
    }

    override fun verifyPendingPasswordReset(snapshot: VaultStoredSnapshot, password: VaultCredentialInput,
        attempt: VaultAttemptIdentity, context: VaultOperationContext): VaultCredentialMutation = serial(context) {
        if (password.kind != VaultCredentialKind.PASSWORD) throw VaultOperationException(VaultFailure.REJECTED)
        val data = requireReadyData(snapshot)
        requireReservedAttempt(data, attempt)
        val pending = data.pendingPassword ?: throw VaultOperationException(VaultFailure.REJECTED)
        if (data.recoveryState != VaultRecoveryState.ACTIVE || pending.attempt != attempt ||
            pending.reservation != data.retryLedger) throw VaultOperationException(VaultFailure.CONFLICT)
        // Uses the actual persisted pending slot. Ordinary unlock never selects this slot.
        val handle = unwrapPasswordSlot(snapshot, pending.slot, password, context)
        verifyCatalog(handle, snapshot, context)
        val proposed = resealCatalog(handle, snapshot, data.copy(slots = listOf(pending.slot),
            catalogRevision = nextCatalogRevision(snapshot), recoveryState = VaultRecoveryState.NONE,
            pendingPassword = null), context)
        // Keep the original ledger/envelope: store authenticates RESET and freezes the final
        // whole record once. Its uncertainty reconciliation must reuse exactly those bytes.
        registerMutation(snapshot, proposed, VaultCredentialMutationKind.ACTIVATE_PASSWORD_RESET, attempt, context)
    }

    override fun validateCredentialMutation(snapshot: VaultStoredSnapshot, mutation: VaultCredentialMutation,
        context: VaultOperationContext): VaultCredentialMutationProposal = synchronized(registryLock) {
        requireOpen(); context.checkActive()
        val registered = mutations[mutation] ?: throw VaultOperationException(VaultFailure.REJECTED)
        registered.operation.checkActive()
        if (!registered.operation.sharesOperation(context) ||
            context.deadlineElapsedMillis > registered.operation.deadlineElapsedMillis)
            throw VaultOperationException(VaultFailure.REJECTED)
        val actual = VaultCryptoWire.snapshotFingerprint(snapshot)
        try {
            if (snapshot.phase != VaultStorePhase.READY || !MessageDigest.isEqual(registered.fingerprint, actual))
                throw VaultOperationException(VaultFailure.CONFLICT)
        } finally { actual.fill(0) }
        registered.proposal
    }

    private fun registerMutation(snapshot: VaultStoredSnapshot, proposal: VaultStagedData,
        kind: VaultCredentialMutationKind, attempt: VaultAttemptIdentity?, context: VaultOperationContext): VaultCredentialMutation {
        val fingerprint = VaultCryptoWire.snapshotFingerprint(snapshot)
        // An operation ID identifies immutable ciphertext only. It is not an authorization token.
        val proposedSnapshot = snapshot.copy(storeRevision = snapshot.storeRevision + 1, data = proposal)
        val proposalDigest = VaultCryptoWire.snapshotFingerprint(proposedSnapshot)
        val operationId = try { "credential_${kind.ordinal}_" + proposalDigest.joinToString("") { "%02x".format(it.toInt() and 255) } }
            finally { proposalDigest.fill(0) }
        return synchronized(registryLock) {
            try {
                requireOpen(); context.checkActive()
                Mutation(fingerprint, VaultCredentialMutationProposal(kind, proposal, attempt, operationId), context)
                    .also { mutations[it] = it }
            } catch (failure: Exception) { fingerprint.fill(0); throw failure }
        }
    }

    private fun ownedRoot(root: VaultRootCapability, snapshot: VaultStoredSnapshot): KeysetHandle = synchronized(registryLock) {
        requireOpen()
        val owned = roots[root] ?: throw VaultOperationException(VaultFailure.REJECTED)
        val binding = VaultCryptoWire.catalogFingerprint(snapshot)
        try {
            if (snapshot.phase != VaultStorePhase.READY || owned.identity != snapshot.identity ||
                !MessageDigest.isEqual(owned.catalogBinding, binding)) throw VaultOperationException(VaultFailure.CONFLICT)
            owned.keyset ?: throw VaultOperationException(VaultFailure.REJECTED)
        } finally { binding.fill(0) }
    }

    private fun requireReadyData(snapshot: VaultStoredSnapshot): VaultStagedData {
        if (snapshot.phase != VaultStorePhase.READY) throw VaultOperationException(VaultFailure.REJECTED)
        return requireData(snapshot)
    }
    private fun requireReservedAttempt(data: VaultStagedData, attempt: VaultAttemptIdentity) {
        VaultCryptoWire.requireAttempt(attempt)
        if (data.retryLedger.reservedAttemptCount <= 0 || data.retryLedger.outstandingReservationId == null)
            throw VaultOperationException(VaultFailure.REJECTED)
    }
    private fun nextCatalogRevision(snapshot: VaultStoredSnapshot): Long {
        val revision = requireReadyData(snapshot).catalogRevision
        if (revision >= Long.MAX_VALUE - 1 || snapshot.storeRevision >= Long.MAX_VALUE - 1)
            throw VaultOperationException(VaultFailure.REJECTED)
        return revision + 1
    }

    private fun unwrapRecovery(snapshot: VaultStoredSnapshot, input: VaultRecoveryInput,
        state: VaultRecoveryState, context: VaultOperationContext): KeysetHandle {
        val data = requireReadyData(snapshot)
        if (data.recoveryState != state) throw VaultOperationException(VaultFailure.REJECTED)
        val slot = data.slots.single { it.kind == VaultSlotKind.RECOVERY }
        authenticateLedger(snapshot, context) // Recovery does not bypass unhealthy ledger/marker checks.
        val chars = input.copyCharacters()
        try {
            // Invalid re-entry is a rejected credential, not a reason to discard a healthy Vault.
            try { VaultCryptoProfile.decodeRecovery(chars).fill(0) }
            catch (_: IllegalArgumentException) { throw VaultOperationException(VaultFailure.REJECTED) }
            val root = try { VaultRecoverySlot.unwrapPersisted(snapshot.identity, slot.slotId, slot.generation,
                slot.envelope, chars, context) }
                catch (_: GeneralSecurityException) { throw VaultOperationException(VaultFailure.REJECTED) }
            verifyCatalog(root, snapshot, context)
            return root // Restricted local keyset only; no Root capability/registry insertion.
        } finally { chars.fill('\u0000') }
    }

    private fun wrapPassword(root: KeysetHandle, identity: VaultPersistentIdentity, input: VaultCredentialInput,
        generation: Long, context: VaultOperationContext): VaultSlotRecord {
        context.checkActive()
        val salt = ByteArray(VaultCryptoProfile.SALT_BYTES).also { SecureRandom().nextBytes(it) }
        try {
            val kind = slotKind(input.kind)
            val device = keystore.deviceAead(identity.deviceKeyAlias, requireHardware = kind == VaultSlotKind.PIN)
            return passwordAead(input, salt, context).use { password ->
                val encrypted = VaultTinkKeysets.wrap(root,
                    nestedPassword(password, device, passwordAad(identity, kind, salt, VaultAadPurpose.DEVICE_PASSWORD_OUTER, generation)),
                    passwordAad(identity, kind, salt, generation = generation))
                VaultSlotRecord(VaultCryptoWire.PASSWORD_SLOT, generation, kind,
                    VaultCiphertext(VaultCryptoWire.passwordEnvelope(salt, encrypted)), true).also { context.checkActive() }
            }
        } finally { salt.fill(0) }
    }

    private fun resealCatalog(root: KeysetHandle, snapshot: VaultStoredSnapshot, proposed: VaultStagedData,
        context: VaultOperationContext): VaultStagedData {
        val before = requireReadyData(snapshot)
        require(proposed.catalogRevision == before.catalogRevision + 1)
        val catalog = VaultTinkKeysets.unwrap(before.catalogKeysetEnvelope.copyBytes(), VaultTinkKeysets.primitive(root),
            VaultCryptoWire.aad(snapshot.identity, VaultAadPurpose.ROOT_CATALOG_KEYSET, revision = before.catalogRevision))
        context.checkActive()
        val keysetEnvelope = VaultTinkKeysets.wrap(catalog, VaultTinkKeysets.primitive(root),
            VaultCryptoWire.aad(snapshot.identity, VaultAadPurpose.ROOT_CATALOG_KEYSET, revision = proposed.catalogRevision))
        val plain = VaultCryptoWire.emptyCatalog(snapshot.identity, proposed)
        val catalogEnvelope = try { VaultTinkKeysets.primitive(catalog).encrypt(plain,
            VaultCryptoWire.aad(snapshot.identity, VaultAadPurpose.CATALOG, revision = proposed.catalogRevision)) }
            finally { plain.fill(0) }
        context.checkActive()
        return proposed.copy(catalogKeysetEnvelope = VaultCiphertext(keysetEnvelope),
            catalogEnvelope = VaultCiphertext(catalogEnvelope, VaultRuntimeLimits.EMPTY_CATALOG_BYTES))
    }

    private fun unwrapPassword(snapshot: VaultStoredSnapshot, input: VaultCredentialInput,
        context: VaultOperationContext): KeysetHandle {
        return unwrapPasswordSlot(snapshot, passwordSlot(requireData(snapshot)), input, context)
    }
    private fun unwrapPasswordSlot(snapshot: VaultStoredSnapshot, slot: VaultSlotRecord,
        input: VaultCredentialInput, context: VaultOperationContext): KeysetHandle {
        if (slot.kind != slotKind(input.kind)) throw VaultOperationException(VaultFailure.REJECTED)
        val parsed = VaultCryptoWire.parsePasswordEnvelope(slot.envelope.copyBytes())
        VaultTinkKeysets.requireCanonicalEncrypted(parsed.encryptedKeyset)
        // A bad/missing ledger forbids KDF and password authentication even when ciphertext remains.
        authenticateLedger(snapshot, context)
        val device = keystore.deviceAead(snapshot.identity.deviceKeyAlias, requireHardware = slot.kind == VaultSlotKind.PIN)
        return passwordAead(input, parsed.salt, context).use { password ->
            try {
                val root = VaultTinkKeysets.unwrap(parsed.encryptedKeyset,
                    nestedPassword(password, device, passwordAad(snapshot.identity, slot.kind, parsed.salt, VaultAadPurpose.DEVICE_PASSWORD_OUTER, slot.generation)),
                    passwordAad(snapshot.identity, slot.kind, parsed.salt, generation = slot.generation))
                context.checkActive()
                root
            } catch (_: GeneralSecurityException) { throw VaultOperationException(VaultFailure.REJECTED) }
        }
    }

    private fun verifyCatalog(root: KeysetHandle, snapshot: VaultStoredSnapshot,
        context: VaultOperationContext): VaultEmptyCatalog {
        val data = requireData(snapshot)
        val catalog = VaultTinkKeysets.unwrap(data.catalogKeysetEnvelope.copyBytes(), VaultTinkKeysets.primitive(root),
            VaultCryptoWire.aad(snapshot.identity, VaultAadPurpose.ROOT_CATALOG_KEYSET, revision = data.catalogRevision))
        val plain = VaultTinkKeysets.primitive(catalog).decrypt(data.catalogEnvelope.copyBytes(),
            VaultCryptoWire.aad(snapshot.identity, VaultAadPurpose.CATALOG, revision = data.catalogRevision))
        try {
            require(plain.size <= VaultRuntimeLimits.EMPTY_CATALOG_BYTES) { "catalog-size" }
            val expected = VaultCryptoWire.emptyCatalog(snapshot.identity, data)
            try { require(MessageDigest.isEqual(plain, expected)) { "catalog-integrity" } }
            finally { expected.fill(0) }
            context.checkActive()
            return VaultEmptyCatalog(data.catalogRevision)
        } finally { plain.fill(0) }
    }
    private fun requireData(snapshot: VaultStoredSnapshot): VaultStagedData {
        VaultCryptoWire.requireIdentity(snapshot.identity)
        require(snapshot.storeRevision in 1 until Long.MAX_VALUE && snapshot.phase != VaultStorePhase.INITIALIZING) { "snapshot-phase" }
        return requireNotNull(snapshot.data) { "missing-staged-data" }.also {
            VaultCryptoWire.requireCredentialShape(it); VaultCryptoWire.requireLedger(it.retryLedger)
        }
    }
    private fun passwordSlot(data: VaultStagedData): VaultSlotRecord {
        VaultCryptoWire.requireCredentialShape(data)
        return data.slots.single { it.kind == VaultSlotKind.PIN || it.kind == VaultSlotKind.PASSWORD }
    }
    private fun passwordAead(input: VaultCredentialInput, salt: ByteArray, context: VaultOperationContext): VaultJcaAead {
        val bounded = context.bounded(VaultRuntimeLimits.KDF_MILLIS)
        val chars = input.copyCharacters()
        var derived: ByteArray? = null
        try {
            bounded.checkActive()
            derived = VaultCryptoProfile.deriveWrappingKey(chars, profileKind(input.kind), salt)
            bounded.checkActive()
            return VaultJcaAead.fromOwnedBytes(derived)
        } finally { chars.fill('\u0000'); derived?.fill(0) }
    }
    private fun profileKind(kind: VaultCredentialKind) = if (kind == VaultCredentialKind.PIN)
        VaultCryptoProfile.CredentialKind.PIN else VaultCryptoProfile.CredentialKind.PASSWORD
    private fun slotKind(kind: VaultCredentialKind) = if (kind == VaultCredentialKind.PIN) VaultSlotKind.PIN else VaultSlotKind.PASSWORD
    private fun passwordAad(identity: VaultPersistentIdentity, kind: VaultSlotKind, salt: ByteArray,
        purpose: VaultAadPurpose = VaultAadPurpose.PASSWORD_ROOT, generation: Long = 1) =
        VaultCryptoWire.aad(identity, purpose, kind, VaultCryptoWire.PASSWORD_SLOT, generation, salt = salt)
    private fun nestedPassword(password: Aead, device: Aead, deviceAad: ByteArray): Aead = object : Aead {
        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray {
            val inner = password.encrypt(plaintext, associatedData)
            return try { device.encrypt(inner, deviceAad) } finally { inner.fill(0) }
        }
        override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray {
            val inner = device.decrypt(ciphertext, deviceAad)
            return try { password.decrypt(inner, associatedData) } finally { inner.fill(0) }
        }
    }
    private fun requireOpen() { if (closed) throw VaultOperationException(VaultFailure.CANCELLED) }
    private inline fun <T> serial(context: VaultOperationContext, block: () -> T): T = synchronized(operationLock) {
        synchronized(registryLock) { requireOpen() }; context.checkActive()
        try { finishVaultCryptoResult(block(), context) { synchronized(registryLock) { requireOpen() } } }
        catch (failure: VaultOperationException) { throw failure }
        catch (_: GeneralSecurityException) { throw VaultOperationException(VaultFailure.CORRUPT) }
        catch (_: IllegalArgumentException) { throw VaultOperationException(VaultFailure.CORRUPT) }
        catch (_: java.io.IOException) { throw VaultOperationException(VaultFailure.CORRUPT) }
        catch (_: SecurityException) { throw VaultOperationException(VaultFailure.UNAVAILABLE) }
    }
    override fun close() { synchronized(registryLock) {
        closed = true
        roots.values.toList().forEach { it.close() }
        proofs.values.toList().forEach { it.close() }
        mutations.values.toList().forEach { it.close() }
    } }
}

/** Last acceptance can fail after registration. Unreturned capabilities must be revoked too. */
internal fun <T> finishVaultCryptoResult(result: T, context: VaultOperationContext, requireOpen: () -> Unit): T {
    try { requireOpen(); context.checkActive(); return result }
    catch (failure: Exception) {
        if (result is VaultRootCapability || result is VaultStagedVerification || result is VaultCredentialMutation ||
            result is VaultRecoveryEnrollment || result is VaultRecoveryInput) (result as AutoCloseable).close()
        throw failure
    }
}
