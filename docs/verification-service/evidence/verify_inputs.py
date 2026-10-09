#!/usr/bin/env python3
"""Explicit source-only scope and frozen-input verifier for optional verification development."""
from pathlib import Path
import argparse, hashlib, json, os, stat, subprocess
import xml.etree.ElementTree as ET
ROOT = Path(__file__).resolve().parents[3]
BASE = '32747b7c9e1be81ac5df1cc4a9a1de81650c33c4'
DOC = 'docs/verification-service/'
OUT = DOC + 'evidence/runs/'
MANIFEST = DOC + 'evidence/frozen-inputs.json'
EDITS = {'AGENTS.md', 'README.md', 'app/build.gradle.kts', 'settings.gradle.kts',
 'app/src/main/kotlin/com/easysavevideo/app/discovery/AndroidDiscoveryController.kt',
 'app/src/main/kotlin/com/easysavevideo/app/ui/DiscoveryScreen.kt',
 'discovery/src/main/kotlin/com/easysavevideo/discovery/ValidationPipeline.kt'}
NEW = ('verification-service/', 'verification-client/', DOC,
 'app/src/main/kotlin/com/easysavevideo/app/discovery/Verification',
 'app/src/main/kotlin/com/easysavevideo/app/discovery/network/SystemVerification',
 'app/src/main/kotlin/com/easysavevideo/app/discovery/network/StrictVerification',
 'app/src/main/kotlin/com/easysavevideo/app/ui/VerificationServiceSettings.kt',
 'app/src/test/kotlin/com/easysavevideo/app/discovery/Verification',
 'app/src/test/kotlin/com/easysavevideo/app/discovery/network/SystemVerification',
 'app/src/test/kotlin/com/easysavevideo/app/discovery/network/StrictVerification',
 'discovery/src/test/kotlin/com/easysavevideo/discovery/OptionalValidationPipelineTest.kt',
 'scripts/verify-verification-service-workspace.sh')
def git(*args): return subprocess.check_output(['git', *args], cwd=ROOT)
def scope():
 rows = {}
 for entry in git('ls-tree', '-rz', BASE).split(b'\0'):
  if not entry: continue
  metadata, name = entry.split(b'\t',1); mode, kind, oid = metadata.decode().split(); path=name.decode()
  assert kind=='blob' and mode in ('100644','100755')
  file=ROOT/path; assert file.is_file() and not file.is_symlink(), path
  data=file.read_bytes(); actual=hashlib.sha1(b'blob '+str(len(data)).encode()+b'\0'+data).hexdigest()
  assert actual==oid or path in EDITS, ('IMMUTABLE_BASELINE_MODIFIED',path)
  assert bool(file.stat().st_mode&0o111)==(mode=='100755'),path
  rows[path]=True
 current=set(x.decode() for x in git('ls-files','-z','--cached','--others','--exclude-standard').split(b'\0') if x)
 assert all(p.startswith(NEW) for p in current-rows.keys()),sorted(current-rows.keys())
 for file in (ROOT/'verification-client/src/main').rglob('*.kt'):
  for line in file.read_text().splitlines():
   assert not (line.startswith('import ') and any(token in line for token in ('android.', 'androidx.', 'scrapling', 'playwright', 'org.python', 'com.android.billing'))), ('CLIENT_BOUNDARY',str(file))
 result=[]
 for path in sorted(current):
  if path==MANIFEST: continue
  file=ROOT/path
  if path.startswith(OUT):
   assert not file.is_symlink() and file.is_file() and not file.stat().st_mode&0o111, ('UNSAFE_OUTPUT',path)
   assert file.suffix not in ('.py','.sh','.kt','.java','.js','.mjs','.sql','.jar','.so','.whl','.apk','.aab'),('INPUT_IN_OUTPUTS',path)
   continue
  assert file.resolve().is_relative_to(ROOT) and not file.is_symlink() and stat.S_ISREG(file.stat().st_mode),path
  if path.startswith(('verification-service/','verification-client/')):
   assert file.suffix in ('.py','.md','.txt','.json','.html','.js','.sh','.kt','.kts','.toml','.lock') or file.name=='.gitignore',('SOURCE_ONLY_EXTENSION',path)
   assert '/.venv/' not in path and not file.read_bytes().startswith((b'\x7fELF',b'MZ',b'PK\x03\x04')),('RUNTIME_BINARY_EXCLUDED',path)
  data=file.read_bytes(); result.append(dict(path=path,bytes=len(data),sha256=hashlib.sha256(data).hexdigest(),executable=bool(file.stat().st_mode&0o111)))
 return result
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--freeze',type=int);p.add_argument('--service-tests',type=int);p.add_argument('--record');a=p.parse_args()
 rows=scope()
 if a.freeze is not None:
  assert a.freeze>2184 and a.service_tests is not None and a.service_tests>0
  baseline=json.loads((ROOT/'docs/commons-unicode/evidence/final/junit-summary.json').read_text())
  modules={}
  for module in ('core-model','core-domain','ui-theme','discovery','commons-provider','searxng-provider','verification-client','save','recording','library','hls','app'):
   folder=ROOT/module/'build/test-results'/('testDebugUnitTest' if module=='app' else 'test')
   counts={key:0 for key in ('tests','failures','errors','skipped')}
   for report in folder.glob('TEST-*.xml'):
    record=ET.parse(report).getroot()
    for key in counts: counts[key]+=int(record.get(key))
   assert counts['failures']==counts['errors']==counts['skipped']==0, (module,counts)
   assert counts['tests']>=baseline.get(module,{}).get('tests',0),(module,counts)
   modules[module]=counts
  assert sum(x['tests'] for x in modules.values())==a.freeze,modules
  (ROOT/MANIFEST).write_text(json.dumps(dict(baseline=BASE,expected_jvm_tests=a.freeze,expected_jvm_by_module=modules,expected_service_tests=a.service_tests,files=rows),indent=2)+'\n')
 else:
  data=json.loads((ROOT/MANIFEST).read_text());assert data['baseline']==BASE;assert data['files']==rows,'FROZEN_INPUT_CHANGED'
  if a.record: Path(a.record).write_text(json.dumps(dict(manifest_sha256=hashlib.sha256((ROOT/MANIFEST).read_bytes()).hexdigest(),files=len(rows),expected_jvm_tests=data['expected_jvm_tests']),indent=2)+'\n')
  print(json.dumps(dict(scope='OPTIONAL_VERIFICATION_SOURCE_ONLY',files=len(rows),expected_jvm_tests=data['expected_jvm_tests'],immutable_baseline='PASS')))
