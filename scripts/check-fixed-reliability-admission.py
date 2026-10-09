#!/usr/bin/env python3
"""M9-A current-source ordinary reliability admission; historical gates remain unmodified historical evidence."""
from pathlib import Path
import json, os, subprocess, zipfile
ROOT = Path(__file__).resolve().parents[1]
BASE = 'dfdddbbba2ea5fc3f810f02fa2652e55bb2ecba0'
OUT = Path(os.environ.get('ESV_EVIDENCE_DIR', ROOT/'docs/m9a/evidence'))
OUT.mkdir(parents=True, exist_ok=True)
def require(ok, message):
    if not ok: raise RuntimeError(message)
allowed = {
 'discovery/src/main/kotlin/com/easysavevideo/discovery/VideoDiscoveryCoordinator.kt',
 'scripts/check-fixed-reliability-admission.py', 'scripts/verify-fixed-reliability-workspace.sh',
}
newtests = {
 'app/src/test/kotlin/com/easysavevideo/app/discovery/network/SystemCommonsTransportTest.kt',
 'commons-provider/src/test/kotlin/com/easysavevideo/commons/CommonsFailureRecoveryTest.kt',
 'app/src/test/kotlin/com/easysavevideo/app/reliability/OrdinaryIsolationTest.kt',
 'discovery/src/test/kotlin/com/easysavevideo/discovery/DiscoveryReliabilityMatrixTest.kt',
}
changed = set(subprocess.check_output(['git','diff','--name-only',BASE],cwd=ROOT,text=True).splitlines())
changed |= set(subprocess.check_output(['git','ls-files','--others','--exclude-standard'],cwd=ROOT,text=True).splitlines())
for path in sorted(changed):
    require(path in allowed or path in newtests or path.startswith('docs/m9a/'), 'Out-of-scope change: '+path)
# All app inputs outside the enumerated lifecycle/origin surface remain identical, including
# native codecs/muxing, Save publication/storage, permissions, entitlements and SkinKit contract.
protected = ['commons-provider','vendor','third_party','gradle','core-model','core-domain','save','library',
             'recording','hls','ui-theme','discovery/src/main','app/src/main','app/src/debug','app/src/release',
             'app/build.gradle.kts','settings.gradle.kts',
             'scripts/check-discovery-history-admission.py','scripts/check-provider-maintenance-admission.py',
             'scripts/check-discovery-event-admission.py','scripts/verify-discovery-events-workspace.sh',
             'scripts/check-discovery-power-admission.py','scripts/verify-discovery-power-workspace.sh','docs/m8b2','docs/m8b3']
checked = 0
for path in subprocess.check_output(['git','ls-tree','-r','--name-only',BASE,*protected],cwd=ROOT,text=True).splitlines():
    if path in allowed: continue
    require((ROOT/path).read_bytes() == subprocess.check_output(['git','show',BASE+':'+path],cwd=ROOT), 'Protected bytes differ: '+path)
    checked += 1
controller = (ROOT/'app/src/main/kotlin/com/easysavevideo/app/discovery/AndroidDiscoveryController.kt').read_text()
require('identityMatch = DiscoveryOriginMediaIdentityMatch.UNKNOWN' in controller, 'Current page-only bridge must not emit video SAVED')
for path in (ROOT/'app/src/main').rglob('*.kt'):
    if path.name == 'DiscoveryOriginTracker.kt': continue # Pure capability API, no native identity observer.
    require('DiscoveryOriginMediaIdentityMatch.VERIFIED' not in path.read_text(), 'Unadmitted native video identity bridge: '+str(path.relative_to(ROOT)))
origin = (ROOT/'app/src/main/kotlin/com/easysavevideo/app/discovery/DiscoveryOriginTracker.kt').read_text()
require('SeenEvent.SAVED' in origin and 'SeenEvent.PLAYED' not in origin and '120_000' in origin, 'Unadmitted event signal or unbounded token')
browser = (ROOT/'app/src/main/kotlin/com/easysavevideo/app/browser/SystemBrowserHost.kt').read_text()
require('if (request.isRedirect) discoveryOrigins.invalidate(entry.context)' in browser and 'doUpdateVisitedHistory' in browser,
        'Redirect/history origin fencing missing')
require('discoveryOrigins.close()' in browser and 'tab.incognito' in browser, 'Browser origin lifecycle fencing missing')
main = (ROOT/'app/src/main/kotlin/com/easysavevideo/app/MainActivity.kt').read_text()
require('saves.addMediaTaskActivityListener' in main and 'discoveryPrioritySubscription?.close()' in main, 'Application activity subscription absent')
save = (ROOT/'app/src/main/kotlin/com/easysavevideo/app/save/AndroidSaveRuntime.kt').read_text()
require('discoverySaveLinks.afterExecution(taskId)' in save and 'coordinator.record(taskId)' in save, 'Verified committed save bridge missing')
coordinator = (ROOT/'discovery/src/main/kotlin/com/easysavevideo/discovery/VideoDiscoveryCoordinator.kt').read_text()
require('beforeCommit { if (!stillCurrent()) throw DiscoveryFailure(DiscoveryError.CANCELLED) }' in coordinator,
        'Final correlated-event commit fence absent')
