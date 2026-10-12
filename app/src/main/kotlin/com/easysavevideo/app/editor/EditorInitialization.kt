package com.easysavevideo.app.editor

/** Constructor status only; never entitlement, recovery, source or execution authority. */
enum class EditorInitializationStatus { INITIALIZING, READY, STORAGE_UNAVAILABLE, RUNTIME_UNAVAILABLE }
internal data class EditorInitialization<T>(val value:T?,val status:EditorInitializationStatus)
internal fun <T:Any> initializeEditor(storageProbe:()->Unit,create:()->T):EditorInitialization<T> {
    try { storageProbe() } catch (_:Exception) { return EditorInitialization(null,EditorInitializationStatus.STORAGE_UNAVAILABLE) }
    return try { EditorInitialization(create(),EditorInitializationStatus.READY) }
    catch (_:Exception) { EditorInitialization(null,EditorInitializationStatus.RUNTIME_UNAVAILABLE) }
}
internal fun editorEntryEnabled(available:Boolean,status:EditorInitializationStatus)=available && status==EditorInitializationStatus.READY
