#!/usr/bin/env python3
"""Reviewer-owned ordinary Provider source binding; no builds, network or external keys."""
import datetime
import hashlib
import json
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[3]
EVIDENCE = ROOT / 'docs/m7c/evidence'
MANIFEST = EVIDENCE / 'independent-final-source-manifest.json'


def sha(data):
    return hashlib.sha256(data).hexdigest()


def utc():
    return datetime.datetime.now(datetime.timezone.utc).isoformat().replace('+00:00', 'Z')


if len(sys.argv) != 2 or sys.argv[1] not in ('freeze', 'verify'):
    raise SystemExit('usage: review-manifest.py freeze|verify')

if sys.argv[1] == 'freeze':
    baseline = json.loads((ROOT / 'docs/m7b/evidence/independent-final-source-manifest.json').read_text())
    paths = {entry['path'] for entry in baseline['entries']}
    for folder in ('core-model/src/main', 'discovery/src/main', 'commons-provider/src/main',
                   'commons-provider/src/test', 'app/src/main/kotlin/com/easysavevideo/app/discovery',
                   'app/src/test/kotlin/com/easysavevideo/app/discovery'):
        paths.update(str(file.relative_to(ROOT)) for file in (ROOT / folder).rglob('*') if file.is_file())
    paths.update(str(file.relative_to(ROOT)) for file in EVIDENCE.glob('*Test.kt'))
    paths.update({
        'AGENTS.md',
        'app/src/main/kotlin/com/easysavevideo/app/MainActivity.kt',
        'app/src/main/kotlin/com/easysavevideo/app/ui/BrowserScreen.kt',
        'app/src/main/kotlin/com/easysavevideo/app/ui/DiscoveryScreen.kt',
        'app/src/main/kotlin/com/easysavevideo/app/ui/ProviderManagementScreen.kt',
        'docs/m7c/SCOPE_AND_SEQUENCE.md',
        'docs/m7c/evidence/run-independent-provider-host.sh',
        'docs/m7c/evidence/review-manifest.py',
        'scripts/check-provider-health-admission.py',
        'scripts/verify-provider-health-workspace.sh',
    })
    entries = []
    for path in sorted(paths):
        data = (ROOT / path).read_bytes()
        entries.append({'path': path, 'size_bytes': len(data), 'sha256': sha(data)})
    result = {
        'review': 'M7-C independent ordinary built-in Commons health and local management source review',
        'baseline': '13ab2b09e1ba33b1e8312b52ba42f78939071c3d',
        'frozen_at_utc': utc(),
        'entries': entries,
    }
    MANIFEST.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps({'entries': len(entries), 'manifest_sha256': sha(MANIFEST.read_bytes())}))
else:
    manifest_bytes = MANIFEST.read_bytes()
    manifest = json.loads(manifest_bytes)
    mismatches = []
    for entry in manifest['entries']:
        path = ROOT / entry['path']
        if not path.is_file():
            mismatches.append({'path': entry['path'], 'reason': 'missing'})
            continue
        data = path.read_bytes()
        if len(data) != entry['size_bytes'] or sha(data) != entry['sha256']:
            mismatches.append({'path': entry['path'], 'reason': 'extent-or-digest-mismatch'})
    result = {
        'verified_at_utc': utc(),
        'manifest_sha256': sha(manifest_bytes),
        'entry_count': len(manifest['entries']),
        'all_match': not mismatches,
        'mismatches': mismatches,
    }
    (EVIDENCE / 'independent-final-manifest-verification.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result))
    raise SystemExit(bool(mismatches))
