package com.easysavevideo.app.discovery

import android.content.*
import android.os.*
import android.net.*
import com.easysavevideo.discovery.*
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test

/** Actual Android adapter compiled against local API-shape doubles, never a device execution. */
class IndependentOsApiReviewTest {
    private class Listener : DiscoveryOsSignalSource.Listener {
        val battery = mutableListOf<Triple<Int,Int,Boolean?>>()
        val saver = mutableListOf<Boolean?>(); val thermal = mutableListOf<ThermalSignal>(); val network = mutableListOf<String>()
        override fun battery(level: Int,scale: Int,low: Boolean?) { battery += Triple(level,scale,low) }
        override fun saver(enabled: Boolean?) { saver += enabled }
        override fun thermal(value: ThermalSignal) { thermal += value }
        override fun available(network: Any) { this.network += "available:${(network as Network).id}" }
        override fun capabilities(network: Any,internet: Boolean,validated: Boolean,notSuspended: Boolean,unmetered: Boolean) {
            this.network += "caps:${(network as Network).id}:$internet:$validated:$notSuspended:$unmetered"
        }
        override fun blocked(network: Any,blocked: Boolean) { this.network += "blocked:${(network as Network).id}:$blocked" }
        override fun lost(network: Any) { this.network += "lost:${(network as Network).id}" }
        val total get() = battery.size+saver.size+thermal.size+network.size
    }
    private class Env : Context() {
        val events = mutableListOf<String>(); var fail: String? = null; var failCleanup = false
        var missing: Class<*>? = null; var receiver: BroadcastReceiver? = null
        var batterySeed: Intent? = battery(90); var onBatteryRegister: (() -> Unit)? = null
        var onThermalRegister: (() -> Unit)? = null; var onThermalRead: (() -> Unit)? = null
        var onNetworkRegister: (() -> Unit)? = null; var thermalListener: PowerManager.OnThermalStatusChangedListener? = null
        var networkCallback: ConnectivityManager.NetworkCallback? = null
        var saverReadFailure = false; var thermalReadFailure = false; var saverValue = false; var thermalValue = 0
        var thermalReads = 0; var flags: Int? = null
        val power = object : PowerManager() {
            override val isPowerSaveMode: Boolean get() { if (saverReadFailure) error("read"); return saverValue }
            override val currentThermalStatus: Int get() { thermalReads++; onThermalRead?.invoke(); if (thermalReadFailure) error("read"); return thermalValue }
            override fun addThermalStatusListener(executor: Executor,listener: OnThermalStatusChangedListener) {
                events += "thermal+"; thermalListener = listener; onThermalRegister?.invoke(); if (fail == "thermal") error("register")
            }
            override fun removeThermalStatusListener(listener: OnThermalStatusChangedListener) {
                assertSame(thermalListener,listener); events += "thermal-"; if (failCleanup) error("cleanup")
            }
        }
        val connectivity = object : ConnectivityManager() {
            override fun registerDefaultNetworkCallback(callback: NetworkCallback,handler: Handler) {
                events += "network+"; networkCallback = callback; onNetworkRegister?.invoke(); if (fail == "network") error("register")
            }
            override fun unregisterNetworkCallback(callback: NetworkCallback) {
                assertSame(networkCallback,callback); events += "network-"; if (failCleanup) error("cleanup")
            }
        }
        override fun <T> getSystemService(type: Class<T>): T? {
            if (type == missing) return null
            return type.cast(when(type) { PowerManager::class.java -> power; ConnectivityManager::class.java -> connectivity; else -> null })
        }
        private fun register(r: BroadcastReceiver,f: IntentFilter): Intent? {
            events += "battery+"; receiver = r
            assertEquals(setOf(Intent.ACTION_BATTERY_CHANGED,PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),f.actions.toSet())
            onBatteryRegister?.invoke(); if (fail == "battery") error("register"); return batterySeed
        }
        override fun registerReceiver(receiver: BroadcastReceiver,filter: IntentFilter,permission: String?,handler: Handler): Intent? {
            assertNull(permission); flags = null; return register(receiver,filter)
        }
        override fun registerReceiver(receiver: BroadcastReceiver,filter: IntentFilter,permission: String?,handler: Handler,flags: Int): Intent? {
            assertNull(permission); this.flags = flags; return register(receiver,filter)
        }
        override fun unregisterReceiver(receiver: BroadcastReceiver) {
            assertSame(this.receiver,receiver); events += "battery-"; if (failCleanup) error("cleanup")
        }
        fun emitBattery(level: Int) = receiver!!.onReceive(this,battery(level))
        fun emitThermal(status: Int) = thermalListener!!.onThermalStatusChanged(status)
    }
    companion object {
        private fun battery(level: Int) = Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_LEVEL,level)
            .putExtra(BatteryManager.EXTRA_SCALE,100).putExtra(BatteryManager.EXTRA_BATTERY_LOW,level < 20)
    }
    @Test fun registersOnlyExpectedApisAndUnregistersExactlyOnceInReverseOrder() {
        for (sdk in listOf(31,33,35)) {
            Build.VERSION.SDK_INT = sdk; val e = Env(); val l = Listener(); val lease = AndroidDiscoveryOsSignalSource(e).open(l)
            assertEquals(listOf("battery+","thermal+","network+"),e.events)
            assertEquals(if (sdk >= 33) Context.RECEIVER_NOT_EXPORTED else null,e.flags)
            assertEquals(Triple(90,100,false),l.battery.single()); assertEquals(false,l.saver.single()); assertEquals(ThermalSignal.NORMAL,l.thermal.single())
            lease.close(); lease.close(); assertEquals(listOf("battery+","thermal+","network+","network-","thermal-","battery-"),e.events)
            val count = l.total; e.emitBattery(1); e.emitThermal(6); val n = Network(1)
            e.networkCallback!!.onAvailable(n); e.networkCallback!!.onCapabilitiesChanged(n,NetworkCapabilities()); e.networkCallback!!.onBlockedStatusChanged(n,true); e.networkCallback!!.onLost(n)
            e.receiver!!.onReceive(e,Intent(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)); assertEquals(count,l.total)
        }
    }
    @Test fun synchronousBatteryCallbackWinsOverReturnedSticky() {
        val e = Env(); val l = Listener(); e.onBatteryRegister = { e.emitBattery(1) }
        AndroidDiscoveryOsSignalSource(e).open(l).use { assertEquals(listOf(Triple(1,100,true)),l.battery) }
    }
    @Test fun synchronousThermalRegistrationCallbackSkipsOldInitialGetter() {
        val e = Env(); val l = Listener(); e.onThermalRegister = { e.emitThermal(6) }
        AndroidDiscoveryOsSignalSource(e).open(l).use { assertEquals(listOf(ThermalSignal.SEVERE),l.thermal); assertEquals(0,e.thermalReads) }
    }
    @Test fun thermalCallbackDuringInitialGetterWinsOverReturnedSeed() {
        val e = Env(); val l = Listener(); e.onThermalRead = { e.emitThermal(3) }
        AndroidDiscoveryOsSignalSource(e).open(l).use { assertEquals(listOf(ThermalSignal.SEVERE),l.thermal) }
    }
    @Test fun sourceMapsThermalOnlyByKnownPlatformStatus() {
        val e = Env(); val l = Listener(); AndroidDiscoveryOsSignalSource(e).open(l).use {
            for (status in listOf(-1,0,1,2,3,4,5,6,7,Int.MAX_VALUE)) e.emitThermal(status)
            assertEquals(listOf(ThermalSignal.UNKNOWN,ThermalSignal.NORMAL,ThermalSignal.WARM,ThermalSignal.WARM,
                ThermalSignal.SEVERE,ThermalSignal.SEVERE,ThermalSignal.SEVERE,ThermalSignal.SEVERE,ThermalSignal.UNKNOWN,ThermalSignal.UNKNOWN),l.thermal.drop(1))
        }
    }
    @Test fun missingBatteryExtrasAndFailedReadsStayUnknown() {
        val e = Env(); val l = Listener(); e.batterySeed = Intent(Intent.ACTION_BATTERY_CHANGED); e.saverReadFailure = true; e.thermalReadFailure = true
        AndroidDiscoveryOsSignalSource(e).open(l).use {
            assertEquals(Triple(-1,-1,null),l.battery.single()); assertNull(l.saver.single()); assertEquals(ThermalSignal.UNKNOWN,l.thermal.single())
        }
    }
    @Test fun callbacksForwardOnlyTheirNetworkArguments() {
        val e = Env(); val l = Listener(); AndroidDiscoveryOsSignalSource(e).open(l).use {
            val n = Network(8); e.networkCallback!!.onAvailable(n)
            e.networkCallback!!.onCapabilitiesChanged(n,NetworkCapabilities(NetworkCapabilities.NET_CAPABILITY_INTERNET,NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
            e.networkCallback!!.onBlockedStatusChanged(n,false); e.networkCallback!!.onLost(n)
            assertEquals(listOf("available:8","caps:8:true:false:false:true","blocked:8:false","lost:8"),l.network)
        }
    }
    @Test fun eachPartiallyAcquiredRegistrationIsCleanedEvenWhenItsRegistrationThrows() {
        for ((stage,expected) in listOf("battery" to listOf("battery+","battery-"),
            "thermal" to listOf("battery+","thermal+","thermal-","battery-"),
            "network" to listOf("battery+","thermal+","network+","network-","thermal-","battery-"))) {
            val e = Env(); e.fail = stage
            val failure = assertThrows(DiscoverySignalSourceFailure::class.java) { AndroidDiscoveryOsSignalSource(e).open(Listener()) }
            assertFalse(failure.cleanupUncertain); assertEquals(expected,e.events)
        }
    }
    @Test fun cleanupTriesAllRegistrationsDespiteFailuresAndReportsUncertainty() {
        val e = Env(); e.fail = "network"; e.failCleanup = true
        val failure = assertThrows(DiscoverySignalSourceFailure::class.java) { AndroidDiscoveryOsSignalSource(e).open(Listener()) }
        assertTrue(failure.cleanupUncertain); assertEquals(listOf("battery+","thermal+","network+","network-","thermal-","battery-"),e.events)
    }
    @Test fun missingServiceFailsBeforeAnyRegistration() {
        for (missing in listOf(PowerManager::class.java,ConnectivityManager::class.java)) {
            val e = Env(); e.missing = missing
            val failure = assertThrows(DiscoverySignalSourceFailure::class.java) { AndroidDiscoveryOsSignalSource(e).open(Listener()) }
            assertFalse(failure.cleanupUncertain); assertTrue(e.events.isEmpty())
        }
    }
}
