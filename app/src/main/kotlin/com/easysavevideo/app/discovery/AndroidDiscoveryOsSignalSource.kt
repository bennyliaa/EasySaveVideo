package com.easysavevideo.app.discovery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.easysavevideo.discovery.ThermalSignal
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/** OS APIs only. No locations, network identities in storage/logs, network requests or device locks. */
class AndroidDiscoveryOsSignalSource(context: Context) : DiscoveryOsSignalSource {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    override fun open(listener: DiscoveryOsSignalSource.Listener): AutoCloseable {
        val power = context.getSystemService(PowerManager::class.java)
            ?: throw DiscoverySignalSourceFailure(false)
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
            ?: throw DiscoverySignalSourceFailure(false)
        val live = AtomicBoolean(true)
        val batteryCallback = AtomicBoolean(false)
        val thermalCallback = AtomicBoolean(false)
        val batteryLock = Any()
        val thermalLock = Any()
        val saverLock = Any()
        fun battery(intent: Intent?) {
            if (!live.get()) return
            listener.battery(intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1,
                intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1,
                if (intent?.hasExtra(BatteryManager.EXTRA_BATTERY_LOW) == true)
                    intent.getBooleanExtra(BatteryManager.EXTRA_BATTERY_LOW, false) else null)
        }
        fun saver() {
            synchronized(saverLock) {
                if (live.get()) listener.saver(try { power.isPowerSaveMode } catch (_: Exception) { null })
            }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (!live.get()) return
                when (intent?.action) {
                    Intent.ACTION_BATTERY_CHANGED -> synchronized(batteryLock) { batteryCallback.set(true); battery(intent) }
                    PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> saver()
                }
            }
        }
        val thermal = PowerManager.OnThermalStatusChangedListener { status ->
            synchronized(thermalLock) {
                thermalCallback.set(true)
                if (live.get()) listener.thermal(mapThermal(status))
            }
        }
        val network = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { if (live.get()) listener.available(network) }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (live.get()) listener.capabilities(network,
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED),
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
            }
            override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
                if (live.get()) listener.blocked(network, blocked)
            }
            override fun onLost(network: Network) { if (live.get()) listener.lost(network) }
        }
        // Set ownership BEFORE each registration call. A throwing binder may have had a side effect.
        var batteryOwned = false; var thermalOwned = false; var networkOwned = false
        val released = AtomicBoolean(false)
        val subscription = AutoCloseable {
            if (released.compareAndSet(false, true)) {
                live.set(false)
                var uncertain = false
                if (networkOwned) try { connectivity.unregisterNetworkCallback(network) } catch (_: Exception) { uncertain = true }
                if (thermalOwned) try { power.removeThermalStatusListener(thermal) } catch (_: Exception) { uncertain = true }
                if (batteryOwned) try { context.unregisterReceiver(receiver) } catch (_: Exception) { uncertain = true }
                if (uncertain) throw DiscoverySignalSourceFailure(true)
            }
        }
        try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED).apply { addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED) }
            batteryOwned = true
            val sticky = if (Build.VERSION.SDK_INT >= 33)
                context.registerReceiver(receiver, filter, null, main, Context.RECEIVER_NOT_EXPORTED)
            else context.registerReceiver(receiver, filter, null, main)
            // A synchronous/reentrant callback is newer than the returned registration seed.
            synchronized(batteryLock) { if (!batteryCallback.get()) battery(sticky) }
            saver()
            thermalOwned = true
            // Execute only the small reducer/fence inline: no unbounded app-created thermal queue.
            power.addThermalStatusListener(Executor { it.run() }, thermal)
            if (!thermalCallback.get()) {
                val seed = try { mapThermal(power.currentThermalStatus) } catch (_: Exception) { ThermalSignal.UNKNOWN }
                synchronized(thermalLock) { if (!thermalCallback.get() && live.get()) listener.thermal(seed) }
            }
            networkOwned = true
            connectivity.registerDefaultNetworkCallback(network, main)
            return subscription
        } catch (_: Exception) {
            var uncertain = false
            try { subscription.close() } catch (_: Exception) { uncertain = true }
            throw DiscoverySignalSourceFailure(uncertain)
        }
    }
    private fun mapThermal(status: Int): ThermalSignal = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> ThermalSignal.NORMAL
        PowerManager.THERMAL_STATUS_LIGHT, PowerManager.THERMAL_STATUS_MODERATE -> ThermalSignal.WARM
        PowerManager.THERMAL_STATUS_SEVERE, PowerManager.THERMAL_STATUS_CRITICAL,
        PowerManager.THERMAL_STATUS_EMERGENCY, PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalSignal.SEVERE
        else -> ThermalSignal.UNKNOWN
    }
}
