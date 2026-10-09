from pathlib import Path
import hashlib,json,stat,sys
mode,manifest,record,*required=sys.argv[1:]
p=Path(manifest)
raw=p.read_bytes()
current=json.loads(raw)
if mode == 'after':
 saved=json.loads(Path(record).read_text())
 assert saved['manifest_sha256']==hashlib.sha256(raw).hexdigest(), 'Shared frozen manifest changed during run'
 assert saved['manifest']==current, 'Shared manifest content changed'
rows=current['files']; names=[row['path'] for row in rows]
assert len(names)==len(set(names)), 'Duplicate frozen paths'
assert set(required)<=set(names), 'Reviewed inputs absent from manifest: '+str(sorted(set(required)-set(names)))
for row in rows:
 path=Path(row['path']); assert not path.is_absolute() and '..' not in path.parts
 metadata=path.lstat(); assert stat.S_ISREG(metadata.st_mode), 'Non-regular frozen input: '+str(path)
 data=path.read_bytes(); assert len(data)==row['size'], 'Frozen size mismatch: '+str(path)
 assert hashlib.sha256(data).hexdigest()==row['sha256'], 'Frozen hash mismatch: '+str(path)
 expected=row.get('executable')
 assert isinstance(expected,bool), 'Missing or invalid frozen executable mode: '+str(path)
 assert bool(metadata.st_mode & 0o111)==expected, 'Frozen Git executable-mode mismatch: '+str(path)

if mode == 'before':
 Path(record).write_text(json.dumps({'manifest_sha256':hashlib.sha256(raw).hexdigest(),'manifest':current},indent=2)+'\n')
print('PASS: full frozen manifest bytes, regular-file/Git executable modes, hashes, membership; '+str(len(rows))+' files')
