package android.os
import java.util.concurrent.Executor
class Looper { companion object { private val main = Looper(); fun getMainLooper() = main } }
class Handler(val looper: Looper)
object Build { object VERSION { var SDK_INT = 35 } }
object BatteryManager { const val EXTRA_LEVEL = "level"; const val EXTRA_SCALE = "scale"; const val EXTRA_BATTERY_LOW = "battery_low" }
open class PowerManager {
    fun interface OnThermalStatusChangedListener { fun onThermalStatusChanged(status: Int) }
    open val isPowerSaveMode: Boolean get() = false
    open val currentThermalStatus: Int get() = THERMAL_STATUS_NONE
    open fun addThermalStatusListener(executor: Executor,listener: OnThermalStatusChangedListener) {}
    open fun removeThermalStatusListener(listener: OnThermalStatusChangedListener) {}
    companion object {
        const val ACTION_POWER_SAVE_MODE_CHANGED = "android.os.action.POWER_SAVE_MODE_CHANGED"
        const val THERMAL_STATUS_NONE = 0; const val THERMAL_STATUS_LIGHT = 1; const val THERMAL_STATUS_MODERATE = 2
        const val THERMAL_STATUS_SEVERE = 3; const val THERMAL_STATUS_CRITICAL = 4
        const val THERMAL_STATUS_EMERGENCY = 5; const val THERMAL_STATUS_SHUTDOWN = 6
    }
}
