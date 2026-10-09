package android.content
import android.os.Handler
open class Context {
    open val applicationContext: Context get() = this
    open fun <T> getSystemService(type: Class<T>): T? = null
    open fun registerReceiver(receiver: BroadcastReceiver, filter: IntentFilter, permission: String?, handler: Handler): Intent? = null
    open fun registerReceiver(receiver: BroadcastReceiver, filter: IntentFilter, permission: String?, handler: Handler, flags: Int): Intent? = null
    open fun unregisterReceiver(receiver: BroadcastReceiver) {}
    companion object { const val RECEIVER_NOT_EXPORTED = 4 }
}
abstract class BroadcastReceiver { abstract fun onReceive(context: Context?, intent: Intent?) }
class Intent(val action: String?) {
    private val extras = mutableMapOf<String,Any>()
    fun putExtra(key: String,value: Int): Intent = apply { extras[key] = value }
    fun putExtra(key: String,value: Boolean): Intent = apply { extras[key] = value }
    fun hasExtra(key: String) = key in extras
    fun getIntExtra(key: String,default: Int) = extras[key] as? Int ?: default
    fun getBooleanExtra(key: String,default: Boolean) = extras[key] as? Boolean ?: default
    companion object { const val ACTION_BATTERY_CHANGED = "android.intent.action.BATTERY_CHANGED" }
}
class IntentFilter(action: String) {
    val actions = mutableListOf(action)
    fun addAction(action: String) { actions += action }
}
