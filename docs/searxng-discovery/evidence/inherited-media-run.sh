#!/usr/bin/env bash
set -euo pipefail
REPO=${ESV_REVIEW_REPO:-$(cd "$(dirname "$0")/../../.." && pwd)}
cd "$REPO"
OUT=${1:-$REPO/docs/searxng-discovery/evidence/runs/inherited-media-run}
[[ "$OUT" == /* && "$OUT" != / && "$OUT" != "$REPO" ]] || { echo 'Output must remain in independent-media evidence scope' >&2; exit 2; }
ROOT=${ESV_TOOLCHAIN_ROOT:-$(dirname "$REPO")}
CACHE=${ESV_REVIEW_CACHE:-$ROOT/android-build-home/gradle-home/caches/modules-2/files-2.1}
one() { find "$CACHE/$1" -name '*.jar' ! -name '*-sources.jar' ! -name '*-all.jar' | sort | head -1; }
COMPILER=$(one org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.10); STDLIB=$(one org.jetbrains.kotlin/kotlin-stdlib/2.2.10); SCRIPT=$(one org.jetbrains.kotlin/kotlin-script-runtime/2.2.10); REFLECT=$(one org.jetbrains.kotlin/kotlin-reflect); COROUTINES=$(one org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.9.0); ANNOTATIONS=$(one org.jetbrains/annotations/13.0); JUNIT=$(one junit/junit/4.13.2); HAMCREST=$(one org.hamcrest/hamcrest-core/1.3)
JAVA=${ESV_REVIEW_JAVA:-$ROOT/esv-toolchain-recovery/jdk21-module-launchers/bin/java}
mkdir -p "$OUT"
CLASSES=$(mktemp -d "$OUT/independent-media-classes.XXXXXX")
trap 'rm -rf -- "$CLASSES"' EXIT
mapfile -t SOURCES < <(find core-model/src/main/kotlin recording/src/main/kotlin save/src/main/kotlin discovery/src/main/kotlin -name '*.kt' | sort)
SOURCES+=(core-domain/src/main/kotlin/com/easysavevideo/core/domain/TaskStateMachine.kt save/src/test/kotlin/com/easysavevideo/save/SaveTestFixtures.kt app/src/main/kotlin/com/easysavevideo/app/discovery/DiscoveryOriginTracker.kt app/src/main/kotlin/com/easysavevideo/app/discovery/DiscoveryPresentationPolicy.kt app/src/main/kotlin/com/easysavevideo/app/save/MediaTaskActivity.kt app/src/main/kotlin/com/easysavevideo/app/save/MediaTaskActivityHandoff.kt app/src/main/kotlin/com/easysavevideo/app/save/MediaTaskActivityExpiry.kt app/src/main/kotlin/com/easysavevideo/app/save/MediaTaskActivitySaveLinks.kt app/src/main/kotlin/com/easysavevideo/app/save/MediaTaskActivitySaveOutcome.kt app/src/test/kotlin/com/easysavevideo/app/save/MediaTaskActivityTest.kt app/src/test/kotlin/com/easysavevideo/app/save/MediaTaskActivityHandoffTest.kt app/src/test/kotlin/com/easysavevideo/app/save/MediaTaskActivityExpiryTest.kt app/src/test/kotlin/com/easysavevideo/app/save/MediaTaskActivitySaveLinksTest.kt docs/m8b2/evidence/independent-media-review.kt)
python3 - docs/searxng-discovery/evidence/frozen-review-source-manifest.json "$OUT/independent-media-frozen-before.json" <<'PY'
from pathlib import Path
import hashlib,json,sys
m=json.loads(Path(sys.argv[1]).read_text())
for f in m['files']:
 b=Path(f['path']).read_bytes(); assert len(b)==f['size'] and hashlib.sha256(b).hexdigest()==f['sha256'], f['path']
Path(sys.argv[2]).write_text(json.dumps(m,indent=2)+'\n')
print('PASS exact frozen source manifest:',len(m['files']))
PY
sha256sum "${SOURCES[@]}" docs/searxng-discovery/evidence/inherited-media-run.sh > "$OUT/independent-media-compiled-source-sha256.txt"
sha256sum "$COMPILER" "$STDLIB" "$SCRIPT" "$REFLECT" "$COROUTINES" "$ANNOTATIONS" "$JUNIT" "$HAMCREST" > "$OUT/independent-media-toolchain-sha256.txt"
python3 - "$OUT/independent-media-toolchain-sha256.txt" docs/m7c/evidence/independent-final-toolchain-sha256.txt <<'PY'
from pathlib import Path
import json,sys
def artifacts(path):
 rows=[line.split(maxsplit=1) for line in Path(path).read_text().splitlines() if line.strip()]
 result={Path(name).name:digest for digest,name in rows}
 assert len(rows)==len(result)==8
 return result
current,admitted=map(artifacts,sys.argv[1:]); assert current==admitted
print('PASS eight previously admitted compiler/runtime/JUnit artifact SHA-256 values')
PY
"$JAVA" -cp "$COMPILER:$STDLIB:$SCRIPT:$REFLECT:$COROUTINES:$ANNOTATIONS" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -classpath "$STDLIB:$JUNIT" -d "$CLASSES" "${SOURCES[@]}"
set +e
"$JAVA" -cp "$CLASSES:$STDLIB:$JUNIT:$HAMCREST" org.junit.runner.JUnitCore com.easysavevideo.app.save.IndependentMediaReviewTest com.easysavevideo.app.save.MediaTaskActivityTest com.easysavevideo.app.save.MediaTaskActivityHandoffTest com.easysavevideo.app.save.MediaTaskActivityExpiryTest com.easysavevideo.app.save.MediaTaskActivitySaveLinksTest | tee "$OUT/independent-media-results.log"
RESULT=${PIPESTATUS[0]}
set -e
sha256sum --check "$OUT/independent-media-compiled-source-sha256.txt" > "$OUT/independent-media-source-unchanged.log"
python3 - "$OUT/independent-media-frozen-before.json" "$OUT/independent-media-frozen-after.log" <<'PY'
from pathlib import Path
import hashlib,json,sys
m=json.loads(Path(sys.argv[1]).read_text())
for f in m['files']:
 b=Path(f['path']).read_bytes(); assert len(b)==f['size'] and hashlib.sha256(b).hexdigest()==f['sha256'], f['path']
Path(sys.argv[2]).write_text('PASS exact frozen manifest: '+str(len(m['files']))+' files unchanged before/after\n')
PY
exit "$RESULT"
