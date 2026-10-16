package com.easysavevideo.core.domain

import com.easysavevideo.core.model.*

/** Deterministic metadata reducer. There is deliberately no effect executor or production wiring. */
class VaultSessionReducer(private val retryPolicy: VaultRetryPolicy) {
    fun start(processInstance: Long, now: VaultTick, ledgerLoad: VaultLedgerLoad): VaultTransition {
        val ledger = (ledgerLoad as? VaultLedgerLoad.Restored)?.ledger ?: VaultRetryLedger(0, 0, -1, null)
        val valid = processInstance > 0 && now.processInstance == processInstance && now.elapsedMillis >= 0 &&
            retryPolicy.isValid() && validLedger(ledger) &&
            (ledgerLoad is VaultLedgerLoad.Restored || ledgerLoad == VaultLedgerLoad.TrustedFirstUse)
        val seed = VaultState(
            0, processInstance, now, 0, 0, 0, VaultMode.LOCKED, VaultReason.STARTUP, false,
            VaultVisibility.COVERED, VaultAutoLockPolicy.IMMEDIATE, 0, 0, null, null, null, null,
            VaultRetryStatus.READY, null, ledger, null, VaultCleanupStatus.PENDING, null,
            VaultRecoveryChoice.UNDECIDED, VaultRecoveryMaterialStatus.NOT_PROVISIONED,
        )
        val engine = Engine(seed, now)
        if (!valid) engine.failClosed(VaultReason.UNSUPPORTED_STATE)
        else {
            engine.protect(true, VaultReason.STARTUP)
            if (ledger.reservedAttemptCount > 0) engine.cooldown()
        }
        return engine.finish()
    }

    fun reduce(current: VaultState, event: VaultEvent, now: VaultTick): VaultTransition {
        val engine = Engine(current, now)
        if (!retryPolicy.isValid() || !validState(current)) {
            engine.failClosed(VaultReason.UNSUPPORTED_STATE)
        } else if (now.processInstance != current.processInstance || now.elapsedMillis < 0 ||
            now.elapsedMillis < current.lastObservedTick.elapsedMillis) {
            engine.failClosed(VaultReason.CLOCK_INVALID)
        } else if (current.revision == Long.MAX_VALUE) {
            engine.failClosed(VaultReason.COUNTER_EXHAUSTED)
        } else {
            // Even ignored stale events advance the observation watermark.
            engine.state = current.copy(revision = current.revision + 1, lastObservedTick = now)
            engine.expire()
            if (engine.outcome != VaultTransitionOutcome.FAILED_CLOSED) engine.accept(event)
        }
        return engine.finish()
    }

    private fun validLedger(ledger: VaultRetryLedger): Boolean {
        val reservationId = ledger.outstandingReservationId
        return ledger.ledgerRevision >= 0 && ledger.reservedAttemptCount >= 0 &&
            ledger.delayTier == retryPolicy.tierFor(ledger.reservedAttemptCount) &&
            if (ledger.reservedAttemptCount == 0L) reservationId == null
            else reservationId != null && reservationId > 0
    }

