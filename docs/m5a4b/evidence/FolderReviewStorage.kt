package com.easysavevideo.app.save.storage
import com.easysavevideo.library.MediaReadRef
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
/** Storage/journal doubles; production SQL is exercised separately by the independent SQLite suite. */
class AndroidSaveStorage(val sourceFile:File) {
    val folderGrants=FolderStore(); val destinations=DestinationRefs()
    var exactSource=MediaReadRef("private-source",7,3); var sourceOpens=0
    fun openLibraryMedia(ref:MediaReadRef):Opened {
        require(ref==exactSource); sourceOpens++
        val bytes=sourceFile.readBytes()
        return Opened(FileInputStream(sourceFile),bytes.size.toLong(),MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
    }
    class Opened(val stream:FileInputStream,val sizeBytes:Long,val sha256:String) : AutoCloseable { override fun close()=stream.close() }
}
class DestinationRefs { val byOperation=linkedMapOf<String,String>(); fun folderGrantForOperation(id:String)=byOperation[id] }
class FolderStore {
    data class Row(val id:String,val uri:String,val flags:Int,val retired:Boolean=false,val invalidated:Boolean=false)
    val rows=linkedMapOf<String,Row>(); var capacity=true; val events=mutableListOf<String>()
    var onIssue:(()->Unit)?=null
    fun availableCapacity(uri:String,flags:Int)=capacity
    fun issue(uri:String,flags:Int):Row {
        events+="issue"
        val existing=rows.values.firstOrNull { it.uri==uri&&it.flags==flags&&!it.retired&&!it.invalidated }
        val row=existing ?: Row("folder-${rows.size+1}",uri,flags).also { rows[it.id]=it }
        onIssue?.invoke(); return row
    }
    fun get(id:String)=rows[id]
    fun invalidateUri(uri:String) { events+="invalidate"; rows.replaceAll { _,v -> if(v.uri==uri) v.copy(invalidated=true) else v } }
}
