package androidx.activity
import androidx.lifecycle.Lifecycle
import android.content.Context
import android.content.ContentResolver
import android.content.Intent
class ComponentActivity : Context(ContentResolver(java.io.File(System.getProperty("java.io.tmpdir")))) {
    val lifecycle=Lifecycle(); val launched=mutableListOf<Pair<Intent,Int>>(); var throwLaunch=false
    var onLaunch:((Intent,Int)->Unit)?=null
    fun startActivityForResult(intent:Intent,code:Int) { launched+=intent to code; onLaunch?.invoke(intent,code); if(throwLaunch) error("SYNTHETIC") }
}
