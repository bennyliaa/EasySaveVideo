#!/usr/bin/env python3
"""Verify and reassemble this exact ESV handoff; never extract or run its source."""
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent
NAME = 'esv-slice07-handoff-20261018T0647Z.tar.gz'
SIZE = 29125872
SHA = 'fca4b4359844f51735aceb3925241a89d10d7a8a4f4a46cafe43ce06f80a0859'
manifest = json.loads((ROOT / 'TRANSFER_MANIFEST.json').read_text(encoding='utf-8'))
if manifest.get('schema') != 1 or manifest.get('archive') != {'file': NAME, 'bytes': SIZE, 'sha256': SHA}:
    raise SystemExit('FAIL: unexpected transfer manifest')
parts = manifest.get('parts', [])
if len(parts) != 4:
    raise SystemExit('FAIL: expected exactly four parts')
whole = hashlib.sha256()
offset = 0
for index, part in enumerate(parts, 1):
    expected = f'{NAME}.part{index:02d}'
    if part.get('order') != index or part.get('file') != expected or part.get('offset') != offset:
        raise SystemExit('FAIL: part order, path or offset mismatch')
    path = ROOT / expected
    if path.is_symlink() or not path.is_file():
        raise SystemExit('FAIL: missing regular part file')
    data = path.read_bytes()
    blob = hashlib.sha1(b'blob ' + str(len(data)).encode('ascii') + b'\0' + data).hexdigest()
    if not 0 < len(data) <= 8 * 1024 * 1024 or len(data) != part.get('bytes') or hashlib.sha256(data).hexdigest() != part.get('sha256') or blob != part.get('git_blob_sha1'):
        raise SystemExit('FAIL: part size or digest mismatch')
    offset += len(data)
    whole.update(data)
if offset != SIZE or whole.hexdigest() != SHA:
    raise SystemExit('FAIL: complete archive size or digest mismatch')
output = ROOT / NAME
# Exclusive creation refuses an existing file, directory or symlink.
with output.open('xb') as writer:
    second_pass = hashlib.sha256()
    for index in range(1, 5):
        path = ROOT / f'{NAME}.part{index:02d}'
        if path.is_symlink():
            raise SystemExit('FAIL: part changed after verification; output is incomplete')
        data = path.read_bytes()
        writer.write(data)
        second_pass.update(data)
if output.stat().st_size != SIZE or second_pass.hexdigest() != SHA:
    raise SystemExit('FAIL: input changed during assembly; do not use output')
print(f'PASS: {SIZE} bytes, SHA-256 {SHA}')
print('Archive assembled only. Extract into a new empty directory and follow README_HANDOFF.md.')
