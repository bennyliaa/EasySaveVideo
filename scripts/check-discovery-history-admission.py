#!/usr/bin/env python3
"""M8-B1 exact source-scope guard; no device or complete milestone claim."""
from pathlib import Path
import json, os, subprocess, zipfile
ROOT = Path(__file__).resolve().parents[1]
BASE = '0762843aa22bf1907cedf5aeed84224fdb335f0f'
OUT = Path(os.environ.get('ESV_EVIDENCE_DIR', ROOT / 'docs/m8b1/evidence'))
OUT.mkdir(parents=True, exist_ok=True)
def require(ok, message):
    if not ok: raise RuntimeError(message)
allowed = {
 'discovery/src/main/kotlin/com/easysavevideo/discovery/' + name for name in
 ('DiscoveryRetention.kt','DiscoveryStore.kt','VideoDiscoveryCoordinator.kt')
} | {
 'app/src/main/kotlin/com/easysavevideo/app/' + name for name in
 ('discovery/AndroidDiscoveryController.kt','discovery/DiscoveryPresentationPolicy.kt',
  'storage/AndroidDiscoveryStore.kt','ui/DiscoveryScreen.kt')
} | {'scripts/check-discovery-history-admission.py','scripts/verify-discovery-history-workspace.sh',
 'scripts/test-discovery-sqlite.py','scripts/test-discovery-history-sqlite.py'}
changed = set(subprocess.check_output(['git','diff','--name-only',BASE],cwd=ROOT,text=True).splitlines())
changed |= set(subprocess.check_output(['git','ls-files','--others','--exclude-standard'],cwd=ROOT,text=True).splitlines())
for path in sorted(changed):
    require(path in allowed or path.startswith(('docs/m8b1/','discovery/src/test/',
        'app/src/test/kotlin/com/easysavevideo/app/discovery/','app/src/test/kotlin/com/easysavevideo/app/storage/DiscoveryCheckpointCodecTest.kt')), 'Out-of-scope change: ' + path)
# Explicit byte checks are independent of unstaged/staged name filtering.
for path in subprocess.check_output(['git','ls-tree','-r','--name-only',BASE,'commons-provider','vendor','third_party','gradle','core-model','core-domain','save','library','recording','hls','ui-theme'],cwd=ROOT,text=True).splitlines():
    require((ROOT/path).read_bytes() == subprocess.check_output(['git','show',BASE+':'+path],cwd=ROOT), 'Protected bytes differ: '+path)
registration = (ROOT/'app/src/main/kotlin/com/easysavevideo/app/discovery/CommonsSourceRegistration.kt').read_text()
require(not any(token in registration for token in ('https://','http://','PublicKey','ProviderUpdateRuntime')), 'Production trust/endpoint changed')
store = (ROOT/'app/src/main/kotlin/com/easysavevideo/app/storage/AndroidDiscoveryStore.kt').read_text()
require('\"discovery_v1.db\", null, 3' in store and 'oldVersion !in 1..2' in store, 'Existing database migration missing')
require('transaction.finish()' in store and 'DiscoveryRetentionPlanner.plan' in store, 'Atomic shared retention missing')
codec = (ROOT/'app/src/main/kotlin/com/easysavevideo/app/storage/DiscoveryCheckpointCodec.kt').read_text()
require('ResultSort.RELEVANCE' in codec and 'version in 1..3' in codec, 'Checkpoint migration missing')
ui = (ROOT/'app/src/main/kotlin/com/easysavevideo/app/ui/DiscoveryScreen.kt').read_text()
require('MaterialTheme' not in ui and 'EsvButton' in ui and 'EsvText' in ui, 'SkinKit UI boundary changed')
jars=list((ROOT/'commons-provider/build/libs').glob('*.jar')); require(len(jars)==1,'Expected one compiled Commons JAR')
classes=0
with zipfile.ZipFile(jars[0]) as jar:
    for name in jar.namelist():
        if not name.endswith('.class'): continue
        classes+=1; data=jar.read(name)
        for token in (b'Fixtures',b'KeyPairGenerator',b'java/security/PrivateKey',b'initSign',b'confirmHealthy',b'RuleRuntime'):
            require(token not in data, 'Unadmitted compiled content: '+name)
require(classes == 127, 'Compiled Commons class count must match fixed M7-C schema1')
report=dict(kind='STATIC_SCOPE_AND_MAIN_JAR_ONLY',baseline=BASE,scope='M8-B1 BOUNDED LOCAL HISTORY AND SCOPED CONTROLS',
    external_artifacts='EXACT74_UNCHANGED_INPUTS',commons_parser='M7-C_FIXED_SCHEMA1_UNCHANGED',main_jar_classes=classes,
    vault='DISABLED_UNCHANGED',blocked_rule_candidate='NOT_INTEGRATED_OR_REVIEWED',production_roots='EMPTY',
    endpoint='ABSENT',new_dependencies=False,device='NOT_RUN',full_M8='NOT_COMPLETE')
(OUT/'discovery-history-admission.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report,indent=2))
