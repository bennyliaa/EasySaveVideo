#!/usr/bin/env python3
"""Assert inherited executable cases are exact copies with enumerated A4b adaptations only."""
from pathlib import Path
import json

ROOT = Path(__file__).resolve().parents[3]
OLD = ROOT / 'docs/m5a4a/evidence'
CURRENT = ROOT / 'docs/m5a4b/evidence'
RUNNERS = (
    'check-inherited-capture-preview-admission.py', 'check-inherited-editor-admission.py',
    'check-inherited-purchase-admission.py', 'check-inherited-region-recording-admission.py',
    'independent-reliability-run.sh', 'inherited-filter-run.sh', 'inherited-media-run.sh',
    'inherited-origin-run.sh', 'inherited-power-run.sh', 'run-channel-matrix.sh',
    'run-distribution-host.sh', 'run-independent-destination-review.sh',
    'run-independent-entitlement-review.sh', 'run-isolation-host.sh',
)
checked = []
for name in RUNNERS:
    old, new = OLD / name, CURRENT / name
    expected = old.read_text().replace('docs/m5a4a/evidence/', 'docs/m5a4b/evidence/')
    expected = expected.replace('docs/m5a4b/evidence/IndependentDestinationReviewTest.kt',
                                'docs/m5a4a/evidence/IndependentDestinationReviewTest.kt')
    if name == 'independent-reliability-run.sh':
        # A4b freezes executable modes as well as bytes; compare the same four-key input shape.
        expected = expected.replace("row={'path':path,'size':len(data),'sha256':hashlib.sha256(data).hexdigest()}",
            "row={'path':path,'size':len(data),'sha256':hashlib.sha256(data).hexdigest(),'executable':bool(Path(path).stat().st_mode&0o111)}", 1)
    assert new.read_text() == expected, ('UNEXPECTED_INHERITED_BODY_CHANGE', name)
    assert new.stat().st_mode & 0o111 == old.stat().st_mode & 0o111, ('WRAPPER_MODE_CHANGED', name)
    checked.append(str(new.relative_to(ROOT)))
for group in ('library', 'export', 'editor'):
    old = ROOT / f'scripts/verify-a4a-{group}-sqlite.py'
    new = ROOT / f'scripts/verify-a4b-{group}-sqlite.py'
    expected = old.read_text().replace('# M5-A4a current helper version assertions only;',
                                       '# M5-A4b current helper version assertions only;')
    expected = expected.replace("'null, 6,'", "'null, 7,'")
    expected = expected.replace('oldVersion !in 1..5 || newVersion != 6', 'oldVersion !in 1..6 || newVersion != 7')
    assert new.read_text() == expected, ('UNEXPECTED_INHERITED_SQL_CASE_CHANGE', group)
    checked.append(str(new.relative_to(ROOT)))
for old, new in ((ROOT / 'scripts/verify-destination-sqlite.py', CURRENT / 'inherited-destination-sqlite.py'),
                 (OLD / 'independent-destination-sqlite.py', CURRENT / 'inherited-independent-destination-sqlite.py')):
    expected = old.read_text().replace('oldVersion !in 1..5 || newVersion != 6', 'oldVersion !in 1..6 || newVersion != 7')
    if old.parent.name == 'scripts':
        expected = expected.replace('parents[1]', 'parents[3]', 1)
    assert new.read_text() == expected, ('UNEXPECTED_INHERITED_DESTINATION_CASE_CHANGE', new.name)
    checked.append(str(new.relative_to(ROOT)))
print(json.dumps({'status': 'PASS', 'historical_case_bodies_preserved': True,
                  'adaptations': 'Current manifest/output/self paths, script relocation, helper target-v7 assertions, executable-mode manifest input shape only',
                  'checked': checked}, indent=2))
