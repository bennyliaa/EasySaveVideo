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
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import com.easysavevideo.library.export.ExportEvidence
import com.easysavevideo.library.export.ExportRelease

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

    private val mediaPrepared = IdentityHashMap<VaultPreparedMediaImport, PreparedMedia>()
    private val mediaMutations = IdentityHashMap<VaultMediaMutation, MediaMutation>()
    private val mediaProofs = IdentityHashMap<VaultFinalizedMediaProof, MediaProof>()
    private val mediaReaders = IdentityHashMap<VaultMediaReadCapability, MediaReaderOwner>()
    private class MediaReaderOwner(val root: VaultRootCapability, val reader: VaultControlledMediaReader)
    private inner class PreparedMedia(val root: VaultRootCapability, val rootHandle: KeysetHandle,
        var handle: KeysetHandle?, val binding: ByteArray, val source: VaultMediaSourceEvidence,
        val operation: VaultMediaOperation, val identity: VaultMediaObjectIdentity, val wrapped: VaultCiphertext,
        val context: VaultOperationContext) : VaultPreparedMediaImport {
        var writeStarted = false
        var writeCompleted = false
        var file: VaultOwnedCiphertext? = null
        override fun close() { synchronized(registryLock) { mediaPrepared.remove(this); handle = null; binding.fill(0) } }
        override fun toString() = "VaultPreparedMediaImport(REDACTED)"
    }
    private inner class MediaMutation(val fingerprint: ByteArray, val proposal: VaultMediaProposal,
        val context: VaultOperationContext, val root: VaultRootCapability, val rootHandle: KeysetHandle) : VaultMediaMutation {
        override fun close() { synchronized(registryLock) { mediaMutations.remove(this); fingerprint.fill(0) } }
        override fun toString() = "VaultMediaMutation(REDACTED)"
    }
    private inner class MediaProof(val file: VaultOwnedCiphertext, val root: VaultRootCapability,
        val evidence: VaultMediaFinalizedEvidence, val context: VaultOperationContext,
        val prepared: VaultPreparedMediaImport? = null, val committedEntry: VaultMediaEntry? = null,
        val committedSnapshot: ByteArray? = null) : VaultFinalizedMediaProof {
        override fun close() { synchronized(registryLock) { mediaProofs.remove(this); committedSnapshot?.fill(0) } }
        override fun toString() = "VaultFinalizedMediaProof(REDACTED)"
    }

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
        var revoked = false
        override fun close() {
            val readers = synchronized(registryLock) {
                revoked = true
                mediaPrepared.values.filter { it.root === this }.toList().forEach { it.close() }
                mediaMutations.values.filter { it.root === this }.toList().forEach { it.close() }
                mediaProofs.values.filter { it.root === this }.toList().forEach { it.close() }
                mediaReaders.values.filter { it.root === this }.map { it.reader }
                    .also { list -> list.forEach { it.revoke() } }
            }
            var unknown = false
            // Never hold the registry/revocation gate while waiting on actual reader IO or close.
            readers.forEach { if (it.closeAndObserve() != ExportRelease.RELEASED) unknown = true }
            if (unknown) throw VaultOperationException(VaultFailure.RELEASE_UNKNOWN)
            synchronized(registryLock) {
                if (mediaReaders.values.any { it.root === this }) throw VaultOperationException(VaultFailure.RELEASE_UNKNOWN)
                roots.remove(this); keyset = null; catalogBinding.fill(0)
            }
        }
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
            if (owned.revoked || snapshot.phase != VaultStorePhase.READY || owned.identity != snapshot.identity ||
                !MessageDigest.isEqual(owned.catalogBinding, VaultCryptoWire.catalogFingerprint(snapshot)))
                throw VaultOperationException(VaultFailure.CONFLICT)
            owned.keyset ?: throw VaultOperationException(VaultFailure.REJECTED)
        }
        val catalog = verifyCatalog(handle, snapshot, context)
        if (catalog.entries.isNotEmpty()) throw VaultOperationException(VaultFailure.UNSUPPORTED)
        val result = VaultEmptyCatalog(catalog.catalogRevision)
        synchronized(registryLock) {
            requireOpen(); context.checkActive()
            if (roots[root]?.keyset !== handle || roots[root]?.revoked != false) throw VaultOperationException(VaultFailure.CANCELLED)
            result
        }
    }

    override fun observeMediaDrain(): ExportRelease = synchronized(registryLock) {
        if (mediaReaders.isEmpty() && mediaPrepared.isEmpty()) ExportRelease.RELEASED else ExportRelease.UNCONFIRMED
    }

    override fun authenticateCatalog(root: VaultRootCapability, snapshot: VaultStoredSnapshot,
        context: VaultOperationContext): VaultAuthenticatedCatalog = serial(context) {
        val handle = ownedRoot(root, snapshot)
        verifyCatalog(handle, snapshot, context).also { requireRootCurrent(root, handle, context) }
    }

    override fun authenticateMediaJournal(root: VaultRootCapability, snapshot: VaultStoredSnapshot,
        context: VaultOperationContext): VaultMediaJournal? = serial(context) {
        val handle = ownedRoot(root, snapshot)
        readMediaJournal(handle, snapshot, context).also { requireRootCurrent(root, handle, context) }
    }

    override fun prepareMediaImport(root: VaultRootCapability, snapshot: VaultStoredSnapshot,
        source: VaultMediaSourceEvidence, operation: VaultMediaOperation, identity: VaultMediaObjectIdentity,
        context: VaultOperationContext): VaultPreparedMediaImport = serial(context) {
        val handle = ownedRoot(root, snapshot)
        val catalog = verifyCatalog(handle, snapshot, context)
        val journal = readMediaJournal(handle, snapshot, context) ?: throw VaultOperationException(VaultFailure.INCOMPLETE)
        require(operation.kind == VaultMediaKind.MOVE_IN && journal.operation == operation && journal.source == source.source &&
            journal.objectIdentity == identity && journal.phase in setOf(VaultMediaPhase.SOURCE_RESERVED, VaultMediaPhase.ENCRYPTING) &&
            !journal.cancelRequested && catalog.entries.none { it.identity.objectId == identity.objectId }) { "media-import-binding" }
        synchronized(registryLock) {
            requireRootCurrent(root, handle, context)
            require(mediaPrepared.isEmpty() && mediaReaders.isEmpty()) { "media-busy" }
        }
        require(identity.generation in 1 until Long.MAX_VALUE && operation.sequence in 1 until Long.MAX_VALUE) { "media-import-profile" }
        val streaming = VaultTinkStreamingKeysets.fresh()
        val wrapped = VaultCiphertext(VaultTinkStreamingKeysets.wrap(streaming, VaultTinkKeysets.primitive(handle),
            VaultMediaCatalogWire.aad(snapshot.identity, identity, source.evidence, 1)))
        // Capacity check is completed before the store creates any output file.
        val placeholder = VaultMediaEntry(identity, VaultMediaLimits.PROFILE, source.evidence,
            VaultCiphertextEvidence(source.evidence.bytes + 56, "0".repeat(64)), wrapped, source.displayName,
            source.durationMillis, source.width, source.height, operation, source.source)
        val capacity = VaultMediaCatalogWire.encode(snapshot.identity,
            requireData(snapshot).copy(catalogRevision = nextCatalogRevision(snapshot)), catalog.entries + placeholder)
        capacity.fill(0)
        synchronized(registryLock) {
            requireRootCurrent(root, handle, context)
            PreparedMedia(root, handle, streaming, VaultCryptoWire.catalogFingerprint(snapshot), source, operation,
                identity, wrapped, context).also { mediaPrepared[it] = it }
        }
    }

    override fun encryptMedia(prepared: VaultPreparedMediaImport, source: VaultMoveSourceCapability,
        target: VaultOwnedCiphertext, gate: VaultMediaPublicationGate, context: VaultOperationContext): Unit = serial(context) {
        val owned = requirePrepared(prepared, context)
        require(source.metadata == owned.source && target.identity == owned.identity &&
            target.operationId == owned.operation.operationId) { "media-import-source" }
        synchronized(registryLock) {
            require(!owned.writeStarted) { "media-no-restart" }; owned.writeStarted = true; owned.file = target
        }
        val check = { requirePrepared(prepared, context); Unit }
        require(withMediaIo(gate, context, check) { target.size() } == 0L) { "media-output-not-empty" }
        val raw = object : OutputStream() {
            override fun write(value: Int) { val one = byteArrayOf(value.toByte()); try { write(one, 0, 1) } finally { one.fill(0) } }
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
                var done = 0
                while (done < length) {
                    val chunk = minOf(VaultMediaLimits.BUFFER_BYTES, length - done)
                    withMediaIo(gate, context, check) { target.append(bytes, offset + done, chunk) }
                    done += chunk
                }
            }
            override fun close() { /* Workflow, not Tink, owns actual output force/release. */ }
        }
        val buffer = ByteArray(VaultMediaLimits.BUFFER_BYTES)
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L; var zero = 0
        var stream: OutputStream? = null
        var streamCloseStarted = false
        var sourceCloseStarted = false
        try {
            stream = withMediaIo(gate, context, check) { VaultTinkStreamingKeysets.primitive(requireNotNull(owned.handle))
                .newEncryptingStream(raw, VaultMediaCatalogWire.aad(rootIdentity(owned.root), owned.identity, owned.source.evidence, 2)) }
            while (true) {
                context.checkActive(); check()
                val readPermit = gate.admit()
                try {
                    val read = source.input.stream.read(buffer)
                    context.checkActive(); check()
                    require(read in -1..buffer.size) { "media-source-count" }
                    if (read < 0) break
                    if (read == 0) { require(++zero <= VaultMediaLimits.MAX_ZERO_PROGRESS) { "media-zero-progress" }; continue }
                    zero = 0
                    require(count <= owned.source.evidence.bytes - read) { "media-source-size" }
                    digest.update(buffer, 0, read); count += read
                    withMediaIo(gate, context, check) { stream.write(buffer, 0, read) }
                } finally { buffer.fill(0); readPermit.close() }
            }
            require(count == owned.source.evidence.bytes && hex(digest.digest()) == owned.source.evidence.sha256) { "media-source-mismatch" }
            withMediaIo(gate, context, check) { streamCloseStarted = true; stream.close() }; stream = null
            if (withMediaIo(gate, context, check) { target.forceAndCloseWrite() } != ExportRelease.RELEASED)
                throw VaultOperationException(VaultFailure.RELEASE_UNKNOWN)
            sourceCloseStarted = true
            if (source.input.close() != ExportRelease.RELEASED) throw VaultOperationException(VaultFailure.RELEASE_UNKNOWN)
            requirePrepared(prepared, context).writeCompleted = true
        } finally {
            buffer.fill(0)
            if (!streamCloseStarted) {
                streamCloseStarted = true
                try { stream?.close() } catch (_: Exception) { }
            }
            // A close attempt is never blindly repeated after an uncertain result.
            if (!sourceCloseStarted) {
                sourceCloseStarted = true
                val release = try { source.input.close() } catch (_: Exception) { ExportRelease.UNCONFIRMED }
                if (release != ExportRelease.RELEASED) throw VaultOperationException(VaultFailure.RELEASE_UNKNOWN)
            }
        }
    }

    override fun verifyFinalMedia(prepared: VaultPreparedMediaImport, file: VaultOwnedCiphertext,
        gate: VaultMediaPublicationGate, context: VaultOperationContext): VaultFinalizedMediaProof = serial(context) {
        val owned = requirePrepared(prepared, context)
        require(owned.writeCompleted && owned.file === file) { "media-not-finalized" }
        val check = { requirePrepared(prepared, context); Unit }
        val ciphertext = digestCiphertext(file, gate, context, check)
        verifyPlaintext(owned.root, requireNotNull(owned.handle), rootIdentity(owned.root), owned.identity,
            owned.source.evidence, ciphertext, file, gate, context, check)
        synchronized(registryLock) {
            requirePrepared(prepared, context)
            MediaProof(file, owned.root, VaultMediaFinalizedEvidence(owned.identity, owned.source.evidence, ciphertext),
                context, prepared).also { mediaProofs[it] = it }
        }
    }

    override fun verifyCommittedMedia(root: VaultRootCapability, snapshot: VaultStoredSnapshot, ref: VaultMediaReadRef,
        file: VaultOwnedCiphertext, gate: VaultMediaPublicationGate, context: VaultOperationContext): VaultFinalizedMediaProof = serial(context) {
        val handle = ownedRoot(root, snapshot)
        val entry = selectMedia(handle, snapshot, ref, context)
        requireFile(file, entry)
        val check = { requireRootCurrent(root, handle, context) }
        require(digestCiphertext(file, gate, context, check) == entry.ciphertext) { "media-ciphertext-mismatch" }
        val streaming = unwrapMedia(handle, snapshot.identity, entry)
        verifyPlaintext(root, streaming, snapshot.identity, entry.identity, entry.plaintext, entry.ciphertext, file, gate, context, check)
        synchronized(registryLock) {
            requireRootCurrent(root, handle, context)
            MediaProof(file, root, VaultMediaFinalizedEvidence(entry.identity, entry.plaintext, entry.ciphertext), context,
                committedEntry = entry, committedSnapshot = VaultCryptoWire.snapshotFingerprint(snapshot))
                .also { mediaProofs[it] = it }
        }
    }

    override fun finishMediaImport(root: VaultRootCapability, expected: VaultStoredSnapshot,
        prepared: VaultPreparedMediaImport, proof: VaultFinalizedMediaProof, nextJournal: VaultMediaJournal,
        context: VaultOperationContext): VaultMediaMutation = serial(context) {
        val handle = ownedRoot(root, expected)
        val owned = requirePrepared(prepared, context)
        require(owned.root === root && owned.rootHandle === handle) { "media-root-substitution" }
        val binding = VaultCryptoWire.catalogFingerprint(expected)
        try { require(MessageDigest.isEqual(binding, owned.binding)) { "media-stale-catalog" } } finally { binding.fill(0) }
        val final = synchronized(registryLock) {
            val registered = mediaProofs[proof] ?: throw VaultOperationException(VaultFailure.REJECTED)
            require(registered.prepared === prepared && registered.file === owned.file) { "media-proof-substitution" }
            validateFinalizedMedia(proof, registered.file, context)
        }
        val before = requireReadyData(expected)
        val catalog = verifyCatalog(handle, expected, context)
        require(nextJournal.operation == owned.operation && nextJournal.source == owned.source.source &&
            nextJournal.objectIdentity == owned.identity && nextJournal.phase == VaultMediaPhase.COMMITTED_SOURCE_PENDING &&
            !nextJournal.cancelRequested && nextJournal.expectedCatalogRevision == before.catalogRevision + 1 &&
            nextJournal.plaintextEvidence == final.plaintext && nextJournal.ciphertextEvidence == final.ciphertext) { "media-final-journal" }
        VaultMediaJournalWire.requireSuccessor(readMediaJournal(handle, expected, context), nextJournal)
        val entry = VaultMediaEntry(owned.identity, VaultMediaLimits.PROFILE, final.plaintext, final.ciphertext, owned.wrapped,
            owned.source.displayName, owned.source.durationMillis, owned.source.width, owned.source.height, owned.operation, owned.source.source)
        val catalogKey = VaultTinkKeysets.unwrap(before.catalogKeysetEnvelope.copyBytes(), VaultTinkKeysets.primitive(handle),
            VaultCryptoWire.aad(expected.identity, VaultAadPurpose.ROOT_CATALOG_KEYSET, revision = before.catalogRevision))
        var proposed = before.copy(catalogRevision = nextCatalogRevision(expected))
        val plain = VaultMediaCatalogWire.encode(expected.identity, proposed, catalog.entries + entry)
        val envelope = try { VaultTinkKeysets.primitive(catalogKey).encrypt(plain,
            VaultCryptoWire.aad(expected.identity, VaultAadPurpose.CATALOG, revision = proposed.catalogRevision)) }
            finally { plain.fill(0) }
        proposed = proposed.copy(catalogKeysetEnvelope = VaultCiphertext(VaultTinkKeysets.wrap(catalogKey,
            VaultTinkKeysets.primitive(handle), VaultCryptoWire.aad(expected.identity, VaultAadPurpose.ROOT_CATALOG_KEYSET,
                revision = proposed.catalogRevision))), catalogEnvelope = VaultCiphertext(envelope, VaultRuntimeLimits.EMPTY_CATALOG_BYTES))
        proposed = sealJournalData(handle, expected.identity, proposed, nextJournal, context)
        requireRootCurrent(root, handle, context)
        registerMediaMutation(expected, proposed, nextJournal, context, root, handle, owned.identity, owned.source.source, true)
    }

    override fun sealMediaJournal(root: VaultRootCapability, expected: VaultStoredSnapshot, next: VaultMediaJournal,
        context: VaultOperationContext): VaultMediaMutation = serial(context) {
        val handle = ownedRoot(root, expected)
        require(next.expectedCatalogRevision == requireReadyData(expected).catalogRevision &&
            next.phase != VaultMediaPhase.COMMITTED_SOURCE_PENDING) { "media-journal-catalog" }
        val previous = readMediaJournal(handle, expected, context)
        VaultMediaJournalWire.requireSuccessor(previous, next)
        next.batch?.let { batch ->
            // Confirmation, revision refresh and each untouched-row activation authenticate the
            // original immutable identities against this current exact root/catalog snapshot.
            // This does not rebase a previously issued MediaMutation or adopt newer generations.
            if (previous?.batch == null || previous.operation != next.operation ||
                previous.expectedCatalogRevision != next.expectedCatalogRevision ||
                previous.batch.runCount != batch.runCount || next.phase == VaultMediaPhase.CHILD_CREATE_INTENT) {
                val catalog = verifyCatalog(handle, expected, context)
                requireBatchSources(catalog, next)
            }
            if (previous?.batch == null) VaultMediaJournalWire.requireCapacity(next)
        }
        if (next.cancelRequested && next.operation.kind == VaultMediaKind.MOVE_IN) {
            require(verifyCatalog(handle, expected, context).entries.none {
                it.identity == next.objectIdentity || it.moveOperation == next.operation
            }) { "media-committed-not-cancellable" }
        }
        val proposed = sealJournalData(handle, expected.identity, requireReadyData(expected), next, context)
        requireRootCurrent(root, handle, context)
        registerMediaMutation(expected, proposed, next, context, root, handle)
    }

    override fun validateMediaMutation(expected: VaultStoredSnapshot, mutation: VaultMediaMutation,
        context: VaultOperationContext): VaultMediaProposal = synchronized(registryLock) {
        requireOpen(); context.checkActive()
        val registered = mediaMutations[mutation] ?: throw VaultOperationException(VaultFailure.REJECTED)
        requireLineage(registered.context, context)
        requireRootCurrent(registered.root, registered.rootHandle, context)
        val actual = VaultCryptoWire.snapshotFingerprint(expected)
        try { if (!MessageDigest.isEqual(actual, registered.fingerprint)) throw VaultOperationException(VaultFailure.CONFLICT) }
        finally { actual.fill(0) }
        registered.proposal
    }

    override fun validateFinalizedMedia(proof: VaultFinalizedMediaProof, file: VaultOwnedCiphertext,
        context: VaultOperationContext): VaultMediaFinalizedEvidence = synchronized(registryLock) {
        requireOpen(); context.checkActive()
        val registered = mediaProofs[proof] ?: throw VaultOperationException(VaultFailure.REJECTED)
        requireLineage(registered.context, context)
        if (registered.file !== file || roots[registered.root]?.keyset == null || roots[registered.root]?.revoked != false) throw VaultOperationException(VaultFailure.REJECTED)
        registered.evidence
    }

    override fun validateCommittedMedia(expected: VaultStoredSnapshot, operation: VaultMediaOperation,
        source: com.easysavevideo.library.MediaReadRef, file: VaultOwnedCiphertext, proof: VaultFinalizedMediaProof,
        context: VaultOperationContext): VaultMediaEntry = synchronized(registryLock) {
        validateFinalizedMedia(proof, file, context)
        val registered = mediaProofs[proof] ?: throw VaultOperationException(VaultFailure.REJECTED)
        val entry = registered.committedEntry ?: throw VaultOperationException(VaultFailure.REJECTED)
        val actual = VaultCryptoWire.snapshotFingerprint(expected)
        try {
            if (registered.committedSnapshot == null || !MessageDigest.isEqual(actual, registered.committedSnapshot) ||
                entry.moveOperation != operation || entry.source != source || !entry.sourceCleanupPending)
                throw VaultOperationException(VaultFailure.CONFLICT)
        } finally { actual.fill(0) }
        entry
    }

    override fun openMediaRead(root: VaultRootCapability, snapshot: VaultStoredSnapshot, ref: VaultMediaReadRef,
        file: VaultOwnedCiphertext, gate: VaultMediaPublicationGate, context: VaultOperationContext): VaultMediaReadCapability = serial(context) {
        val handle = ownedRoot(root, snapshot)
        val entry = selectMedia(handle, snapshot, ref, context)
        requireFile(file, entry)
        val check = { requireRootCurrent(root, handle, context) }
        require(digestCiphertext(file, gate, context, check) == entry.ciphertext) { "media-ciphertext-mismatch" }
        createReader(root, unwrapMedia(handle, snapshot.identity, entry), snapshot.identity, entry.identity,
            entry.plaintext, entry.ciphertext, file, gate, context, check)
    }

    private fun createReader(root: VaultRootCapability, streaming: KeysetHandle, identity: VaultPersistentIdentity,
        objectIdentity: VaultMediaObjectIdentity, evidence: ExportEvidence, ciphertext: VaultCiphertextEvidence,
        file: VaultOwnedCiphertext, gate: VaultMediaPublicationGate, context: VaultOperationContext,
        check: () -> Unit): VaultControlledMediaReader {
        synchronized(registryLock) { requireOpen(); require(mediaReaders.isEmpty()) { "media-reader-busy" } }
        val raw = VaultCiphertextReadChannel(file, ciphertext.bytes, gate, context, check)
        var reader: VaultControlledMediaReader? = null
        try {
            val channel = withMediaIo(gate, context, check) { VaultTinkStreamingKeysets.primitive(streaming)
                .newSeekableDecryptingChannel(raw, VaultMediaCatalogWire.aad(identity, objectIdentity, evidence, 2)) }
            val created = VaultControlledMediaReader(evidence.bytes, channel, gate, context, check) {
                synchronized(registryLock) { mediaReaders.remove(reader) }
            }
            reader = created
            synchronized(registryLock) {
                // Register before checking late rejection, so UNKNOWN close always has an owner.
                mediaReaders[created] = MediaReaderOwner(root, created)
                requireOpen(); check()
            }
            created.authenticateFinalSegment(context)
            check(); context.checkActive()
            return created
        } catch (failure: Exception) {
            // An unconfirmed close retains the registered reader; no replacement can bypass it.
            if (reader == null) raw.close() else if (reader.closeAndObserve() != ExportRelease.RELEASED)
                throw VaultOperationException(VaultFailure.RELEASE_UNKNOWN)
            throw failure
        }
    }

    private fun verifyPlaintext(root: VaultRootCapability, streaming: KeysetHandle, identity: VaultPersistentIdentity,
        objectIdentity: VaultMediaObjectIdentity, evidence: ExportEvidence, ciphertext: VaultCiphertextEvidence,
        file: VaultOwnedCiphertext, gate: VaultMediaPublicationGate, context: VaultOperationContext, check: () -> Unit) {
        val reader = createReader(root, streaming, identity, objectIdentity, evidence, ciphertext, file, gate, context, check)
        val buffer = ByteArray(VaultMediaLimits.BUFFER_BYTES)
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            var offset = 0L
            while (offset < evidence.bytes) {
                val count = reader.readAt(offset, buffer, 0, minOf(buffer.size.toLong(), evidence.bytes - offset).toInt(), context)
                require(count > 0) { "media-truncated" }; digest.update(buffer, 0, count); offset += count; buffer.fill(0)
            }
            require(reader.readAt(offset, buffer, 0, 1, context) == -1 && hex(digest.digest()) == evidence.sha256) { "media-plaintext-mismatch" }
        } finally {
            buffer.fill(0)
            if (reader.closeAndObserve() != ExportRelease.RELEASED) throw VaultOperationException(VaultFailure.RELEASE_UNKNOWN)
        }
    }

    private fun digestCiphertext(file: VaultOwnedCiphertext, gate: VaultMediaPublicationGate,
        context: VaultOperationContext, check: () -> Unit): VaultCiphertextEvidence {
        val extent = withMediaIo(gate, context, check) { file.size() }
        require(extent in 1..VaultMediaLimits.MAX_CIPHERTEXT_BYTES) { "media-cipher-size" }
        val buffer = ByteArray(VaultMediaLimits.BUFFER_BYTES); val digest = MessageDigest.getInstance("SHA-256")
        try {
            var offset = 0L; var zero = 0
            while (offset < extent) {
                val requested = minOf(buffer.size.toLong(), extent - offset).toInt()
                val count = withMediaIo(gate, context, check) { file.readAt(offset, buffer, 0, requested) }
                require(count in 0..requested) { "media-cipher-truncated" }
                if (count == 0) { require(++zero <= VaultMediaLimits.MAX_ZERO_PROGRESS) { "media-zero-progress" }; continue }
                zero = 0; digest.update(buffer, 0, count); offset += count; buffer.fill(0)
            }
            zero = 0
            while (true) {
                val eof = withMediaIo(gate, context, check) { file.readAt(extent, buffer, 0, 1) }
                if (eof == -1) break
                require(eof == 0 && ++zero <= VaultMediaLimits.MAX_ZERO_PROGRESS) { "media-cipher-trailer" }
            }
            require(withMediaIo(gate, context, check) { file.size() } == extent) { "media-cipher-changed" }
            return VaultCiphertextEvidence(extent, hex(digest.digest()))
        } finally { buffer.fill(0) }
    }

    private fun selectMedia(handle: KeysetHandle, snapshot: VaultStoredSnapshot, ref: VaultMediaReadRef,
        context: VaultOperationContext): VaultMediaEntry {
        if (ref.vaultId != snapshot.identity.vaultId || ref.catalogRevision != requireReadyData(snapshot).catalogRevision)
            throw VaultOperationException(VaultFailure.CONFLICT)
        return verifyCatalog(handle, snapshot, context).entries.singleOrNull { it.identity == ref.identity }
            ?: throw VaultOperationException(VaultFailure.REJECTED)
    }
    private fun requireFile(file: VaultOwnedCiphertext, entry: VaultMediaEntry) {
        require(file.identity == entry.identity && file.operationId == entry.moveOperation.operationId) { "media-file-substitution" }
    }
    private fun unwrapMedia(root: KeysetHandle, identity: VaultPersistentIdentity, entry: VaultMediaEntry): KeysetHandle =
        VaultTinkStreamingKeysets.unwrap(entry.wrappedKeyset.copyBytes(), VaultTinkKeysets.primitive(root),
            VaultMediaCatalogWire.aad(identity, entry.identity, entry.plaintext, 1))
    private fun requirePrepared(value: VaultPreparedMediaImport, context: VaultOperationContext): PreparedMedia = synchronized(registryLock) {
        requireOpen()
        val owned = mediaPrepared[value] ?: throw VaultOperationException(VaultFailure.REJECTED)
        requireLineage(owned.context, context); requireRootCurrent(owned.root, owned.rootHandle, context)
        if (owned.handle == null) throw VaultOperationException(VaultFailure.CANCELLED)
        owned
    }
    private fun requireRootCurrent(root: VaultRootCapability, handle: KeysetHandle, context: VaultOperationContext) = synchronized(registryLock) {
        requireOpen(); context.checkActive()
        if (roots[root]?.keyset !== handle || roots[root]?.revoked != false) throw VaultOperationException(VaultFailure.CANCELLED)
    }
    private fun rootIdentity(root: VaultRootCapability): VaultPersistentIdentity = synchronized(registryLock) {
        val owned = roots[root] ?: throw VaultOperationException(VaultFailure.CANCELLED)
        if (owned.revoked) throw VaultOperationException(VaultFailure.CANCELLED)
        owned.identity
    }
    private fun requireLineage(original: VaultOperationContext, context: VaultOperationContext) {
        original.checkActive(); context.checkActive()
        if (!original.sharesOperation(context) || context.deadlineElapsedMillis > original.deadlineElapsedMillis)
            throw VaultOperationException(VaultFailure.REJECTED)
    }
    private inline fun <T> withMediaIo(gate: VaultMediaPublicationGate, context: VaultOperationContext,
        check: () -> Unit, block: () -> T): T {
        context.checkActive(); check()
        val permit = gate.admit()
        try { return block().also { context.checkActive(); check() } } finally { permit.close() }
    }
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }

    /** Caller DTOs are not proofs; only verifyCatalog's authenticated result reaches this check. */
    private fun requireBatchSources(catalog: VaultAuthenticatedCatalog, journal: VaultMediaJournal) {
        VaultMediaJournalWire.requireSourcePlan(catalog, journal)
    }

    private fun readMediaJournal(root: KeysetHandle, snapshot: VaultStoredSnapshot,
        context: VaultOperationContext): VaultMediaJournal? {
        val data = requireData(snapshot)
        val envelope = data.mediaJournalEnvelope ?: return null
        require(data.mediaJournalRevision in 1 until Long.MAX_VALUE && envelope.size <= VaultMediaJournalWire.MAX_ENVELOPE_BYTES)
        val input = DataInputStream(ByteArrayInputStream(envelope.copyBytes()))
        require(input.readInt() == 0x45534a45 && input.readInt() == 1) { "media-journal-envelope" }
        val header = with(VaultMediaWireIO) { input.blob(2048) }
        val payload = with(VaultMediaWireIO) { input.blob(VaultMediaJournalWire.MAX_ENVELOPE_BYTES) }
        require(input.available() == 0) { "media-journal-envelope-trailer" }
        val primitive = VaultTinkKeysets.primitive(root)
        val binding = primitive.decrypt(header, VaultMediaJournalWire.aad(snapshot.identity, data.mediaJournalRevision, 1))
        try {
            val decoded = VaultMediaJournalWire.decodeBinding(binding)
            val plain = primitive.decrypt(payload, VaultMediaJournalWire.aad(snapshot.identity, data.mediaJournalRevision, 2, binding))
            try {
                val journal = VaultMediaJournalWire.decode(plain)
                val actualBinding = VaultMediaJournalWire.binding(journal)
                try { require(MessageDigest.isEqual(binding, actualBinding)) { "media-journal-binding" } }
                finally { actualBinding.fill(0) }
                require(decoded.expectedCatalogRevision <= data.catalogRevision && journal.expectedCatalogRevision == decoded.expectedCatalogRevision) { "media-journal-future" }
                context.checkActive(); return journal
            } finally { plain.fill(0) }
        } finally { binding.fill(0) }
    }
    private fun sealJournalData(root: KeysetHandle, identity: VaultPersistentIdentity, data: VaultStagedData,
        journal: VaultMediaJournal, context: VaultOperationContext): VaultStagedData {
        require(data.mediaJournalRevision in 0 until Long.MAX_VALUE - 1)
        val revision = data.mediaJournalRevision + 1
        val binding = VaultMediaJournalWire.binding(journal); val plain = VaultMediaJournalWire.encode(journal)
        try {
            val primitive = VaultTinkKeysets.primitive(root)
            val header = primitive.encrypt(binding, VaultMediaJournalWire.aad(identity, revision, 1))
            val payload = primitive.encrypt(plain, VaultMediaJournalWire.aad(identity, revision, 2, binding))
            val envelope = with(VaultMediaWireIO) { encode(VaultMediaJournalWire.MAX_ENVELOPE_BYTES) {
                writeInt(0x45534a45); writeInt(1); blob(header, 2048); blob(payload, VaultMediaJournalWire.MAX_ENVELOPE_BYTES)
            } }
            context.checkActive()
            return data.copy(mediaJournalEnvelope = VaultCiphertext(envelope), mediaJournalRevision = revision)
        } finally { binding.fill(0); plain.fill(0) }
    }
    private fun registerMediaMutation(expected: VaultStoredSnapshot, data: VaultStagedData, journal: VaultMediaJournal,
        context: VaultOperationContext, root: VaultRootCapability, rootHandle: KeysetHandle,
        identity: VaultMediaObjectIdentity? = null,
        source: com.easysavevideo.library.MediaReadRef? = null, pin: Boolean = false): VaultMediaMutation {
        require(expected.storeRevision in 1 until Long.MAX_VALUE - 1)
        val proposed = expected.copy(storeRevision = expected.storeRevision + 1, data = data)
        val fingerprint = VaultCryptoWire.snapshotFingerprint(expected)
        return synchronized(registryLock) {
            try {
                requireRootCurrent(root, rootHandle, context)
                MediaMutation(fingerprint, VaultMediaProposal(journal.operation, journal.phase, proposed, journal, identity, source, pin),
                    context, root, rootHandle)
                    .also { mediaMutations[it] = it }
            } catch (failure: Exception) { fingerprint.fill(0); throw failure }
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
                if (roots[root]?.keyset !== handle || roots[root]?.revoked != false) throw VaultOperationException(VaultFailure.CANCELLED)
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
            if (owned.revoked || snapshot.phase != VaultStorePhase.READY || owned.identity != snapshot.identity ||
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
        val authenticatedBefore = VaultTinkKeysets.primitive(catalog).decrypt(before.catalogEnvelope.copyBytes(),
            VaultCryptoWire.aad(snapshot.identity, VaultAadPurpose.CATALOG, revision = before.catalogRevision))
        val plain = try { VaultMediaCatalogWire.resealPlaintext(snapshot.identity, before, proposed, authenticatedBefore) }
            finally { authenticatedBefore.fill(0) }
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
        context: VaultOperationContext): VaultAuthenticatedCatalog {
        val data = requireData(snapshot)
        val catalog = VaultTinkKeysets.unwrap(data.catalogKeysetEnvelope.copyBytes(), VaultTinkKeysets.primitive(root),
            VaultCryptoWire.aad(snapshot.identity, VaultAadPurpose.ROOT_CATALOG_KEYSET, revision = data.catalogRevision))
        val plain = VaultTinkKeysets.primitive(catalog).decrypt(data.catalogEnvelope.copyBytes(),
            VaultCryptoWire.aad(snapshot.identity, VaultAadPurpose.CATALOG, revision = data.catalogRevision))
        try {
            val result = VaultMediaCatalogWire.decode(snapshot.identity, data, plain)
            context.checkActive()
            return result
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
        catch (_: com.easysavevideo.library.LibraryFailure) { throw VaultOperationException(VaultFailure.CORRUPT) }
        catch (_: java.io.IOException) { throw VaultOperationException(VaultFailure.CORRUPT) }
        catch (_: SecurityException) { throw VaultOperationException(VaultFailure.UNAVAILABLE) }
    }
    override fun close() {
        val ownedRoots = synchronized(registryLock) {
            closed = true
            proofs.values.toList().forEach { it.close() }
            mutations.values.toList().forEach { it.close() }
            mediaPrepared.values.toList().forEach { it.close() }
            mediaMutations.values.toList().forEach { it.close() }
            mediaProofs.values.toList().forEach { it.close() }
            roots.values.toList()
        }
        var unknown = false
        ownedRoots.forEach { try { it.close() } catch (_: Exception) { unknown = true } }
        // A factory may have finished after its root was revoked. Retain and observe that exact
        // late reader too, even when the root registry has already completed its own retirement.
        val remaining = synchronized(registryLock) { mediaReaders.values.map { it.reader } }
        remaining.forEach { if (it.closeAndObserve() != ExportRelease.RELEASED) unknown = true }
        if (unknown || synchronized(registryLock) { mediaReaders.isNotEmpty() })
            throw VaultOperationException(VaultFailure.RELEASE_UNKNOWN)
    }
}

/** Last acceptance can fail after registration. Unreturned capabilities must be revoked too. */
internal fun <T> finishVaultCryptoResult(result: T, context: VaultOperationContext, requireOpen: () -> Unit): T {
    try { requireOpen(); context.checkActive(); return result }
    catch (failure: Exception) {
        if (result is VaultMediaReadCapability) {
            result.revoke()
            if (result.closeAndObserve() != ExportRelease.RELEASED) throw VaultOperationException(VaultFailure.RELEASE_UNKNOWN)
        }
        if (result is VaultPreparedMediaImport || result is VaultMediaMutation || result is VaultFinalizedMediaProof)
            (result as AutoCloseable).close()
        if (result is VaultRootCapability || result is VaultStagedVerification || result is VaultCredentialMutation ||
            result is VaultRecoveryEnrollment || result is VaultRecoveryInput) (result as AutoCloseable).close()
        throw failure
    }
}
