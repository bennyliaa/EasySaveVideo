package com.easysavevideo.commons.maintenance

import com.easysavevideo.commons.CommonsLimits
import com.easysavevideo.core.model.EpochClock
import com.easysavevideo.commons.rules.RepositoryRuleExecutionLease
import com.easysavevideo.discovery.DiscoveryError
import com.easysavevideo.discovery.DiscoveryFailure
import com.easysavevideo.discovery.DiscoveryOperation
import com.easysavevideo.discovery.ProviderAuthorityView
import com.easysavevideo.discovery.ProviderDescriptor
import com.easysavevideo.discovery.ProviderProgressScope
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong
import java.util.Collections

/** Only technical codes cross this boundary. No package bytes, filesystem names or exceptions. */
enum class ProviderRepositoryError {
    CLOSED, BUSY, CORRUPT_STATE, STORAGE_FAILURE, INVALID_PACKAGE, EXPIRED, NOT_YET_VALID, RECOVERY_REQUIRED,
    REPLAYED_VERSION, CANDIDATE_PENDING, NO_CANDIDATE, STALE_CANDIDATE, NO_ROLLBACK, CANCELLED,
    INVALID_REPOSITORY_POLICY, REPLAYED_POLICY, SECURITY_FLOOR_REGRESSION, REVOCATION_REGRESSION,
    POLICY_DISABLED, POLICY_EXPIRED, POLICY_NOT_YET_VALID, UNSAFE_VERSION, REVOKED_VERSION, REVOKED_KEY,
    SIGNED_FALLBACK_REQUIRED, ROLLBACK_NOT_AUTHORIZED, DISABLE_BARRIER, CLOCK_ROLLBACK, INVALID_TIME,
    SELF_TEST_FAILED, QUARANTINED, QUARANTINE_CAPACITY_REACHED, STALE_OBSERVATION,
    HEALTH_BUDGET_EXHAUSTED, HEALTH_WINDOW_EXPIRED,
}
enum class ProviderRepositoryState { BUNDLED, ACTIVE, CANDIDATE, DISABLED }

data class ProviderRepositorySnapshot(
    val policy: ProviderPolicy,
    /** Changes only when policy authority changes, not when an unchanged candidate is promoted. */
    val generation: Long,
    val state: ProviderRepositoryState,
    val highestAcceptedVersion: Long,
    val pendingVersion: Long?,
    val canRollback: Boolean,
    val error: ProviderRepositoryError? = null,
    val highestAcceptedPolicyVersion: Long = 0,
    val minimumSafePackageVersion: Long = 0,
    val activeVersion: Long? = null,
    val currentDigest: String? = null,
    val lastKnownGoodVersion: Long? = null,
    val health: ProviderHealthSnapshot = ProviderHealthSnapshot(ProviderHealthPhase.NONE),
    val quarantinedVersions: List<ProviderQuarantinedVersion> = emptyList(),
    val canRejectCandidate: Boolean = false,
)
sealed class ProviderRepositoryResult {
    data class Success(val snapshot: ProviderRepositorySnapshot) : ProviderRepositoryResult()
    data class Failure(val code: ProviderRepositoryError) : ProviderRepositoryResult()
}

/** Failure injection is package-internal and has no effect in production. */
internal enum class ProviderWriteStage { AFTER_INTENT_SYNC, AFTER_DATA_SYNC, AFTER_ATOMIC_MOVE, AFTER_DIRECTORY_SYNC, AFTER_COMMIT_SYNC }

/**
 * One fixed host target per repository. Package and independent repository-policy version anchors
 * are retained atomically. Every activation, health promotion and fallback passes the same gate.
 * A signed security policy is never rolled back with a package. Floors/revocations only accumulate.
 * An older package requires the current signed exact-target rollback authorization; bundled is
 * available only before the first signed package/policy. Disabled packages form a retained barrier:
 * only a newer signed package can re-enable, never a fallback behind a kill switch.
 *
 * The bounded checksum detects damage, not a hostile filesystem rewrite. Signatures authenticate
 * anchors; no local scheme here prevents replay of an entire coherent old private-directory image.
 * The clock lower bound covers committed mutations and this process's observations, not a trusted
 * clock or read-only observations lost on restart. Format-1/2 state fails closed, without reset.
 * Probation and quarantine are bounded local compatibility signals, not statistical guarantees.
 *
 * Snapshots do no filesystem I/O. Mutations belong on a background thread. One owner, initialization
 * and intent markers, atomic replacement and directory force retain the M7-A fail-closed workflow.
 */
