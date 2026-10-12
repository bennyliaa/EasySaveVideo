package com.easysavevideo.app.library.destination

/** Main-thread confined. The allocation lane never touches request state or invokes a UI callback. */
internal class FolderPickerAsyncBridge<O : Any, R>(
    private val requireMain: () -> Unit,
    private val enqueue: (() -> Unit) -> Unit,
    private val post: (() -> Unit) -> Unit,
    private val allocate: () -> Int,
) {
    private class Pending<O, R>(var owner: O?, var current: () -> Boolean,
        var launch: (Int) -> Unit, var failed: (Exception) -> Unit, var result: (R) -> Unit) {
        var code: Int? = null
        var abandoned = false
        var launched = false
        fun releaseCallbacks() {
            owner = null; current = { false }; launch = {}; failed = {}; result = {}
        }
    }
    private var pending: Pending<O, R>? = null

    fun launch(owner: O, current: () -> Boolean, launch: (Int) -> Unit,
        failed: (Exception) -> Unit, result: (R) -> Unit) {
        requireMain()
        pending?.let { if (!it.launched && (it.abandoned || !it.current())) { pending = null; it.releaseCallbacks() } }
        if (pending != null) throw FolderPickerFailure(FolderPickerError.PENDING)
        val request = Pending(owner, current, launch, failed, result)
        pending = request
        try { enqueue {
            val allocated = runCatching { allocate() }
            post {
                requireMain()
                if (pending !== request) return@post
                if (request.abandoned) { pending = null; return@post }
                if (!request.current()) {
                    pending = null; request.failed(FolderPickerFailure(FolderPickerError.INVALID_STATE)); return@post
                }
                allocated.fold(onSuccess = { code ->
                    request.code = code
                    // Set before invoking platform code: an uncertain launch still consumes its code.
                    request.launched = true
                    try { request.launch(code) } catch (failure: Exception) {
                        if (pending === request) pending = null
                        request.failed(failure)
                    }
                }, onFailure = { failure ->
                    pending = null
                    request.failed(failure as? Exception ?: FolderPickerFailure(FolderPickerError.STORAGE))
                })
            }
        } } catch (failure: Exception) {
            if (pending === request) pending = null
            throw failure
        }
    }
    fun cancelUnlaunched(owner: O) {
        requireMain()
        pending?.takeIf { it.owner === owner && !it.launched }?.let {
            pending = null
            try { it.failed(FolderPickerFailure(FolderPickerError.INVALID_STATE)) } finally { it.releaseCallbacks() }
        }
    }
    fun abandon(owner: O) {
        requireMain()
        pending?.takeIf { it.owner === owner }?.let {
            it.abandoned = true
            it.releaseCallbacks()
            // A launched picker owns its slot until its result, including after rotation.
            if (!it.launched) pending = null
        }
    }
    fun dispatch(owner: O?, code: Int, result: R): Boolean {
        requireMain()
        if (!FolderPickerRequestCodes.owns(code)) return false
        val request = pending
        if (request?.code != code || !request.launched) return true
        pending = null
        if (!request.abandoned && request.owner === owner) {
            if (request.current()) request.result(result)
            else request.failed(FolderPickerFailure(FolderPickerError.INVALID_STATE))
        }
        return true
    }
}

/** Serial-worker confined. Uncertain persistence poisons this process; no retry reuses the number. */
internal class DurableFolderPickerAllocator(private val read: () -> Int, private val persist: (Int) -> Boolean) {
    private var failed = false
    fun allocate(): Int {
        if (failed) throw FolderPickerFailure(FolderPickerError.STORAGE)
        try {
            val next = read()
            return FolderPickerRequestCodes.consume(next, persist)
        } catch (failure: Exception) {
            failed = true
            throw failure
        }
    }
}
