#!/usr/bin/env bash
set -euo pipefail
REPO=$(cd "$(dirname "$0")/../../.." && pwd)
cd "$REPO"
ROOT=${ESV_TOOLCHAIN_ROOT:-$(dirname "$REPO")}
MODE=frozen
if [[ ${1:-} == --provisional ]]; then MODE=provisional; shift; fi
OUT=${1:-$REPO/docs/m5a4b/evidence/independent-folder-run}
CACHE=${ESV_REVIEW_CACHE:-$ROOT/android-build-home/gradle-home/caches/modules-2/files-2.1}
one() { find "$CACHE/$1" -name '*.jar' ! -name '*-sources.jar' ! -name '*-all.jar' | sort | head -1; }
COMPILER=$(one org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.10); STDLIB=$(one org.jetbrains.kotlin/kotlin-stdlib/2.2.10); SCRIPT=$(one org.jetbrains.kotlin/kotlin-script-runtime/2.2.10); REFLECT=$(one org.jetbrains.kotlin/kotlin-reflect); COROUTINES=$(one org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.9.0); ANNOTATIONS=$(one org.jetbrains/annotations/13.0); JUNIT=$(one junit/junit/4.13.2); HAMCREST=$(one org.hamcrest/hamcrest-core/1.3)
JAVA=${ESV_REVIEW_JAVA:-$ROOT/esv-toolchain-recovery/jdk21-module-launchers/bin/java}
mkdir -p "$OUT"
if [[ "$MODE" == frozen ]]; then python3 docs/m5a4b/evidence/verify-frozen.py > "$OUT/full-manifest-before.log"; fi
CLASSES=$(mktemp -d "$OUT/classes.XXXXXX")
trap 'rm -rf -- "$CLASSES"' EXIT
SOURCES=(library/src/main/kotlin/com/easysavevideo/library/LibraryModels.kt
 library/src/main/kotlin/com/easysavevideo/library/LibraryPolicies.kt
 library/src/main/kotlin/com/easysavevideo/library/export/ExportContracts.kt
 library/src/main/kotlin/com/easysavevideo/library/export/ExportCoordinator.kt
 library/src/main/kotlin/com/easysavevideo/library/destination/OrdinaryDestinationContracts.kt
 library/src/main/kotlin/com/easysavevideo/library/destination/OrdinaryDestinationRouter.kt
 app/src/main/kotlin/com/easysavevideo/app/library/destination/OrdinaryDestinationSelection.kt
 app/src/main/kotlin/com/easysavevideo/app/library/destination/FolderSelectionGate.kt
 app/src/main/kotlin/com/easysavevideo/app/library/destination/FolderSettingsOrder.kt
 app/src/main/kotlin/com/easysavevideo/app/library/destination/FolderPickerRequestCodes.kt
 app/src/main/kotlin/com/easysavevideo/app/library/destination/AndroidFolderGrantRegistry.kt
 app/src/main/kotlin/com/easysavevideo/app/library/destination/AndroidFolderPickerHost.kt
 app/src/main/kotlin/com/easysavevideo/app/library/export/AndroidExportPort.kt
 docs/m5a4b/evidence/FolderReview*.kt
 docs/m5a4b/evidence/IndependentFolderReviewTest.kt)
python3 - "$OUT" "$MODE" "${SOURCES[@]}" docs/m5a4b/evidence/run-independent-folder-review.sh <<'PY_BEFORE'
from pathlib import Path
import hashlib,json,sys
out=Path(sys.argv[1]); mode=sys.argv[2]; needed=sys.argv[3:]; path=Path('docs/m5a4b/evidence/frozen-review-source-manifest.json')
if mode=='frozen':
    raw=path.read_bytes(); manifest=json.loads(raw)
else:
    # A provisional run is intentionally not a completed candidate/aggregate acceptance.
    prior=json.loads(Path('docs/m5a4a/evidence/frozen-review-source-manifest.json').read_text())
    paths={f['path'] for f in prior['files']} | set(needed)
    paths.update(str(p) for p in Path('docs/m5a4b/evidence').glob('*') if p.suffix in ('.kt','.sh','.py'))
    for base in ('app/src/main/kotlin/com/easysavevideo/app/library/destination',
                 'app/src/test/kotlin/com/easysavevideo/app/library/destination',
                 'library/src/main/kotlin/com/easysavevideo/library/destination',
                 'library/src/test/kotlin/com/easysavevideo/library/destination'):
        paths.update(str(p) for p in Path(base).rglob('*') if p.is_file())
    paths.update(str(p) for p in Path('scripts').glob('*folder-destination*') if p.is_file())
    manifest={'scope':'PROVISIONAL ONLY', 'files':[]}
    for name in sorted(paths):
        p=Path(name); data=p.read_bytes(); manifest['files'].append({'path':name,'size':len(data),'sha256':hashlib.sha256(data).hexdigest(),'executable':bool(p.stat().st_mode&0o111)})
    raw=(json.dumps(manifest,indent=2)+'\n').encode()
