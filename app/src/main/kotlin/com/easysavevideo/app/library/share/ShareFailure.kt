package com.easysavevideo.app.library.share

import java.io.IOException
import com.easysavevideo.app.diagnostics.*

internal enum class ShareError { SOURCE_TOO_LARGE, COPY_CAPACITY, PROCESS_CAPACITY, CLEANUP_PENDING, RELEASE_UNCONFIRMED, CANCELLED, IO, INITIALIZATION_FAILED }
/** A bounded technical code, never provider exception text or a private path. */
internal class ShareFailure(val code: ShareError,val diagnostic:InitializationDiagnostic?=null) : IOException(code.name)

/** Preserve known resource fences; classify previously swallowed constructor exceptions without text. */
internal fun <T> initializeShareOwner(create:()->T):T=initializeShareOwner(InitializationProbe(),create)
internal fun <T> initializeShareOwner(probe:InitializationProbe,create:()->T):T = try { probe.at(InitializationStage.SHARE_CONSTRUCT,create) }
catch (failure:ShareFailure) { throw failure }
catch (_:Exception) { throw ShareFailure(ShareError.INITIALIZATION_FAILED,probe.failure) }
