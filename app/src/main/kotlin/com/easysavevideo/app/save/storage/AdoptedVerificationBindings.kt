package com.easysavevideo.app.save.storage

import com.easysavevideo.core.model.OperationContext
import com.easysavevideo.save.ArtifactOutputDescriptor
import com.easysavevideo.save.DirectLocalProcessingGate
import com.easysavevideo.save.SaveError
import com.easysavevideo.save.SaveFailure
import com.easysavevideo.save.StagingArtifact
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Inactive identity-scoped state engine. A local permit proves ONLY a live lexical invocation of
 * expectedGate. It does not prove an entered Save Claim, accepted Runtime, drained legacy handles,
 * or exact ordinary-native batch ownership. There is deliberately no production issuance bridge.
 *
 * The optional internal seam is implemented only by synthetic test authorities in test sources.
 * The default constructor cannot register a binding. No Boolean or caller-created token grants
 * production execution authority. A future real bridge needs separate review and lifecycle hooks.
 */
internal class AdoptedVerificationBindings(
    private val expectedGate: DirectLocalProcessingGate,
    private val syntheticAuthority: SyntheticAuthority? = null,
) {
    internal data class SyntheticRequest(
        val artifact: StagingArtifact,
        val output: ArtifactOutputDescriptor.NormalizedDirect,
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
    ) {
        private val revoked = AtomicBoolean()
        // Only registry transitions these fields under its short monitor.
        internal var consumed = false
        internal var complete = false
        val isRevoked: Boolean get() = revoked.get()
        fun revoke() { revoked.set(true) }
        internal fun requireLive() {
            registry.expectedGate.assertIssuedHere(permit, request.context)
            if (isRevoked) reject()
        }
        internal fun complete() = registry.complete(this)
    }
    private val lock = Any()
    private val registrations = Collections.newSetFromMap(IdentityHashMap<Binding, Boolean>())

    internal fun <T> withSyntheticBinding(
        localPermit: DirectLocalProcessingGate.LocalPermit,
        artifact: StagingArtifact,
        output: ArtifactOutputDescriptor.NormalizedDirect,
        context: OperationContext,
        expectedRecordRevision: Long,
        block: (Binding) -> T,
    ): T {
        val authority = syntheticAuthority ?: reject()
        val request = SyntheticRequest(artifact, output, context, expectedRecordRevision)
        if (expectedRecordRevision < 0) reject()
        expectedGate.assertIssuedHere(localPermit, context)
        authority.assertAuthorized(request)
        val binding = Binding(this, request, localPermit)
        synchronized(lock) {
            binding.requireLive()
            // Never let an ambiguous duplicate registration choose which worker receives authority.
            if (registrations.any { !it.complete && it.request.artifact.artifactId == artifact.artifactId }) reject()
            registrations.add(binding)
        }
        try {
            // A fixture authority may change during registration. No callback runs under lock.
            authority.assertAuthorized(request)
            binding.requireLive()
            return block(binding)
        } finally {
            binding.revoke()
            synchronized(lock) { if (!binding.consumed) { binding.complete = true; registrations.remove(binding) } }
        }
    }

    internal fun consume(artifact: StagingArtifact, output: ArtifactOutputDescriptor.NormalizedDirect,
        context: OperationContext): Binding = synchronized(lock) {
        val matching = registrations.filter { it.request.artifact === artifact &&
            it.request.output === output && it.request.context === context && !it.complete }
        if (matching.size != 1) reject()
        matching.single().also {
            it.requireLive()
            if (it.consumed) reject()
            it.consumed = true
        }
    }

    private fun complete(binding: Binding) = synchronized(lock) {
        // A stale worker may only retire its own identity-registered entry.
        if (registrations.contains(binding)) {
            binding.revoke(); binding.complete = true; registrations.remove(binding)
        }
    }
    private companion object {
        fun reject(): Nothing = throw SaveFailure(SaveError.RECOVERY_REQUIRED)
    }
}
