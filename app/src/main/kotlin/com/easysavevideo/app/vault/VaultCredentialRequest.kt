package com.easysavevideo.app.vault


internal enum class VaultCredentialMode { SETUP, UNLOCK, RECOVERY_ENROLL, RECOVERY_DISPLAY, RECOVERY_CONFIRM, RECOVERY_RESET }

/** Exact request lifetime, not authentication authority. Only its host accepts submission. */
internal class VaultCredentialRequest internal constructor(val mode: VaultCredentialMode,
    val workflow: VaultRecoveryWorkflow? = null, display: VaultRecoveryInput? = null) {
    private var displayInput: VaultRecoveryInput? = display
    internal fun copyDisplayCharacters(): CharArray? { requireMain(); return if (live) displayInput?.copyCharacters() else null }
    internal fun clearDisplayInput() { requireMain(); displayInput?.close(); displayInput = null }
    // Production host constructs on Main; callbacks remain on this exact owner thread.
    private val ownerThread = Thread.currentThread()
    private fun requireMain() = check(Thread.currentThread() === ownerThread)
    private val cleanups = mutableListOf<Cleanup>()
    private var live = true
    internal var cleanupFailed = false; private set
    private inner class Cleanup(private val clear: () -> Unit) : AutoCloseable {
        var done = false
        override fun close() {
            requireMain()
            if (done) return
            try { clear(); done = true; cleanups.remove(this) }
            catch (_: Exception) { cleanupFailed = true }
        }
    }
    fun onRetired(clear: () -> Unit): AutoCloseable {
        requireMain()
        return Cleanup(clear).also {
        if (live) cleanups += it else it.close()
        }
    }
    internal fun retire() {
        requireMain()
        live = false
        displayInput?.close(); displayInput = null
        cleanups.toList().forEach { it.close() }
    }
    internal fun isLive() = live
    internal fun retiredCleanly() = !live && !cleanupFailed && cleanups.isEmpty()
    override fun toString() = "VaultCredentialRequest(REDACTED)"
}
