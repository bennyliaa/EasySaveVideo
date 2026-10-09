package com.easysavevideo.app.discovery

import com.easysavevideo.discovery.BatterySignal
import com.easysavevideo.discovery.DiscoveryPowerSignals
import com.easysavevideo.discovery.NetworkSignal
import com.easysavevideo.discovery.ThermalSignal

/** All observations are process-local. Age is since receipt/read, not a hardware sensor timestamp. */
enum class OsSignalAge { UNKNOWN, FRESH, EXPIRED }
enum class OsObservationState { STOPPED, OBSERVING, UNAVAILABLE, CLOSED }
data class DiscoveryOsPowerSnapshot(
    val signals: DiscoveryPowerSignals = DiscoveryPowerSignals(enabled = true),
    val state: OsObservationState = OsObservationState.STOPPED,
    val battery: OsSignalAge = OsSignalAge.UNKNOWN,
    val saver: OsSignalAge = OsSignalAge.UNKNOWN,
    val thermal: OsSignalAge = OsSignalAge.UNKNOWN,
    val network: OsSignalAge = OsSignalAge.UNKNOWN,
) { override fun toString() = "DiscoveryOsPowerSnapshot([redacted])" }

/** A failed open must clean every possibly acquired registration before reporting certainty. */
class DiscoverySignalSourceFailure(val cleanupUncertain: Boolean) : RuntimeException()
interface DiscoveryOsSignalSource {
    fun open(listener: Listener): AutoCloseable
    interface Listener {
        fun battery(level: Int, scale: Int, low: Boolean?)
        fun saver(enabled: Boolean?)
        fun thermal(value: ThermalSignal)
        fun available(network: Any)
        fun capabilities(network: Any, internet: Boolean, validated: Boolean, notSuspended: Boolean, unmetered: Boolean)
        fun blocked(network: Any, blocked: Boolean)
        fun lost(network: Any)
    }
}
fun interface DiscoverySignalCancellation { fun cancel() }
fun interface DiscoverySignalTimer {
    /** Asynchronous, one-shot, removable task. Failure must throw; silently dropped work is forbidden. */
    fun schedule(delayMs: Long, action: () -> Unit): DiscoverySignalCancellation
}

/**
 * Lifecycle leases, one latest sample per signal, one expiry task, one registration operation.
 * No polling, network queries, persistent state or demand creation. Every generation uses identity,
 * so counters cannot wrap. Source calls run outside the state monitor; stop invalidates first.
 * The receiver must be nonblocking: it may fence work and enqueue ONE latest-value drain only.
 */
