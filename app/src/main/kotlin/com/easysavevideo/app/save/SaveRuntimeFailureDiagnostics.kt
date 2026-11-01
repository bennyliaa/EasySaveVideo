package com.easysavevideo.app.save

import java.io.IOException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger

/** Error-only, process-owner budget. Never reads Throwable message/cause/stack/toString. */
internal class SaveRuntimeFailureDiagnostics {
    enum class Stage {
        STORAGE, STORAGE_DIRECTORY, STORAGE_OWNER_FENCE, STORAGE_LEASE, STORAGE_HELPER,
        STORAGE_PROTOCOL, STORAGE_LIBRARY, STORAGE_PENDING_DELETES, STORAGE_SNAPSHOT, STORAGE_DATABASE,
        DIRECT_TRANSPORT, HLS_TRANSPORT, RECORD_PORT, DIRECT_COMPONENTS,
        COORDINATOR, RECOVERY, TERMINAL_CLEANUP, SNAPSHOT, DESTINATIONS, EDITOR, INSTALL, READY
    }
    enum class Category { DATABASE, SAVE_FAILURE, SECURITY, IO, ARGUMENT, STATE, REJECTED, OTHER }
    @Volatile var stage = Stage.STORAGE
    private val emitted = AtomicInteger()

    fun failure(error: Exception, ready: Boolean, fixedStage: Stage? = null): String? {
        while (true) {
            val count = emitted.get()
            if (count >= 8) return null
            if (emitted.compareAndSet(count, count + 1)) break
        }
        val phase = fixedStage?.name ?: if (ready) "CONTROL" else stage.name
        return "save_error phase=$phase category=${category(error).name} ready=$ready"
    }

    fun install(ready: () -> Boolean, emit: (String) -> Unit, action: () -> Unit) {
        try { action() }
        catch (original: Exception) {
            try { failure(original, ready(), Stage.INSTALL)?.let(emit) }
            catch (_: Throwable) { /* Diagnostics never replace the installation failure. */ }
            throw original
        }
    }

    private fun category(error: Exception): Category {
        // Exact allowlisted superclass identities only; no arbitrary class names enter output.
        var type: Class<*>? = error.javaClass
        repeat(16) {
            when (type?.name) {
                "android.database.sqlite.SQLiteException" -> return Category.DATABASE
                "com.easysavevideo.save.SaveFailure" -> return Category.SAVE_FAILURE
            }
            type = type?.superclass
        }
        return when (error) {
            is SecurityException -> Category.SECURITY
            is IOException -> Category.IO
            is RejectedExecutionException -> Category.REJECTED
            is IllegalArgumentException -> Category.ARGUMENT
            is IllegalStateException -> Category.STATE
            else -> Category.OTHER
        }
    }
}
