#!/usr/bin/env python3
"""Freeze the completed A4b candidate explicitly; never update historical snapshots."""
from pathlib import Path
import hashlib
import json
import os

ROOT = Path(__file__).resolve().parents[3]
OUT = ROOT / 'docs/m5a4b/evidence/frozen-review-source-manifest.json'
BASE = '046209b39d932f08910a8b79f33d4d02d6183ccd'
for hook in ('docs/m5a4b/evidence/run-independent-folder-review.sh',
             'docs/m5a4b/evidence/independent-folder-sqlite.py',
             'scripts/verify-folder-destination-sqlite.py'):
    assert (ROOT / hook).is_file(), ('REQUIRED_REVIEW_INPUT_MISSING', hook)
expected = int(os.environ['ESV_EXPECTED_JVM_TESTS'])
assert expected > 0, 'An explicit exact JVM test count is required before freezing'
old = json.loads((ROOT / 'docs/m5a4a/evidence/frozen-review-source-manifest.json').read_text())
paths = {row['path'] for row in old['files']}
for base in (
    'app/src/main/kotlin/com/easysavevideo/app/library/destination',
    'app/src/test/kotlin/com/easysavevideo/app/library/destination',
    'app/src/test/resources/destination',
    'library/src/main/kotlin/com/easysavevideo/library/destination',
    'library/src/test/kotlin/com/easysavevideo/library/destination',
):
    paths.update(str(p.relative_to(ROOT)) for p in (ROOT / base).rglob('*') if p.is_file())
paths.update(str(p.relative_to(ROOT)) for p in (ROOT / 'docs/m5a4b/evidence').glob('*')
             if p.is_file() and p.suffix in ('.py', '.sh', '.kt'))
paths.update((
    'app/src/main/kotlin/com/easysavevideo/app/MainActivity.kt',
    'app/src/main/kotlin/com/easysavevideo/app/save/storage/AndroidSaveStorage.kt',
    'app/src/main/kotlin/com/easysavevideo/app/library/export/AndroidExportPort.kt',
    'app/src/main/kotlin/com/easysavevideo/app/library/export/AndroidExportRuntime.kt',
    'app/src/main/kotlin/com/easysavevideo/app/library/export/AndroidExportJournal.kt',
    'scripts/check-folder-destination-admission.py', 'scripts/verify-folder-destination-sqlite.py',
    'scripts/verify-folder-destination-workspace.sh', 'scripts/verify-a4b-library-sqlite.py',
    'scripts/verify-a4b-export-sqlite.py', 'scripts/verify-a4b-editor-sqlite.py',
))
files = []
for path in sorted(paths):
    file = ROOT / path
    assert file.is_file() and not file.is_symlink(), path
    content = file.read_bytes()
    files.append({'path': path, 'size': len(content), 'sha256': hashlib.sha256(content).hexdigest(),
                  'executable': bool(file.stat().st_mode & 0o111)})
result = {'scope': 'M5-A4b ordinary remembered SAF folder destination source/tests/current-scope guards; not Android acceptance',
          'baseline': BASE, 'expected_jvm_tests': expected, 'files': files}
OUT.write_text(json.dumps(result, indent=2) + '\n')
print('Frozen inputs:', len(files), '; exact expected JVM tests:', expected)
