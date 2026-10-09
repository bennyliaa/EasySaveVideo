#!/usr/bin/env python3
"""Read-only M11-A source/document consistency check; no build, device or network work."""
from pathlib import Path
import hashlib
import json
import stat
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
DOC = ROOT / 'docs/m11a'
sha = lambda data: hashlib.sha256(data).hexdigest()
facts = json.loads((DOC / 'CODE_FACTS.json').read_text())
assert facts['tested_base_commit'] == 'bdf31387ec99c86c25847f5bbadd1eff8908f45d'
assert facts['scope'] == 'M11_A_CODE_FACTS_ONLY_NOT_ANDROID_RELEASE_ACCEPTANCE'
protected = []
for line in (DOC / 'evidence/protected-base-files.tsv').read_text().splitlines():
    if not line or line.startswith('#'):
        continue
    digest, mode, size, relative = line.split('\t')
    path = ROOT / relative
    assert path.is_file() and not path.is_symlink(), relative
    assert path.stat().st_size == int(size) and sha(path.read_bytes()) == digest, relative
    assert bool(path.stat().st_mode & stat.S_IXUSR) == (mode == '100755'), relative
    protected.append(relative)
assert len(protected) == len(set(protected)) == facts['protected_base_files'] == 2704
assert sha((ROOT / 'NOTICE').read_bytes()) == facts['root_notice_correction']['new_sha256']
# Git is optional so the same checker works on the standalone source archive.
if (ROOT / '.git').exists():
    changed = subprocess.check_output(
        ['git', 'diff', '--name-only', facts['tested_base_commit']], cwd=ROOT, text=True
    ).splitlines()
    assert all(p == 'NOTICE' or p.startswith('docs/m11a/') for p in changed), changed

ns = '{http://schemas.android.com/apk/res/android}'
for record in facts['manifest'].values():
    payload = (ROOT / record['path']).read_bytes()
    assert sha(payload) == record['sha256'], record['path']
    names = [e.attrib[ns + 'name'] for e in ET.fromstring(payload).findall('uses-permission')]
    assert names == record['permissions'], record['path']
assert len(facts['manifest']['app_authored']['permissions']) == 5
assert set(facts['manifest']['observed_debug']['permissions']) == set(facts['manifest']['observed_release']['permissions'])
assert len(facts['manifest']['observed_release']['permissions']) == 8

rows = {tuple(line.split('\t')) for line in (ROOT / 'third_party/admitted-runtime.tsv').read_text().splitlines()
        if line and not line.startswith('#')}
assert len(rows) == len({r[0] for r in rows}) == 74
for variant in ('debug', 'release'):
    observed = {tuple(line.split('\t')[:3]) for line in
                (ROOT / f'docs/m10a/evidence/{variant}RuntimeClasspath-inventory.tsv').read_text().splitlines()
                if not line.startswith('com.easysavevideo:')}
    assert observed == rows, variant
index = json.loads((DOC / 'DEPENDENCY_FACTS.json').read_text())['artifacts']
assert {(r['coordinate'], r['artifact'], r['sha256']) for r in index} == rows
assert sum(r['ownership'] == 'official_third_party' for r in index) == 72
notice = (ROOT / facts['app_notice']['path']).read_bytes()
assert sha(notice) == facts['app_notice']['sha256']
assert len(notice) == facts['app_notice']['bytes'] == 210385
text = notice.decode()
assert len(text) == facts['app_notice']['characters'] <= 512 * 1024
for row in index:
    assert (ROOT / row['evidence_index']).is_file()
    marker = row['notice_marker']
    assert marker in text, row['coordinate']
    assert text[:text.index(marker)].count('\n') + 1 == row['notice_marker_line']
    for source in row['source_records']:
        if 'path' in source:
            assert sha((ROOT / source['path']).read_bytes()) == source['sha256'], source['path']
    for license_path in row.get('license_evidence_files', []):
        assert (ROOT / license_path).is_file(), license_path
# Check preserved complete Tink notice payloads, not only component-name tokens.
for name in ('TINK-APACHE-2.0.txt', 'PROTOBUF-v33.6-LICENSE.txt', 'JACKSON-2.8-NOTICE.txt'):
    assert (ROOT / 'third_party/candidates/tink-1.23.0/licenses' / name).read_bytes() in notice

prior = json.loads((DOC / facts['prior_verification']).read_text())
assert prior['head'] == facts['tested_base_commit']
assert prior['main_verification']['jvm'] == {'tests': 1822, 'failures': 0, 'errors': 0, 'skipped': 0}
assert set(prior['cold_verification']) == {'bundle', 'source'}
for restore in prior['cold_verification'].values():
    assert restore['jvm'] == prior['main_verification']['jvm']
    assert restore['dual_compile_lint_exact74_SQL_DOM_media_prior_new_host'] == 'PASS'
for key in ('apk_aab_assembly', 'device_install'):
    assert facts['status'][key] == 'NOT_RUN'
for key in ('native_saved', 'native_played'):
    assert facts['status'][key] == 'UNKNOWN'
assert facts['status']['release_os_power_adapter'] == 'NOT_CONNECTED'
assert facts['status']['real_store_iap'] == 'NOT_IMPLEMENTED_NOT_ACCEPTED'
assert facts['status']['current_store_rule_privacy_legal_review'] == 'NOT_PERFORMED'
assert all(facts['status'][key] == 'NOT_COMPLETE' for key in ('full_m8', 'full_m9', 'full_m10', 'full_m11'))
print(json.dumps({
    'scope': 'READ_ONLY_SOURCE_DOC_FACT_CHECK_NOT_ANDROID_OR_LEGAL_ACCEPTANCE',
    'status': 'PASS', 'protected_original_files': len(protected),
    'root_notice_only_existing_file_change': True,
    'app_manifest_permissions': 5, 'observed_merged_permissions': 8,
    'exact_runtime_artifacts': 74, 'notice_component_markers': 74,
    'runtime_inputs_and_historical_evidence': 'BYTE_AND_EXECUTABLE_MODE_UNCHANGED',
    'base_tests': 'EXISTING_RECEIPT_ONLY_NOT_RERUN',
    'device_apk_legal_review': 'NOT_RUN',
}, indent=2))
