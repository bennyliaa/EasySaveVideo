package com.easysavevideo.app.save.storage

import com.easysavevideo.core.model.OperationContext
import com.easysavevideo.save.ArtifactOutputDescriptor
import com.easysavevideo.save.DirectLocalProcessingGate
import com.easysavevideo.save.SaveError
import com.easysavevideo.save.SaveFailure
import com.easysavevideo.save.StagingArtifact
import com.easysavevideo.app.save.AndroidDirectExecutionBridge
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Identity-scoped state engine with separately configured actual execution issuance. A local permit proves ONLY a live lexical invocation of
 * expectedGate. It does not prove an entered Save Claim, accepted Runtime, drained legacy handles,
 * or exact ordinary-native batch ownership. A production registry requires its configured actual execution bridge.
 *
 * The optional internal seam is implemented only by synthetic test authorities in test sources.
 * The default constructor cannot register a binding. No Boolean or caller-created token grants
 * production execution authority. Production registration never accepts the synthetic test seam.
 */
internal class AdoptedVerificationBindings(
    private val expectedGate: DirectLocalProcessingGate,
    private val syntheticAuthority: SyntheticAuthority? = null,
    private val executionBridge: AndroidDirectExecutionBridge? = null,
) {
    internal data class SyntheticRequest(
        val artifact: StagingArtifact,
        val output: ArtifactOutputDescriptor,
        val context: OperationContext,
        val expectedRecordRevision: Long,
    )
    internal fun interface SyntheticAuthority {
        /** Test-only exact execution/batch fixture check; called outside every registry/storage lock. */
        fun assertAuthorized(request: SyntheticRequest)
    }
    internal class Binding internal constructor(
        private val registry: AdoptedVerificationBindings,
        internal val request: SyntheticRequest,
        internal val permit: DirectLocalProcessingGate.LocalPermit,
        private val authorityCheck: () -> Unit,
        private val releaseWitness: () -> Boolean,
    ) {
        private val revoked = AtomicBoolean()
        val isRevoked: Boolean get() = revoked.get()
        fun revoke() { revoked.set(true) }
        internal fun requireLive() {
            registry.expectedGate.assertIssuedHere(permit, request.context)
            authorityCheck()
            if (isRevoked) reject()
        }
        // This terminal fact survives registry removal; it grants no live IO or execution authority.
        internal fun requireConsumedAndReleased() { if (!releaseWitness()) reject() }
        internal fun complete() = registry.complete(this)
        internal fun completeReleasedBy(owner: AdoptedCanonicalOutputOwner) = registry.completeReleased(this, owner)
    }
    private val lock = Any()
    private class State(val released: AtomicBoolean, var consumed: Boolean = false, var consumingOwner: AdoptedCanonicalOutputOwner? = null)
    private val registrations = IdentityHashMap<Binding, State>()

    internal fun <T> withExecutionBinding(scope: AndroidDirectExecutionBridge.Scope,
        artifact: StagingArtifact, output: ArtifactOutputDescriptor.NormalizedDirect,
        context: OperationContext, expectedRecordRevision: Long, block: (Binding) -> T): T =
        withOwnedExecutionBinding(scope, artifact, output, context, expectedRecordRevision, block)

    internal fun <T> withExecutionBinding(scope: AndroidDirectExecutionBridge.Scope,
        artifact: StagingArtifact, output: ArtifactOutputDescriptor.ConvertedDirect,
        context: OperationContext, expectedRecordRevision: Long, block: (Binding) -> T): T =
        withOwnedExecutionBinding(scope, artifact, output, context, expectedRecordRevision, block)

    private fun <T> withOwnedExecutionBinding(scope: AndroidDirectExecutionBridge.Scope,
        artifact: StagingArtifact, output: ArtifactOutputDescriptor,
        context: OperationContext, expectedRecordRevision: Long, block: (Binding) -> T): T {
        val bridge = executionBridge ?: reject()
        if (scope.context !== context) reject()
        scope.requireIssuer(bridge)
        return register(scope.permit, SyntheticRequest(artifact, output, context, expectedRecordRevision),
            { scope.requireIssuer(bridge); scope.requireLive(context) }, block,
            liveAuthority = { scope.requireIssuer(bridge); scope.requireLive(context) })
    }


    internal fun <T> withSyntheticBinding(
        localPermit: DirectLocalProcessingGate.LocalPermit,
        artifact: StagingArtifact,
        output: ArtifactOutputDescriptor,
        context: OperationContext,
        expectedRecordRevision: Long,
        block: (Binding) -> T,
    ): T {
        val authority = syntheticAuthority ?: reject()
        if (executionBridge != null) reject() // A production registry never accepts the fixture seam.
        val request = SyntheticRequest(artifact, output, context, expectedRecordRevision)
        return register(localPermit, request, { authority.assertAuthorized(request) }, block)
    }

    private fun <T> register(localPermit: DirectLocalProcessingGate.LocalPermit, request: SyntheticRequest,
        authority: () -> Unit, block: (Binding) -> T, liveAuthority: () -> Unit = {}): T {
        if (request.expectedRecordRevision < 0 || request.output is ArtifactOutputDescriptor.Transferred) reject()
        expectedGate.assertIssuedHere(localPermit, request.context)
        authority()
        val released = AtomicBoolean()
        val binding = Binding(this, request, localPermit, liveAuthority, released::get)
        synchronized(lock) {
            binding.requireLive()
            if (registrations.keys.any { it.request.artifact.artifactId == request.artifact.artifactId }) reject()
            registrations[binding] = State(released)
        }
        try {
            authority(); binding.requireLive()
            return block(binding)
        } finally {
            binding.revoke()
            synchronized(lock) { if (registrations[binding]?.consumed == false) registrations.remove(binding) }
        }
    }

    internal fun consume(artifact: StagingArtifact, output: ArtifactOutputDescriptor,
        context: OperationContext): Binding = consume(artifact, output, context, null)

    internal fun consumeForOwner(artifact: StagingArtifact, output: ArtifactOutputDescriptor,
        context: OperationContext, owner: AdoptedCanonicalOutputOwner): Binding = consume(artifact, output, context, owner)

    private fun consume(artifact: StagingArtifact, output: ArtifactOutputDescriptor,
        context: OperationContext, owner: AdoptedCanonicalOutputOwner?): Binding = synchronized(lock) {
        val matching = registrations.keys.filter { it.request.artifact === artifact &&
            it.request.output === output && it.request.context === context }
        if (matching.size != 1) reject()
        matching.single().also {
            it.requireLive()
            val state = registrations[it] ?: reject()
            if (state.consumed) reject()
            state.consumed = true
            state.consumingOwner = owner
        }
    }

    private fun completeReleased(binding: Binding, owner: AdoptedCanonicalOutputOwner) {
        // Owner supplies only its private, already-confirmed close fact. No callbacks or IO under
        // the registry monitor. Foreign, late and general complete() calls cannot create a witness.
        if (!owner.confirmsReleasedBinding(binding)) reject()
        synchronized(lock) {
            val state = registrations[binding] ?: reject()
            if (!state.consumed || state.consumingOwner !== owner) reject()
            state.released.set(true)
            binding.revoke()
            registrations.remove(binding)
        }
    }

    private fun complete(binding: Binding) = synchronized(lock) {
        // A stale worker may only retire its own identity-registered entry.
        if (registrations.containsKey(binding)) {
            binding.revoke(); registrations.remove(binding)
        }
    }
    private companion object {
        fun reject(): Nothing = throw SaveFailure(SaveError.RECOVERY_REQUIRED)
    }
}
