package com.easysavevideo.app.editor.engine

/** Legacy test/adapter name delegates to the single shared cleanup implementation. */
internal object EditorCleanupWait {
    fun awaitCompletion(result: java.util.concurrent.CompletableFuture<*>, timeoutMillis: Long, previouslyInterrupted: Boolean = false): Boolean =
        com.easysavevideo.app.media.transform.TransformerCleanupWait.awaitCompletion(result, timeoutMillis, previouslyInterrupted)
}