class DiscoveryOsPowerBridge(
    private val source: DiscoveryOsSignalSource,
    private val nowMs: () -> Long,
    private val timer: DiscoverySignalTimer,
    private val receive: (DiscoveryOsPowerSnapshot) -> Unit,
    private val freshnessMs: Long = 5 * 60_000L,
) : AutoCloseable {
    init { require(freshnessMs in 1..60 * 60_000L) }
    private class Epoch
    private data class Sample<T>(val value: T, val time: Long)
    private data class Capabilities(val usable: Boolean, val unmetered: Boolean)
    private val lock = Any()
    private var wanted = false
    private var closed = false
    private var poisoned = false
    private var failed = false
    private var operating = false
    private var epoch: Epoch? = null
    private var opened: Epoch? = null
    private var registration: Pair<Epoch, AutoCloseable>? = null
    private var expiry: DiscoverySignalCancellation? = null
    private var expiryToken: Any? = null
    private var battery: Sample<BatterySignal>? = null
    private var saver: Sample<Boolean?>? = null
    private var thermal: Sample<ThermalSignal>? = null
    private var network: Any? = null
    private var capabilities: Sample<Capabilities>? = null
    private var blocked: Sample<Boolean>? = null
    private var lost: Sample<Boolean>? = null
    private var snapshot = DiscoveryOsPowerSnapshot()
    fun snapshot(): DiscoveryOsPowerSnapshot = synchronized(lock) { snapshot }

    fun setActive(active: Boolean) {
        synchronized(lock) {
            if (closed || wanted == active) return
            wanted = active
            failed = false
            invalidateLocked()
            publishLocked()
        }
        reconcile()
    }
    /** Re-check age without reading OS services or creating demand (also used before queued commands). */
    fun revalidate() {
        synchronized(lock) { publishLocked() }
        reconcile()
    }
    /** Explicit user refresh renews observations only; callers must not start a discovery round. */
    fun refresh() {
        synchronized(lock) {
            if (closed || !wanted || poisoned) return
            failed = false
            invalidateLocked()
            publishLocked()
        }
        reconcile()
    }
    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true; wanted = false
            invalidateLocked(); publishLocked()
        }
        reconcile()
    }
    private fun invalidateLocked() {
        epoch = null; opened = null
        expiryToken = null; expiry?.cancel(); expiry = null
        battery = null; saver = null; thermal = null
        network = null; capabilities = null; blocked = null; lost = null
    }
    private fun reconcile() {
        while (true) {
            // At most one open/close in progress, including reentrant or concurrent lifecycle changes.
            val operation = synchronized(lock) {
                if (operating) return
                val existing = registration
                when {
                    existing != null && (existing.first !== epoch || !wanted || closed || poisoned) -> {
                        registration = null; operating = true
                        Pair(existing.first, existing.second)
                    }
                    wanted && !closed && !poisoned && !failed && epoch == null -> {
                        val token = Epoch(); epoch = token; operating = true
                        Pair(token, null)
                    }
                    else -> return
                }
            }
            if (operation.second != null) {
                var cleanupFailed = false
                try { operation.second!!.close() } catch (_: Exception) { cleanupFailed = true }
                synchronized(lock) {
                    operating = false
                    if (cleanupFailed) { poisoned = true; invalidateLocked(); publishLocked() }
                }
            } else {
                var acquired: AutoCloseable? = null
                var failure: Exception? = null
                try { acquired = source.open(listener(operation.first)) } catch (caught: Exception) { failure = caught }
                synchronized(lock) {
                    operating = false
                    if (failure != null) {
                        failed = true
                        poisoned = failure !is DiscoverySignalSourceFailure || failure.cleanupUncertain
                        invalidateLocked(); publishLocked()
                    } else {
                        registration = operation.first to requireNotNull(acquired)
                        if (epoch === operation.first && wanted && !closed && !poisoned) {
                            opened = operation.first
                            publishLocked()
                        }
                    }
                }
            }
        }
    }
    private fun listener(token: Epoch) = object : DiscoveryOsSignalSource.Listener {
        override fun battery(level: Int, scale: Int, low: Boolean?) = observe(token) { time ->
            val value = when {
                scale <= 0 || level < 0 || level > scale || low == null -> BatterySignal.UNKNOWN
                level.toLong() * 100 <= scale.toLong() * 5 -> BatterySignal.CRITICAL
                low || level.toLong() * 100 <= scale.toLong() * 20 -> BatterySignal.LOW
                else -> BatterySignal.NORMAL
            }
            battery = Sample(value, time)
        }
        override fun saver(enabled: Boolean?) = observe(token) { saver = Sample(enabled, it) }
        override fun thermal(value: ThermalSignal) = observe(token) { thermal = Sample(value, it) }
        override fun available(network: Any) = observe(token) {
            this@DiscoveryOsPowerBridge.network = network
            capabilities = null; blocked = null; lost = null
        }
        override fun capabilities(network: Any, internet: Boolean, validated: Boolean, notSuspended: Boolean, unmetered: Boolean) = observe(token) {
            if (this@DiscoveryOsPowerBridge.network == network)
                capabilities = Sample(Capabilities(internet && validated && notSuspended, unmetered), it)
        }
        override fun blocked(network: Any, blocked: Boolean) = observe(token) {
            if (this@DiscoveryOsPowerBridge.network == network) this@DiscoveryOsPowerBridge.blocked = Sample(blocked, it)
        }
        override fun lost(network: Any) = observe(token) {
            if (this@DiscoveryOsPowerBridge.network == network) {
                this@DiscoveryOsPowerBridge.network = null; capabilities = null; blocked = null
                lost = Sample(true, it)
            }
        }
    }
    private fun observe(token: Epoch, update: (Long) -> Unit) {
        synchronized(lock) {
            if (closed || !wanted || poisoned || failed || epoch !== token) return
            val now = nowMs()
            // Monotonic times must have representable finite expiry. Never saturate to healthy forever.
            if (now < 0 || now > Long.MAX_VALUE - freshnessMs) {
                failed = true; invalidateLocked(); publishLocked()
            } else { update(now); publishLocked() }
        }
        reconcile()
    }
    private fun age(sample: Sample<*>?, now: Long): OsSignalAge = when {
        sample == null -> OsSignalAge.UNKNOWN
        now < sample.time || now - sample.time >= freshnessMs -> OsSignalAge.EXPIRED
        else -> OsSignalAge.FRESH
    }
    private fun publishLocked() {
        val now = nowMs()
        val active = !closed && wanted && !poisoned && !failed && opened === epoch && epoch != null
        val ba = if (active) age(battery, now) else OsSignalAge.UNKNOWN
        val sa = if (active) age(saver, now) else OsSignalAge.UNKNOWN
        val ta = if (active) age(thermal, now) else OsSignalAge.UNKNOWN
        val ca = if (active) age(capabilities, now) else OsSignalAge.UNKNOWN
        val ka = if (active) age(blocked, now) else OsSignalAge.UNKNOWN
        val la = if (active) age(lost, now) else OsSignalAge.UNKNOWN
        val na = when {
            lost != null -> la
            ca == OsSignalAge.EXPIRED || ka == OsSignalAge.EXPIRED -> OsSignalAge.EXPIRED
            ca == OsSignalAge.FRESH && ka == OsSignalAge.FRESH -> OsSignalAge.FRESH
            else -> OsSignalAge.UNKNOWN
        }
        val bs = if (ba != OsSignalAge.FRESH || sa != OsSignalAge.FRESH || saver?.value == null) BatterySignal.UNKNOWN
            else if (battery?.value == BatterySignal.NORMAL && saver?.value == true) BatterySignal.LOW
            else battery?.value ?: BatterySignal.UNKNOWN
        val ns = when {
            na != OsSignalAge.FRESH -> NetworkSignal.UNKNOWN
            lost != null || blocked?.value == true || capabilities?.value?.usable == false -> NetworkSignal.OFFLINE
            capabilities?.value?.unmetered == true -> NetworkSignal.UNMETERED
            else -> NetworkSignal.METERED
        }
        val next = DiscoveryOsPowerSnapshot(
            DiscoveryPowerSignals(true, bs, if (ta == OsSignalAge.FRESH) thermal?.value ?: ThermalSignal.UNKNOWN else ThermalSignal.UNKNOWN, ns),
            when { closed -> OsObservationState.CLOSED; poisoned || failed -> OsObservationState.UNAVAILABLE
                wanted -> OsObservationState.OBSERVING; else -> OsObservationState.STOPPED }, ba, sa, ta, na)
        if (next != snapshot) { snapshot = next; receive(next) }
        expiryToken = null; expiry?.cancel(); expiry = null
        if (!active) return
        val deadline = listOfNotNull(battery, saver, thermal, capabilities, blocked, lost)
            .filter { age(it, now) == OsSignalAge.FRESH }.minOfOrNull { it.time + freshnessMs } ?: return
        val taskToken = Any(); val epochToken = epoch
        expiryToken = taskToken
        try {
            expiry = timer.schedule((deadline - now).coerceAtLeast(1)) {
                synchronized(lock) {
                    if (expiryToken !== taskToken || epoch !== epochToken || closed) return@synchronized
                    expiry = null; expiryToken = null
                    publishLocked()
                }
                reconcile()
            }
        } catch (_: Exception) {
            failed = true; invalidateLocked(); publishLocked()
        }
    }
}