class ProviderRepository internal constructor(
    directory: File,
    private val verifier: ProviderPackageVerifier,
    private val clock: EpochClock,
    private val bundledPolicy: ProviderPolicy,
    private val policyVerifier: RepositoryPolicyVerifier,
    private val writeFault: (ProviderWriteStage) -> Unit,
    private val selfTest: (ProviderPolicy, () -> Unit) -> Boolean = ProviderFixedSelfTest::run,
) : AutoCloseable {
    val target: ProviderTarget = verifier.target
    val maximumPackageBytes: Int get() = target.maxPackageBytes
    val maximumPolicyBytes: Int get() = target.maxPolicyBytes
    private val maximumLegacyLayoutBytes: Int get() = maximumPackageBytes * 5 + maximumPolicyBytes +
        ProviderHealthLimits.MAX_QUARANTINED_VERSIONS * 82 + 512
    private val maximumRecordBytes: Int get() = maximumLegacyLayoutBytes +
        if (target.kind.isCandidateRuleKind()) INCARNATION_BYTES else 0

    constructor(
        directory: File,
        verifier: ProviderPackageVerifier = ProviderPackageVerifier(),
        clock: EpochClock = EpochClock(System::currentTimeMillis),
        bundledPolicy: ProviderPolicy = ProviderPolicy(0, 0, Long.MAX_VALUE, true, CommonsLimits()),
        policyVerifier: RepositoryPolicyVerifier = RepositoryPolicyVerifier(),
    ) : this(directory, verifier, clock, bundledPolicy, policyVerifier, {})

    /** A host-admitted target starts disabled until a signed plan passes this same repository. */
    constructor(directory: File, target: ProviderTarget,
        verifier: ProviderPackageVerifier = ProviderPackageVerifier(target = target),
        clock: EpochClock = EpochClock(System::currentTimeMillis),
        policyVerifier: RepositoryPolicyVerifier = RepositoryPolicyVerifier(target = target),
    ) : this(directory, verifier, clock,
        ProviderPolicy(0, 0, Long.MAX_VALUE, target.kind == ProviderPackageKind.COMMONS_PARAMETERS_V1,
            target.localLimits, target), policyVerifier, {})

    internal constructor(directory: File, verifier: ProviderPackageVerifier, clock: EpochClock,
        bundledPolicy: ProviderPolicy, writeFault: (ProviderWriteStage) -> Unit) :
        this(directory, verifier, clock, bundledPolicy, RepositoryPolicyVerifier(), writeFault)

    private data class Record(
        val generation: Long = 0,
        val current: SignedProviderPackage? = null,
        val lastGood: SignedProviderPackage? = null,
        val previousGood: SignedProviderPackage? = null,
        val highWater: SignedProviderPackage? = null,
        val pending: Boolean = false,
        val hasPrevious: Boolean = false,
        val repositoryPolicy: SignedRepositoryPolicy? = null,
        val disableBarrier: SignedProviderPackage? = null,
        val observedAtEpochMillis: Long = 0,
        val health: ProviderHealthRecord? = null,
        val quarantined: List<ProviderQuarantinedVersion> = emptyList(),
        // Local non-secret stable repository identity; encoded only for rule record format 4.
        val incarnation: String? = null,
    )
    private data class Observation(
        val token: ProviderHealthObservation,
        val version: Long,
        val packageDigest: String,
        val policyVersion: Long,
        val policyDigest: String?,
        val generation: Long,
    )
    // Exactly one outstanding observation per repository, not one per caller/runtime.
    private var observation: Observation? = null
    private val mutex = Any()
    // Short publication gate only: R/U may enter A; A never enters R/U/store/coordinator or I/O.
    private val authorityGate = Any()
    private data class RuleAuthority(
        val descriptor: ProviderDescriptor,
        val packageValue: SignedProviderPackage,
        val generation: Long,
        val packageDigest: String,
        val policyDigest: String?,
        val incarnation: String,
        val notBefore: Long,
        val expiresAt: Long,
    )
    @Volatile private var ruleAuthority: RuleAuthority? = null
    // JVM monitors are reentrant: caller callbacks must not nest transactions in this owner.
    private var mutating = false
    private val observedClock = AtomicLong(0)
    private val clockMutex = Any()
    // Only genuine owner samples update clock validity. A later sample may catch up and recover;
    // caller-supplied older samples alone may simply have raced another legitimate reader.
    @Volatile private var ruleClockFailure: ProviderRepositoryError? = null
    private val directory = directory.toPath()
    private val stateFile = this.directory.resolve("provider-state.bin")
    private val intentFile = this.directory.resolve("provider-state.intent")
    private val temporaryFile = this.directory.resolve("provider-state.next")
    private val initializedFile = this.directory.resolve("provider-state.initialized")
    private var channel: FileChannel? = null
    private var fileLock: FileLock? = null
    @Volatile private var record = Record()
    @Volatile private var recoveryRequired = false
    @Volatile private var failure: ProviderRepositoryError? = null
    @Volatile private var closed = false
    private val listenerMutex = Object()
    private val listeners = linkedMapOf<Long, () -> Unit>()
    private var nextListener = 0L
    private var notifier: Thread? = null
    private var notificationPending = false
    private var notifierClosed = false

    init {
        require(bundledPolicy.packageVersion == 0L) { "INVALID_BUNDLED_POLICY" }
        require(policyVerifier.target === target && bundledPolicy.target === target) { "TARGET_MISMATCH" }
        try {
            Files.createDirectories(this.directory)
            require(Files.isDirectory(this.directory, NOFOLLOW_LINKS))
            val lockFile = this.directory.resolve("provider-state.lock")
            channel = FileChannel.open(lockFile, CREATE, WRITE, NOFOLLOW_LINKS)
            fileLock = channel!!.tryLock()
            if (fileLock == null) failure = ProviderRepositoryError.BUSY else load()
        } catch (_: java.nio.channels.OverlappingFileLockException) {
            failure = ProviderRepositoryError.BUSY
        } catch (_: Exception) {
            failure = ProviderRepositoryError.CORRUPT_STATE
        }
        replaceRecord(record, recoveryRequired)
    }

    fun snapshot(): ProviderRepositorySnapshot = snapshotLocked()

    /** Descriptor identity is a hash of complete framed identities, never a truncated digest. */
    fun ruleDescriptor(): ProviderDescriptor {
        val now = sampleTime()
        val admitted = ruleAuthority
        if (admitted != null && now.error == null && authorityCurrent(admitted, now.now)) return admitted.descriptor
        return ProviderDescriptor(target.providerId, "unavailable", progressScope = ProviderProgressScope.DURABLE)
    }

    /** One immutable signed plan and its publication view are acquired under the original owner. */
    internal fun acquireRuleExecution(expected: ProviderDescriptor, operation: DiscoveryOperation): RepositoryRuleExecutionLease = synchronized(mutex) {
        if (mutating) throw DiscoveryFailure(DiscoveryError.BUSY)
        val now = checkedNow()
        operation.check(now)
        val admitted = ruleAuthority ?: throw DiscoveryFailure(DiscoveryError.SOURCE_CONFIGURATION)
        if (!target.kind.isCandidateRuleKind() || admitted.descriptor != expected ||
            !authorityCurrent(admitted, now)) throw DiscoveryFailure(DiscoveryError.SOURCE_CONFIGURATION)
        val executionClock = EpochClock { sampleTime().let { if (it.error == null) it.now else -1L } }
        RepositoryRuleExecutionLease(this, admitted.packageValue, authorityView(admitted), operation, executionClock, beginObservation())
    }

    /** No R lock, I/O, clock callback, observation or request resources: only the original A gate. */
    internal fun currentRuleAuthority(expected: ProviderDescriptor, nowMillis: Long): ProviderAuthorityView? = synchronized(authorityGate) {
        val admitted = ruleAuthority ?: return@synchronized null
        if (admitted.descriptor != expected || !authorityCurrentLocked(admitted, nowMillis)) return@synchronized null
        authorityView(admitted)
    }

    private fun authorityView(admitted: RuleAuthority): ProviderAuthorityView = object : ProviderAuthorityView {
        override val descriptor = admitted.descriptor
        override fun isCurrent(nowMillis: Long): Boolean = authorityCurrent(admitted, nowMillis)
        override fun publishIfCurrent(nowMillis: Long, operation: DiscoveryOperation?, publish: () -> Unit): Boolean =
            synchronized(authorityGate) {
                if (!authorityCurrentLocked(admitted, nowMillis)) return@synchronized false
                operation?.check(maxOf(nowMillis, observedClock.get()))
                // Only the coordinator's already-locked, bounded memory publication is admitted.
                publish()
                true
            }
        override fun toString(): String = "ProviderAuthorityView([redacted])"
    }

    internal fun ruleAuthorityExpiry(view: ProviderAuthorityView): Long = synchronized(authorityGate) {
        ruleAuthority?.takeIf { it.descriptor == view.descriptor }?.expiresAt ?: 0L
    }

    private fun authorityCurrent(admitted: RuleAuthority, now: Long): Boolean = synchronized(authorityGate) {
        authorityCurrentLocked(admitted, now)
    }
    private fun authorityCurrentLocked(admitted: RuleAuthority, now: Long): Boolean {
        val current = ruleAuthority ?: return false
        val effectiveNow = maxOf(now, observedClock.get())
        return !closed && failure == null && ruleClockFailure == null && !recoveryRequired && now >= 0 &&
            current.descriptor == admitted.descriptor && current.generation == admitted.generation &&
            current.packageDigest == admitted.packageDigest && current.policyDigest == admitted.policyDigest &&
            current.incarnation == admitted.incarnation &&
            effectiveNow >= current.notBefore && effectiveNow < current.expiresAt
    }

    /** Compute outside A; publishing the owner record and exact view reference is atomic under A. */
    private fun replaceRecord(next: Record, recovering: Boolean) {
        if (target.kind == ProviderPackageKind.COMMONS_PARAMETERS_V1) {
            synchronized(authorityGate) { record = next; recoveryRequired = recovering; ruleAuthority = null }
            return
        }
        val now = sampleTime()
        val value = next.current
        val eligible = value != null && value.kind.isCandidateRuleKind() && value.kind == target.kind &&
            value.candidatePlan != null && value.ruleDigest != null && value.policy.enabled && next.incarnation != null &&
            !closed && failure == null && !recovering && now.error == null &&
            securityError(value, next, now = now.now) == null && timeError(value.policy, now.now) == null &&
            healthError(next, now.now) == null
        val nextAuthority = if (eligible) {
            val pack = value!!
            val packageDigest = digest(pack.encodedBytes())
            val policyDigest = next.repositoryPolicy?.let { digest(it.encodedBytes()) }
            val ruleDigest = pack.ruleDigest!!.joinToString("") { "%02x".format(it.toInt() and 255) }
            val identity = ruleAuthorityDescriptorToken(target.providerId, target.providerVersion,
                packageDigest.hexBytes(), ruleDigest.hexBytes(), policyDigest?.hexBytes(), next.generation,
                next.incarnation!!.incarnationBytes(), target.kind)
            val security = next.repositoryPolicy?.policy
            RuleAuthority(ProviderDescriptor(target.providerId, identity, progressScope = ProviderProgressScope.DURABLE),
                pack, next.generation, packageDigest, policyDigest, next.incarnation!!,
                maxOf(0L, pack.policy.issuedAtEpochMillis - ProviderPackageProtocol.MAX_CLOCK_SKEW_MILLIS,
                    (security?.issuedAtEpochMillis ?: 0) - ProviderPackageProtocol.MAX_CLOCK_SKEW_MILLIS),
                minOf(pack.policy.expiresAtEpochMillis, security?.expiresAtEpochMillis ?: Long.MAX_VALUE,
                    next.health?.deadlineEpochMillis ?: Long.MAX_VALUE))
        } else null
        synchronized(authorityGate) {
            record = next
            recoveryRequired = recovering
            ruleAuthority = nextAuthority
        }
    }

    /** Bounded, coalesced notifications run on one daemon, never while holding the repository lock. */
    fun observeChanges(listener: () -> Unit): AutoCloseable {
        val id = synchronized(listenerMutex) {
            check(!notifierClosed) { "CLOSED" }
            check(listeners.size < MAX_LISTENERS) { "BUSY" }
            val id = ++nextListener
            listeners[id] = listener
            if (notifier == null) notifier = Thread({
                while (true) {
                    val callbacks = synchronized(listenerMutex) {
                        while (!notificationPending && !notifierClosed) listenerMutex.wait()
                        if (notifierClosed && !notificationPending) return@Thread
                        notificationPending = false
                        listeners.values.toList()
                    }
                    callbacks.forEach { callback ->
                        try { callback() } catch (_: Exception) { } catch (_: LinkageError) { }
                    }
                }
            }, "esv-provider-policy").apply { isDaemon = true; start() }
            id
        }
        return AutoCloseable { synchronized(listenerMutex) { listeners.remove(id) } }
    }

    fun install(bytes: ByteArray, checkCancelled: () -> Unit = {}): ProviderRepositoryResult =
        installGuarded(bytes, checkCancelled) { publish -> publish() }

    /** Runtime-only final gate: publication contains no file I/O and must execute synchronously. */
    internal fun installGuarded(bytes: ByteArray, checkCancelled: () -> Unit,
        publication: (() -> ProviderRepositoryResult) -> ProviderRepositoryResult): ProviderRepositoryResult = mutate {
        checkAvailable()?.let { return@mutate it }
        if (record.pending) return@mutate rejected(ProviderRepositoryError.CANDIDATE_PENDING)
        val verified = verifier.verify(bytes, checkedNow(), checkCancelled)
        val candidate = (verified as? ProviderPackageResult.Verified)?.packageValue
            ?: return@mutate rejected(packageError((verified as ProviderPackageResult.Rejected).code))
        if (candidate.policy.packageVersion <= (record.highWater?.policy?.packageVersion ?: 0L))
            return@mutate rejected(ProviderRepositoryError.REPLAYED_VERSION)
        securityError(candidate, record, enforceRollback = false)?.let { return@mutate rejected(it) }
        if (candidate.policy.enabled) {
            if (record.quarantined.size >= ProviderHealthLimits.MAX_QUARANTINED_VERSIONS)
                return@mutate rejected(ProviderRepositoryError.QUARANTINE_CAPACITY_REACHED)
            checkpoint(checkCancelled)
            val passed = if (candidate.kind == ProviderPackageKind.COMMONS_PARAMETERS_V1)
                selfTest(candidate.policy) { checkpoint(checkCancelled) }
            else ProviderFixedSelfTest.run(candidate) { checkpoint(checkCancelled) }
            checkpoint(checkCancelled)
            if (!passed) {
                val next = record.copy(generation = nextGeneration(), quarantined = quarantine(candidate,
                    ProviderQuarantineReason.FIXED_SELF_TEST_FAILED))
                val result = persist(next, checkCancelled, publication)
                return@mutate if (result is ProviderRepositoryResult.Success)
                    rejected(ProviderRepositoryError.SELF_TEST_FAILED) else result
            }
        }
        val now = checkedNow()
        securityError(candidate, record, enforceRollback = false, now = now)?.let { return@mutate rejected(it) }
        timeError(candidate.policy, now)?.let { return@mutate rejected(it) }
        val next = if (candidate.policy.enabled) {
            record.copy(generation = nextGeneration(), current = candidate, highWater = candidate, pending = true,
                health = ProviderHealthRecord(now, minOf(candidate.policy.expiresAtEpochMillis,
                    record.repositoryPolicy?.policy?.expiresAtEpochMillis ?: Long.MAX_VALUE,
                    if (now > Long.MAX_VALUE - ProviderHealthLimits.MAX_PROBATION_MILLIS) Long.MAX_VALUE
                    else now + ProviderHealthLimits.MAX_PROBATION_MILLIS)))
        } else {
            // A signed kill switch needs no successful network exercise; it is safe immediately.
            record.copy(generation = nextGeneration(), current = candidate, highWater = candidate,
                lastGood = candidate, previousGood = record.lastGood, pending = false, hasPrevious = true,
                disableBarrier = candidate, health = null)
        }
        persist(next, checkCancelled, publication)
    }

    fun installPolicy(bytes: ByteArray, checkCancelled: () -> Unit = {}): ProviderRepositoryResult =
        installPolicyGuarded(bytes, checkCancelled) { publish -> publish() }

    /** Policy updates can revoke a pending candidate immediately and never wait for its health. */
    internal fun installPolicyGuarded(bytes: ByteArray, checkCancelled: () -> Unit,
        publication: (() -> ProviderRepositoryResult) -> ProviderRepositoryResult): ProviderRepositoryResult = mutate {
        checkAvailable()?.let { return@mutate it }
        val verified = policyVerifier.verify(bytes, checkedNow(), checkCancelled)
        val candidate = (verified as? RepositoryPolicyResult.Verified)?.policyValue
            ?: return@mutate rejected(repositoryPolicyError((verified as RepositoryPolicyResult.Rejected).code))
        val old = record.repositoryPolicy?.policy
        if (candidate.policy.policyVersion <= (old?.policyVersion ?: 0L))
            return@mutate rejected(ProviderRepositoryError.REPLAYED_POLICY)
        if (old != null && candidate.policy.minimumSafePackageVersion < old.minimumSafePackageVersion)
            return@mutate rejected(ProviderRepositoryError.SECURITY_FLOOR_REGRESSION)
        if (old != null && (!candidate.policy.revokedPackageVersions.containsAll(old.revokedPackageVersions) ||
            !candidate.policy.revokedPackageKeyIds.containsAll(old.revokedPackageKeyIds)))
            return@mutate rejected(ProviderRepositoryError.REVOCATION_REGRESSION)
        persist(record.copy(generation = nextGeneration(), repositoryPolicy = candidate,
            health = record.health?.copy(consecutiveSuccesses = 0, consecutiveStructureFailures = 0,
                deadlineEpochMillis = minOf(record.health!!.deadlineEpochMillis, candidate.policy.expiresAtEpochMillis))),
            checkCancelled, publication)
    }

    /** Acquire just before fresh I/O; cache reads and already-disabled candidates are not observations. */
    fun beginObservation(): ProviderHealthObservation? = synchronized(mutex) {
        if (mutating || observation != null || !record.pending || !snapshotLocked().policy.enabled) return@synchronized null
        val current = record.current ?: return@synchronized null
        val token = ProviderHealthObservation()
        observation = Observation(token, current.policy.packageVersion, digest(current.encodedBytes()),
            record.repositoryPolicy?.policy?.policyVersion ?: 0L,
            record.repositoryPolicy?.let { digest(it.encodedBytes()) }, record.generation)
        token
    }

    /** Cancellation/cache hits release the physical observation slot without any health evidence. */
    fun abandonObservation(token: ProviderHealthObservation) = synchronized(mutex) {
        if (observation?.token === token) observation = null
    }

    /** All one-use identity, cancellation, authority and time checks are inside the repository lock. */
    fun completeObservation(token: ProviderHealthObservation, outcome: ProviderHealthOutcome,
        checkCancelled: () -> Unit = {}): ProviderRepositoryResult = mutate {
        val lease = observation
        if (lease?.token !== token) return@mutate rejected(ProviderRepositoryError.STALE_OBSERVATION)
        observation = null
        checkpoint(checkCancelled)
        checkAvailable()?.let { return@mutate it }
        if (!record.pending) return@mutate rejected(ProviderRepositoryError.NO_CANDIDATE)
        if (recoveryRequired) return@mutate rejected(ProviderRepositoryError.RECOVERY_REQUIRED)
        val current = record.current ?: return@mutate rejected(ProviderRepositoryError.STALE_OBSERVATION)
        if (lease.version != current.policy.packageVersion || lease.packageDigest != digest(current.encodedBytes()) ||
            lease.generation != record.generation ||
            lease.policyVersion != (record.repositoryPolicy?.policy?.policyVersion ?: 0L) ||
            lease.policyDigest != record.repositoryPolicy?.let { digest(it.encodedBytes()) })
            return@mutate rejected(ProviderRepositoryError.STALE_OBSERVATION)
        validateNow(current, record)?.let { return@mutate rejected(it) }
        healthError(record, checkedNow())?.let { return@mutate rejected(it) }
        val old = record.health ?: return@mutate poison(ProviderRepositoryError.CORRUPT_STATE)
        val health = old.copy(
            consecutiveSuccesses = if (outcome == ProviderHealthOutcome.SUCCESS) old.consecutiveSuccesses + 1 else 0,
            consecutiveStructureFailures = if (outcome == ProviderHealthOutcome.STRUCTURE_FAILURE)
                old.consecutiveStructureFailures + 1 else 0,
            conclusiveObservations = old.conclusiveObservations + if (outcome == ProviderHealthOutcome.INCONCLUSIVE) 0 else 1,
            totalObservations = old.totalObservations + 1,
        )
        val next = when {
            health.consecutiveSuccesses >= ProviderHealthLimits.REQUIRED_SUCCESSES ->
                // Promotion changes health, not authority: the successful request remains usable.
                record.copy(lastGood = current, previousGood = record.lastGood, pending = false,
                    hasPrevious = true, health = null)
            health.consecutiveStructureFailures >= ProviderHealthLimits.REQUIRED_STRUCTURE_FAILURES ->
                record.copy(generation = nextGeneration(), current = record.lastGood, pending = false,
                    health = null, quarantined = quarantine(current, ProviderQuarantineReason.STRUCTURE_FAILURES))
            else -> record.copy(health = health, generation = if (healthExhausted(health)) nextGeneration() else record.generation)
        }
        persist(next, checkCancelled, commitValidation = {
            val now = checkedNow()
            securityError(current, record, now = now) ?: timeError(current.policy, now) ?: healthError(record, now)
        })
    }

    /** Explicit candidate failure. An expired last-good can only become a disabled effective policy. */
    fun rejectCandidate(expectedVersion: Long, checkCancelled: () -> Unit = {}): ProviderRepositoryResult = mutate {
        checkAvailable()?.let { return@mutate it }
        if (!record.pending) return@mutate rejected(ProviderRepositoryError.NO_CANDIDATE)
        if (record.current?.policy?.packageVersion != expectedVersion) return@mutate rejected(ProviderRepositoryError.STALE_CANDIDATE)
        authenticate(record.lastGood) ?: return@mutate poison(ProviderRepositoryError.CORRUPT_STATE)
        persist(record.copy(generation = nextGeneration(), current = record.lastGood, pending = false, health = null,
            quarantined = quarantine(record.current!!, ProviderQuarantineReason.EXPLICIT_REJECTION)), checkCancelled)
    }

    /** A stable rollback is one-way, and never moves the trusted version high-water backwards. */
    fun rollback(checkCancelled: () -> Unit = {}): ProviderRepositoryResult = rollbackGuarded(null, checkCancelled)

    /** Management confirmations name the exact authority the user reviewed, under the same lock. */
    fun rollback(expectedGeneration: Long, checkCancelled: () -> Unit = {}): ProviderRepositoryResult =
        rollbackGuarded(expectedGeneration, checkCancelled)

    private fun rollbackGuarded(expectedGeneration: Long?, checkCancelled: () -> Unit): ProviderRepositoryResult = mutate {
        checkAvailable()?.let { return@mutate it }
        if (expectedGeneration != null && expectedGeneration != record.generation)
            return@mutate rejected(ProviderRepositoryError.STALE_CANDIDATE)
        if (record.pending) return@mutate rejected(ProviderRepositoryError.CANDIDATE_PENDING)
        if (!record.hasPrevious) return@mutate rejected(ProviderRepositoryError.NO_ROLLBACK)
        validateNow(record.previousGood, record)?.let { return@mutate rejected(it) }
        rollbackTargetError(record.previousGood, record, checkedNow())?.let { return@mutate rejected(it) }
        persist(record.copy(generation = nextGeneration(), current = record.previousGood, lastGood = record.previousGood,
            previousGood = null, hasPrevious = false, health = null), checkCancelled)
    }

    override fun close() {
        synchronized(mutex) {
            if (closed) return
            observation = null
            synchronized(authorityGate) {
                closed = true
                ruleAuthority = null
                record = record.copy(generation = safeGeneration())
            }
            // Keep ownership until an in-progress transaction has fenced publication and left
            // its recovery marker. A new owner must not read an unpublished committed image.
            if (!mutating) releaseOwnership()
        }
        signalChange()
        synchronized(listenerMutex) { notifierClosed = true; listenerMutex.notifyAll() }
    }

    private fun releaseOwnership() {
        try { fileLock?.release() } catch (_: Exception) { }
        try { channel?.close() } catch (_: Exception) { }
    }

    private fun mutate(action: () -> ProviderRepositoryResult): ProviderRepositoryResult {
        val changed: Boolean
        val result: ProviderRepositoryResult
        synchronized(mutex) {
            if (mutating) return rejected(ProviderRepositoryError.BUSY)
            mutating = true
            try {
                val oldRecord = record
                val oldFailure = failure
                result = try { action() } catch (_: TransactionCancelled) { rejected(ProviderRepositoryError.CANCELLED) }
                    catch (denied: PublicationRejected) { rejected(denied.code) }
                    catch (badClock: ClockFailure) { rejected(badClock.code) }
                    catch (_: Exception) { poison(ProviderRepositoryError.STORAGE_FAILURE) }
                changed = record !== oldRecord || failure != oldFailure
            } finally {
                mutating = false
                if (closed) releaseOwnership()
            }
        }
        if (changed) signalChange()
        return result
    }
    private fun signalChange() = synchronized(listenerMutex) {
        if (listeners.isNotEmpty()) { notificationPending = true; listenerMutex.notifyAll() }
    }
    private fun checkAvailable(): ProviderRepositoryResult.Failure? =
        if (closed) rejected(ProviderRepositoryError.CLOSED) else (failure ?: clockError())?.let { rejected(it) }

    private data class ClockSample(val now: Long, val error: ProviderRepositoryError?)
    private class ClockFailure(val code: ProviderRepositoryError) : RuntimeException(code.name, null, false, false)

    /** Every safety decision records its sole time sample under this short sampling lock. */
    private fun sampleTime(): ClockSample = synchronized(clockMutex) {
        val rules = target.kind.isCandidateRuleKind()
        val now = try { clock.nowMillis() } catch (_: Exception) {
            if (rules) ruleClockFailure = ProviderRepositoryError.INVALID_TIME
            return@synchronized ClockSample(0, if (rules) ruleClockFailure else ProviderRepositoryError.INVALID_TIME)
        }
        val error = when {
            now < 0 -> ProviderRepositoryError.INVALID_TIME
            now < observedClock.get() -> ProviderRepositoryError.CLOCK_ROLLBACK
            else -> { observedClock.set(now); null }
        }
        if (rules) ruleClockFailure = error
        ClockSample(now, error)
    }
    private fun clockError(): ProviderRepositoryError? = sampleTime().error
    private fun checkedNow(): Long = sampleTime().let { it.error?.let { error -> throw ClockFailure(error) }; it.now }
    private fun snapshotLocked(): ProviderRepositorySnapshot {
        val record = this.record
        val policy = record.current?.policy ?: bundledPolicy
        val time = sampleTime()
        val error = if (closed) ProviderRepositoryError.CLOSED else failure ?:
            if (recoveryRequired) ProviderRepositoryError.RECOVERY_REQUIRED else time.error ?:
                securityError(record.current, record, now = time.now) ?: timeError(policy, time.now) ?: healthError(record, time.now)
        return ProviderRepositorySnapshot(
            policy = if (error == null) policy else policy.copy(enabled = false),
            generation = record.generation,
            state = when {
                error != null || !policy.enabled -> ProviderRepositoryState.DISABLED
                record.pending -> ProviderRepositoryState.CANDIDATE
                record.current == null -> ProviderRepositoryState.BUNDLED
                else -> ProviderRepositoryState.ACTIVE
            },
            highestAcceptedVersion = record.highWater?.policy?.packageVersion ?: 0L,
            pendingVersion = if (record.pending) record.current?.policy?.packageVersion else null,
            canRollback = !closed && failure == null && !recoveryRequired && !record.pending && record.hasPrevious &&
                time.error == null && rollbackTargetError(record.previousGood, record, time.now) == null,
            error = error,
            highestAcceptedPolicyVersion = record.repositoryPolicy?.policy?.policyVersion ?: 0L,
            minimumSafePackageVersion = record.repositoryPolicy?.policy?.minimumSafePackageVersion ?: 0L,
            activeVersion = if (error == null && policy.enabled) record.current?.policy?.packageVersion else null,
            currentDigest = record.current?.let { digest(it.encodedBytes()) },
            lastKnownGoodVersion = record.lastGood?.policy?.packageVersion,
            health = record.health?.let { ProviderHealthSnapshot(
                phase = if (error == null && policy.enabled) ProviderHealthPhase.PROBATION else ProviderHealthPhase.SUSPENDED,
                consecutiveSuccesses = it.consecutiveSuccesses, consecutiveStructureFailures = it.consecutiveStructureFailures,
                conclusiveObservations = it.conclusiveObservations, totalObservations = it.totalObservations,
                startedAtEpochMillis = it.startedAtEpochMillis, deadlineEpochMillis = it.deadlineEpochMillis,
            ) } ?: ProviderHealthSnapshot(if (record.current != null && policy.enabled && error == null)
                ProviderHealthPhase.HEALTHY else ProviderHealthPhase.NONE),
            quarantinedVersions = Collections.unmodifiableList(record.quarantined.toList()),
            canRejectCandidate = !closed && failure == null && time.error == null && record.pending,
        )
    }
    private fun timeError(policy: ProviderPolicy, now: Long): ProviderRepositoryError? {
        if (policy.packageVersion == 0L) return null
        return when {
            now < 0 || (now < policy.issuedAtEpochMillis && policy.issuedAtEpochMillis - now > ProviderPackageProtocol.MAX_CLOCK_SKEW_MILLIS) -> ProviderRepositoryError.NOT_YET_VALID
            now >= policy.expiresAtEpochMillis -> ProviderRepositoryError.EXPIRED
            else -> null
        }
    }
    private fun validateNow(value: SignedProviderPackage?, state: Record): ProviderRepositoryError? {
        val now = checkedNow()
        securityError(value, state, now = now)?.let { return it }
        if (value == null) return null
        return when (val verified = verifier.verify(value.encodedBytes(), now)) {
            is ProviderPackageResult.Verified -> null
            is ProviderPackageResult.Rejected -> packageError(verified.code)
        }
    }
    private fun securityError(value: SignedProviderPackage?, state: Record,
        enforceRollback: Boolean = true, now: Long = checkedNow()): ProviderRepositoryError? {
        val security = state.repositoryPolicy?.policy
        if (security != null) {
            if (now < 0 || (now < security.issuedAtEpochMillis &&
                security.issuedAtEpochMillis - now > ProviderPackageProtocol.MAX_CLOCK_SKEW_MILLIS))
                return ProviderRepositoryError.POLICY_NOT_YET_VALID
            if (now >= security.expiresAtEpochMillis) return ProviderRepositoryError.POLICY_EXPIRED
            if (!security.enabled) return ProviderRepositoryError.POLICY_DISABLED
        }
        if (value == null) return if (state.highWater != null || security != null)
            ProviderRepositoryError.SIGNED_FALLBACK_REQUIRED else null
        val version = value.policy.packageVersion
        if (state.quarantined.any { it.version == version }) return ProviderRepositoryError.QUARANTINED
        if (security != null) {
            if (version < security.minimumSafePackageVersion) return ProviderRepositoryError.UNSAFE_VERSION
            if (version in security.revokedPackageVersions) return ProviderRepositoryError.REVOKED_VERSION
            if (value.keyId in security.revokedPackageKeyIds) return ProviderRepositoryError.REVOKED_KEY
        }
        if (value.policy.enabled && version <= (state.disableBarrier?.policy?.packageVersion ?: 0L))
            return ProviderRepositoryError.DISABLE_BARRIER
        if (enforceRollback && version < (state.highWater?.policy?.packageVersion ?: 0L) && value.policy.enabled) {
            val authorization = security?.rollbackAuthorization ?: return ProviderRepositoryError.ROLLBACK_NOT_AUTHORIZED
            if (authorization.targetPackageVersion != version || !MessageDigest.isEqual(
                authorization.targetPackageDigest, MessageDigest.getInstance("SHA-256").digest(value.encodedBytes())))
                return ProviderRepositoryError.ROLLBACK_NOT_AUTHORIZED
        }
        return null
    }

    /** Explicit pointer rollback always needs a grant, even when the destination stays disabled. */
    private fun rollbackTargetError(value: SignedProviderPackage?, state: Record, now: Long): ProviderRepositoryError? {
        securityError(value, state, now = now)?.let { return it }
        if (value == null) return ProviderRepositoryError.SIGNED_FALLBACK_REQUIRED
        timeError(value.policy, now)?.let { return it }
        val grant = state.repositoryPolicy?.policy?.rollbackAuthorization
            ?: return ProviderRepositoryError.ROLLBACK_NOT_AUTHORIZED
        return if (grant.targetPackageVersion == value.policy.packageVersion && MessageDigest.isEqual(
            grant.targetPackageDigest, MessageDigest.getInstance("SHA-256").digest(value.encodedBytes()))) null
            else ProviderRepositoryError.ROLLBACK_NOT_AUTHORIZED
    }

    private fun healthExhausted(health: ProviderHealthRecord): Boolean =
        health.conclusiveObservations >= ProviderHealthLimits.MAX_CONCLUSIVE_OBSERVATIONS ||
            health.totalObservations >= ProviderHealthLimits.MAX_TOTAL_OBSERVATIONS

    private fun healthError(state: Record, now: Long): ProviderRepositoryError? {
        val health = state.health ?: return null
        return when {
            healthExhausted(health) -> ProviderRepositoryError.HEALTH_BUDGET_EXHAUSTED
            now >= health.deadlineEpochMillis -> ProviderRepositoryError.HEALTH_WINDOW_EXPIRED
            else -> null
        }
    }

    private fun quarantine(value: SignedProviderPackage, reason: ProviderQuarantineReason): List<ProviderQuarantinedVersion> {
        check(record.quarantined.size < ProviderHealthLimits.MAX_QUARANTINED_VERSIONS)
        check(record.quarantined.none { it.version == value.policy.packageVersion })
        return record.quarantined + ProviderQuarantinedVersion(value.policy.packageVersion, digest(value.encodedBytes()),
            record.repositoryPolicy?.policy?.policyVersion ?: 0L,
            record.repositoryPolicy?.let { digest(it.encodedBytes()) }, reason)
    }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun authenticate(value: SignedProviderPackage?): Boolean? {
        if (value == null) return true
        return if (verifier.verifyAuthenticity(value.encodedBytes()) is ProviderPackageResult.Verified) true else null
    }
    private fun nextGeneration(): Long {
        check(record.generation < Long.MAX_VALUE) { "CORRUPT_STATE" }
        return record.generation + 1
    }
    private fun safeGeneration(): Long = if (record.generation == Long.MAX_VALUE) Long.MAX_VALUE else record.generation + 1
    private fun poison(code: ProviderRepositoryError): ProviderRepositoryResult.Failure {
        observation = null
        synchronized(authorityGate) {
            failure = code
            ruleAuthority = null
            record = record.copy(generation = safeGeneration())
        }
        return rejected(code)
    }
    private fun rejected(code: ProviderRepositoryError) = ProviderRepositoryResult.Failure(code)

    /** Unlike Files.exists, access/I/O uncertainty must never be mistaken for a fresh store. */
    private fun exists(path: java.nio.file.Path): Boolean = try {
        Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        true
    } catch (_: NoSuchFileException) { false }

    private fun load() {
        if (exists(intentFile) || exists(temporaryFile)) {
            failure = ProviderRepositoryError.CORRUPT_STATE; return
        }
        val initialized = exists(initializedFile)
        val hasState = exists(stateFile)
        if (!initialized && !hasState) {
            if (target.kind.isCandidateRuleKind())
                record = record.copy(incarnation = newIncarnation())
            return
        }
        check(initialized && hasState)
        check(readBounded(initializedFile, INITIALIZED_BYTES.size).contentEquals(INITIALIZED_BYTES))
        record = decode(readBounded(stateFile, maximumRecordBytes))
        observedClock.set(record.observedAtEpochMillis)
        recoveryRequired = record.pending
        if (target.kind == ProviderPackageKind.DECLARATIVE_JSON_CANDIDATES_V1 && record.incarnation == null) {
            // decode already authenticated every old format-3 anchor. Migration uses the same
            // intent/atomic replacement/publication transaction while the instance is unexported.
            // Pending recovery remains pending; a failed transaction never issues a view.
            val migration = persist(record.copy(incarnation = newIncarnation()))
            if (migration is ProviderRepositoryResult.Failure && failure == null) poison(migration.code)
        }
    }

    private fun newIncarnation(): String = ByteArray(INCARNATION_BYTES).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun String.incarnationBytes(): ByteArray {
        check(length == INCARNATION_BYTES * 2 && all { it in '0'..'9' || it in 'a'..'f' })
        return ByteArray(INCARNATION_BYTES) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private class PublicationRejected(val code: ProviderRepositoryError) : RuntimeException(code.name, null, false, false)
    private class TransactionCancelled : RuntimeException("CANCELLED", null, false, false)
    private fun checkpoint(checkCancelled: () -> Unit) {
        try { checkCancelled() } catch (_: Exception) { throw TransactionCancelled() }
        if (closed) throw PublicationRejected(ProviderRepositoryError.CLOSED)
    }
    private fun persist(next: Record, checkCancelled: () -> Unit = {},
        publication: (() -> ProviderRepositoryResult) -> ProviderRepositoryResult = { it() },
        commitValidation: () -> ProviderRepositoryError? = { null }): ProviderRepositoryResult {
        val sourceRecord = record
        fun transactionCheckpoint() {
            checkpoint(checkCancelled)
            if (record !== sourceRecord) throw PublicationRejected(ProviderRepositoryError.STALE_OBSERVATION)
        }
        if (closed) return rejected(ProviderRepositoryError.CLOSED)
        clockError()?.let { return rejected(it) }
        commitValidation()?.let { return rejected(it) }
        val durableNext = next.copy(observedAtEpochMillis = observedClock.get())
        val bytes = encode(durableNext)
        // Cancellation before the transaction begins cannot create a marker or disable old policy.
        try { checkCancelled() } catch (_: Exception) { return rejected(ProviderRepositoryError.CANCELLED) }
        if (closed) return rejected(ProviderRepositoryError.CLOSED)
        if (record !== sourceRecord) return rejected(ProviderRepositoryError.STALE_OBSERVATION)
        var transactionStarted = false
        try {
            if (!exists(initializedFile)) writeNew(initializedFile, INITIALIZED_BYTES)
            writeNew(intentFile, INTENT_BYTES)
            transactionStarted = true
            syncDirectory()
            writeFault(ProviderWriteStage.AFTER_INTENT_SYNC)
            transactionCheckpoint()
            writeNew(temporaryFile, bytes)
            writeFault(ProviderWriteStage.AFTER_DATA_SYNC)
            transactionCheckpoint()
            Files.move(temporaryFile, stateFile, ATOMIC_MOVE, REPLACE_EXISTING)
            writeFault(ProviderWriteStage.AFTER_ATOMIC_MOVE)
            transactionCheckpoint()
            syncDirectory()
            writeFault(ProviderWriteStage.AFTER_DIRECTORY_SYNC)
            transactionCheckpoint()
            Files.delete(intentFile)
            syncDirectory()
            writeFault(ProviderWriteStage.AFTER_COMMIT_SYNC)
            transactionCheckpoint()
            return publication {
                transactionCheckpoint()
                commitValidation()?.let { throw PublicationRejected(it) }
                if (closed) throw PublicationRejected(ProviderRepositoryError.CLOSED)
                if (record !== sourceRecord) throw PublicationRejected(ProviderRepositoryError.STALE_OBSERVATION)
                if (record.generation != durableNext.generation) observation = null
                replaceRecord(durableNext, recoveryRequired && durableNext.pending)
                ProviderRepositoryResult.Success(snapshotLocked())
            }
        } catch (error: Exception) {
            // Recreate the marker if a post-unlink sync or publication check failed. Do not remove
            // signed state or initialization evidence. A filesystem that refuses further writes
            // cannot promise durable recovery, but this instance always stays disabled.
            try {
                if (!exists(intentFile)) writeNew(intentFile, INTENT_BYTES)
                syncDirectory()
            } catch (_: Exception) { }
            poison(if (transactionStarted) ProviderRepositoryError.STORAGE_FAILURE else ProviderRepositoryError.CORRUPT_STATE)
            return rejected(when (error) {
                is TransactionCancelled -> ProviderRepositoryError.CANCELLED
                is PublicationRejected -> error.code
                is ClockFailure -> error.code
                else -> failure!!
            })
        }
    }
    private fun writeNew(path: java.nio.file.Path, bytes: ByteArray) {
        check(bytes.size <= maximumRecordBytes)
        FileChannel.open(path, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { file ->
            val data = ByteBuffer.wrap(bytes)
            while (data.hasRemaining()) check(file.write(data) > 0)
            file.force(true)
        }
    }
    private fun syncDirectory() { FileChannel.open(directory, READ).use { it.force(true) } }
    private fun readBounded(path: java.nio.file.Path, maximum: Int): ByteArray {
        check(Files.isRegularFile(path, NOFOLLOW_LINKS))
        FileChannel.open(path, READ, NOFOLLOW_LINKS).use { file ->
            check(file.size() in 1..maximum.toLong())
            val output = ByteArrayOutputStream()
            val buffer = ByteBuffer.allocate(minOf(4096, maximum + 1))
            while (true) {
                buffer.clear()
                buffer.limit(minOf(buffer.capacity(), maximum - output.size() + 1))
                val count = file.read(buffer)
                if (count < 0) break
                check(count > 0 && output.size() + count <= maximum)
                output.write(buffer.array(), 0, count)
            }
            return output.toByteArray()
        }
    }
    private fun encode(value: Record): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            val rules = target.kind.isCandidateRuleKind()
            out.writeInt(MAGIC); out.writeInt(if (rules) RULE_FORMAT else FORMAT); out.writeLong(value.generation)
            if (rules) out.write(checkNotNull(value.incarnation).incarnationBytes()) else check(value.incarnation == null)
            out.writeBoolean(value.pending); out.writeBoolean(value.hasPrevious)
            out.writeLong(value.observedAtEpochMillis)
            val security = value.repositoryPolicy?.encodedBytes()
            out.writeInt(security?.size ?: 0)
            if (security != null) out.write(security)
            out.writeBoolean(value.health != null)
            value.health?.let { health ->
                out.writeLong(health.startedAtEpochMillis); out.writeLong(health.deadlineEpochMillis)
                out.writeInt(health.consecutiveSuccesses); out.writeInt(health.consecutiveStructureFailures)
                out.writeInt(health.conclusiveObservations); out.writeInt(health.totalObservations)
            }
            out.writeInt(value.quarantined.size)
            value.quarantined.forEach { entry ->
                out.writeLong(entry.version); out.write(entry.packageDigest.hexBytes())
                out.writeLong(entry.policyVersion)
                out.writeBoolean(entry.policyDigest != null)
                entry.policyDigest?.let { out.write(it.hexBytes()) }
                out.writeByte(entry.reason.ordinal)
            }
            listOf(value.current, value.lastGood, value.previousGood, value.disableBarrier, value.highWater).forEach { pack ->
                val encoded = pack?.encodedBytes()
                out.writeInt(encoded?.size ?: 0)
                if (encoded != null) out.write(encoded)
            }
        }
        val payload = bytes.toByteArray()
        return payload + MessageDigest.getInstance("SHA-256").digest(payload)
    }
    private fun decode(bytes: ByteArray): Record {
        check(bytes.size in MIN_RECORD_BYTES..maximumRecordBytes)
        val payload = bytes.copyOfRange(0, bytes.size - DIGEST_BYTES)
        check(MessageDigest.isEqual(bytes.copyOfRange(payload.size, bytes.size), MessageDigest.getInstance("SHA-256").digest(payload)))
        val input = DataInputStream(ByteArrayInputStream(payload))
        check(input.readInt() == MAGIC)
        val format = input.readInt()
        check(when (target.kind) {
            ProviderPackageKind.COMMONS_PARAMETERS_V1 -> format == FORMAT
            ProviderPackageKind.DECLARATIVE_JSON_CANDIDATES_V1 -> format == FORMAT || format == RULE_FORMAT
            ProviderPackageKind.BOUNDED_HTTP_CANDIDATES_V1 -> format == RULE_FORMAT
        })
        check(bytes.size in (MIN_RECORD_BYTES + if (format == RULE_FORMAT) INCARNATION_BYTES else 0)..
            (maximumLegacyLayoutBytes + if (format == RULE_FORMAT) INCARNATION_BYTES else 0))
        val generation = input.readLong(); check(generation > 0)
        val incarnation = if (format == RULE_FORMAT) ByteArray(INCARNATION_BYTES).also(input::readFully)
            .joinToString("") { "%02x".format(it.toInt() and 255) } else null
        fun flag(): Boolean = when (input.readUnsignedByte()) { 0 -> false; 1 -> true; else -> error("CORRUPT_STATE") }
        val pending = flag(); val hasPrevious = flag()
        val observedAt = input.readLong(); check(observedAt >= 0)
        val policySize = input.readInt()
        check(policySize in 0..maximumPolicyBytes && policySize <= input.available())
        val security = if (policySize == 0) null else {
            val encoded = ByteArray(policySize); input.readFully(encoded)
            (policyVerifier.verifyAuthenticity(encoded) as? RepositoryPolicyResult.Verified)?.policyValue
                ?: error("CORRUPT_STATE")
        }
        val health = if (!flag()) null else {
            val started = input.readLong(); val deadline = input.readLong()
            val successes = input.readInt(); val failures = input.readInt()
            val conclusive = input.readInt(); val total = input.readInt()
            check(started >= 0 && started <= observedAt && deadline > started &&
                deadline - started <= ProviderHealthLimits.MAX_PROBATION_MILLIS)
            check(successes in 0 until ProviderHealthLimits.REQUIRED_SUCCESSES &&
                failures in 0 until ProviderHealthLimits.REQUIRED_STRUCTURE_FAILURES &&
                (successes == 0 || failures == 0))
            check(conclusive in 0..ProviderHealthLimits.MAX_CONCLUSIVE_OBSERVATIONS &&
                total in conclusive..ProviderHealthLimits.MAX_TOTAL_OBSERVATIONS &&
                maxOf(successes, failures) <= conclusive)
            ProviderHealthRecord(started, deadline, successes, failures, conclusive, total)
        }
        val quarantineCount = input.readInt()
        check(quarantineCount in 0..ProviderHealthLimits.MAX_QUARANTINED_VERSIONS)
        fun hash(): String {
            val hash = ByteArray(DIGEST_BYTES); input.readFully(hash)
            return hash.joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        val quarantined = List(quarantineCount) {
            val version = input.readLong(); check(version > 0)
            val packageDigest = hash()
            val policyVersion = input.readLong(); check(policyVersion >= 0 && policyVersion <= (security?.policy?.policyVersion ?: 0L))
            val policyDigest = if (flag()) hash() else null
            check((policyVersion == 0L) == (policyDigest == null))
            val reason = input.readUnsignedByte(); check(reason in ProviderQuarantineReason.entries.indices)
            ProviderQuarantinedVersion(version, packageDigest, policyVersion, policyDigest, ProviderQuarantineReason.entries[reason])
        }
        check(quarantined.map { it.version }.distinct().size == quarantined.size)
        check(pending == (health != null))
        fun pack(): SignedProviderPackage? {
            val size = input.readInt()
            if (size == 0) return null
            check(size in 1..maximumPackageBytes && size <= input.available())
            val encoded = ByteArray(size); input.readFully(encoded)
            return (verifier.verifyAuthenticity(encoded) as? ProviderPackageResult.Verified)?.packageValue ?: error("CORRUPT_STATE")
        }
        val current = pack(); val lastGood = pack(); val previous = pack(); val barrier = pack(); val highWater = pack()
        check(input.available() == 0 && (highWater != null || security != null || quarantined.isNotEmpty()))
        val high = highWater?.policy?.packageVersion ?: 0L
        listOfNotNull(current, lastGood, previous, barrier).forEach { check(it.policy.packageVersion <= high) }
        check(!pending || (current != null && current.policy.packageVersion == high &&
            current.encodedBytes().contentEquals(highWater?.encodedBytes()) &&
            (lastGood == null || lastGood.policy.packageVersion < current.policy.packageVersion)))
        check(pending || samePackage(current, lastGood))
        check(health == null || (current != null && current.policy.enabled &&
            health.deadlineEpochMillis <= current.policy.expiresAtEpochMillis))
        check(current == null || quarantined.none { it.version == current.policy.packageVersion })
        check(lastGood == null || quarantined.none { it.version == lastGood.policy.packageVersion })
        check(hasPrevious || previous == null)
        check(previous == null || (lastGood != null && previous.policy.packageVersion < lastGood.policy.packageVersion))
        check(barrier == null || !barrier.policy.enabled)
        check(listOfNotNull(current, lastGood, previous, highWater).none {
            !it.policy.enabled && it.policy.packageVersion > (barrier?.policy?.packageVersion ?: 0L)
        })
        return Record(generation, current, lastGood, previous, highWater, pending, hasPrevious, security, barrier, observedAt, health, quarantined, incarnation)
    }
    private fun String.hexBytes(): ByteArray {
        check(length == DIGEST_BYTES * 2)
        return ByteArray(DIGEST_BYTES) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun samePackage(a: SignedProviderPackage?, b: SignedProviderPackage?): Boolean =
        if (a == null || b == null) a == null && b == null else a.encodedBytes().contentEquals(b.encodedBytes())

    override fun toString(): String = "ProviderRepository(state=${snapshot().state})"
    companion object {
        const val MAX_PACKAGE_BYTES = 8192
        const val MAX_POLICY_BYTES = 8192
        const val MAX_RECORD_BYTES = MAX_PACKAGE_BYTES * 5 + MAX_POLICY_BYTES +
            ProviderHealthLimits.MAX_QUARANTINED_VERSIONS * 82 + 512
        private const val MIN_RECORD_BYTES = 82
        private const val MAGIC = 0x45535652
        private const val FORMAT = 3
        private const val RULE_FORMAT = 4
        private const val INCARNATION_BYTES = 16
        private const val DIGEST_BYTES = 32
        private const val MAX_LISTENERS = 32
        private val INITIALIZED_BYTES = byteArrayOf(69, 83, 86, 73, 1)
        private val INTENT_BYTES = byteArrayOf(69, 83, 86, 84, 1)
        private fun repositoryPolicyError(code: RepositoryPolicyError): ProviderRepositoryError = when (code.name) {
            "EXPIRED" -> ProviderRepositoryError.POLICY_EXPIRED
            "NOT_YET_VALID" -> ProviderRepositoryError.POLICY_NOT_YET_VALID
            "CANCELLED" -> ProviderRepositoryError.CANCELLED
            else -> ProviderRepositoryError.INVALID_REPOSITORY_POLICY
        }
        private fun packageError(code: ProviderPackageError): ProviderRepositoryError = when (code.name) {
            "EXPIRED" -> ProviderRepositoryError.EXPIRED
            "NOT_YET_VALID" -> ProviderRepositoryError.NOT_YET_VALID
            "CANCELLED" -> ProviderRepositoryError.CANCELLED
            else -> ProviderRepositoryError.INVALID_PACKAGE
        }
    }
}

/** Canonical identity frame v2; no executable authority is created by this pure encoding helper. */
internal fun ruleAuthorityDescriptorToken(targetId: String, implementationVersion: String,
    packageDigest: ByteArray, ruleDigest: ByteArray, policyDigest: ByteArray?, generation: Long, incarnation: ByteArray,
    kind: ProviderPackageKind = ProviderPackageKind.DECLARATIVE_JSON_CANDIDATES_V1): String {
    require(packageDigest.size == 32 && ruleDigest.size == 32 && (policyDigest == null || policyDigest.size == 32))
    require(generation >= 0 && incarnation.size == 16)
    val bytes = ByteArrayOutputStream().also { buffer ->
        DataOutputStream(buffer).use { output ->
            fun text(value: String) { val encoded = value.toByteArray(Charsets.UTF_8); output.writeInt(encoded.size); output.write(encoded) }
            text(when (kind) {
                ProviderPackageKind.DECLARATIVE_JSON_CANDIDATES_V1 -> "esv-signed-json-authority"
                ProviderPackageKind.BOUNDED_HTTP_CANDIDATES_V1 -> "esv-signed-http-authority"
                ProviderPackageKind.COMMONS_PARAMETERS_V1 -> error("NOT_A_CANDIDATE_RULE")
            })
            output.writeInt(2)
            output.write(incarnation)
            text(targetId)
            text(implementationVersion)
            output.write(packageDigest)
            output.write(ruleDigest)
            output.writeBoolean(policyDigest != null)
            if (policyDigest != null) output.write(policyDigest)
            output.writeLong(generation)
        }
    }.toByteArray()
    return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
}

/** New kinds require deliberate admission; an unknown future kind never inherits rule authority. */
private fun ProviderPackageKind.isCandidateRuleKind(): Boolean = when (this) {
    ProviderPackageKind.COMMONS_PARAMETERS_V1 -> false
    ProviderPackageKind.DECLARATIVE_JSON_CANDIDATES_V1, ProviderPackageKind.BOUNDED_HTTP_CANDIDATES_V1 -> true
}
