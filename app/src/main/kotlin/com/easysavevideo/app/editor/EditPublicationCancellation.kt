package com.easysavevideo.app.editor

import com.easysavevideo.core.model.CancellationToken
import com.easysavevideo.library.edit.EditError

internal enum class EditCancelResult { ACCEPTED, ALREADY_CANCELLED, TOO_LATE }
internal enum class EditPublicationAdmission { ADMITTED, CANCELLED, DEADLINE_EXCEEDED, ALREADY_ADMITTED }

/**
 * One short in-memory linearization point shared by Cancel/deadline and catalog publication.
 * Never hold this monitor during disk, SQLite or native work. Admission is one-way: afterwards
 * cancellation must say TOO_LATE and the actual catalog decides committed versus uncertain.
 */
internal class EditPublicationCancellation(private val nowMillis: () -> Long = System::currentTimeMillis) : CancellationToken {
    private enum class State { ACTIVE, CANCELLED, ADMITTED, COMMITTED }
    private var state = State.ACTIVE
    @get:Synchronized override val isCancelled: Boolean get() = state == State.CANCELLED
    @get:Synchronized val publicationAdmitted: Boolean get() = state in setOf(State.ADMITTED, State.COMMITTED)
    @get:Synchronized val publicationCommitted: Boolean get() = state == State.COMMITTED
    @Synchronized fun cancel(): EditCancelResult = when (state) {
        State.ACTIVE -> { state = State.CANCELLED; EditCancelResult.ACCEPTED }
        State.CANCELLED -> EditCancelResult.ALREADY_CANCELLED
        State.ADMITTED, State.COMMITTED -> EditCancelResult.TOO_LATE
    }
    /** Invoke only after verification, all long IO, source checks and output promotion. */
    @Synchronized fun admitPublication(deadlineEpochMillis: Long): EditPublicationAdmission {
        if (state == State.CANCELLED) return EditPublicationAdmission.CANCELLED
        if (state != State.ACTIVE) return EditPublicationAdmission.ALREADY_ADMITTED
        if (nowMillis() >= deadlineEpochMillis) {
            state = State.CANCELLED
            return EditPublicationAdmission.DEADLINE_EXCEEDED
        }
        state = State.ADMITTED
        return EditPublicationAdmission.ADMITTED
    }
    @Synchronized fun committed() { check(state == State.ADMITTED); state = State.COMMITTED }
}

/** Definite rejection before the final visibility transaction, so owned output cleanup is safe. */
internal class EditPublicationAdmissionFailure(val error: EditError) : RuntimeException(error.name)