    private fun validState(s: VaultState): Boolean {
        if (s.revision < 0 || s.processInstance <= 0 || s.lastObservedTick.processInstance != s.processInstance ||
            s.lastObservedTick.elapsedMillis < 0 || s.authEpoch < 0 || s.presentationEpoch < 0 ||
            s.lastIdentifier < 0 || s.policyRevision < 0 || s.highestWindowId < 0 || !validLedger(s.retryLedger)) return false
        fun tick(t: VaultTick?) = t == null || t.processInstance == s.processInstance && t.elapsedMillis >= 0
        if (!tick(s.retryDeadline) || !tick(s.lockDeadline) || !tick(s.lockWindowStartedAt)) return false
        if ((s.lockWindowId == null) != (s.lockDeadline == null) ||
            (s.lockWindowId == null) != (s.lockWindowStartedAt == null)) return false
        val windowId = s.lockWindowId
        if (windowId != null && (windowId <= 0 || windowId > s.highestWindowId ||
                s.lockDeadline!!.elapsedMillis < s.lockWindowStartedAt!!.elapsedMillis)) return false
        if (s.visibility == VaultVisibility.VISIBLE && (s.mode != VaultMode.SESSION_OPEN || !s.foreground || s.inVaultRoute == false ||
                s.cleanupStatus != VaultCleanupStatus.COMPLETE)) return false
        if (s.retryStatus == VaultRetryStatus.WAIT_UNTIL && s.retryDeadline == null) return false
        val cleanup = s.currentCleanup
        if (s.cleanupStatus == VaultCleanupStatus.PENDING && cleanup == null) return false
        if (cleanup != null && (cleanup.cleanupId <= 0 || cleanup.cleanupId > s.lastIdentifier ||
                cleanup.processInstance != s.processInstance ||
                s.cleanupStatus != VaultCleanupStatus.FAILED && (cleanup.authEpoch != s.authEpoch || cleanup.presentationEpoch != s.presentationEpoch) ||
                !tick(cleanup.deadline))) return false
        val knownAbsent = s.inVaultRoute != null && (s.inVaultRoute == false || !s.foreground)
        if (knownAbsent && (s.activeAttempt != null || s.mode == VaultMode.SESSION_OPEN && s.lockWindowId == null)) return false
        val attempt = s.activeAttempt
        if (attempt != null && (attempt.identity.processInstance != s.processInstance ||
                attempt.identity.authEpoch != s.authEpoch || attempt.identity.attemptId <= 0 ||
                attempt.identity.attemptId > s.lastIdentifier || !tick(attempt.deadline))) return false
        val pending = s.pendingRetryWrite
        if (pending != null && (pending.identity.attempt.processInstance != s.processInstance ||
                pending.identity.attempt.authEpoch > s.authEpoch || pending.identity.writeId <= 0 ||
                pending.identity.writeId > s.lastIdentifier || !tick(pending.deadline) ||
                !validLedger(pending.proposedLedger) || !pending.matchesStoredRevision(s.retryLedger.ledgerRevision))) return false
        return when (s.mode) {
            VaultMode.RESERVING_ATTEMPT -> attempt != null && pending?.kind == VaultRetryWriteKind.RESERVATION
            VaultMode.AUTHENTICATING -> attempt != null && pending == null
            VaultMode.ACCEPTED_PENDING_RETRY_RESET -> attempt != null && pending?.kind == VaultRetryWriteKind.RESET
            VaultMode.SESSION_OPEN -> attempt == null && pending == null
            else -> attempt == null
        }
    }

