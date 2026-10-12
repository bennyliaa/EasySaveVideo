package com.easysavevideo.app.diagnostics

import android.system.ErrnoException
import java.io.IOException
import java.nio.channels.OverlappingFileLockException

/** Fixed technical identifiers only; no exception object/message, paths or functional data. */
enum class InitializationStage {
    EDIT_STORAGE_CONSTRUCT, EDIT_STAGING_CHECK, EDIT_STAGING_CREATE, EDIT_STAGING_CANONICAL,
    EDIT_MEDIA_CHECK, EDIT_MEDIA_CREATE, EDIT_MEDIA_CANONICAL, EDIT_ROOT_FSYNC, EDIT_RUNTIME_CONSTRUCT,
    SHARE_CONSTRUCT, SHARE_DIRECTORY_RESOLVE, SHARE_SERVICE, SHARE_DIRECTORY_CHECK,
    SHARE_DIRECTORY_CREATE, SHARE_DIRECTORY_CANONICAL, SHARE_OWNER_CHECK, SHARE_OWNER_OPEN, SHARE_OWNER_LOCK
}
enum class InitializationErrorKind { ERRNO, IO, LOCK, SECURITY, ARGUMENT, STATE, OTHER }
data class InitializationDiagnostic(val stage:InitializationStage,val kind:InitializationErrorKind,val errno:Int?=null)
internal fun initializationErrorKind(failure:Exception)=when(failure) {
    is ErrnoException -> InitializationErrorKind.ERRNO
    is OverlappingFileLockException -> InitializationErrorKind.LOCK
    is IOException -> InitializationErrorKind.IO
    is SecurityException -> InitializationErrorKind.SECURITY
    is IllegalArgumentException -> InitializationErrorKind.ARGUMENT
    is IllegalStateException -> InitializationErrorKind.STATE
    else -> InitializationErrorKind.OTHER
}
/** One construction attempt, worker-confined; first error cannot be overwritten by cleanup/fallback. */
internal class InitializationProbe(
    private val kind:(Exception)->InitializationErrorKind=::initializationErrorKind,
    private val errno:(Exception)->Int?={ (it as? ErrnoException)?.errno },
) {
    var failure:InitializationDiagnostic?=null;private set
    fun record(stage:InitializationStage,error:Exception) {
        if(failure!=null)return
        val category=kind(error)
        val number=if(category==InitializationErrorKind.ERRNO)errno(error)?.takeIf{it in 1..4095}else null
        failure=InitializationDiagnostic(stage,category,number)
    }
    fun <T> at(stage:InitializationStage,action:()->T):T=try{action()}catch(error:Exception){record(stage,error);throw error}
}
