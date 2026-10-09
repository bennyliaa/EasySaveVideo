package com.easysavevideo.app.editor.verification

/** A Future becoming cancelled/done is not evidence that a codec or extractor released its FD. */
internal class EditVerificationNativeGate {
    private enum class Phase { FREE, QUEUED, RUNNING, DRAINING, UNCERTAIN }
    private var phase = Phase.FREE
    private var retainedOwner: Any? = null
    @get:Synchronized val occupied: Boolean get() = phase != Phase.FREE
    @get:Synchronized val recoveryRequired: Boolean get() = phase == Phase.DRAINING || phase == Phase.UNCERTAIN

    @Synchronized fun acquire(owner: Any): Boolean {
        if (phase != Phase.FREE) return false
        retainedOwner = owner; phase = Phase.QUEUED
        return true
    }

    /** A queued worker must acknowledge abandonment itself; no racing UI-side release. */
    @Synchronized fun start(owner: Any): Boolean {
        check(retainedOwner === owner)
        return when (phase) {
            Phase.QUEUED -> { phase = Phase.RUNNING; true }
            Phase.DRAINING -> false
            else -> error("Invalid edit verifier phase")
        }
    }

    @Synchronized fun abandon(owner: Any) {
        if (retainedOwner !== owner) return
        if (phase == Phase.QUEUED || phase == Phase.RUNNING) phase = Phase.DRAINING
    }

    @Synchronized fun rejectedBeforeScheduling(owner: Any) {
        check(retainedOwner === owner && phase == Phase.QUEUED)
        retainedOwner = null; phase = Phase.FREE
    }

    /** Called only by the actual worker after its entire cleanup body returns. */
    @Synchronized fun finish(owner: Any, confirmed: Boolean) {
        check(retainedOwner === owner && phase != Phase.FREE && phase != Phase.UNCERTAIN)
        if (confirmed) { retainedOwner = null; phase = Phase.FREE }
        else phase = Phase.UNCERTAIN
    }
}
