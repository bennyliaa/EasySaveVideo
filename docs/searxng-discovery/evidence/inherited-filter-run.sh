#!/usr/bin/env bash
set -euo pipefail
REPO=${ESV_REVIEW_REPO:-$(cd "$(dirname "$0")/../../.." && pwd)}
cd "$REPO"
OUT=${1:-${ESV_EVIDENCE_DIR:-$REPO/docs/searxng-discovery/evidence/runs/independent-filter-run}}
[[ "$OUT" == /* && "$OUT" != / && "$OUT" != "$REPO" ]] || { echo "Absolute independent output directory required" >&2; exit 2; }
ROOT=${ESV_TOOLCHAIN_ROOT:-$(dirname "$REPO")}
CACHE=${ESV_REVIEW_CACHE:-$ROOT/android-build-home/gradle-home/caches/modules-2/files-2.1}
one() { find "$CACHE/$1" -name '*.jar' ! -name '*-sources.jar' ! -name '*-all.jar' | sort | head -1; }
COMPILER=$(one org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.10); STDLIB=$(one org.jetbrains.kotlin/kotlin-stdlib/2.2.10); SCRIPT=$(one org.jetbrains.kotlin/kotlin-script-runtime/2.2.10); REFLECT=$(one org.jetbrains.kotlin/kotlin-reflect/1.6.10); COROUTINES=$(one org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.9.0); ANNOTATIONS=$(one org.jetbrains/annotations/13.0); JUNIT=$(one junit/junit/4.13.2); HAMCREST=$(one org.hamcrest/hamcrest-core/1.3)
JAVA=${ESV_REVIEW_JAVA:-$ROOT/esv-toolchain-recovery/jdk21-module-launchers/bin/java}
mkdir -p "$OUT"
CLASSES=$(mktemp -d "$OUT/independent-filter-classes.XXXXXX")
trap 'rm -rf -- "$CLASSES"' EXIT
mapfile -t SOURCES < <(find core-model/src/main/kotlin discovery/src/main/kotlin commons-provider/src/main/kotlin -name '*.kt' | sort)
SOURCES+=(app/src/main/kotlin/com/easysavevideo/app/storage/DiscoveryCheckpointCodec.kt
  app/src/main/kotlin/com/easysavevideo/app/discovery/DiscoveryPresentationPolicy.kt
  app/src/main/kotlin/com/easysavevideo/app/discovery/DiscoveryFilterPresentationPolicy.kt
  docs/m8b3/evidence/independent-filter-review.kt)
python3 - "$REPO/docs/searxng-discovery/evidence/frozen-review-source-manifest.json" "$OUT/independent-filter-frozen-before.json" <<'PY_FROZEN'
from pathlib import Path
import hashlib,json,sys
m=json.loads(Path(sys.argv[1]).read_text())
for f in m['files']:
 b=Path(f['path']).read_bytes(); assert len(b)==f['size'] and hashlib.sha256(b).hexdigest()==f['sha256'], f['path']
Path(sys.argv[2]).write_text(json.dumps(m,indent=2)+'\n')
print('PASS frozen source manifest exact hashes:',len(m['files']))
PY_FROZEN
sha256sum "${SOURCES[@]}" app/src/main/kotlin/com/easysavevideo/app/discovery/AndroidDiscoveryController.kt app/src/main/kotlin/com/easysavevideo/app/ui/DiscoveryScreen.kt app/src/debug/kotlin/com/easysavevideo/app/discovery/BuildDiscovery.kt app/src/release/kotlin/com/easysavevideo/app/discovery/BuildDiscovery.kt docs/searxng-discovery/evidence/inherited-filter-run.sh docs/m7c/evidence/independent-final-toolchain-sha256.txt > "$OUT/independent-compiled-source-sha256.txt"
sha256sum "$COMPILER" "$STDLIB" "$SCRIPT" "$REFLECT" "$COROUTINES" "$ANNOTATIONS" "$JUNIT" "$HAMCREST" > "$OUT/independent-toolchain-sha256.txt"
python3 - "$OUT/independent-toolchain-sha256.txt" "$REPO/docs/m7c/evidence/independent-final-toolchain-sha256.txt" > "$OUT/independent-toolchain-match.log" <<'PY_CHECK'
from pathlib import Path
import json,sys
def artifacts(path):
 rows=[line.split(maxsplit=1) for line in Path(path).read_text().splitlines() if line.strip()]
 result={Path(name).name:digest for digest,name in rows}
 assert len(rows)==len(result)==8, 'Expected exactly eight distinct toolchain artifacts'
 return result
current,admitted=map(artifacts,sys.argv[1:])
assert current==admitted, 'Standalone compiler/runtime/JUnit hashes differ from the prior M7-C record'
print(json.dumps({'result':'PASS','artifacts_matched':len(current),'reference':'docs/m7c/evidence/independent-final-toolchain-sha256.txt','comparison':'artifact basename and exact SHA-256'},indent=2))
PY_CHECK
"$JAVA" -cp "$COMPILER:$STDLIB:$SCRIPT:$REFLECT:$COROUTINES:$ANNOTATIONS" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -classpath "$STDLIB:$JUNIT" -d "$CLASSES" "${SOURCES[@]}" 2>&1 | tee "$OUT/independent-compiler.log"
set +e
"$JAVA" -cp "$CLASSES:$STDLIB:$JUNIT:$HAMCREST" org.junit.runner.JUnitCore com.easysavevideo.discovery.IndependentFilterReviewTest | tee "$OUT/independent-results.log"
RESULT=${PIPESTATUS[0]}
set -e
sha256sum --check "$OUT/independent-compiled-source-sha256.txt" > "$OUT/independent-source-unchanged.log"
python3 - "$OUT/independent-filter-frozen-before.json" "$OUT/independent-filter-frozen-after.log" <<'PY_FROZEN'
from pathlib import Path
import hashlib,json,sys
m=json.loads(Path(sys.argv[1]).read_text())
for f in m['files']:
 b=Path(f['path']).read_bytes(); assert len(b)==f['size'] and hashlib.sha256(b).hexdigest()==f['sha256'], f['path']
Path(sys.argv[2]).write_text('PASS exact frozen manifest: '+str(len(m['files']))+' files unchanged before/after\n')
PY_FROZEN
exit "$RESULT"
