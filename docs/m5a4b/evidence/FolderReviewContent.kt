package android.content
import android.net.Uri
import android.database.Cursor
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.content.pm.PackageManager
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class Intent(var action: String? = null) {
    var flags: Int = 0; var data: Uri? = null
    fun addFlags(value: Int): Intent { flags = flags or value; return this }
    companion object { const val FLAG_GRANT_READ_URI_PERMISSION=1; const val FLAG_GRANT_WRITE_URI_PERMISSION=2; const val FLAG_GRANT_PERSISTABLE_URI_PERMISSION=64; const val FLAG_GRANT_PREFIX_URI_PERMISSION=128; const val ACTION_OPEN_DOCUMENT_TREE="android.intent.action.OPEN_DOCUMENT_TREE" }
}
class ContentValues { fun put(key:String,value:String) {}; fun put(key:String,value:Int) {} }
data class UriPermission(val uri:Uri,val isReadPermission:Boolean,val isWritePermission:Boolean)
open class Context(val contentResolver: ContentResolver) {
    var preferences=SharedPreferences()
    fun getSharedPreferences(name:String,mode:Int)=preferences
    companion object { const val MODE_PRIVATE=0 }
    val applicationContext: Context get() = this
    val packageManager = PackageManager(); val packageName="synthetic.review"
    var grant: (Uri,Int)->Boolean = { _,_ -> true }
    fun checkUriPermission(uri:Uri,pid:Int,uid:Int,flags:Int) = if (grant(uri,flags)) 0 else -1
}
/** Deliberately explicit provider responses: tests drive authority, existence and IO independently. */
open class ContentResolver(val directory: File) {
    val events = mutableListOf<String>()
    val persistedUriPermissions = mutableListOf<UriPermission>()
    val files = linkedMapOf<Uri,File>()
    var directoryMime = "vnd.android.document/directory"; var directoryFlags = 8L; var directoryRows = 1
    var takeFails=false; var persistOnTake=true; var readbackUnsupported=false
    var childAllowed=true; var createResult: Uri? = null; var createFailure=false
    var beforeOpen: ((Uri,String)->Unit)?=null; var afterCreate: ((Uri)->Unit)?=null
    var readbackTransform: ((ByteArray)->ByteArray)?=null
    var deleteCalls=0; var createCalls=0; var writes=0; var reads=0; var takes=0
    fun takePersistableUriPermission(uri:Uri,flags:Int) {
        takes++; events += "take:$flags"
        if(takeFails) throw SecurityException("SYNTHETIC")
        if(persistOnTake) { persistedUriPermissions.removeAll { it.uri==uri }; persistedUriPermissions += UriPermission(uri,flags and 1!=0,flags and 2!=0) }
    }
    fun query(uri:Uri, projection:Array<String>, selection:String?, args:Array<String>?, sort:String?): Cursor? {
        events += "query"
        return if(projection.first()=="mime_type") Cursor(List(directoryRows) { listOf(directoryMime,directoryFlags) })
        else Cursor(if(files.containsKey(uri)) listOf(listOf("synthetic-child")) else emptyList())
    }
    fun query(uri:Uri, projection:Array<String>, args:Bundle, signal:Any?): Cursor? = error("MEDIASTORE_NOT_TESTED")
    fun insert(uri:Uri,values:ContentValues):Uri? = error("MEDIASTORE_NOT_TESTED")
    fun update(uri:Uri,values:ContentValues,selection:String,args:Array<String>):Int = error("MEDIASTORE_NOT_TESTED")
    fun delete(uri:Uri,selection:String,args:Array<String>):Int = error("MEDIASTORE_NOT_TESTED")
    fun openFileDescriptor(uri:Uri,mode:String): ParcelFileDescriptor? {
        beforeOpen?.invoke(uri,mode); events += "open:$mode"
        val file=files[uri] ?: throw java.io.FileNotFoundException("SYNTHETIC")
        if(mode=="wt") { writes++; val out=FileOutputStream(file); return ParcelFileDescriptor(out.fd,out) }
        if(readbackUnsupported) throw UnsupportedOperationException("SYNTHETIC")
        reads++
        val selected=readbackTransform?.let { transform -> File(directory,"readback-$reads").apply { writeBytes(transform(file.readBytes())) } } ?: file
        val input=FileInputStream(selected); return ParcelFileDescriptor(input.fd,input)
    }
    companion object { const val QUERY_ARG_LIMIT="limit"; const val QUERY_ARG_SQL_SELECTION="selection"; const val QUERY_ARG_SQL_SELECTION_ARGS="args" }
}
object ContentUris {
    fun withAppendedId(uri:Uri,id:Long) = Uri.parse("$uri/$id")
    fun parseId(uri:Uri) = uri.path.substringAfterLast('/').toLong()
}
/** Private preference durability double; actual SharedPreferences filesystem semantics are untested. */
class SharedPreferences {
    val values=linkedMapOf<String,Any>(); var commits=0; var commitSuccess=true; var persistOnFailure=false
    fun getInt(key:String,default:Int):Int = if(values.containsKey(key)) values.getValue(key) as Int else default
    fun edit()=Editor(this)
    class Editor(private val owner:SharedPreferences) {
        private val staged=linkedMapOf<String,Int>()
        fun putInt(key:String,value:Int):Editor { staged[key]=value; return this }
        fun commit():Boolean { owner.commits++; if(owner.commitSuccess||owner.persistOnFailure) owner.values.putAll(staged); return owner.commitSuccess }
    }
}