    private inner class Engine(initial: VaultState, private val now: VaultTick) {
        var state = initial
        var outcome = VaultTransitionOutcome.APPLIED
        private val effects = mutableListOf<VaultEffect>()

        private fun deadline(delay: Long): VaultTick? = if (delay < 0 || now.elapsedMillis < 0 ||
            now.elapsedMillis > Long.MAX_VALUE - delay) null else VaultTick(state.processInstance, now.elapsedMillis + delay)
        private fun allocate(): Long? = if (state.lastIdentifier == Long.MAX_VALUE) null else
            (state.lastIdentifier + 1).also { state = state.copy(lastIdentifier = it) }
        private fun due(tick: VaultTick?): Boolean = tick != null && now.elapsedMillis >= tick.elapsedMillis
        private fun matches(identity: VaultAttemptIdentity): Boolean = state.activeAttempt?.identity == identity &&
            identity.authEpoch == state.authEpoch && identity.processInstance == state.processInstance
        private fun ignore() { if (outcome == VaultTransitionOutcome.APPLIED) outcome = VaultTransitionOutcome.IGNORED }
        private fun block() { if (outcome != VaultTransitionOutcome.FAILED_CLOSED) outcome = VaultTransitionOutcome.BLOCKED }

        fun failClosed(reason: VaultReason) {
            val oldEpoch = state.authEpoch
            state = state.copy(
                mode = VaultMode.UNAVAILABLE, reason = reason, visibility = VaultVisibility.COVERED,
                authEpoch = if (state.authEpoch == Long.MAX_VALUE) Long.MAX_VALUE else state.authEpoch + 1,
                presentationEpoch = if (state.presentationEpoch == Long.MAX_VALUE) Long.MAX_VALUE else state.presentationEpoch + 1,
                activeAttempt = null, lockWindowId = null, lockWindowStartedAt = null, lockDeadline = null,
                cleanupStatus = VaultCleanupStatus.FAILED, currentCleanup = null,
                retryStatus = VaultRetryStatus.UNAVAILABLE, retryDeadline = null,
            )
            effects += VaultEffect.MaskPrivatePresentation
            effects += VaultEffect.StopPrivatePlaybackAndReaders
            effects += VaultEffect.RevokeExternalSession(oldEpoch)
            effects += VaultEffect.ClearPrivateMemory
            effects += VaultEffect.ReportTechnicalCategory(reason)
            outcome = VaultTransitionOutcome.FAILED_CLOSED
        }

        fun protect(revokeAuthentication: Boolean, reason: VaultReason, requestedMode: VaultMode? = null) {
            if (state.presentationEpoch == Long.MAX_VALUE || revokeAuthentication && state.authEpoch == Long.MAX_VALUE) {
                failClosed(VaultReason.COUNTER_EXHAUSTED); return
            }
            val id = allocate()
            val expiry = deadline(retryPolicy.cleanupTimeoutMillis)
            if (id == null || expiry == null) { failClosed(VaultReason.COUNTER_EXHAUSTED); return }
            val oldEpoch = state.authEpoch
            val auth = if (revokeAuthentication) oldEpoch + 1 else oldEpoch
            val presentation = state.presentationEpoch + 1
            val binding = VaultCleanupBinding(id, state.processInstance, auth, presentation, expiry)
            val lockedMode = when {
                state.mode == VaultMode.RECOVERY_REQUIRED -> VaultMode.RECOVERY_REQUIRED
                state.retryStatus == VaultRetryStatus.UNAVAILABLE -> VaultMode.UNAVAILABLE
                else -> VaultMode.LOCKED
            }
            state = state.copy(
                mode = requestedMode ?: if (revokeAuthentication) lockedMode else state.mode,
                reason = reason, authEpoch = auth, presentationEpoch = presentation, visibility = VaultVisibility.COVERED,
                activeAttempt = if (revokeAuthentication) null else state.activeAttempt,
                lockWindowId = if (revokeAuthentication) null else state.lockWindowId,
                lockWindowStartedAt = if (revokeAuthentication) null else state.lockWindowStartedAt,
                lockDeadline = if (revokeAuthentication) null else state.lockDeadline,
                cleanupStatus = VaultCleanupStatus.PENDING, currentCleanup = binding,
            )
            effects += VaultEffect.MaskPrivatePresentation
            effects += VaultEffect.StopPrivatePlaybackAndReaders
            if (revokeAuthentication) effects += VaultEffect.RevokeExternalSession(oldEpoch)
            effects += VaultEffect.ClearPrivateMemory
            effects += VaultEffect.RequestOwnedPlaintextCleanup(binding)
        }

        fun cooldown(extraDelay: Long = 0) {
            if (state.pendingRetryWrite != null) return
            if (extraDelay < 0) { failClosed(VaultReason.UNSUPPORTED_STATE); return }
            val delay = maxOf(retryPolicy.delayFor(state.retryLedger.reservedAttemptCount), extraDelay)
            val expiry = deadline(delay)
            if (expiry == null) { failClosed(VaultReason.CLOCK_INVALID); return }
            val retained = state.retryDeadline?.takeIf { it.processInstance == state.processInstance && it.elapsedMillis > expiry.elapsedMillis }
            val strictest = retained ?: expiry
            val waiting = strictest.elapsedMillis > now.elapsedMillis
            state = state.copy(retryStatus = if (waiting) VaultRetryStatus.WAIT_UNTIL else VaultRetryStatus.READY,
                retryDeadline = if (waiting) strictest else null)
        }

        fun expire() {
            if (due(state.lockDeadline)) protect(true, VaultReason.BACKGROUND_TIMEOUT)
            if (outcome == VaultTransitionOutcome.FAILED_CLOSED) return
            if (state.pendingRetryWrite != null && due(state.pendingRetryWrite!!.deadline) && state.retryStatus != VaultRetryStatus.UNAVAILABLE) {
                // Keep pending write and the confirmed ledger until a matching durable acknowledgement.
                protect(true, VaultReason.RETRY_STORE_UNAVAILABLE, VaultMode.UNAVAILABLE)
                state = state.copy(retryStatus = VaultRetryStatus.UNAVAILABLE)
            } else if (state.activeAttempt != null && due(state.activeAttempt!!.deadline)) {
                protect(true, VaultReason.AUTHENTICATION_TIMEOUT)
                cooldown()
            }
            if (state.cleanupStatus == VaultCleanupStatus.PENDING && due(state.currentCleanup?.deadline)) {
                failCleanup()
            }
            if (state.retryStatus == VaultRetryStatus.WAIT_UNTIL && due(state.retryDeadline)) {
                state = state.copy(retryStatus = VaultRetryStatus.READY, retryDeadline = null)
            }
        }

        private fun failCleanup() {
            val binding = state.currentCleanup
            if (state.authEpoch == Long.MAX_VALUE) { failClosed(VaultReason.COUNTER_EXHAUSTED); return }
            effects += VaultEffect.MaskPrivatePresentation
            effects += VaultEffect.StopPrivatePlaybackAndReaders
            effects += VaultEffect.RevokeExternalSession(state.authEpoch)
            effects += VaultEffect.ClearPrivateMemory
            state = state.copy(mode = VaultMode.UNAVAILABLE, reason = VaultReason.CLEANUP_FAILED,
                authEpoch = state.authEpoch + 1, visibility = VaultVisibility.COVERED, activeAttempt = null,
                lockWindowId = null, lockWindowStartedAt = null, lockDeadline = null,
                cleanupStatus = VaultCleanupStatus.FAILED,
                // Terminal failure retains its original identity; no further ack may change this round.
                currentCleanup = binding)
            effects += VaultEffect.ReportTechnicalCategory(VaultReason.CLEANUP_FAILED)
        }

        fun accept(event: VaultEvent) {
            when (event) {
                VaultEvent.ClockObserved -> Unit
                is VaultEvent.LifecycleObserved -> observeLifecycle(event.inVaultRoute, event.foreground)
                VaultEvent.ForegroundEntered -> {
                    val route = state.inVaultRoute
                    if (route == null) state = state.copy(foreground = true, visibility = VaultVisibility.COVERED)
                    else observeLifecycle(route, true)
                }
                VaultEvent.BackgroundEntered -> {
                    val route = state.inVaultRoute
                    if (route != null) observeLifecycle(route, false)
                    else {
                        state = state.copy(foreground = false)
                        protect(state.activeAttempt != null, state.reason)
                        if (state.activeAttempt == null && state.pendingRetryWrite == null && state.mode != VaultMode.SESSION_OPEN) cooldown()
                    }
                }
                VaultEvent.SurfaceCovered -> protect(false, state.reason)
                VaultEvent.SurfaceVisible -> if (state.mode == VaultMode.SESSION_OPEN && state.foreground && state.inVaultRoute != false &&
                    state.cleanupStatus == VaultCleanupStatus.COMPLETE) state = state.copy(visibility = VaultVisibility.VISIBLE) else block()
                VaultEvent.ExplicitLock -> { protect(true, VaultReason.USER_LOCK); cooldown() }
                VaultEvent.DeviceLocked -> { protect(true, VaultReason.DEVICE_LOCK); cooldown() }
                is VaultEvent.PolicyChanged -> changePolicy(event.policy)
                is VaultEvent.AutoLockWindowStarted -> startWindow(event)
                is VaultEvent.AutoLockWindowCancelled -> if (event.windowId == state.lockWindowId &&
                    (state.inVaultRoute == null || state.inVaultRoute == true && state.foreground)) {
                    state = state.copy(lockWindowId = null, lockWindowStartedAt = null, lockDeadline = null)
                } else ignore()
                VaultEvent.UnlockRequested -> requestUnlock()
                is VaultEvent.RetryWriteStored -> stored(event)
                is VaultEvent.RetryWriteFailed -> if (event.identity == state.pendingRetryWrite?.identity) {
                    protect(true, VaultReason.RETRY_STORE_UNAVAILABLE, VaultMode.UNAVAILABLE)
                    state = state.copy(retryStatus = VaultRetryStatus.UNAVAILABLE)
                } else ignore()
                is VaultEvent.AuthenticationResult -> authenticate(event)
                is VaultEvent.AuthenticationTimedOut -> if (matches(event.identity) && due(state.activeAttempt?.deadline)) {
                    protect(true, VaultReason.AUTHENTICATION_TIMEOUT); cooldown()
                } else ignore()
                is VaultEvent.CleanupCompleted -> if (state.cleanupStatus == VaultCleanupStatus.PENDING && event.binding == state.currentCleanup) {
                    state = state.copy(cleanupStatus = VaultCleanupStatus.COMPLETE)
                } else ignore()
                is VaultEvent.CleanupFailed -> if (state.cleanupStatus == VaultCleanupStatus.PENDING && event.binding == state.currentCleanup) failCleanup() else ignore()
                is VaultEvent.KeyStateObserved -> if (event.invalidated) recovery(VaultReason.KEY_INVALIDATED) else ignore()
                VaultEvent.IntegrityFailureObserved -> recovery(VaultReason.INTEGRITY_FAILED)
                is VaultEvent.RecoveryRequired -> if (event.reason in setOf(VaultReason.FORGOTTEN_PASSWORD, VaultReason.BIOMETRIC_CHANGED,
                    VaultReason.APPLICATION_REMOVED, VaultReason.DEVICE_CHANGED, VaultReason.KEY_INVALIDATED)) recovery(event.reason) else block()
                is VaultEvent.RecoveryChoiceRecorded -> state = state.copy(recoveryChoice = event.choice)
                is VaultEvent.RecoveryMaterialStatusObserved -> state = state.copy(recoveryMaterialStatus = event.status)
            }
        }

        private fun requestUnlock() {
            if (state.mode != VaultMode.LOCKED || !state.foreground || state.inVaultRoute == false || state.cleanupStatus != VaultCleanupStatus.COMPLETE ||
                state.retryStatus != VaultRetryStatus.READY || state.pendingRetryWrite != null) { block(); return }
            if (state.retryLedger.reservedAttemptCount == Long.MAX_VALUE || state.retryLedger.ledgerRevision == Long.MAX_VALUE) {
                failClosed(VaultReason.COUNTER_EXHAUSTED); return
            }
            val attemptId = allocate(); val writeId = allocate(); val expiry = deadline(retryPolicy.persistenceTimeoutMillis)
            if (attemptId == null || writeId == null || expiry == null) { failClosed(VaultReason.COUNTER_EXHAUSTED); return }
            val identity = VaultAttemptIdentity(state.processInstance, attemptId, state.authEpoch)
            val count = state.retryLedger.reservedAttemptCount + 1
            val proposed = VaultRetryLedger(state.retryLedger.ledgerRevision + 1, count, retryPolicy.tierFor(count), writeId)
            val write = VaultRetryWrite(VaultRetryWriteKind.RESERVATION, VaultRetryWriteIdentity(identity, writeId),
                state.retryLedger.ledgerRevision, proposed, expiry)
            state = state.copy(mode = VaultMode.RESERVING_ATTEMPT, reason = VaultReason.NONE,
                activeAttempt = VaultAttempt(identity, expiry), pendingRetryWrite = write, retryStatus = VaultRetryStatus.PERSISTENCE_PENDING)
            effects += VaultEffect.PersistRetryWrite(write)
        }

        private fun stored(event: VaultEvent.RetryWriteStored) {
            val write = state.pendingRetryWrite
            if (write == null || write.identity != event.identity || write.expectedLedgerRevision != event.expectedLedgerRevision ||
                write.proposedLedger.ledgerRevision != event.storedLedgerRevision) { ignore(); return }
            val current = matches(write.identity.attempt) && !due(write.deadline) && state.foreground && state.inVaultRoute != false &&
                state.cleanupStatus == VaultCleanupStatus.COMPLETE && state.retryStatus != VaultRetryStatus.UNAVAILABLE
            state = state.copy(retryLedger = write.proposedLedger, pendingRetryWrite = null)
            if (!current) {
                if (state.activeAttempt != null) protect(true, VaultReason.ATTEMPT_CANCELLED)
                cooldown()
                return
            }
            if (write.kind == VaultRetryWriteKind.RESERVATION) {
                val expiry = deadline(retryPolicy.authenticationTimeoutMillis)
                if (expiry == null) { failClosed(VaultReason.CLOCK_INVALID); return }
                val attempt = VaultAttempt(write.identity.attempt, expiry)
                state = state.copy(mode = VaultMode.AUTHENTICATING, activeAttempt = attempt, retryStatus = VaultRetryStatus.READY)
                effects += VaultEffect.BeginExternalAuthentication(attempt)
            } else {
                state = state.copy(mode = VaultMode.SESSION_OPEN, activeAttempt = null, retryStatus = VaultRetryStatus.READY,
                    retryDeadline = null, visibility = VaultVisibility.COVERED)
            }
        }

        private fun authenticate(event: VaultEvent.AuthenticationResult) {
            if (state.mode != VaultMode.AUTHENTICATING || !matches(event.identity) || !state.foreground || state.inVaultRoute == false) {
                effects += VaultEffect.DiscardAttemptResult(event.identity); ignore(); return
            }
            when (event.outcome) {
                VaultAuthenticationOutcome.SESSION_ESTABLISHED -> {
                    if (state.retryLedger.ledgerRevision == Long.MAX_VALUE) { failClosed(VaultReason.COUNTER_EXHAUSTED); return }
                    val id = allocate(); val expiry = deadline(retryPolicy.persistenceTimeoutMillis)
                    if (id == null || expiry == null) { failClosed(VaultReason.COUNTER_EXHAUSTED); return }
                    val proposed = VaultRetryLedger(state.retryLedger.ledgerRevision + 1, 0, -1, null)
                    val write = VaultRetryWrite(VaultRetryWriteKind.RESET, VaultRetryWriteIdentity(event.identity, id),
                        state.retryLedger.ledgerRevision, proposed, expiry)
                    state = state.copy(mode = VaultMode.ACCEPTED_PENDING_RETRY_RESET, pendingRetryWrite = write,
                        activeAttempt = state.activeAttempt?.copy(deadline = expiry), retryStatus = VaultRetryStatus.PERSISTENCE_PENDING)
                    effects += VaultEffect.PersistRetryWrite(write)
                }
                VaultAuthenticationOutcome.KEY_INVALIDATED -> recovery(VaultReason.KEY_INVALIDATED)
                else -> {
                    val reason = when (event.outcome) {
                        VaultAuthenticationOutcome.REJECTED -> VaultReason.REJECTED_CREDENTIAL
                        VaultAuthenticationOutcome.CANCELLED -> VaultReason.ATTEMPT_CANCELLED
                        else -> VaultReason.PLATFORM_THROTTLED
                    }
                    protect(true, reason)
                    cooldown(if (event.outcome == VaultAuthenticationOutcome.TEMPORARILY_UNAVAILABLE) event.platformRetryAfterMillis else 0)
                }
            }
        }

        private fun observeLifecycle(inVaultRoute: Boolean, foreground: Boolean) {
            val wasPresent = state.inVaultRoute?.let { it && state.foreground }
            val present = inVaultRoute && foreground
            state = state.copy(inVaultRoute = inVaultRoute, foreground = foreground)
            if (present) {
                // Returning does not show private content or undo a pending cleanup round.
                if (wasPresent != true) state = state.copy(visibility = VaultVisibility.COVERED)
                state.lockWindowId?.let { accept(VaultEvent.AutoLockWindowCancelled(it)) }
            } else if (wasPresent != false) {
                val wasOpen = state.mode == VaultMode.SESSION_OPEN
                if (wasOpen && state.lockWindowId == null) {
                    if (state.highestWindowId == Long.MAX_VALUE) {
                        failClosed(VaultReason.COUNTER_EXHAUSTED); return
                    }
                    startWindow(VaultEvent.AutoLockWindowStarted(state.highestWindowId + 1, state.policyRevision))
                    if (outcome == VaultTransitionOutcome.FAILED_CLOSED) return
                    // Immediate policy has already revoked and requested the exact lock cleanup.
                    if (state.mode != VaultMode.SESSION_OPEN) return
                }
                protect(state.activeAttempt != null, state.reason)
                if (outcome != VaultTransitionOutcome.FAILED_CLOSED && state.activeAttempt == null &&
                    state.pendingRetryWrite == null && state.mode != VaultMode.SESSION_OPEN) cooldown()
            }
            // Already absent: neither duplicate nor the second absent condition renews any deadline.
        }

        private fun startWindow(event: VaultEvent.AutoLockWindowStarted) {
            if (event.policyRevision != state.policyRevision || event.windowId <= state.highestWindowId || state.mode != VaultMode.SESSION_OPEN ||
                state.inVaultRoute != null && (state.inVaultRoute == true && state.foreground || state.lockWindowId != null)) {
                ignore(); return
            }
            val expiry = deadline(state.autoLockPolicy.durationMillis)
            if (expiry == null) { failClosed(VaultReason.CLOCK_INVALID); return }
            state = state.copy(highestWindowId = event.windowId, lockWindowId = event.windowId, lockWindowStartedAt = now, lockDeadline = expiry)
            if (due(expiry)) protect(true, VaultReason.BACKGROUND_TIMEOUT)
        }

        private fun changePolicy(policy: VaultAutoLockPolicy) {
            if (state.policyRevision == Long.MAX_VALUE) { failClosed(VaultReason.COUNTER_EXHAUSTED); return }
            state = state.copy(autoLockPolicy = policy, policyRevision = state.policyRevision + 1)
            val start = state.lockWindowStartedAt ?: return
            if (start.elapsedMillis > Long.MAX_VALUE - policy.durationMillis) { failClosed(VaultReason.CLOCK_INVALID); return }
            state = state.copy(lockDeadline = VaultTick(state.processInstance, start.elapsedMillis + policy.durationMillis))
            if (due(state.lockDeadline)) protect(true, VaultReason.BACKGROUND_TIMEOUT)
        }

        private fun recovery(reason: VaultReason) {
            protect(true, reason, VaultMode.RECOVERY_REQUIRED)
            val routes = mutableListOf(VaultRecoveryRoute.TRY_EXISTING_PASSWORD)
            if (state.recoveryChoice == VaultRecoveryChoice.OPTED_IN && state.recoveryMaterialStatus == VaultRecoveryMaterialStatus.CONFIRMED_AVAILABLE)
                routes += VaultRecoveryRoute.USER_OFFLINE_RECOVERY
            routes += VaultRecoveryRoute.REVIEW_UNRECOVERABLE_STATE
            effects += VaultEffect.RequireUserRecovery(reason, routes)
        }

        fun finish(): VaultTransition {
            effects += VaultEffect.CancelWake
            val deadlines = listOfNotNull(state.lockDeadline, state.activeAttempt?.deadline,
                state.pendingRetryWrite?.deadline?.takeIf { state.retryStatus != VaultRetryStatus.UNAVAILABLE },
                state.currentCleanup?.deadline?.takeIf { state.cleanupStatus == VaultCleanupStatus.PENDING },
                state.retryDeadline?.takeIf { state.retryStatus == VaultRetryStatus.WAIT_UNTIL })
            deadlines.minByOrNull { it.elapsedMillis }?.let { effects += VaultEffect.ScheduleWake(it) }
            return VaultTransition(state, effects, outcome)
        }
    }
}
