package com.easysavevideo.app.vault.playback

import com.easysavevideo.app.vault.VaultPlaybackDrain
import java.util.IdentityHashMap

/** Exact-resource facts only. No Player callback, timer, shutdown request or finally is a receipt. */
internal class VaultPlaybackRetirement {
    internal class Acquisition internal constructor()
    internal class RendererFact internal constructor() {
        var enabled = false
        var disabled = false
        var reset = false
        var released = false
    }
    private class AudioFact(val track: Any, val initialized: Boolean) { var terminal = false }
    private val acquiring = IdentityHashMap<Acquisition, Unit>()
    private val renderers = IdentityHashMap<Any, RendererFact>()
    private val audio = IdentityHashMap<Any, AudioFact>()
    private val loaders = IdentityHashMap<Any, Boolean>()
    private val owned = IdentityHashMap<Any, Boolean>()
    private var retiring = false
    private var failure = false
    private var playbackFailure = false
    private var deadlineObserved = false
    private var playerReturned = false
    private var controlBarrier = false
    private var detached = false
    private var detachFailed = false
    private var readOwnerReturned = false
    private var applicationJoined = false
    private var playbackJoined = false
    private var releaseTimedOut = false

    @Synchronized fun acquire(): Acquisition {
        check(acquiring.size < 32) { "vault_playback_acquisition_limit" }
        return Acquisition().also { acquiring[it] = Unit }
    }
    /** Called only after the exact returned resource has been retained by the original owner. */
    @Synchronized fun adopted(acquisition: Acquisition) { check(acquiring.remove(acquisition) != null) }
    @Synchronized fun failed() { failure = true }
    @Synchronized fun playbackError() { playbackFailure = true; retiring = true }
    @Synchronized fun hadPlaybackError(): Boolean = playbackFailure
    @Synchronized fun revoke() { retiring = true }
    @Synchronized fun deadline() { deadlineObserved = true }
    @Synchronized fun releaseTimeout() { releaseTimedOut = true }
    @Synchronized fun detachFailure() { detachFailed = true; failure = true }
    @Synchronized fun detachReturned() { if (!detachFailed) detached = true }
    @Synchronized fun playerReleaseReturned() { playerReturned = true }
    @Synchronized fun playbackBarrierReturned() { controlBarrier = true }
    @Synchronized fun readOwnerRetired() { readOwnerReturned = true }
    @Synchronized fun mayJoinLoopers(): Boolean = resourcesReturned() && readOwnerReturned
    @Synchronized fun controlBarrierConfirmed(): Boolean = controlBarrier
    @Synchronized fun loopersJoined() { applicationJoined = true; playbackJoined = true }
    @Synchronized fun addRenderer(identity: Any): RendererFact {
        check(renderers.size < 8 && !renderers.containsKey(identity)) { "vault_playback_renderer_limit" }
        return RendererFact().also { renderers[identity] = it }
    }
    @Synchronized fun rendererEnabled(identity: Any) {
        renderers.getValue(identity).apply { enabled = true; disabled = false; reset = false }
    }
    @Synchronized fun rendererDisabled(identity: Any) { renderers.getValue(identity).disabled = true }
    @Synchronized fun rendererReset(identity: Any) { renderers.getValue(identity).reset = true }
    @Synchronized fun rendererReleased(identity: Any) { renderers.getValue(identity).released = true }
    @Synchronized fun addOwned(identity: Any) { check(owned.size < 8); owned[identity] = false }
    @Synchronized fun ownedReleased(identity: Any) { check(owned.containsKey(identity)); owned[identity] = true }
    @Synchronized fun canAcquireAudio(): Boolean = !retiring && audio.size < 64
    @Synchronized fun addAudio(output: Any, track: Any, initialized: Boolean) {
        check(audio.size < 64 && !audio.containsKey(output)) { "vault_playback_audio_limit" }
        audio[output] = AudioFact(track, initialized)
        if (!initialized || audio.entries.any { it.key !== output && it.value.track === track }) failure = true
    }
    /** Must be called after the real output's Handler delivery, with that output's same track. */
    @Synchronized fun audioReleased(output: Any, track: Any, uninitialized: Boolean) {
        val fact = audio[output]
        if (fact == null || fact.track !== track || !fact.initialized || !uninitialized) failure = true
        else fact.terminal = true
    }
    @Synchronized fun canAcquireLoader(): Boolean = !retiring && loaders.size < 4
    @Synchronized fun addLoader(executor: Any) {
        check(loaders.size < 4 && !loaders.containsKey(executor)) { "vault_playback_loader_limit" }
        loaders[executor] = false
    }
    @Synchronized fun loaderTerminated(executor: Any, normally: Boolean) {
        check(loaders.containsKey(executor))
        loaders[executor] = normally
        if (!normally) failure = true
    }
    /** The timeout is not sticky; actual cleanup failures and unresolved acquisition are. */
    @Synchronized fun resourcesReturned(): Boolean = retiring && !failure && acquiring.isEmpty() &&
        playerReturned && controlBarrier && detached && !detachFailed &&
        renderers.values.all { it.released && (!it.enabled || it.disabled && it.reset) } &&
        audio.values.all { it.terminal } && loaders.values.all { it } && owned.values.all { it }
    @Synchronized fun observe(): VaultPlaybackDrain = when {
        !retiring -> if (failure) VaultPlaybackDrain.UNCONFIRMED else VaultPlaybackDrain.LIVE
        resourcesReturned() && readOwnerReturned && applicationJoined && playbackJoined -> VaultPlaybackDrain.RELEASED
        failure || deadlineObserved || releaseTimedOut -> VaultPlaybackDrain.UNCONFIRMED
        else -> VaultPlaybackDrain.RETIRING
    }
    @Synchronized fun detachConfirmed(): Boolean = detached && !detachFailed
    @Synchronized fun pendingAcquisitions(): Int = acquiring.size
}
