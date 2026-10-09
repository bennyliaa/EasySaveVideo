#!/usr/bin/env bash
set -euo pipefail
REPO=${ESV_REVIEW_REPO:-$(cd "$(dirname "$0")/../../.." && pwd)}
cd "$REPO"
OUT=${1:-$REPO/docs/m5a4a/evidence/independent-reliability}
ROOT=${ESV_TOOLCHAIN_ROOT:-$(dirname "$REPO")}
CACHE=${ESV_REVIEW_CACHE:-$ROOT/android-build-home/gradle-home/caches/modules-2/files-2.1}
JAVA=${ESV_REVIEW_JAVA:-$ROOT/esv-toolchain-recovery/jdk21-module-launchers/bin/java}
one() { find "$CACHE/$1" -name '*.jar' ! -name '*-sources.jar' ! -name '*-all.jar' | sort | head -1; }
COMPILER=$(one org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.10)
STDLIB=$(one org.jetbrains.kotlin/kotlin-stdlib/2.2.10)
SCRIPT=$(one org.jetbrains.kotlin/kotlin-script-runtime/2.2.10)
REFLECT=$(one org.jetbrains.kotlin/kotlin-reflect/1.6.10)
COROUTINES=$(one org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.9.0)
ANNOTATIONS=$(one org.jetbrains/annotations/13.0)
JUNIT=$(one junit/junit/4.13.2)
HAMCREST=$(one org.hamcrest/hamcrest-core/1.3)
JARS=("$COMPILER" "$STDLIB" "$SCRIPT" "$REFLECT" "$COROUTINES" "$ANNOTATIONS" "$JUNIT" "$HAMCREST")
mkdir -p "$OUT"
RAW=$(mktemp -d)
trap 'rm -rf -- "$RAW"' EXIT
mkdir "$RAW/classes"
mapfile -t SOURCES < <(find core-model/src/main/kotlin discovery/src/main/kotlin commons-provider/src/main/kotlin save/src/main/kotlin recording/src/main/kotlin -name '*.kt' | sort)
SOURCES+=(
 core-domain/src/main/kotlin/com/easysavevideo/core/domain/TaskStateMachine.kt
 core-domain/src/main/kotlin/com/easysavevideo/core/domain/BrowserSessionController.kt
 app/src/main/kotlin/com/easysavevideo/app/browser/BrowserInputPolicy.kt
 app/src/main/kotlin/com/easysavevideo/app/discovery/network/SystemCommonsTransport.kt
 app/src/main/kotlin/com/easysavevideo/app/discovery/network/StrictCommonsHttp.kt
 app/src/main/kotlin/com/easysavevideo/app/save/MediaTaskActivity.kt
 app/src/main/kotlin/com/easysavevideo/app/save/storage/SaveRecordCodec.kt
 app/src/main/kotlin/com/easysavevideo/app/storage/BrowserSessionCodec.kt
 app/src/test/kotlin/com/easysavevideo/app/discovery/network/SystemCommonsTransportTest.kt
 app/src/test/kotlin/com/easysavevideo/app/reliability/OrdinaryIsolationTest.kt
 discovery/src/test/kotlin/com/easysavevideo/discovery/DiscoveryTestFixtures.kt
 discovery/src/test/kotlin/com/easysavevideo/discovery/DiscoveryReliabilityMatrixTest.kt
 commons-provider/src/test/kotlin/com/easysavevideo/commons/CommonsTestSupport.kt
 commons-provider/src/test/kotlin/com/easysavevideo/commons/CommonsMetadataProviderTest.kt
 commons-provider/src/test/kotlin/com/easysavevideo/commons/CommonsFailureRecoveryTest.kt
 docs/m9a/evidence/IndependentReliabilityTest.kt
 docs/m9a/evidence/IndependentReliabilityRunner.kt
)
mapfile -t RESOURCES < <(find commons-provider/src/test/resources -type f | sort)
INPUTS=("${SOURCES[@]}" "${RESOURCES[@]}" docs/m5a4a/evidence/independent-reliability-run.sh docs/m5a4a/evidence/verify-frozen.py)
python3 docs/m5a4a/evidence/verify-frozen.py | tee "$OUT/frozen-before.log"
python3 - "$OUT/input-manifest.json" "${INPUTS[@]}" <<'PY'
from pathlib import Path
import hashlib,json,sys
frozen_path=Path('docs/m5a4a/evidence/frozen-review-source-manifest.json')
frozen={r['path']:r for r in json.loads(frozen_path.read_text())['files']}
assert len(sys.argv[2:])==len(set(sys.argv[2:]))
rows=[]
for path in sys.argv[2:]:
    data=Path(path).read_bytes()
    row={'path':path,'size':len(data),'sha256':hashlib.sha256(data).hexdigest()}
    assert row==frozen[path], 'INPUT_NOT_IDENTICAL_TO_FROZEN_SOURCE'
    rows.append(row)
