package com.easysavevideo.app.library.share

import java.io.IOException

internal enum class ShareError { SOURCE_TOO_LARGE, COPY_CAPACITY, PROCESS_CAPACITY, CLEANUP_PENDING, RELEASE_UNCONFIRMED, CANCELLED, IO, INITIALIZATION_FAILED }
/** A bounded technical code, never provider exception text or a private path. */
internal class ShareFailure(val code: ShareError) : IOException(code.name)

/** Preserve known resource fences; classify previously swallowed constructor exceptions without text. */
internal fun <T> initializeShareOwner(create:()->T):T = try { create() }
catch (failure:ShareFailure) { throw failure }
catch (_:Exception) { throw ShareFailure(ShareError.INITIALIZATION_FAILED) }
