package com.easysavevideo.app.save

import java.util.concurrent.ConcurrentHashMap

/** Completed work retains its exact claim until existing transport/native release evidence is clear. */
internal class SaveExecutionRetirement<C : Any>(
    private val owns: (C) -> Boolean,
    private val releaseUnconfirmed: () -> Boolean,
    private val release: (C) -> Boolean,
    private val retired: (C) -> Unit,
) {
    private val completed = ConcurrentHashMap.newKeySet<C>()
    fun request(claim: C) { completed.add(claim); retry() }
    fun retry() {
        completed.forEach { claim ->
            if (!owns(claim)) completed.remove(claim)
            else if (!releaseUnconfirmed() && release(claim)) {
                completed.remove(claim)
                retired(claim)
            }
        }
    }
}
