#!/usr/bin/env python3
"""Freeze current ordinary reliability inputs; historical evidence is never rewritten."""
from pathlib import Path
import hashlib, json
ROOT=Path(__file__).resolve().parents[3]
old=json.loads((ROOT/'docs/m8b3/evidence/frozen-review-source-manifest.json').read_text())
paths={row['path'] for row in old['files']}
for module in ('core-model','core-domain','ui-theme','discovery','commons-provider','save','recording','library','hls','app'):
    paths.update(str(p.relative_to(ROOT)) for p in (ROOT/module/'src').rglob('*') if p.is_file())
paths.update(str(p.relative_to(ROOT)) for p in (ROOT/'docs/m9a/evidence').glob('*') if p.suffix in ('.kt','.py','.sh'))
paths.update(('scripts/check-fixed-reliability-admission.py','scripts/verify-fixed-reliability-workspace.sh'))
files=[]
for name in sorted(paths):
    data=(ROOT/name).read_bytes()
    files.append(dict(path=name,size=len(data),sha256=hashlib.sha256(data).hexdigest()))
manifest=dict(scope='M9-A ordinary fixed-source reliability plus inherited current-source hosts',
              baseline='dfdddbbba2ea5fc3f810f02fa2652e55bb2ecba0',files=files)
out=ROOT/'docs/m9a/evidence/frozen-review-source-manifest.json'
out.write_text(json.dumps(manifest,indent=2)+'\n')
print(json.dumps(dict(inputs=len(files),sha256=hashlib.sha256(out.read_bytes()).hexdigest())))
