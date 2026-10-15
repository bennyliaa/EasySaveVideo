package com.easysavevideo.app.save.storage

import com.easysavevideo.app.save.DirectNormalizedOutputLease
import com.easysavevideo.app.save.DirectNormalizedOutputOwner
import com.easysavevideo.app.save.DirectConvertedPreflight
import com.easysavevideo.app.media.verification.BorrowedAvcAacInspection
import com.easysavevideo.app.save.DirectNormalizedPreflight
import com.easysavevideo.core.model.OperationContext
import com.easysavevideo.core.model.TaskMethod
import com.easysavevideo.core.model.TaskState
import com.easysavevideo.save.*
import java.io.File
import java.io.FileDescriptor
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Inactive adopted-generation owner. No production execution/batch binding is issued here. It never
 * invokes the normalization protocol, opens a writer, repairs a row, publishes, or reopens P.
 *
 * ownerLock is AndroidSaveStorage's fileLock. Only short registry/durable-authority decisions use
 * it. Every new stat, directory enumeration, RAF constructor, FD validation and resource close runs
 * outside it after a shared ACQUIRING reservation has been installed. Legacy remux IO is unchanged.
 */
internal class AdoptedCanonicalOutputOwner(
    private val root: File,
    private val bindings: AdoptedVerificationBindings,
    private val ledger: NormalizationLedger,
    private val readSave: (String) -> SaveRecord?,
    private val readCatalog: (String) -> OwnedMedia?,
    private val ownerLock: Any,
    private val closed: () -> Boolean,
    private val ensureNoExistingHandles: (StagingArtifact) -> Unit,
    private val checkOperation: (OperationContext) -> Unit,
    private val io: AdoptedCanonicalRafIo = AndroidAdoptedCanonicalRafIo,
) : DirectNormalizedOutputOwner {
    private enum class Stage { ACQUIRING, ACTIVE, VALIDATING, CLOSING, RELEASED, UNCERTAIN }
    private data class Authority(val record: SaveRecord, val row: NormalizationWorkspace, val catalog: OwnedMedia)
    private data class Layout(
        val parents: List<Pair<File, NormalizationDescriptorIo.Stat>>,
        val primary: NormalizationDescriptorIo.Stat,
        val raw: NormalizationDescriptorIo.Stat,
    )
    private class DirectoryObligation {
        var resource: AdoptedCanonicalRafIo.Directory? = null
        var opening = true
        var closeStarted = false
        var released = false
    }
    private class Reservation(
        val binding: AdoptedVerificationBindings.Binding,
        val authority: Authority,
        val worker: Thread,
    ) {
        val artifact get() = binding.request.artifact
        val output get() = binding.request.output
        val context get() = binding.request.context
        val key get() = authority.row.key
        val closeRequested = AtomicBoolean()
        val nativeUncertain = AtomicBoolean()
        @Volatile var stage = Stage.ACQUIRING
        @Volatile var failed = false
        @Volatile var unresolvedOpen = false
        @Volatile var input: RandomAccessFile? = null
        var descriptor: FileDescriptor? = null
        var layout: Layout? = null
        @Volatile var nativeInspection: BorrowedAvcAacInspection? = null
        @Volatile var convertedNativeReleased = false
        @Volatile var retainedNativeInspection: BorrowedAvcAacInspection? = null
        var releaseWitnessEligible = false
        var rafCloseStarted = false
        var rafReleased = false
        val directories = mutableListOf<DirectoryObligation>() // Acquiring/validating worker only.
        // A failure may itself retain a partial resource. Keep the object with its reservation,
        // never merely a message. This list is only touched by the acquiring/validating worker.
        val retainedFailures = mutableListOf<Throwable>()
        fun needsRecovery(): Boolean = nativeUncertain.get() || failed || closeRequested.get() ||
            binding.isRevoked || stage == Stage.CLOSING || stage == Stage.UNCERTAIN
    }
    // Structural writes are under ownerLock. Concurrent memory reads/revoke never need that lock.
    private val reservations = ConcurrentHashMap<String, Reservation>()
    @Volatile private var poisoned = false

    fun hasReservation(artifactId: String): Boolean = synchronized(ownerLock) { reservations.containsKey(artifactId) }
    fun hasAnyLeases(): Boolean = synchronized(ownerLock) { reservations.isNotEmpty() }
    fun hasAnyReservations(): Boolean = hasAnyLeases()
    override fun requiresProcessRecovery(): Boolean = poisoned || reservations.values.any { it.needsRecovery() }
    /** Cancellation revokes evidence only. It neither touches SQL/IO nor retires/force-closes owners. */
    fun revoke(artifactId: String) { reservations[artifactId]?.binding?.revoke() }

    override fun acquire(artifact: StagingArtifact, output: ArtifactOutputDescriptor.NormalizedDirect,
        context: OperationContext): DirectNormalizedOutputLease = acquireOwned(artifact, output, context)

    override fun acquire(artifact: StagingArtifact, output: ArtifactOutputDescriptor.ConvertedDirect,
        context: OperationContext): DirectNormalizedOutputLease = acquireOwned(artifact, output, context)

    private fun acquireOwned(artifact: StagingArtifact, output: ArtifactOutputDescriptor,
        context: OperationContext): DirectNormalizedOutputLease {
        checkOperation(context)
        when (output) {
            is ArtifactOutputDescriptor.NormalizedDirect -> DirectNormalizedPreflight.check(artifact, output, context)
            is ArtifactOutputDescriptor.ConvertedDirect -> DirectConvertedPreflight.check(artifact, output, context)
            is ArtifactOutputDescriptor.Transferred -> reject()
        }
        val binding = bindings.consumeForOwner(artifact, output, context, this)
        var reservation: Reservation? = null
        try {
            val owned = synchronized(ownerLock) {
                available()
                if (requiresProcessRecovery()) throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
                binding.requireLive()
                if (reservations.containsKey(artifact.artifactId)) busy()
                // Does not include this registry; existing writer/remux ownership is checked once.
                ensureNoExistingHandles(artifact)
                val authority = authority(binding)
                Reservation(binding, authority, Thread.currentThread()).also {
                    reservations[artifact.artifactId] = it
                }
            }
            reservation = owned
            checkLive(owned)
            val layout = captureLayout(owned)
            checkLive(owned)
            // Set the obligation before entering the actual constructor. A thrown constructor is
            // deliberately not classified as "nothing opened" just because no RAF was returned.
            owned.unresolvedOpen = true
            val input = io.openReadOnly(primary(owned.key))
            owned.input = input // Retain before even input.fd or any other fallible operation.
            owned.unresolvedOpen = false
            owned.descriptor = io.descriptor(input)
            owned.layout = layout
            validateLayoutAndDescriptor(owned, layout)
            checkOperation(context)
            synchronized(ownerLock) {
                requireExactReservation(owned)
                requireUnchanged(owned)
                if (owned.closeRequested.get()) reject()
                owned.stage = Stage.ACTIVE
            }
            return Lease(owned)
        } catch (failure: Throwable) {
            val owned = reservation
            if (owned == null) binding.complete()
            else {
                owned.failed = true
                owned.retainedFailures += failure
                owned.binding.revoke()
                closeOwned(owned)
            }
            if (owned != null && owned.stage != Stage.RELEASED && !isStop(failure))
                throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
            throw failure
        }
    }

    private fun available() {
        if (closed()) throw SaveFailure(SaveError.STORAGE_FAILURE)
        if (poisoned) throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
    }
    /** Complete immutable snapshots, including exact Save and ledger revisions. No filesystem IO. */
    private fun authority(binding: AdoptedVerificationBindings.Binding): Authority {
        binding.requireLive()
        val request = binding.request
        val selected = request.output
        val taskId = when (selected) {
            is ArtifactOutputDescriptor.NormalizedDirect -> selected.normalizationProof.taskId
            is ArtifactOutputDescriptor.ConvertedDirect -> selected.conversionProof.taskId
            is ArtifactOutputDescriptor.Transferred -> reject()
        }
        val record = readSave(taskId) ?: reject()
        val transfer = record.transfer ?: reject()
        val source = record.request.networkSourceMetadata ?: reject()
        val artifact = request.artifact
        val context = request.context
        if (record.task.taskId != taskId || record.task.revision != request.expectedRecordRevision ||
            record.task.state != TaskState.RUNNING || record.task.method != TaskMethod.DIRECT ||
            record.request.method != TaskMethod.DIRECT || record.phase != SavePhase.VERIFYING ||
            record.artifact != artifact || record.commitId != artifact.commitId ||
            record.operationId != context.operationId || record.attempt != context.attempt ||
            record.retryBudget != context.retryBudget || context.deadlineEpochMillis > record.deadlineEpochMillis ||
            record.verification != null || record.receipt != null || record.checkpoint != null ||
            transfer.bytesOnDisk <= 0 || transfer.expectedTotalBytes?.let { it != transfer.bytesOnDisk } == true ||
            transfer.streamExpectation != null || transfer.recordingExpectation != null ||
            transfer != selected.sourceTransfer || record.selectedOutputDescriptor() != selected) reject()
        val catalog = readCatalog(artifact.commitId) ?: reject()
        if (catalog.taskId != record.task.taskId || catalog.artifact != artifact ||
            catalog.state != OwnedState.STAGING || catalog.evidence != null) reject()
        val row = (ledger.lookup(NormalizationOwner(taskId, artifact.artifactId, artifact.commitId))
            as? NormalizationLookup.Present)?.row ?: reject()
        if (row.lifecycle != NormalizationLifecycle.ADOPTED ||
            row.disposition != NormalizationDisposition.CONTINUE || row.cleanup != null || row.failure != null ||
            row.sourceTransferSha256 != sourceTransferFingerprint(transfer)) reject()
        when (selected) {
            is ArtifactOutputDescriptor.NormalizedDirect -> {
                val proof = selected.normalizationProof
                DirectNormalizedPreflight.check(artifact, selected, context)
                if (record.directConversion != null || record.directNormalization != proof ||
                    !proof.matchesSource(taskId, source.sourceIdentity, artifact, transfer) ||
                    (source.expectedAudio && proof.conservation.audio == null) ||
                    row.key != NormalizationKey(NormalizationOwner(taskId, artifact.artifactId, artifact.commitId), proof.normalizationId) ||
                    row.raw != NormalizationFingerprint(proof.rawSizeBytes, proof.rawSha256) ||
                    row.output != NormalizationFingerprint(proof.outputSizeBytes, proof.outputSha256) ||
                    row.proofSha256 != proof.digest()) reject()
            }
            is ArtifactOutputDescriptor.ConvertedDirect -> {
                val proof = selected.conversionProof
                DirectConvertedPreflight.check(artifact, selected, context)
                if (record.directNormalization != null || record.directConversion != proof || !proof.isCurrentPolicy() ||
                    (source.expectedAudio && proof.expectation.audio == null) ||
                    !proof.matchesSource(taskId, source.sourceIdentity, artifact, transfer) ||
                    row.key != NormalizationKey(NormalizationOwner(taskId, artifact.artifactId, artifact.commitId), proof.normalizationId) ||
                    row.raw != NormalizationFingerprint(proof.rawSizeBytes, proof.rawSha256) ||
                    row.output != NormalizationFingerprint(proof.outputSizeBytes, proof.outputSha256) ||
                    row.proofSha256 != proof.digest()) reject()
            }
            is ArtifactOutputDescriptor.Transferred -> reject()
        }
        binding.requireLive() // Revocation during a durable read wins before any filesystem work.
        return Authority(record, row, catalog)
    }
    private fun requireUnchanged(owned: Reservation) {
        available()
        owned.binding.requireLive()
        if (authority(owned.binding) != owned.authority) reject()
        owned.binding.requireLive()
    }
    private fun requireExactReservation(owned: Reservation) {
        if (reservations[owned.artifact.artifactId] !== owned || owned.stage == Stage.RELEASED) reject()
    }
    private fun checkWorker(owned: Reservation) { if (Thread.currentThread() !== owned.worker) reject() }
    private fun checkLive(owned: Reservation) {
        checkWorker(owned)
        checkOperation(owned.context)
        owned.binding.requireLive()
        if (owned.closeRequested.get() || owned.nativeUncertain.get()) reject()
    }
    private fun staging() = File(root, "staging")
    private fun workspace(key: NormalizationKey) = File(staging(), key.owner.artifactId + ".normalization")
    private fun generation(key: NormalizationKey) = File(workspace(key), key.normalizationId)
    private fun primary(key: NormalizationKey) = File(staging(), key.owner.artifactId + ".part")
    private fun raw(key: NormalizationKey) = File(generation(key), "raw.bin")
    private fun output(key: NormalizationKey) = File(generation(key), "output.mp4")

    private fun captureLayout(owned: Reservation): Layout {
        val key = owned.key
        val parents = listOf(root, staging(), workspace(key), generation(key)).map { path ->
            checkLive(owned)
            path to (io.lstat(path) ?: reject())
        }
        val device = parents.first().second.device
        val identities = mutableSetOf<Pair<Long, Long>>()
        for ((_, stat) in parents) {
            if (stat.kind != NormalizationDescriptorIo.Kind.DIRECTORY || stat.device != device ||
                !identities.add(stat.device to stat.inode)) reject()
        }
        children(owned, workspace(key), setOf(key.normalizationId))
        children(owned, generation(key), setOf("raw.bin"))
        checkLive(owned)
        val p = io.lstat(primary(key)) ?: reject()
        val r = io.lstat(raw(key)) ?: reject()
        if (io.lstat(output(key)) != null) reject()
        regular(p, device, owned.output.sizeBytes)
        regular(r, device, owned.authority.row.raw.size)
        if (!identities.add(p.device to p.inode) || !identities.add(r.device to r.inode)) reject()
        // Catch parent replacement while enumeration/leaf metadata was in progress.
        for ((index, parent) in parents.withIndex()) {
            val (path, before) = parent
            if (!sameParent(index, before, io.lstat(path) ?: reject())) reject()
        }
        return Layout(parents, p, r)
    }
    /**
     * Root/staging are shared by other Direct transfers and SQLite journals. Their directory
     * timestamps, extent and link count may change without replacing our directory. The owned
     * workspace/generation and both file leaves remain strictly immutable during verification.
     */
    private fun sameParent(index: Int, expected: NormalizationDescriptorIo.Stat,
        actual: NormalizationDescriptorIo.Stat): Boolean =
        if (index < 2) expected.sameInode(actual) else expected == actual

    private fun sameLayout(expected: Layout, actual: Layout): Boolean =
        expected.primary == actual.primary && expected.raw == actual.raw &&
            expected.parents.size == actual.parents.size && expected.parents.indices.all { index ->
                val (expectedPath, expectedStat) = expected.parents[index]
                val (actualPath, actualStat) = actual.parents[index]
                expectedPath == actualPath && sameParent(index, expectedStat, actualStat)
            }
    private fun regular(stat: NormalizationDescriptorIo.Stat, device: Long, size: Long) {
        if (stat.kind != NormalizationDescriptorIo.Kind.REGULAR || stat.links != 1L ||
            stat.device != device || stat.size != size || size !in 1..NORMALIZATION_MAX_BYTES) reject()
    }
    private fun children(owned: Reservation, path: File, allowed: Set<String>) {
        checkLive(owned)
        val obligation = DirectoryObligation()
        owned.directories += obligation // Account before the possibly blocking directory constructor.
        try {
            obligation.resource = io.openDirectory(path)
            obligation.opening = false
            // A late constructor result is retained first, then closed without fresh enumeration
            // when cancellation/revocation won while the constructor was blocked.
            checkLive(owned)
            val entries = obligation.resource!!.children(allowed.size)
            if (entries.size != allowed.size || entries.toSet() != allowed) reject()
        } catch (failure: Throwable) {
            owned.retainedFailures += failure
            throw failure
        } finally {
            closeDirectory(owned, obligation)
        }
    }
    private fun closeDirectory(owned: Reservation, obligation: DirectoryObligation) {
        val resource = obligation.resource
        if (resource == null) {
            if (obligation.opening) poison(owned)
            return
        }
        if (obligation.closeStarted) return
        obligation.closeStarted = true
        try { resource.close(); obligation.released = true }
        catch (failure: Throwable) { owned.retainedFailures += failure; poison(owned) }
        if (!obligation.released) throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
    }
    private fun validateLayoutAndDescriptor(owned: Reservation, expected: Layout) {
        checkLive(owned)
        val descriptor = owned.descriptor ?: reject()
        if (!io.valid(descriptor)) reject()
        val before = io.fstat(descriptor)
        if (before != expected.primary) reject()
        if (!sameLayout(expected, captureLayout(owned))) reject()
        if (io.fstat(descriptor) != expected.primary || !io.valid(descriptor)) reject()
        checkLive(owned)
    }

    private inner class Lease(private val owned: Reservation) : DirectNormalizedOutputLease {
        override val artifact: StagingArtifact get() = owned.artifact
        override val output: ArtifactOutputDescriptor get() = owned.output
        override val readOnlyInput: RandomAccessFile get() {
            checkLive(owned)
            return synchronized(ownerLock) {
                requireExactReservation(owned)
                if (owned.stage != Stage.ACTIVE || owned.closeRequested.get()) reject()
                owned.input ?: reject()
            }
        }
        override fun revalidate() {
            checkWorker(owned)
            var validationStarted = false
            var successful = false
            try {
                checkLive(owned)
                synchronized(ownerLock) {
                    requireExactReservation(owned)
                    if (owned.stage != Stage.ACTIVE || owned.closeRequested.get()) reject()
                    requireUnchanged(owned)
                    owned.stage = Stage.VALIDATING
                    validationStarted = true
                }
                validateLayoutAndDescriptor(owned, owned.layout ?: reject())
                checkOperation(owned.context)
                synchronized(ownerLock) {
                    requireExactReservation(owned)
                    requireUnchanged(owned)
                    if (owned.closeRequested.get()) reject()
                    successful = true
                }
            } finally {
                synchronized(ownerLock) {
                    if (validationStarted && owned.stage == Stage.VALIDATING) owned.stage = Stage.ACTIVE
                    if (!successful && owned.stage != Stage.RELEASED) {
                        owned.failed = true
                        owned.binding.revoke()
                    }
                }
            }
        }
        override fun retainOnNativeReleaseUncertainty() {
            // One-way atomic/memory-only. Never wait for fileLock or invoke an external callback.
            owned.nativeUncertain.set(true)
            owned.binding.revoke()
            poison(owned)
        }
        override fun bindNativeInspection(resource: Any) {
            checkLive(owned)
            val inspection = resource as? BorrowedAvcAacInspection ?: reject()
            val input = owned.input ?: reject()
            val descriptor = owned.descriptor ?: reject()
            if (owned.output !is ArtifactOutputDescriptor.ConvertedDirect || owned.stage != Stage.ACTIVE ||
                owned.nativeInspection != null || !inspection.isPristine ||
                !inspection.borrows(descriptor, input.channel, owned.output.sizeBytes)) reject()
            // Worker-only, memory-only assignment. No native/IO callback under ownerLock.
            owned.nativeInspection = inspection
        }
        override fun confirmNativeRelease(resource: Any) {
            checkWorker(owned)
            val inspection = owned.nativeInspection ?: reject()
            if (resource !== inspection || owned.nativeUncertain.get() ||
                owned.retainedNativeInspection != null || !inspection.nativeReleaseConfirmed ||
                owned.stage != Stage.ACTIVE || owned.rafCloseStarted) reject()
            owned.convertedNativeReleased = true
        }
        override fun retainOnNativeReleaseUncertainty(resource: Any) {
            // Binding predates native entry. Keep exactly that borrower, never a replacement.
            val inspection = owned.nativeInspection
            if (inspection == null || resource !== inspection) {
                // A broken integration must still never close a potentially borrowed descriptor.
                owned.nativeUncertain.set(true)
                owned.binding.revoke()
                poison(owned)
                return
            }
            owned.retainedNativeInspection = inspection
            retainOnNativeReleaseUncertainty()
        }
        override val releaseConfirmed: Boolean get() = owned.stage == Stage.RELEASED && !owned.nativeUncertain.get()
        override fun close() {
            if (Thread.currentThread() === owned.worker && owned.stage == Stage.ACTIVE &&
                !owned.closeRequested.get() && !owned.binding.isRevoked && !owned.failed &&
                !owned.nativeUncertain.get()) owned.releaseWitnessEligible = true
            owned.closeRequested.set(true)
            owned.binding.revoke()
            if (Thread.currentThread() !== owned.worker) return // Only request; never close under native IO.
            closeOwned(owned)
            if (owned.stage == Stage.UNCERTAIN) throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
        }
    }

    /** Only the owning worker, after all its native cleanup, performs bottom-level exact-once close. */
    private fun closeOwned(owned: Reservation) {
        checkWorker(owned)
        // Native borrowing is sticky. Scope finally, late/repeated close and cancellation may only
        // revoke/retain this same reservation; they cannot close or retry the borrowed RAF.
        if (owned.output is ArtifactOutputDescriptor.ConvertedDirect &&
            (owned.nativeUncertain.get() || owned.retainedNativeInspection != null ||
                (owned.nativeInspection != null && !owned.convertedNativeReleased))) {
            owned.binding.revoke()
            owned.stage = Stage.UNCERTAIN
            poison(owned)
            return
        }
        val shouldClose = synchronized(ownerLock) {
            if (owned.stage == Stage.RELEASED || owned.stage == Stage.CLOSING || owned.stage == Stage.VALIDATING) false
            else if (owned.rafCloseStarted) false
            else {
                requireExactReservation(owned)
                owned.stage = Stage.CLOSING
                owned.rafCloseStarted = true
                true
            }
        }
        if (!shouldClose) return
        // Close every known resource independently. Never run cancellation checkpoints here.
        for (directory in owned.directories) try { closeDirectory(owned, directory) }
            catch (_: Throwable) { poison(owned) }
        val input = owned.input
        if (input != null) {
            try {
                io.close(input)
                val descriptor = owned.descriptor
                // Without the captured original FD identity, even close return is insufficient.
                owned.rafReleased = descriptor != null && !io.valid(descriptor)
                if (!owned.rafReleased) poison(owned)
            } catch (failure: Throwable) { owned.retainedFailures += failure; poison(owned) }
        } else if (!owned.unresolvedOpen) owned.rafReleased = true
        else poison(owned)
        synchronized(ownerLock) {
            val released = owned.rafReleased && !owned.unresolvedOpen && !owned.nativeUncertain.get() &&
                owned.directories.all { !it.opening && it.released } && owned.stage != Stage.UNCERTAIN
            if (released) {
                owned.stage = Stage.RELEASED
                if (confirmsReleasedBinding(owned.binding)) owned.binding.completeReleasedBy(this)
                else owned.binding.complete()
                if (reservations[owned.artifact.artifactId] === owned) reservations.remove(owned.artifact.artifactId, owned)
            } else {
                owned.stage = Stage.UNCERTAIN
                poison(owned)
            }
        }
    }
    /** Private reservation facts only; no IO, authority callback, or new release attempt. */
    internal fun confirmsReleasedBinding(binding: AdoptedVerificationBindings.Binding): Boolean {
        val owned = reservations[binding.request.artifact.artifactId] ?: return false
        return owned.binding === binding && Thread.currentThread() === owned.worker &&
            owned.stage == Stage.RELEASED && owned.releaseWitnessEligible && !owned.failed &&
            owned.input != null && owned.descriptor != null && owned.rafReleased && !owned.unresolvedOpen &&
            !owned.nativeUncertain.get() && owned.retainedNativeInspection == null &&
            owned.directories.all { !it.opening && it.released } &&
            (owned.output !is ArtifactOutputDescriptor.ConvertedDirect ||
                (owned.nativeInspection != null && owned.convertedNativeReleased &&
                    owned.nativeInspection!!.successfulInspection && owned.nativeInspection!!.nativeReleaseConfirmed))
    }
    private fun poison(owned: Reservation) {
        owned.failed = true
        poisoned = true
        failedOwners.add(this) // Strong process lifetime root, including no-return partial resources.
    }
    private companion object {
        val failedOwners: MutableSet<AdoptedCanonicalOutputOwner> = ConcurrentHashMap.newKeySet()
        fun reject(): Nothing = throw SaveFailure(SaveError.RECOVERY_REQUIRED)
        fun busy(): Nothing = throw SaveFailure(SaveError.BUSY)
        fun isStop(failure: Throwable): Boolean = failure is SaveFailure && failure.code in setOf(
            SaveError.CANCELLED, SaveError.DEADLINE_EXCEEDED, SaveError.RETRY_EXHAUSTED)
    }
}