Path(sys.argv[1]).write_text(json.dumps({'frozen_manifest_sha256':hashlib.sha256(frozen_path.read_bytes()).hexdigest(),'files':rows},indent=2)+'\n')
print('PASS INDEPENDENT_INPUTS_FROZEN='+str(len(rows)))
PY
toolchain() {
 local phase=$1
 sha256sum "${JARS[@]}" > "$RAW/toolchain-$phase.txt"
 python3 - "$RAW/toolchain-$phase.txt" docs/m7c/evidence/independent-final-toolchain-sha256.txt "$OUT/toolchain-$phase.json" "$phase" <<'PY'
from pathlib import Path
import json,sys
def rows(path):
    source=[line.split(maxsplit=1) for line in Path(path).read_text().splitlines() if line.strip()]
    result={Path(name).name:sha for sha,name in source}
    assert len(source)==len(result)==8
    return result
actual,admitted=map(rows,sys.argv[1:3]); assert actual==admitted, 'UNADMITTED_TOOLCHAIN'
Path(sys.argv[3]).write_text(json.dumps(actual,indent=2)+'\n')
print('PASS ADMITTED_EXACT8_'+sys.argv[4].upper())
PY
}
toolchain before
if ! "$JAVA" -cp "$COMPILER:$STDLIB:$SCRIPT:$REFLECT:$COROUTINES:$ANNOTATIONS" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
 -no-stdlib -no-reflect -jvm-target 17 -classpath "$STDLIB:$JUNIT" -d "$RAW/classes" "${SOURCES[@]}" > "$RAW/compiler" 2>&1; then
 echo 'FAIL INDEPENDENT_COMPILATION'
 # Diagnostic locations and error kinds only; do not copy source excerpts or exception messages.
 python3 - "$RAW/compiler" <<'PY'
from pathlib import Path
import re,sys
for line in Path(sys.argv[1]).read_text().splitlines():
    match=re.match(r'([^:]+\.kt):(\d+):(\d+): error:',line)
    if match: print('COMPILATION_LOCATION='+':'.join(match.groups()))
PY
 toolchain after
 python3 docs/m5a4a/evidence/verify-frozen.py | tee "$OUT/frozen-after.log"
 exit 1
fi
echo 'PASS INDEPENDENT_COMPILATION'
set +e
"$JAVA" -cp "$RAW/classes:$STDLIB:$JUNIT:$HAMCREST:commons-provider/src/test/resources" \
 com.easysavevideo.review.m9a.IndependentReliabilityRunnerKt > "$RAW/results" 2>&1
RESULT=$?
set -e
grep -E '^(PASS |FAIL |FAIL_METHOD=|LOCATION=|ORDINARY_TOTAL=|OK \()' "$RAW/results" | tee "$OUT/results.log" || true
python3 - "$OUT/results.log" "$OUT/results.json" "$RESULT" <<'PY'
from pathlib import Path
import json,re,sys
text=Path(sys.argv[1]).read_text()
total=re.search(r'^ORDINARY_TOTAL=(\d+) FAILURES=(\d+)$',text,re.M)
groups=[{'status':s,'name':n,'tests':int(c)} for s,n,c in re.findall(r'^(PASS|FAIL) (\S+) TESTS=(\d+)$',text,re.M)]
report={'status':'PASS' if total and total[2]=='0' and sys.argv[3]=='0' else 'FAIL',
        'tests':int(total[1]) if total else None,'failures':int(total[2]) if total else None,
        'process_exit':int(sys.argv[3]),'groups':groups}
Path(sys.argv[2]).write_text(json.dumps(report,indent=2)+'\n')
PY
toolchain after
cmp "$OUT/toolchain-before.json" "$OUT/toolchain-after.json"
python3 docs/m5a4a/evidence/verify-frozen.py | tee "$OUT/frozen-after.log"
python3 - "$OUT/input-manifest.json" <<'PY'
from pathlib import Path
import hashlib,json,sys
manifest=json.loads(Path(sys.argv[1]).read_text())
assert hashlib.sha256(Path('docs/m5a4a/evidence/frozen-review-source-manifest.json').read_bytes()).hexdigest()==manifest['frozen_manifest_sha256']
for row in manifest['files']:
    data=Path(row['path']).read_bytes()
    assert len(data)==row['size'] and hashlib.sha256(data).hexdigest()==row['sha256']
print('PASS INDEPENDENT_INPUTS_UNCHANGED')
PY
exit "$RESULT"
