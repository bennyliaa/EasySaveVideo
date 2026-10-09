#!/usr/bin/env python3
"""Only current manifest/self/output paths and explicit bridge integration checks change.
Every inherited executable test case remains byte-exact, including the B3 policy tests.
"""
from pathlib import Path
import json,sys
ROOT=Path(__file__).resolve().parents[3]
OLD=ROOT/'docs/m5a4b/evidence'; NEW=ROOT/'docs/m8b4/evidence'
NAMES=('check-inherited-capture-preview-admission.py','check-inherited-editor-admission.py',
 'check-inherited-purchase-admission.py','check-inherited-region-recording-admission.py',
 'independent-reliability-run.sh','inherited-filter-run.sh','inherited-media-run.sh',
 'inherited-origin-run.sh','inherited-power-run.sh','run-channel-matrix.sh',
 'run-distribution-host.sh','run-independent-destination-review.sh','run-independent-entitlement-review.sh',
 'run-isolation-host.sh','run-independent-folder-review.sh','inherited-destination-sqlite.py',
 'inherited-independent-destination-sqlite.py','independent-folder-sqlite.py')
checked=[]
for oldname in NAMES:
    name={'independent-reliability-run.sh':'inherited-reliability-run.sh',
          'independent-folder-sqlite.py':'inherited-folder-sqlite.py'}.get(oldname,oldname)
    old=OLD/oldname; new=NEW/name
    expected=old.read_text().replace('docs/m5a4b/evidence/', 'docs/m8b4/evidence/')
    # Keep historical independently authored fixtures in their original immutable location.
    expected=expected.replace('docs/m8b4/evidence/FolderReview','docs/m5a4b/evidence/FolderReview')
    expected=expected.replace('docs/m8b4/evidence/IndependentFolderReviewTest.kt','docs/m5a4b/evidence/IndependentFolderReviewTest.kt')
    if name != oldname:
        expected=expected.replace('docs/m8b4/evidence/'+oldname,'docs/m8b4/evidence/'+name)
    if oldname=='independent-folder-sqlite.py':
        expected=expected.replace("'docs/m5a4b/evidence'", "'docs/m8b4/evidence'")
        expected=expected.replace("assert manifest['baseline'] == '046209b39d932f08910a8b79f33d4d02d6183ccd'",
            "assert manifest['baseline'] == 'bed084e46fcbf3bffff57177fc21306ddaeec2e1'",1)
        expected=expected.replace("prior = json.loads((ROOT / 'docs/m5a4a/evidence/frozen-review-source-manifest.json').read_text())",
            "prior = json.loads((ROOT / 'docs/m5a4b/evidence/frozen-review-source-manifest.json').read_text())",1)
        expected=expected.replace("(ROOT / 'docs/m8b4/evidence').glob('*')", "(ROOT / 'docs/m8b4/evidence').rglob('*')",1)
        expected=expected.replace("    return paths", """    for base in ('app/src/main/kotlin/com/easysavevideo/app/discovery',
                 'app/src/test/kotlin/com/easysavevideo/app/discovery'):
        paths.update(str(p.relative_to(ROOT)) for p in (ROOT / base).rglob('*') if p.is_file())
    paths.update(('scripts/check-os-power-admission.py', 'scripts/verify-os-power-workspace.sh',
                  'docs/m8b4/evidence/independent-api-review.md'))
    return paths""",1)
    if oldname=='check-inherited-purchase-admission.py':
        expected=expected.replace("'PURE_INJECTED_RELEASE_NOT_CONNECTED_DEBUG_SIMULATION_ONLY'", "'APP_LAYER_OS_BRIDGE_RELEASE_NO_SIMULATION_DEVICE_NOT_RUN'")
    if oldname=='inherited-power-run.sh':
        expected=expected.replace('  app/src/main/kotlin/com/easysavevideo/app/discovery/DiscoveryPowerPresentation.kt',
            '  app/src/main/kotlin/com/easysavevideo/app/discovery/DiscoveryPowerPresentation.kt\n  app/src/main/kotlin/com/easysavevideo/app/discovery/DiscoveryOsPowerBridge.kt',1)
        expected=expected.replace('c.setPowerSignals(power.signals, 0L)','c.setPowerSignals(power.selection.signals, 0L)',1)
        a=expected.index("select=method('fun selectPowerScenario('")
        b=expected.index("assert 'BuildDiscoveryPower.scenarios.isNotEmpty()'",a)
        expected=expected[:a]+'''select=method('fun selectPowerScenario(', 'fun refreshSystemSignals(')
assert 'BuildDiscoveryPower.selection(id) ?: return' in select
assert 'simulatedPower = selection.takeIf { it.scenarioId != null }' in select
assert 'offerPowerLocked(desiredPower.get().os)' in select
feed=method('private fun offerPowerLocked(', 'private fun applyPowerChange(')
assert feed.index('suspendPowerImmediately()') < feed.index('powerDelivery.offer(')
assert 'desiredPower.getAndSet(input)' in feed
# Source integration assertions migrate to the new bounded mailbox; old case bodies are unchanged.
drain=method('private fun applyPowerChange(', 'fun updateSettings(')
assert 'change.coordinator === c' in drain
assert drain.index('pageDemandPending = false') < drain.index('c.setPowerSignals(')
assert 'requireNotNull(change.revision)' in drain and 'refresh(readOnly = true)' in drain
assert 'c.requestMore(' not in drain and 'c.page(' not in drain and 'c.setVisibility(' not in drain
mailbox=Path('app/src/main/kotlin/com/easysavevideo/app/discovery/DiscoveryLatestDelivery.kt').read_text()
assert 'latest = value' in mailbox and 'if (scheduled) return true' in mailbox
assert 'latest.also { latest = null }' in mailbox and 'if (!closed && latest != null) scheduleLocked()' in mailbox
''' + expected[b:]
        expected=expected.replace("'os_signals':'NOT_CONNECTED'", "'os_signals':'APP_LAYER_BRIDGE_DEVICE_NOT_RUN'")
    if '--write' in sys.argv:
        new.write_text(expected);new.chmod(old.stat().st_mode & 0o777)
    assert new.read_text()==expected,('INHERITED_BODY_OR_ADAPTATION_MISMATCH',name)
    assert bool(new.stat().st_mode&0o111)==bool(old.stat().st_mode&0o111),('MODE_MISMATCH',name)
    checked.append(name)
print(json.dumps({'status':'PASS','historical_test_case_bodies':'BYTE_UNCHANGED',
 'adaptations':'Current manifest/self/output paths and manifest baseline and exact current-scope membership; add OS snapshot type dependency to B3 compilation; current controller mailbox source checks; obsolete report status corrected',
 'checked':checked},indent=2))
