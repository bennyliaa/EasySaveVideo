package com.easysavevideo.app.library.export

/** Metadata-only bounded preflight. No name guessing, overwrite, deletion or URI diagnostics. */
internal class FolderCreateIdentity(before:Set<String>) {
    private val existing=before.toSet()
    init { require(existing.size<=MAX_CHILDREN && existing.all{it.isNotEmpty() && it.toByteArray().size<=8192}) }
    fun requireFresh(createdId:String) { require(createdId.isNotEmpty() && createdId !in existing) { "FOLDER_CREATE_NOT_FRESH" } }
    override fun toString()="FolderCreateIdentity(count=${existing.size})"
    companion object {const val MAX_CHILDREN=1_000}
}

/** Binding is the prerequisite for any writer/cleanup ownership; known old IDs never reach it. */
internal fun <T> createFreshFolderChild(before:Set<String>,create:()->T,validate:(T)->Unit,id:(T)->String,bind:(T)->Unit):T {
    val guard=FolderCreateIdentity(before)
    val child=create()
    validate(child);guard.requireFresh(id(child));bind(child)
    return child
}