for path in sorted(allowed):
    if not path.endswith('.kt'): continue
    source = (ROOT/path).read_text()
    require(not any(banned in source for banned in ('Log.d(', 'Log.e(', 'Log.i(', 'println(', 'printStackTrace(',
            'exception.message', 'failure.message', 'addJavascriptInterface(')), 'Sensitive log or new JS bridge: '+path)
registration = (ROOT/'app/src/main/kotlin/com/easysavevideo/app/discovery/CommonsSourceRegistration.kt').read_text()
require(not any(token in registration for token in ('https://','http://','PublicKey','ProviderUpdateRuntime')), 'Production trust/endpoint changed')
jars=list((ROOT/'commons-provider/build/libs').glob('*.jar'));require(len(jars)==1,'Expected one compiled Commons JAR')
classes=0
with zipfile.ZipFile(jars[0]) as jar:
    for name in jar.namelist():
        if not name.endswith('.class'): continue
        classes+=1; data=jar.read(name)
        for token in (b'Fixtures',b'KeyPairGenerator',b'java/security/PrivateKey',b'initSign',b'confirmHealthy',b'RuleRuntime'):
            require(token not in data, 'Unadmitted compiled content: '+name)
require(classes==127, 'Compiled Commons class count must match fixed M7-C schema1')
release = ROOT/'app/build/intermediates/built_in_kotlinc/release/compileReleaseKotlin/classes'
debug = ROOT/'app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes'
power_release = release/'com/easysavevideo/app/discovery/BuildDiscoveryPower.class'
power_debug = debug/'com/easysavevideo/app/discovery/BuildDiscoveryPower.class'
require(power_release.is_file() and power_debug.is_file(), 'Both power build variants must compile')
for marker in ('conserving','normal','unknown','paused'):
    require(marker.encode() in power_debug.read_bytes(), 'Missing positive debug synthetic scenario: '+marker)
    require(marker.encode() not in power_release.read_bytes(), 'Synthetic scenario leaked into release: '+marker)
release_source = (ROOT/'app/src/release/kotlin/com/easysavevideo/app/discovery/BuildDiscoveryPower.kt').read_text()
require('emptyList()' in release_source and 'DiscoveryPowerSelection? = null' in release_source,
        'Release must refuse synthetic signals')
power = (ROOT/'discovery/src/main/kotlin/com/easysavevideo/discovery/DiscoveryPowerPolicy.kt').read_text()
require(not any(x in power for x in ('android.', 'Executors', 'Thread(', 'schedule(', 'System.currentTimeMillis')),
        'Pure policy must not collect signals or start timers')
require('pageDemandPending = false' in controller and 'refresh(readOnly = true)' in controller,
        'Policy controls must not fulfill old page demand')
require('IdentityMatch.UNKNOWN' in controller, 'Native video identity must remain unknown')
require('request.operation !== launchedOperation' in coordinator, 'Per-attempt callback ownership fence missing')
report=dict(kind='STATIC_CURRENT_SCOPE_AND_MAIN_JAR_ONLY',baseline=BASE,scope='M9-A FIXED COMMONS ORDINARY RELIABILITY',
    protected_files=checked,external_artifacts='EXACT74_UNCHANGED_INPUTS',commons_parser='M7-C_FIXED_SCHEMA1_UNCHANGED',
    main_jar_classes=classes,historical_B1_B2_B3_M7A_gates='BYTE_UNCHANGED_HISTORICAL_SCOPE_NOT_CURRENT_SOURCE_CLAIM',
    power_signals='PURE_INJECTED_RELEASE_NOT_CONNECTED_DEBUG_SIMULATION_ONLY',energy_measurement='NOT_RUN',
    saved='UNKNOWN_CURRENT_PAGE_CANDIDATE_IDENTITY_UNPROVEN',played='UNKNOWN_NO_TRUSTWORTHY_SIGNAL',
    vault='DISABLED_UNCHANGED',blocked_rule_candidate='NOT_INTEGRATED_OR_REVIEWED',
    production_roots='EMPTY',endpoint='ABSENT',new_dependencies=False,device='NOT_RUN',full_M8='NOT_COMPLETE',full_M9='NOT_COMPLETE')
(OUT/'fixed-reliability-admission.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report,indent=2))
