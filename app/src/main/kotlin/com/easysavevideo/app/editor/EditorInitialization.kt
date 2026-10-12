package com.easysavevideo.app.editor

import com.easysavevideo.app.diagnostics.*

/** Constructor status only; never entitlement, recovery, source or execution authority. */
enum class EditorInitializationStatus { INITIALIZING, READY, STORAGE_UNAVAILABLE, RUNTIME_UNAVAILABLE }
internal data class EditorInitialization<T>(val value:T?,val status:EditorInitializationStatus,val diagnostic:InitializationDiagnostic?=null)
internal fun <T:Any> initializeEditor(storageProbe:()->Unit,create:()->T):EditorInitialization<T> =
    initializeEditor(InitializationProbe(),storageProbe,create)
internal fun <T:Any> initializeEditor(probe:InitializationProbe,storageProbe:()->Unit,create:()->T):EditorInitialization<T> {
    try { probe.at(InitializationStage.EDIT_STORAGE_CONSTRUCT,storageProbe) } catch (_:Exception) { return EditorInitialization(null,EditorInitializationStatus.STORAGE_UNAVAILABLE,probe.failure) }
    return try { EditorInitialization(probe.at(InitializationStage.EDIT_RUNTIME_CONSTRUCT,create),EditorInitializationStatus.READY) }
    catch (_:Exception) { EditorInitialization(null,EditorInitializationStatus.RUNTIME_UNAVAILABLE,probe.failure) }
}
internal fun editorEntryEnabled(available:Boolean,status:EditorInitializationStatus)=available && status==EditorInitializationStatus.READY
