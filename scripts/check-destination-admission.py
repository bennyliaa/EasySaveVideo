#!/usr/bin/env python3
"""Current A4a scope. Historical milestone gates/evidence remain immutable."""
from pathlib import Path
import hashlib,json,os,subprocess
root=Path(__file__).resolve().parents[1]
base='a3a028d1e1de0ca20303ac7d14e4ed400e32a176'
out=Path(os.environ.get('ESV_EVIDENCE_DIR',root/'docs/m5a4a/evidence'));out.mkdir(parents=True,exist_ok=True)
changed={
'app/src/main/kotlin/com/easysavevideo/app/MainActivity.kt',
'app/src/main/kotlin/com/easysavevideo/app/ui/BrowserScreen.kt',
'app/src/main/kotlin/com/easysavevideo/app/save/SaveTaskScreen.kt',
'app/src/main/kotlin/com/easysavevideo/app/save/AndroidSaveRuntime.kt',
'app/src/main/kotlin/com/easysavevideo/app/save/storage/AndroidSaveStorage.kt',
'app/src/main/kotlin/com/easysavevideo/app/editor/AndroidEditRuntime.kt',
'app/src/main/kotlin/com/easysavevideo/app/editor/storage/AndroidEditStorage.kt',
'app/src/main/kotlin/com/easysavevideo/app/recording/AndroidSegmentRecordingRuntime.kt',
'app/src/main/kotlin/com/easysavevideo/app/recording/RecordingScreen.kt',
'app/src/main/kotlin/com/easysavevideo/app/library/export/AndroidExportRuntime.kt',
}
new_roots=('app/src/main/kotlin/com/easysavevideo/app/library/destination/',
'app/src/test/kotlin/com/easysavevideo/app/library/destination/',
'app/src/test/resources/destination/',
'library/src/main/kotlin/com/easysavevideo/library/destination/',
'library/src/test/kotlin/com/easysavevideo/library/destination/', 'docs/m5a4a/')
new_scripts={'scripts/check-destination-admission.py','scripts/verify-destination-sqlite.py',
'scripts/verify-a4a-library-sqlite.py','scripts/verify-a4a-export-sqlite.py','scripts/verify-a4a-editor-sqlite.py',
'scripts/verify-destination-workspace.sh'}
def git(*args): return subprocess.check_output(['git',*args],cwd=root)
rows={}
for line in git('ls-tree','-r',base).decode().splitlines():
 meta,path=line.split('\t',1); mode,kind,oid=meta.split();rows[path]=(mode,oid)
for path,(mode,oid) in rows.items():
 p=root/path;assert p.is_file(),('MISSING_BASELINE',path)
 assert bool(p.stat().st_mode & 0o111)==(mode=='100755'),('MODE_CHANGED',path)
 if path not in changed: assert git('hash-object',str(p)).decode().strip()==oid,('OUT_OF_SCOPE',path)
for path in git('ls-files','--cached','--others','--exclude-standard').decode().splitlines():
 if path not in rows: assert path in new_scripts or path.startswith(new_roots),('UNEXPECTED_NEW_PATH',path)
assert set(git('diff','--name-only',base).decode().splitlines())<=changed|new_scripts|{p for p in git('ls-files').decode().splitlines() if p.startswith(new_roots)}
app=root/'app/src/main/kotlin/com/easysavevideo/app'
owner=(app/'library/destination/AndroidOrdinaryDestinationRuntime.kt').read_text()
router=(root/'library/src/main/kotlin/com/easysavevideo/library/destination/OrdinaryDestinationRouter.kt').read_text()
schema=(app/'library/destination/OrdinaryDestinationSchema.kt').read_text()
assert 'UUID.randomUUID().toString()' in owner and 'storage.destinations.admit(id, choice)' in owner
assert owner.index('storage.destinations.admit(id, choice)') < owner.index('selection.accepted(admitted)')
assert 'preference' not in router.split('class OrdinaryDestinationRouter',1)[1]
assert 'journal.get(admission.creationId) != admission' in router and 'found.phase != ExportPhase.PREPARED' in router
assert 'firstFailure?.let { throw it }' in router
for name in ['MainActivity.kt','library/destination/OrdinaryDestinationUi.kt']:
 text=(app/name).read_text()
 assert 'copyForCreation' not in text
assert 'OrdinaryDestinationChoice.ALBUM_COPY' in (app/'library/destination/OrdinaryDestinationUi.kt').read_text()
assert 'setPreference(value)' in owner and 'fun updatePreference' in owner
assert 'source_item' in schema and 'operation_id' in schema
for path in list((app/'library/destination').glob('*.kt'))+list((root/'library/src/main/kotlin/com/easysavevideo/library/destination').glob('*.kt')):
 text=path.read_text()
 for forbidden in ('Log.','println(','printStackTrace(','deleteOriginal','ACTION_OPEN_DOCUMENT_TREE','takePersistableUriPermission','java.net.'):
  assert forbidden not in text,(path.name,forbidden)
report={'scope':'M5_A4A_ORDINARY_DESTINATION_ONLY','baseline':base,'protected_baseline_files':len(rows)-len(changed),'permitted_baseline_edits':sorted(changed),
'unchanged':'All other baseline files and executable modes, including dependencies, exact74 admission, manifests, distribution/payment, Save codec/core/native capture, Vault and M7-D',
'ordinary_destination_source_guards':'PASS','android_device_media_store_acceptance':'NOT_RUN','vault_export':'NOT_IMPLEMENTED'}
(out/'destination-admission.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2))
