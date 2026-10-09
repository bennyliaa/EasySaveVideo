#!/usr/bin/env python3
"""Verify the frozen ordinary input set without printing source contents."""
from pathlib import Path
import hashlib,json
root=Path(__file__).resolve().parents[3]
manifest=json.loads((root/'docs/m10a/evidence/frozen-review-source-manifest.json').read_text())
rows=manifest['files']; assert len(rows)==len({row['path'] for row in rows})
for row in rows:
    data=(root/row['path']).read_bytes()
    assert len(data)==row['size'] and hashlib.sha256(data).hexdigest()==row['sha256'], 'FROZEN_INPUT_MISMATCH'
print('PASS frozen ordinary inputs:',len(rows))
