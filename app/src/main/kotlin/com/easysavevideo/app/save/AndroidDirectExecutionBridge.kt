package com.easysavevideo.app.save

import com.easysavevideo.core.model.OperationContext
import com.easysavevideo.library.thumbnail.ThumbnailNativeResourceGate
import com.easysavevideo.save.*
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Joins projections of the actual core execution, actual native batch and exact local invocation. */
internal class AndroidDirectExecutionBridge(
    private val admission: SaveExecutionAdmission,
    private val gate: DirectLocalProcessingGate,
    private val currentBatch: () -> ThumbnailNativeResourceGate.Ownership?,
) {
    private val lock = Any()
    private val issued = WeakHashMap<Scope, Boolean>()
    internal class Scope internal constructor(
        private val issuer: AndroidDirectExecutionBridge,
        internal val execution: AcceptedDirectAttempt,
        internal val permit: DirectLocalProcessingGate.LocalPermit,
        internal val context: OperationContext,
        internal val batch: ThumbnailNativeResourceGate.Ownership,
    ) : AutoCloseable {
        private val closed = AtomicBoolean()
        fun requireLive(context: OperationContext = this.context) {
            if (closed.get()) reject()
            issuer.requireIssued(this, context)
        }
        internal fun requireIssuer(expected: AndroidDirectExecutionBridge) {
            if (issuer !== expected) reject()
            requireLive()
        }
        override fun close() { if (closed.compareAndSet(false, true)) issuer.retire(this) }
    }
    fun capture(execution: AcceptedDirectAttempt, permit: DirectLocalProcessingGate.LocalPermit,
        context: OperationContext): Scope {
        execution.assertAdmission(admission); execution.requireLive(context); gate.assertIssuedHere(permit, context)
        val batch = currentBatch()?.takeIf { it.isLive } ?: reject()
        val scope = Scope(this, execution, permit, context, batch)
        synchronized(lock) { issued[scope] = true }
        try { scope.requireLive(); return scope } catch (failure: Throwable) { scope.close(); throw failure }
    }
    private fun requireIssued(scope: Scope, context: OperationContext) {
        if (synchronized(lock) { issued[scope] != true }) reject()
        scope.execution.assertAdmission(admission); scope.execution.requireLive(context)
        gate.assertIssuedHere(scope.permit, context)
        if (!scope.batch.isLive || currentBatch() !== scope.batch) reject()
    }
    private fun retire(scope: Scope) { synchronized(lock) { issued.remove(scope) } }
    /** Historical completion only. This cannot grant a live scope or acquire any resource. */
    internal fun completedTransfer(execution: AcceptedDirectAttempt): SaveRecord? = execution.recordedTransferFrom(admission)
    internal fun assertExecution(execution: AcceptedDirectAttempt) = execution.assertAdmission(admission)
    private companion object { fun reject(): Nothing = throw SaveFailure(SaveError.RECOVERY_REQUIRED) }
}
