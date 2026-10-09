package com.easysavevideo.app.save

/**
 * Cancellation and native start share one gate. A cancelled Future is not release evidence. The
 * abandonment callback remains outstanding until the native body and all of its finally blocks
 * actually return, including a blocking stop/release. A queued task can instead be revoked safely.
 */
internal class NativeVerificationLease(
    private val abandonedNative: () -> Unit,
    private val releasedNative: () -> Unit,
) {
    private enum class Phase { QUEUED, RUNNING, RELEASED, REVOKED }
    private var phase = Phase.QUEUED
    private var abandoned = false

    @Synchronized fun start(): Boolean {
        if (phase != Phase.QUEUED) return false
        phase = Phase.RUNNING
        return true
    }

    @Synchronized fun abandon() {
        when (phase) {
            Phase.QUEUED -> phase = Phase.REVOKED
            Phase.RUNNING -> if (!abandoned) { abandoned = true; abandonedNative() }
            Phase.RELEASED, Phase.REVOKED -> Unit
        }
    }

    @Synchronized fun released() {
        check(phase == Phase.RUNNING)
        phase = Phase.RELEASED
        if (abandoned) { abandoned = false; releasedNative() }
    }
}
