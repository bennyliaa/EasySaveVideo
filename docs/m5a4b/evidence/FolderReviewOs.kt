package android.os
import java.io.Closeable
import java.io.FileDescriptor
object Process { fun myPid() = 1; fun myUid() = 1 }
object Environment { const val DIRECTORY_MOVIES = "Movies" }
class Bundle { fun putInt(key:String,value:Int) {}; fun putString(key:String,value:String) {}; fun putStringArray(key:String,value:Array<String>) {} }
class ParcelFileDescriptor(val fileDescriptor: FileDescriptor, private val owner: Closeable) : Closeable { override fun close() = owner.close() }
