#!/usr/bin/env python3
"""Verify immutable Tink candidate imports, exact admission and offline notices. No crypto/device actions."""
from pathlib import Path
import hashlib, json, os, zipfile, subprocess
root = Path(__file__).resolve().parents[1]
evidence = Path(os.environ.get('ESV_EVIDENCE_DIR', root / 'docs/m5-vault-dependencies/evidence'))
evidence.mkdir(parents=True, exist_ok=True)
sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
candidate = root / 'third_party/candidates/tink-1.23.0'
imports = json.loads((root / 'docs/m5-vault-dependencies/evidence/candidate-import-map.json').read_text())
assert imports['candidate_archive_sha256'] == '83e851a35a957f273953725915c4d442e348cfe9e5a6e67f73b6f4e23563801e'
assert sha(candidate / 'IMPORT_MANIFEST.json') == imports['candidate_manifest_sha256']
rows = {row['candidate_path']: row for row in imports['files']}
assert len(rows) == len(imports['files']) == 66
assert len({row['repository_path'] for row in rows.values()}) == 66
original = json.loads((candidate / 'IMPORT_MANIFEST.json').read_text())
assert set(rows) == {row['path'] for row in original['files']} | {'IMPORT_MANIFEST.json', 'HASHES.sha256'}
for row in rows.values():
    p = root / row['repository_path']
    assert p.resolve().is_relative_to(root) and p.is_file() and not p.is_symlink(), p
    assert p.stat().st_size == row['bytes'] and sha(p) == row['sha256'], p
for row in original['files']:
    assert all(row[k] == rows[row['path']][k] for k in ('bytes', 'sha256')), row['path']
for line in (candidate / 'HASHES.sha256').read_text().splitlines():
    digest, p = line.split('  ', 1)
    assert sha(root / rows[p]['repository_path']) == digest, p
assert json.loads((candidate / 'CANDIDATE_GATE.json').read_text())['substantive_license_unknowns'] == []
read_rows = lambda p: {line for line in p.read_text().splitlines() if line and not line.startswith('#')}
assert read_rows(root / 'third_party/admitted-runtime.tsv') == read_rows(candidate / 'projected-runtime-74.tsv')
# This graph was accepted only after actual resolution; the exact comparison blocks future drift.
notice = (root / 'app/src/main/assets/third-party-notices.txt').read_bytes()
baseline = subprocess.check_output(['git', 'show', 'f184cc91c7f3e31d7b9b39219ee8b9c9d2139fab:app/src/main/assets/third-party-notices.txt'], cwd=root)
assert notice.startswith(baseline), 'Existing Media3/jsr305/Guava/SkinKit notices must remain intact'
assert len(notice.decode()) <= 512 * 1024
for name in ['TINK-APACHE-2.0.txt', 'PROTOBUF-v33.6-LICENSE.txt', 'JACKSON-2.8-NOTICE.txt']:
    assert (candidate / 'licenses' / name).read_bytes() in notice, name
for token in ['Copyright (C) 2014 Square, Inc.', 'Copyright 2008, Google Inc.', 'Andrew Moon',
              'Daniel J. Bernstein', 'Brian Goetz', 'CC BY 2.5', 'SkinKit', 'Error Prone annotations 2.41.0']:
    assert token.encode() in notice, token
resources = {}
for artifact in ['com/google/crypto/tink/tink-android/1.23.0/tink-android-1.23.0.jar',
                 'com/google/code/gson/gson/2.13.2/gson-2.13.2.jar']:
    with zipfile.ZipFile(root / 'vendor/tink-maven' / artifact) as z:
        proguard = {name: hashlib.sha256(z.read(name)).hexdigest() for name in z.namelist() if name.startswith('META-INF/proguard/') and not name.endswith('/')}
        assert proguard, artifact
        resources[artifact] = proguard
for p in root.rglob('build.gradle.kts'):
    if 'build' not in p.relative_to(root).parts:
        assert 'META-INF' not in p.read_text(), 'No blanket packaging resource removal admitted'
for p in root.glob('*/src/main/**/*'):
    if p.suffix in ('.kt', '.java'):
        assert 'com.google.crypto.tink' not in p.read_text(), 'Dependency admission must not activate Vault'
assert ':vault' not in (root / 'settings.gradle.kts').read_text()
result = {'status': 'PASS', 'immutable_candidate_files_verified': len(rows),
          'candidate_report_unmodified': True, 'runtime_set_exactly_reviewed': True,
          'existing_notice_prefix_byte_identical': True, 'notice_bytes': len(notice),
          'full_apache_bsd_jackson_and_derived_attribution_present': True,
          'retained_proguard_resources': resources, 'vault_production_integration': False,
          'android_and_release_package_acceptance': 'NOT_RUN'}
(evidence / 'tink-admission-checks.json').write_text(json.dumps(result, indent=2) + '\n')
print(json.dumps(result, indent=2))
