#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO=${ESV_REVIEW_REPO:-$(cd "$HERE/../../.." && pwd)}
ROOT=${ESV_TOOLCHAIN_ROOT:-$(dirname "$REPO")}
OUT=${1:-$HERE/runs/independent-review}
CACHE=${ESV_REVIEW_CACHE:-$ROOT/android-build-home/gradle-home/caches/modules-2/files-2.1}
JAVA=${ESV_REVIEW_JAVA:-$ROOT/esv-toolchain-recovery/jdk21-module-launchers/bin/java}
mkdir -p "$OUT"
CLASSES=$(mktemp -d "$OUT/independent-classes.XXXXXX")
trap 'rm -rf -- "$CLASSES"' EXIT
one() { find "$CACHE/$1" -name '*.jar' ! -name '*-sources.jar' ! -name '*-all.jar' | sort | head -1; }
COMPILER=$(one org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.10); STDLIB=$(one org.jetbrains.kotlin/kotlin-stdlib/2.2.10); SCRIPT=$(one org.jetbrains.kotlin/kotlin-script-runtime/2.2.10); REFLECT=$(one org.jetbrains.kotlin/kotlin-reflect/1.6.10); COROUTINES=$(one org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.9.0); ANNOTATIONS=$(one org.jetbrains/annotations/13.0); JUNIT=$(one junit/junit/4.13.2); HAMCREST=$(one org.hamcrest/hamcrest-core/1.3)
cd "$REPO"
mapfile -t SOURCES < <(find core-model/src/main/kotlin discovery/src/main/kotlin searxng-provider/src/main/kotlin -name '*.kt' | sort)
for NAME in SearxngSourceConfiguration SearxngSourceRegistration SearxngConfigurationController SearxngConfigurationHub DiscoverySourceUiFence; do SOURCES+=("app/src/main/kotlin/com/easysavevideo/app/discovery/$NAME.kt"); done
for NAME in SearxngConfigurationCodec SearxngConfigurationStore DiscoveryCheckpointCodec; do SOURCES+=("app/src/main/kotlin/com/easysavevideo/app/storage/$NAME.kt"); done
for NAME in StrictSearxngHttp SystemSearxngTransport; do SOURCES+=("app/src/main/kotlin/com/easysavevideo/app/discovery/network/$NAME.kt"); done
SOURCES+=(docs/searxng-discovery/evidence/IndependentSearxngIntegrationTest.kt
 docs/searxng-discovery/evidence/IndependentConfigurationLifecycleTest.kt
 docs/searxng-discovery/evidence/IndependentInitializationFenceTest.kt
 docs/searxng-discovery/evidence/independent-lifecycle-doubles/AndroidOs.kt
 docs/searxng-discovery/evidence/independent-lifecycle-doubles/ComposeState.kt
 docs/searxng-discovery/evidence/independent-lifecycle-doubles/DiscoveryUiState.kt)
STATIC=(AGENTS.md app/build.gradle.kts settings.gradle.kts searxng-provider/build.gradle.kts
 app/src/main/kotlin/com/easysavevideo/app/discovery/AndroidDiscoveryController.kt
 app/src/main/kotlin/com/easysavevideo/app/discovery/AndroidSearxngConfigurationOwner.kt
 app/src/main/kotlin/com/easysavevideo/app/discovery/DiscoveryFilterPresentationPolicy.kt
 app/src/main/kotlin/com/easysavevideo/app/storage/AndroidSearxngConfigurationStore.kt
 app/src/main/kotlin/com/easysavevideo/app/ui/SearxngSourceSettings.kt
 app/src/main/kotlin/com/easysavevideo/app/ui/PendingCandidateCards.kt
 app/src/main/kotlin/com/easysavevideo/app/ui/DiscoveryScreen.kt
 app/src/debug/kotlin/com/easysavevideo/app/discovery/BuildDiscovery.kt
 app/src/release/kotlin/com/easysavevideo/app/discovery/BuildDiscovery.kt
 docs/searxng-discovery/evidence/check-independent-lifecycle-wiring.py)
sha256sum "${SOURCES[@]}" "${STATIC[@]}" docs/searxng-discovery/evidence/run-independent-integration-review.sh > "$OUT/source-sha256.txt"
sha256sum "$COMPILER" "$STDLIB" "$SCRIPT" "$REFLECT" "$COROUTINES" "$ANNOTATIONS" "$JUNIT" "$HAMCREST" > "$OUT/toolchain-sha256.txt"
python3 - "$OUT/toolchain-sha256.txt" docs/m7c/evidence/independent-final-toolchain-sha256.txt > "$OUT/toolchain-match.log" <<'PY'
from pathlib import Path
import sys
def read(path):
    rows=[line.split(maxsplit=1) for line in Path(path).read_text().splitlines() if line.strip()]
    result={Path(name).name:digest for digest,name in rows}
    assert len(rows)==len(result)==8
    return result
assert read(sys.argv[1])==read(sys.argv[2])
print('PASS: exact eight previously admitted standalone toolchain SHA-256 values')
PY
"$JAVA" -cp "$COMPILER:$STDLIB:$SCRIPT:$REFLECT:$COROUTINES:$ANNOTATIONS" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -classpath "$STDLIB:$JUNIT" -d "$CLASSES" "${SOURCES[@]}" > "$OUT/compile.log" 2>&1
set +e
"$JAVA" -cp "$CLASSES:$STDLIB:$JUNIT:$HAMCREST" org.junit.runner.JUnitCore com.easysavevideo.searxng.IndependentSearxngIntegrationTest com.easysavevideo.app.discovery.IndependentConfigurationLifecycleTest com.easysavevideo.app.discovery.IndependentInitializationFenceTest 2>&1 | tee "$OUT/results.log"
STATUS=${PIPESTATUS[0]}
set -e
python3 docs/searxng-discovery/evidence/check-independent-lifecycle-wiring.py "$REPO" > "$OUT/source-wiring.json"
sha256sum --check "$OUT/source-sha256.txt" > "$OUT/source-unchanged.log"
[[ "$STATUS" -eq 0 ]] || exit "$STATUS"
grep -Fx 'OK (33 tests)' "$OUT/results.log" > "$OUT/exact-test-count.log"
