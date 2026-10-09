#!/usr/bin/env python3
"""Current M8-B4 scope and wiring gate; all historical gates/evidence remain immutable."""
from pathlib import Path
import json,os,subprocess,xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[1];BASE='bed084e46fcbf3bffff57177fc21306ddaeec2e1'
OUT=Path(os.environ.get('ESV_EVIDENCE_DIR',ROOT/'docs/m8b4/evidence'));OUT.mkdir(parents=True,exist_ok=True)
APP='app/src/main/kotlin/com/easysavevideo/app/'
EDITS={APP+'discovery/AndroidDiscoveryController.kt',APP+'discovery/DiscoveryPowerPresentation.kt',
 APP+'ui/DiscoveryPowerControls.kt','app/src/debug/kotlin/com/easysavevideo/app/discovery/BuildDiscoveryPower.kt',
 'app/src/release/kotlin/com/easysavevideo/app/discovery/BuildDiscoveryPower.kt'}
NEW={APP+'discovery/'+n+'.kt' for n in ('AndroidDiscoveryOsSignalSource','DiscoveryOsPowerBridge','DiscoveryLatestDelivery')}
NEW|={'app/src/test/kotlin/com/easysavevideo/app/discovery/'+n+'.kt' for n in
 ('DiscoveryLatestDeliveryTest','DiscoveryOsPowerBridgeTest','DiscoveryOsPowerPresentationTest')}
NEW|={'scripts/check-os-power-admission.py','scripts/verify-os-power-workspace.sh'}
def git(*args):return subprocess.check_output(['git',*args],cwd=ROOT)
base={}
for row in git('ls-tree','-r',BASE).decode().splitlines():
    meta,path=row.split('\t',1);mode,kind,oid=meta.split();assert kind=='blob' and mode in ('100644','100755')
    base[path]=(mode,oid)
assert EDITS <= base.keys()
changed=[]
for path,(mode,oid) in base.items():
    p=ROOT/path;assert p.is_file() and not p.is_symlink(),path
    assert bool(p.stat().st_mode&0o111)==(mode=='100755'),('MODE_CHANGED',path)
    if git('hash-object',str(p)).decode().strip()!=oid:
        assert path in EDITS,('HISTORICAL_OR_UNSCOPED_EDIT',path);changed.append(path)
for path in git('ls-files','--cached','--others','--exclude-standard').decode().splitlines():
    if path not in base:
        assert path in NEW or path.startswith('docs/m8b4/'),('UNSCOPED_NEW_FILE',path)
        assert (ROOT/path).is_file() and not (ROOT/path).is_symlink()
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
report={'scope':'M8_B4_LIFECYCLE_OS_OBSERVATION_BRIDGE_ONLY','baseline':BASE,'baseline_files':len(base),
 'unchanged_baseline_files':len(base)-len(changed),'allowed_production_edits':sorted(EDITS),'changed_files':sorted(changed),
 'historical_scripts_tests_and_evidence':'BYTE_AND_EXECUTABLE_MODE_UNCHANGED','effective_permissions':permissions,
 'network_state_permission':'ALREADY_MERGED_FROM_ADMITTED_MEDIA3_EXOPLAYER_1_11_1; NO_NEW_PERMISSION',
 'runtime_artifacts':'EXACT74_GATED_BY_STRICT_GRADLE','core_android_types':'UNCHANGED_PLATFORM_NEUTRAL',
 'release_simulation':'ABSENT','device_acceptance':'NOT_RUN','energy_or_threshold_calibration':'NOT_RUN',
 'vault':'DISABLED_UNCHANGED','blocked_rule_candidate':'NOT_RETRIED_REVIEWED_OR_SUBSTITUTED',
 'overall_feature_completion':False}
(OUT/'os-power-admission.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2))