indexed={f['path']:f for f in manifest['files']}
assert len(indexed)==len(manifest['files'])
assert set(needed)<=set(indexed),('Compiled inputs absent',set(needed)-set(indexed))
for f in manifest['files']:
    p=Path(f['path']); assert p.is_file() and not p.is_symlink(); data=p.read_bytes()
    assert len(data)==f['size'] and hashlib.sha256(data).hexdigest()==f['sha256'],f['path']
    assert bool(p.stat().st_mode&0o111)==f['executable'],f['path']
(out/'frozen-before.json').write_bytes(raw)
(out/'frozen-before.log').write_text(f'PASS {mode}: exact bytes/modes for {len(indexed)} inputs; compiled membership verified\n')
print(f'Independent folder review mode: {mode}; full checked snapshot inputs: {len(indexed)}')
PY_BEFORE
sha256sum "${SOURCES[@]}" docs/m5a4b/evidence/run-independent-folder-review.sh > "$OUT/source-sha256.txt"
sha256sum "$COMPILER" "$STDLIB" "$SCRIPT" "$REFLECT" "$COROUTINES" "$ANNOTATIONS" "$JUNIT" "$HAMCREST" > "$OUT/toolchain-sha256.txt"
python3 - "$OUT/toolchain-sha256.txt" docs/m7c/evidence/independent-final-toolchain-sha256.txt > "$OUT/toolchain-match.log" <<'PY_TOOLCHAIN'
from pathlib import Path
import json,sys
def artifacts(path):
    rows=[line.split(maxsplit=1) for line in Path(path).read_text().splitlines() if line.strip()]
    values={Path(name).name:digest for digest,name in rows}
    assert len(rows)==len(values)==8,'Expected eight exact trusted host toolchain artifacts'
    return values
assert artifacts(sys.argv[1])==artifacts(sys.argv[2]),'Toolchain differs from trusted exact artifact hashes'
print(json.dumps({'result':'PASS','exact_artifacts':8,'reference':sys.argv[2]},indent=2))
PY_TOOLCHAIN
"$JAVA" -cp "$COMPILER:$STDLIB:$SCRIPT:$REFLECT:$COROUTINES:$ANNOTATIONS" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -classpath "$STDLIB:$JUNIT" -d "$CLASSES" "${SOURCES[@]}" 2>&1 | tee "$OUT/compile.log"
"$JAVA" -cp "$CLASSES:$STDLIB:$JUNIT:$HAMCREST" org.junit.runner.JUnitCore com.easysavevideo.app.library.destination.IndependentFolderReviewTest | tee "$OUT/results.log"
sha256sum --check "$OUT/source-sha256.txt" > "$OUT/source-unchanged.log"
python3 - "$OUT" "$MODE" > "$OUT/frozen-after.log" <<'PY_AFTER'
from pathlib import Path
import hashlib,json,sys
before=(Path(sys.argv[1])/'frozen-before.json').read_bytes()
if sys.argv[2]=='frozen': assert Path('docs/m5a4b/evidence/frozen-review-source-manifest.json').read_bytes()==before,'Manifest changed during tests'
manifest=json.loads(before)
for f in manifest['files']:
    p=Path(f['path']); assert p.is_file() and not p.is_symlink(); data=p.read_bytes()
    assert len(data)==f['size'] and hashlib.sha256(data).hexdigest()==f['sha256'],f['path']
    assert bool(p.stat().st_mode&0o111)==f['executable'],f['path']
print('PASS unchanged full snapshot bytes and executable modes:',len(manifest['files']),'; mode:',sys.argv[2])
PY_AFTER

if [[ "$MODE" == frozen ]]; then python3 docs/m5a4b/evidence/verify-frozen.py > "$OUT/full-manifest-after.log"; fi
