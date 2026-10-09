#!/usr/bin/env bash
set -euo pipefail
REPO=${ESV_REVIEW_REPO:-$(cd "$(dirname "$0")/../../.." && pwd)}
cd "$REPO"
OUT=${1:-$REPO/docs/m8b4/evidence/independent-os-run}
[[ "$OUT" == /* && "$OUT" != / && "$OUT" != "$REPO" ]] || exit 2
ROOT=${ESV_TOOLCHAIN_ROOT:-$(dirname "$REPO")}
CACHE=${ESV_REVIEW_CACHE:-$ROOT/android-build-home/gradle-home/caches/modules-2/files-2.1}
one() { find "$CACHE/$1" -name '*.jar' ! -name '*-sources.jar' ! -name '*-all.jar' | sort | head -1; }
COMPILER=$(one org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.10); STDLIB=$(one org.jetbrains.kotlin/kotlin-stdlib/2.2.10); SCRIPT=$(one org.jetbrains.kotlin/kotlin-script-runtime/2.2.10); REFLECT=$(one org.jetbrains.kotlin/kotlin-reflect/1.6.10); COROUTINES=$(one org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.9.0); ANNOTATIONS=$(one org.jetbrains/annotations/13.0); JUNIT=$(one junit/junit/4.13.2); HAMCREST=$(one org.hamcrest/hamcrest-core/1.3)
JAVA=${ESV_REVIEW_JAVA:-$ROOT/esv-toolchain-recovery/jdk21-module-launchers/bin/java}
mkdir -p "$OUT"
CLASSES=$(mktemp -d "$OUT/independent-classes.XXXXXX")
trap 'rm -rf -- "$CLASSES"' EXIT
mapfile -t SOURCES < <(find core-model/src/main/kotlin discovery/src/main/kotlin -name '*.kt' | sort)
SOURCES+=(app/src/main/kotlin/com/easysavevideo/app/discovery/DiscoveryOsPowerBridge.kt docs/m8b4/evidence/independent-os-bridge-review.kt)
SOURCES+=(app/src/main/kotlin/com/easysavevideo/app/discovery/DiscoveryLatestDelivery.kt docs/m8b4/evidence/independent-delivery-review.kt)
TESTS=(com.easysavevideo.app.discovery.IndependentOsBridgeReviewTest com.easysavevideo.app.discovery.IndependentDeliveryReviewTest)
[[ -f docs/m8b4/evidence/independent-os-api-review.kt && -d docs/m8b4/evidence/independent-api-doubles ]] || { echo 'Required independent API-double coverage absent' >&2; exit 2; }
if [[ -f docs/m8b4/evidence/independent-os-api-review.kt ]]; then
 SOURCES+=(app/src/main/kotlin/com/easysavevideo/app/discovery/AndroidDiscoveryOsSignalSource.kt docs/m8b4/evidence/independent-os-api-review.kt)
 while IFS= read -r file; do SOURCES+=("$file"); done < <(find docs/m8b4/evidence/independent-api-doubles -name '*.kt' | sort)
 TESTS+=(com.easysavevideo.app.discovery.IndependentOsApiReviewTest)
fi
STATIC=(app/src/main/kotlin/com/easysavevideo/app/discovery/AndroidDiscoveryController.kt
 app/src/main/kotlin/com/easysavevideo/app/ui/DiscoveryPowerControls.kt
 app/src/main/kotlin/com/easysavevideo/app/discovery/DiscoveryPowerPresentation.kt
 app/src/debug/kotlin/com/easysavevideo/app/discovery/BuildDiscoveryPower.kt
 app/src/release/kotlin/com/easysavevideo/app/discovery/BuildDiscoveryPower.kt
 docs/m8b4/evidence/independent-static-review.py
 docs/m8b4/evidence/independent-frozen-check.py
 docs/m8b4/evidence/independent-api-review.md
 docs/m7c/evidence/independent-final-toolchain-sha256.txt)
MANIFEST=docs/m8b4/evidence/frozen-review-source-manifest.json
if [[ "${ESV_INDEPENDENT_PROVISIONAL:-0}" == 1 ]]; then
 echo 'PROVISIONAL: shared freeze not required for this exploratory run' > "$OUT/independent-freeze-mode.log"
else
 [[ -f "$MANIFEST" ]] || { echo 'Frozen manifest required; set ESV_INDEPENDENT_PROVISIONAL=1 only for exploratory pre-freeze runs' >&2; exit 2; }
 python3 docs/m8b4/evidence/independent-frozen-check.py before "$MANIFEST" "$OUT/independent-frozen-before.json" "${SOURCES[@]}" "${STATIC[@]}" docs/m8b4/evidence/independent-os-run.sh > "$OUT/independent-frozen-before.log"
fi
python3 docs/m8b4/evidence/independent-static-review.py "$OUT/independent-source-review.json" > "$OUT/independent-source-review.log"
sha256sum "${SOURCES[@]}" "${STATIC[@]}" docs/m8b4/evidence/independent-os-run.sh > "$OUT/independent-input-sha256.txt"
sha256sum "$COMPILER" "$STDLIB" "$SCRIPT" "$REFLECT" "$COROUTINES" "$ANNOTATIONS" "$JUNIT" "$HAMCREST" > "$OUT/independent-toolchain-sha256.txt"
python3 - "$OUT/independent-toolchain-sha256.txt" docs/m7c/evidence/independent-final-toolchain-sha256.txt > "$OUT/independent-toolchain-match.log" <<'PY'
from pathlib import Path
import sys
def read(path):
 rows=[line.split(maxsplit=1) for line in Path(path).read_text().splitlines() if line.strip()]
 result={Path(name).name:digest for digest,name in rows}
 assert len(rows)==len(result)==8
 return result
assert read(sys.argv[1]) == read(sys.argv[2])
print('PASS: exact eight previously admitted standalone toolchain SHA-256 values')
PY
"$JAVA" -cp "$COMPILER:$STDLIB:$SCRIPT:$REFLECT:$COROUTINES:$ANNOTATIONS" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -classpath "$STDLIB:$JUNIT" -d "$CLASSES" "${SOURCES[@]}" > "$OUT/independent-compile.log" 2>&1
set +e
"$JAVA" -cp "$CLASSES:$STDLIB:$JUNIT:$HAMCREST" org.junit.runner.JUnitCore "${TESTS[@]}" | tee "$OUT/independent-results.log"
STATUS=${PIPESTATUS[0]}
set -e
sha256sum --check "$OUT/independent-input-sha256.txt" > "$OUT/independent-input-unchanged.log"
if [[ "${ESV_INDEPENDENT_PROVISIONAL:-0}" != 1 ]]; then
 python3 docs/m8b4/evidence/independent-frozen-check.py after "$MANIFEST" "$OUT/independent-frozen-before.json" "${SOURCES[@]}" "${STATIC[@]}" docs/m8b4/evidence/independent-os-run.sh > "$OUT/independent-frozen-after.log"
fi
exit "$STATUS"
