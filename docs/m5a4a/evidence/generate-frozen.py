#!/usr/bin/env python3
"""Create a new, explicit A4a snapshot; never modify an earlier milestone snapshot."""
from pathlib import Path
import hashlib,json
root=Path(__file__).resolve().parents[3]
old=json.loads((root/'docs/m10b/evidence/frozen-review-source-manifest.json').read_text())
paths={r['path'] for r in old['files']}
for base in ('app/src/main/kotlin/com/easysavevideo/app/library/destination',
'app/src/test/kotlin/com/easysavevideo/app/library/destination','app/src/test/resources/destination',
'library/src/main/kotlin/com/easysavevideo/library/destination','library/src/test/kotlin/com/easysavevideo/library/destination'):
 paths.update(str(p.relative_to(root)) for p in (root/base).rglob('*') if p.is_file())
paths.update(str(p.relative_to(root)) for p in (root/'docs/m5a4a/evidence').glob('*') if p.is_file() and p.suffix in ('.py','.sh','.kt'))
paths.update(('scripts/check-destination-admission.py','scripts/verify-destination-sqlite.py','scripts/verify-destination-workspace.sh',
'scripts/verify-a4a-library-sqlite.py','scripts/verify-a4a-export-sqlite.py','scripts/verify-a4a-editor-sqlite.py'))
files=[]
for path in sorted(paths):
 data=(root/path).read_bytes();files.append({'path':path,'size':len(data),'sha256':hashlib.sha256(data).hexdigest()})
result={'scope':'M5-A4a ordinary destination source/tests/current-scope guards; not Android acceptance',
'baseline':'a3a028d1e1de0ca20303ac7d14e4ed400e32a176','files':files}
(root/'docs/m5a4a/evidence/frozen-review-source-manifest.json').write_text(json.dumps(result,indent=2)+'\n')
print('Frozen inputs:',len(files))
