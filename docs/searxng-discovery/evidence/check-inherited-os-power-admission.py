#!/usr/bin/env python3
"""Inherited M8-B4 OS wiring and permissions under the current M1-B1 scope guard."""
from pathlib import Path
import json,os,subprocess,xml.etree.ElementTree as ET
from scope_support import ROOT, BASE, APP, EDITS, baseline, checked_output, verify_scope
OUT=checked_output()
scope=verify_scope()
base=baseline()
changed=scope['changed_files']
controller=(ROOT/(APP+'discovery/AndroidDiscoveryController.kt')).read_text()
source=(ROOT/(APP+'discovery/AndroidDiscoveryOsSignalSource.kt')).read_text()
bridge=(ROOT/(APP+'discovery/DiscoveryOsPowerBridge.kt')).read_text()
assert 'DiscoveryPowerSignals(enabled = true)' in controller
assert 'DiscoveryOsPowerBridge(AndroidDiscoveryOsSignalSource(appContext)' in controller
assert 'osSignals.setActive(isForeground && discoveryVisible)' in controller
assert 'osSignals.close(); signalClock.shutdownNow()' in controller
assert 'osSignals.revalidate(); guarded(action)' in controller
assert 'removeOnCancelPolicy = true' in controller
assert controller.count('suspendPowerImmediately()')==1
assert controller.index('val revision = c?.suspendPowerImmediately()') < controller.index('powerDelivery.offer(')
for method,nextmethod in [('fun refreshSystemSignals(', 'private fun receiveOsPower('),('private fun applyPowerChange(', 'fun updateSettings(')]:
    body=controller.split(method,1)[1].split(nextmethod,1)[0]
    for forbidden in ('requestMore(','.page(','.start(','.setVisibility(','.setMode('):assert forbidden not in body,(method,forbidden)
assert 'SystemClock::elapsedRealtime' in controller and 'currentTimeMillis' not in bridge
assert 'private class Epoch' in bridge and 'epoch !== token' in bridge
assert 'now > Long.MAX_VALUE - freshnessMs' in bridge
assert 'registerDefaultNetworkCallback(network, main)' in source
assert 'Context.RECEIVER_NOT_EXPORTED' in source
assert 'FLAG_INCLUDE_LOCATION_INFO' not in source
for forbidden in ('getNetworkCapabilities(', 'getActiveNetwork(', 'requestNetwork(', 'requestPermissions(', 'getThermalHeadroom(', 'newWakeLock(', 'Log.', 'println(', 'printStackTrace(', 'getSharedPreferences('):
    assert forbidden not in source,forbidden
for name in ('AndroidDiscoveryOsSignalSource','DiscoveryOsPowerBridge','DiscoveryLatestDelivery'):
    text=(ROOT/(APP+'discovery/'+name+'.kt')).read_text()
    for forbidden in ('http://','https://','Log.','println(', 'printStackTrace(', 'exception.message'):
        assert forbidden not in text,(name,forbidden)
# The original application manifest is byte-exact. The admitted Media3 manifest already supplies
# this normal permission. Compile both actual merged variants and verify the same effective set.
permissions={}
for variant in ('debug','release'):
    p=ROOT/f'app/build/intermediates/merged_manifests/{variant}/process{variant.title()}Manifest/AndroidManifest.xml'
    if not p.exists():
        p=ROOT/f'app/build/intermediates/merged_manifest/{variant}/process{variant.title()}MainManifest/AndroidManifest.xml'
    assert p.is_file(),('MISSING_MERGED_MANIFEST',variant)
    root=ET.parse(p).getroot();ns='{http://schemas.android.com/apk/res/android}'
    actual=sorted(e.attrib[ns+'name'] for e in root.findall('uses-permission'))
    expected=sorted(['android.permission.INTERNET','android.permission.ACCESS_NETWORK_STATE','android.permission.WAKE_LOCK',
      'android.permission.FOREGROUND_SERVICE','android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION',
      'android.permission.RECORD_AUDIO','android.permission.POST_NOTIFICATIONS',
      'com.easysavevideo.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'])
    assert actual==expected,(variant,actual)
    permissions[variant]=actual
for variant in ('debug','release'):
    root=ROOT/f'app/build/intermediates/built_in_kotlinc/{variant}/compile{variant.title()}Kotlin/classes/com/easysavevideo/app/discovery'
    for name in ('AndroidDiscoveryOsSignalSource','DiscoveryOsPowerBridge','DiscoveryLatestDelivery'):
        assert (root/(name+'.class')).is_file(),(variant,name)
    content=(root/'AndroidDiscoveryController.class').read_bytes()
    assert b'AndroidDiscoveryOsSignalSource' in content and b'DiscoveryOsPowerBridge' in content
    build=(root/'BuildDiscoveryPower.class').read_bytes()
    for marker in ('conserving','normal','unknown','paused'):
        assert (marker.encode() in build)==(variant=='debug'),(variant,marker)
report={'scope':'M8_B4_UNCHANGED_OS_REGRESSION_WITH_SEARXNG_SCOPE_GUARD','baseline':BASE,'baseline_files':len(base),
 'unchanged_baseline_files':len(base)-len(changed),'allowed_production_edits':sorted(EDITS),'changed_files':sorted(changed),
 'historical_scripts_tests_and_evidence':'BYTE_AND_EXECUTABLE_MODE_UNCHANGED','effective_permissions':permissions,
 'network_state_permission':'ALREADY_MERGED_FROM_ADMITTED_MEDIA3_EXOPLAYER_1_11_1; NO_NEW_PERMISSION',
 'runtime_artifacts':'EXACT74_GATED_BY_STRICT_GRADLE','core_android_types':'UNCHANGED_PLATFORM_NEUTRAL',
 'release_simulation':'ABSENT','device_acceptance':'NOT_RUN','energy_or_threshold_calibration':'NOT_RUN',
 'vault':'DISABLED_UNCHANGED','blocked_rule_candidate':'NOT_RETRIED_REVIEWED_OR_SUBSTITUTED',
 'overall_feature_completion':False}
(OUT/'os-power-admission.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2))
