#!/usr/bin/env bash
set -euo pipefail
REPO=$(cd "$(dirname "$0")/../../.." && pwd)
cd "$REPO"
ROOT=${ESV_TOOLCHAIN_ROOT:-$(dirname "$REPO")}
OUT=${1:-$REPO/docs/m5a4a/evidence/independent-destination-run}
CACHE=${ESV_REVIEW_CACHE:-$ROOT/android-build-home/gradle-home/caches/modules-2/files-2.1}
one() { find "$CACHE/$1" -name '*.jar' ! -name '*-sources.jar' ! -name '*-all.jar' | sort | head -1; }
COMPILER=$(one org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.10); STDLIB=$(one org.jetbrains.kotlin/kotlin-stdlib/2.2.10); SCRIPT=$(one org.jetbrains.kotlin/kotlin-script-runtime/2.2.10); REFLECT=$(one org.jetbrains.kotlin/kotlin-reflect); COROUTINES=$(one org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.9.0); ANNOTATIONS=$(one org.jetbrains/annotations/13.0); JUNIT=$(one junit/junit/4.13.2); HAMCREST=$(one org.hamcrest/hamcrest-core/1.3)
JAVA=${ESV_REVIEW_JAVA:-$ROOT/esv-toolchain-recovery/jdk21-module-launchers/bin/java}
mkdir -p "$OUT"
CLASSES=$(mktemp -d "$OUT/classes.XXXXXX")
trap 'rm -rf -- "$CLASSES"' EXIT
SOURCES=(library/src/main/kotlin/com/easysavevideo/library/LibraryModels.kt
 library/src/main/kotlin/com/easysavevideo/library/LibraryPolicies.kt
 library/src/main/kotlin/com/easysavevideo/library/export/ExportContracts.kt
 library/src/main/kotlin/com/easysavevideo/library/export/ExportCoordinator.kt
 library/src/main/kotlin/com/easysavevideo/library/destination/OrdinaryDestinationContracts.kt
 library/src/main/kotlin/com/easysavevideo/library/destination/OrdinaryDestinationRouter.kt
 app/src/main/kotlin/com/easysavevideo/app/library/destination/OrdinaryDestinationSelection.kt
 docs/m5a4a/evidence/IndependentDestinationReviewTest.kt)
python3 - "$OUT" "${SOURCES[@]}" docs/m5a4a/evidence/run-independent-destination-review.sh <<'PY_FROZEN'
from pathlib import Path
import hashlib,json,sys
out=Path(sys.argv[1]); path=Path('docs/m5a4a/evidence/frozen-review-source-manifest.json')
raw=path.read_bytes(); manifest=json.loads(raw); indexed={f['path']:f for f in manifest['files']}
assert len(indexed)==len(manifest['files'])
assert all(p in indexed for p in sys.argv[2:]), 'Compiled input missing from frozen manifest'
for f in manifest['files']:
 data=Path(f['path']).read_bytes()
 assert len(data)==f['size'] and hashlib.sha256(data).hexdigest()==f['sha256'],f['path']
(out/'frozen-before.json').write_bytes(raw)
(out/'frozen-before.log').write_text('PASS exact frozen input hashes and compiled membership: '+str(len(indexed))+'\n')
PY_FROZEN
sha256sum "${SOURCES[@]}" docs/m5a4a/evidence/run-independent-destination-review.sh > "$OUT/source-sha256.txt"
sha256sum "$COMPILER" "$STDLIB" "$SCRIPT" "$REFLECT" "$COROUTINES" "$ANNOTATIONS" "$JUNIT" "$HAMCREST" > "$OUT/toolchain-sha256.txt"
python3 - "$OUT/toolchain-sha256.txt" docs/m7c/evidence/independent-final-toolchain-sha256.txt > "$OUT/toolchain-match.log" <<'PY_TOOLCHAIN'
from pathlib import Path
import json,sys
def artifacts(path):
 rows=[line.split(maxsplit=1) for line in Path(path).read_text().splitlines() if line.strip()]
 values={Path(name).name:digest for digest,name in rows}
 assert len(rows)==len(values)==8,'Expected exactly eight distinct toolchain artifacts'
 return values
assert artifacts(sys.argv[1])==artifacts(sys.argv[2]),'Toolchain differs from exact admitted artifacts'
print(json.dumps({'result':'PASS','artifacts_matched':8,'reference':sys.argv[2],'comparison':'artifact basename and exact SHA-256'},indent=2))
PY_TOOLCHAIN
"$JAVA" -cp "$COMPILER:$STDLIB:$SCRIPT:$REFLECT:$COROUTINES:$ANNOTATIONS" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -classpath "$STDLIB:$JUNIT" -d "$CLASSES" "${SOURCES[@]}"
"$JAVA" -cp "$CLASSES:$STDLIB:$JUNIT:$HAMCREST" org.junit.runner.JUnitCore com.easysavevideo.app.library.destination.IndependentDestinationReviewTest | tee "$OUT/results.log"
sha256sum --check "$OUT/source-sha256.txt" > "$OUT/source-unchanged.log"
python3 - "$OUT" > "$OUT/frozen-after.log" <<'PY_AFTER'
from pathlib import Path
import hashlib,json,sys
before=(Path(sys.argv[1])/'frozen-before.json').read_bytes()
assert Path('docs/m5a4a/evidence/frozen-review-source-manifest.json').read_bytes()==before,'Frozen manifest changed during test'
manifest=json.loads(before)
for f in manifest['files']:
 data=Path(f['path']).read_bytes()
 assert len(data)==f['size'] and hashlib.sha256(data).hexdigest()==f['sha256'],f['path']
print('PASS unchanged manifest and exact frozen hashes:',len(manifest['files']))
PY_AFTER
