package android.provider
import android.net.Uri
import android.content.Context
import android.content.ContentResolver
import java.io.File
/** Test protocol intentionally does not claim Android DocumentsContract implementation coverage. */
object DocumentsContract {
    object Document { const val COLUMN_MIME_TYPE="mime_type"; const val COLUMN_FLAGS="flags"; const val COLUMN_DOCUMENT_ID="document_id"; const val MIME_TYPE_DIR="vnd.android.document/directory"; const val FLAG_DIR_SUPPORTS_CREATE=8 }
    fun isTreeUri(uri:Uri) = uri.path.startsWith("/tree/")
    fun getTreeDocumentId(uri:Uri) = uri.path.removePrefix("/tree/").substringBefore('/')
    fun buildTreeDocumentUri(authority:String?,id:String)=Uri.parse("content://$authority/tree/$id")
    fun buildDocumentUriUsingTree(uri:Uri,id:String)=Uri.parse("${buildTreeDocumentUri(uri.authority,getTreeDocumentId(uri))}/document/$id")
    fun isDocumentUri(context:Context,uri:Uri)=uri.path.contains("/document/")
    fun getDocumentId(uri:Uri)=uri.path.substringAfter("/document/", "")
    fun isChildDocument(resolver:ContentResolver,parent:Uri,child:Uri)=resolver.childAllowed
    fun createDocument(resolver:ContentResolver,parent:Uri,mime:String,name:String):Uri? {
        resolver.events += "create"; resolver.createCalls++
        if(resolver.createFailure) throw java.io.IOException("SYNTHETIC")
        val uri=resolver.createResult ?: return null
        resolver.files[uri]=File(resolver.directory,"child-${resolver.createCalls}").apply { writeBytes(byteArrayOf()) }
        resolver.afterCreate?.invoke(uri)
        return uri
    }
    fun deleteDocument(resolver:ContentResolver,uri:Uri):Boolean {
        resolver.events += "delete"; resolver.deleteCalls++
        return resolver.files.remove(uri)?.delete() ?: false
    }
}
object MediaStore {
    const val AUTHORITY="media"; const val VOLUME_EXTERNAL_PRIMARY="external_primary"; const val QUERY_ARG_MATCH_PENDING="pending"; const val MATCH_INCLUDE=1
    object Video { object Media {
        const val DISPLAY_NAME="display_name"; const val MIME_TYPE="mime_type"; const val RELATIVE_PATH="relative_path"; const val IS_PENDING="is_pending"; const val _ID="_id"; const val OWNER_PACKAGE_NAME="owner"
        fun getContentUri(volume:String)=Uri.parse("content://media/$volume/video/media")
    } }
}
