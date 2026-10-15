package com.easysavevideo.app.media.transform

/** The only production entry always uses the existing process-wide owner; no caller-supplied lane. */
internal fun <T> runTransformerSession(session: TransformerSession, work: (TransformerSession) -> T): TransformerOutcome<T> =
    executeTransformerSession(SharedTransformerRuntime.laneOwner, session) { work(session) }
