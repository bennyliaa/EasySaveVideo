#!/usr/bin/env python3
"""Explicitly freeze current verification inputs, never called by an acceptance runner."""
from pathlib import Path
import hashlib,json,sys
ROOT=Path(__file__).resolve().parents[3]
assert sys.argv[1:]==['--write'], 'Only explicit --write prepares a new candidate snapshot'
prior=json.loads((ROOT/'docs/m5a4b/evidence/frozen-review-source-manifest.json').read_text())
paths={f['path'] for f in prior['files']}
for base in ('app/src/main/kotlin/com/easysavevideo/app/discovery','app/src/test/kotlin/com/easysavevideo/app/discovery'):
    paths.update(str(p.relative_to(ROOT)) for p in (ROOT/base).rglob('*') if p.is_file())
paths.update(str(p.relative_to(ROOT)) for p in (ROOT/'docs/m8b4/evidence').rglob('*') if p.is_file() and p.suffix in ('.py','.sh','.kt'))
paths.update(('scripts/check-os-power-admission.py','scripts/verify-os-power-workspace.sh',
              'docs/m8b4/evidence/independent-api-review.md',
              'docs/m7c/evidence/independent-final-toolchain-sha256.txt'))
rows=[]
for path in sorted(paths):
    p=ROOT/path;assert p.is_file() and not p.is_symlink(),path;b=p.read_bytes()
    rows.append({'path':path,'size':len(b),'sha256':hashlib.sha256(b).hexdigest(),'executable':bool(p.stat().st_mode&0o111)})
manifest={'scope':'M8-B4 app-layer lifecycle OS power/network observations; no device or measured energy acceptance',
          'baseline':'bed084e46fcbf3bffff57177fc21306ddaeec2e1','expected_jvm_tests':1944,'files':rows}
p=ROOT/'docs/m8b4/evidence/frozen-review-source-manifest.json';p.write_text(json.dumps(manifest,indent=2)+'\n')
print(json.dumps({'files':len(rows),'sha256':hashlib.sha256(p.read_bytes()).hexdigest(),'expected_jvm_tests':1944},indent=2))
