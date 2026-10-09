#!/usr/bin/env python3
"""Verify exact, declared transformations of immutable M8-B4 gate bodies.

Only current manifest/self/output locations, manifest baseline and complete
current-input membership are adapted. All historical behavioral test bodies
and independently authored Kotlin fixtures remain unchanged.
"""
from pathlib import Path
import json
import sys

ROOT = Path(__file__).resolve().parents[3]
OLD = 'docs/m8b4/evidence/'
NEW = 'docs/browser-bookmarks/evidence/'
NAMES = (
    'check-inherited-capture-preview-admission.py', 'check-inherited-editor-admission.py',
    'check-inherited-purchase-admission.py', 'check-inherited-region-recording-admission.py',
    'inherited-reliability-run.sh', 'inherited-filter-run.sh', 'inherited-media-run.sh',
    'inherited-origin-run.sh', 'inherited-power-run.sh', 'run-channel-matrix.sh',
    'run-distribution-host.sh', 'run-independent-destination-review.sh',
    'run-independent-entitlement-review.sh', 'run-isolation-host.sh',
    'run-independent-folder-review.sh', 'inherited-destination-sqlite.py',
    'inherited-independent-destination-sqlite.py', 'inherited-folder-sqlite.py',
    'independent-os-run.sh',
)
OUTPUT_NAMES = ('independent-reliability', 'isolation-host', 'independent-folder-run',
                'independent-os-run', 'inherited-origin-run', 'independent-filter-run',
                'independent-destination-run')


def transform(name, original):
    expected = original
    # These are executable wrapper/self references or shared freeze references.
    # Kotlin fixtures, API doubles and historical static source checks stay at OLD.
    for target in (*NAMES, 'verify-frozen.py', 'frozen-review-source-manifest.json'):
        expected = expected.replace(OLD + target, NEW + target)
    for target in OUTPUT_NAMES:
        expected = expected.replace(OLD + target + '}', NEW + target + '}')
    if name == 'inherited-media-run.sh':
        expected = expected.replace('docs/m10b/evidence/inherited-media-run}', NEW + 'inherited-media-run}', 1)
    if name.startswith('check-inherited-'):
        expected = expected.replace("'docs/m5a4a/evidence'", "'docs/browser-bookmarks/evidence'")
    if name == 'inherited-folder-sqlite.py':
        begin = expected.index('def candidate_membership():\n')
        end = expected.index('\n\ndef fingerprint(path):', begin)
        expected = expected[:begin] + '''def candidate_membership():
    # Current full membership is strictly larger than the historical snapshot;
    # the SQL cases below and exact equality in check_snapshot are unchanged.
    from scope_support import candidate_membership as current_membership
    return current_membership()
''' + expected[end:]
        old = "assert manifest['baseline'] == 'bed084e46fcbf3bffff57177fc21306ddaeec2e1'"
        assert expected.count(old) == 1
        expected = expected.replace(old, "assert manifest['baseline'] == '0c178320cd78391bfedd5f9846e3ee873f0f5fce'", 1)
    return expected


def check_or_write(source, name, expected):
    target = ROOT / NEW / name
    assert not target.is_symlink(), ('SYMLINKED_WRAPPER', name)
    if '--write' in sys.argv:
        target.write_text(expected)
        target.chmod(source.stat().st_mode & 0o777)
    assert target.read_bytes() == expected.encode(), ('INHERITED_BODY_OR_ADAPTATION_MISMATCH', name)
    assert bool(target.stat().st_mode & 0o111) == bool(source.stat().st_mode & 0o111), ('WRAPPER_MODE_MISMATCH', name)


assert sys.argv[1:] in ([], ['--write']), 'Only --write prepares current wrappers'
checked = []
for name in NAMES:
    source = ROOT / OLD / name
    check_or_write(source, name, transform(name, source.read_text()))
    checked.append(name)
# The old OS scope-enumeration prefix belongs to M8-B4. Replace only that prefix
# with the stronger complete current baseline/scope gate; retain every OS wiring,
# merged-permission, compiled-class and no-simulation assertion below it.
source = ROOT / 'scripts/check-os-power-admission.py'
original = source.read_text()
assert original.count('controller=(ROOT/') == 1
expected = '''#!/usr/bin/env python3
"""Inherited M8-B4 OS wiring and permissions under the current M1-B1 scope guard."""
from pathlib import Path
import json,os,subprocess,xml.etree.ElementTree as ET
from scope_support import ROOT, BASE, APP, EDITS, baseline, checked_output, verify_scope
OUT=checked_output()
scope=verify_scope()
base=baseline()
changed=scope['changed_files']
''' + original[original.index('controller=(ROOT/'):]
expected = expected.replace("'scope':'M8_B4_LIFECYCLE_OS_OBSERVATION_BRIDGE_ONLY'",
                            "'scope':'M8_B4_UNCHANGED_OS_REGRESSION_WITH_M1_B1_SCOPE_GUARD'", 1)
check_or_write(source, 'check-inherited-os-power-admission.py', expected)
checked.append('check-inherited-os-power-admission.py')
print(json.dumps({'status': 'PASS', 'historical_behavioral_case_bodies': 'BYTE_UNCHANGED',
                  'adaptations': ['Current manifest/self/output paths',
                      'Current baseline and exact full-input membership for folder SQLite',
                      'OS admission scope prefix delegates complete baseline byte/mode check; OS checks unchanged'],
                  'checked': checked}, indent=2))
