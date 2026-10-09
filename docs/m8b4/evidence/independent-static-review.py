from pathlib import Path
import json,re,sys
root=Path('.')
controller=(root/'app/src/main/kotlin/com/easysavevideo/app/discovery/AndroidDiscoveryController.kt').read_text()
source=(root/'app/src/main/kotlin/com/easysavevideo/app/discovery/AndroidDiscoveryOsSignalSource.kt').read_text()
bridge=(root/'app/src/main/kotlin/com/easysavevideo/app/discovery/DiscoveryOsPowerBridge.kt').read_text()
ui=(root/'app/src/main/kotlin/com/easysavevideo/app/ui/DiscoveryPowerControls.kt').read_text()
release=(root/'app/src/release/kotlin/com/easysavevideo/app/discovery/BuildDiscoveryPower.kt').read_text()
def section(start,end): return controller.split(start,1)[1].split(end,1)[0]
def clean(s): return re.sub(r'/\*.*?\*/|//[^\n]*','',s,flags=re.S)
initialize=section('private fun initialize(', 'private fun applyLifecycle(')
assert 'c.setPowerSignals(power.selection.signals, 0L)' in initialize
assert initialize.index('val power = desiredPower.get()') < initialize.index('c.setPowerSignals(')
life=section('private fun applyLifecycle(', 'fun setVisibility(')
assert life.index('currentSuspensionGeneration()') < life.index('saveActive') < life.index('c.setPriority(') < life.index('c.setVisibility(')
visibility=section('fun setVisibility(', 'fun setTaskPriority(')
assert visibility.index('coordinator?.suspendImmediately()') < visibility.index('osSignals.setActive(isForeground && discoveryVisible)')
offer=section('private fun offerPowerLocked(', 'private fun applyPowerChange(')
assert offer.index('suspendPowerImmediately()') < offer.index('powerDelivery.offer(')
apply=clean(section('private fun applyPowerChange(', 'fun updateSettings('))
assert 'change.coordinator === c' in apply and 'requireNotNull(change.revision)' in apply
assert apply.index('pageDemandPending = false') < apply.index('c.setPowerSignals(')
assert 'refresh(readOnly = true)' in apply
assert not any(x in apply for x in ['.start(','.restore(','.requestMore(','.page(','.setVisibility('])
refresh=clean(section('fun refreshSystemSignals()', 'private fun receiveOsPower('))
assert 'osSignals.refresh()' in refresh and not any(x in refresh for x in ['.start(','.requestMore(','.page(','.setVisibility('])
submit=section('private fun submit(', 'private fun guarded(')
assert submit.index('osSignals.revalidate()') < submit.index('guarded(action)')
assert 'SystemClock::elapsedRealtime' in controller
assert 'ScheduledThreadPoolExecutor(1)' in controller and 'removeOnCancelPolicy = true' in controller
assert 'setExecuteExistingDelayedTasksAfterShutdownPolicy(false)' in controller
assert 'DiscoveryLatestDelivery<PendingPowerChange>' in controller and 'DiscoveryLatestDelivery<DiscoveryUiState>' in controller
close=controller.split('override fun close()',1)[1]
assert close.index('closed.compareAndSet(false, true)') < close.index('osSignals.close()') < close.index('signalClock.shutdownNow()')
assert 'powerDelivery.close(); uiDelivery.close()' in close
assert 'BuildDiscoveryPower.selection(id) ?: return' in controller
assert 'val scenarios: List<DiscoveryPowerScenario> = emptyList()' in release and 'fun selection(id: String): DiscoveryPowerSelection? = null' in release
assert 'state.osPower' in ui and 'controller::refreshSystemSignals' in ui
assert 'NetworkCallback()' in source and 'registerDefaultNetworkCallback(network, main)' in source
assert all(x not in clean(source) for x in ['getNetworkCapabilities(', 'getActiveNetwork(', 'requestNetwork(', 'getLinkProperties(', 'getSharedPreferences(', 'Log.'])
assert all(x not in clean(bridge) for x in ['scheduleAtFixedRate','scheduleWithFixedDelay','Thread.sleep','getSharedPreferences(','Log.'])
assert 'identityMatch = DiscoveryOriginMediaIdentityMatch.UNKNOWN' in controller
assert 'DiscoveryOriginMediaIdentityMatch.VERIFIED' not in controller
result={'result':'PASS','evidence':'STATIC_SOURCE_ONLY','checks':[
 'enabled-unknown production startup','generation-zero coordinator initialization','generation-before-flags lifecycle helper',
 'foreground-and-discovery-only OS lease','immediate fence before bounded power delivery','coordinator-identity and power-generation apply',
 'no policy-drain or signal-refresh demand creation','monotonic revalidation before serialized commands',
 'independent one-shot cancel-removable expiry executor','bounded power and UI publication','close removes observations and delivery',
 'release synthetic allowlist remains empty','default-network callback-arguments-only source','no new bridge polling or persistence',
 'native media identity remains unknown'],'android_runtime':'NOT_RUN','device_energy':'NOT_RUN'}
Path(sys.argv[1]).write_text(json.dumps(result,indent=2)+'\n')
print('PASS: 16 source-only wiring checks; Android execution and energy measurement NOT_RUN')
