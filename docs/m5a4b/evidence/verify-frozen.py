#!/usr/bin/env python3
"""Verify every byte and executable mode in the completed A4b candidate snapshot."""
from pathlib import Path
import hashlib
import json

ROOT = Path(__file__).resolve().parents[3]
manifest = json.loads((ROOT / 'docs/m5a4b/evidence/frozen-review-source-manifest.json').read_text())
assert manifest['baseline'] == '046209b39d932f08910a8b79f33d4d02d6183ccd'
assert isinstance(manifest['expected_jvm_tests'], int) and manifest['expected_jvm_tests'] > 0
rows = manifest['files']
assert len(rows) == len({row['path'] for row in rows})
indexed = {row['path'] for row in rows}
current = set()
for base in ('app/src/main/kotlin/com/easysavevideo/app/library/destination',
             'app/src/test/kotlin/com/easysavevideo/app/library/destination',
             'app/src/test/resources/destination',
             'library/src/main/kotlin/com/easysavevideo/library/destination',
             'library/src/test/kotlin/com/easysavevideo/library/destination'):
    current.update(str(p.relative_to(ROOT)) for p in (ROOT / base).rglob('*') if p.is_file())
current.update(str(p.relative_to(ROOT)) for p in (ROOT / 'docs/m5a4b/evidence').glob('*')
               if p.is_file() and p.suffix in ('.py', '.sh', '.kt'))
assert current <= indexed, ('UNFROZEN_CANDIDATE_INPUTS', sorted(current - indexed))
for row in rows:
    file = ROOT / row['path']
    assert file.is_file() and not file.is_symlink(), ('FROZEN_FILE_TYPE_MISMATCH', row['path'])
    content = file.read_bytes()
    assert len(content) == row['size'] and hashlib.sha256(content).hexdigest() == row['sha256'], ('FROZEN_INPUT_MISMATCH', row['path'])
    assert bool(file.stat().st_mode & 0o111) == row['executable'], ('FROZEN_MODE_MISMATCH', row['path'])
print('PASS frozen ordinary-folder inputs:', len(rows), '; expected JVM tests:', manifest['expected_jvm_tests'])
