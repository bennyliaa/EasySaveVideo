#!/usr/bin/env bash
set -euo pipefail
REPO=${ESV_REVIEW_REPO:-$(cd "$(dirname "$0")/../../.." && pwd)}
cd "$REPO"
OUT=${1:-${ESV_EVIDENCE_DIR:-$REPO/docs/m10a/evidence/inherited-origin-run}}
[[ "$OUT" == /* && "$OUT" != / && "$OUT" != "$REPO" ]] || { echo "Absolute independent output directory required" >&2; exit 2; }
ROOT=${ESV_TOOLCHAIN_ROOT:-$(dirname "$REPO")}
CACHE=${ESV_REVIEW_CACHE:-$ROOT/android-build-home/gradle-home/caches/modules-2/files-2.1}
one() { find "$CACHE/$1" -name '*.jar' ! -name '*-sources.jar' ! -name '*-all.jar' | sort | head -1; }
COMPILER=$(one org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.10); STDLIB=$(one org.jetbrains.kotlin/kotlin-stdlib/2.2.10); SCRIPT=$(one org.jetbrains.kotlin/kotlin-script-runtime/2.2.10); REFLECT=$(one org.jetbrains.kotlin/kotlin-reflect); COROUTINES=$(one org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.9.0); ANNOTATIONS=$(one org.jetbrains/annotations/13.0); JUNIT=$(one junit/junit/4.13.2); HAMCREST=$(one org.hamcrest/hamcrest-core/1.3)
JAVA=${ESV_REVIEW_JAVA:-$ROOT/esv-toolchain-recovery/jdk21-module-launchers/bin/java}
mkdir -p "$OUT"
CLASSES=$(mktemp -d "$OUT/release-store-classes.XXXXXX")
trap 'rm -rf -- "$CLASSES"' EXIT
SOURCES=(app/src/test/kotlin/com/easysavevideo/app/entitlements/StorePurchaseBoundaryTest.kt)
RELEASE="$REPO/app/build/intermediates/built_in_kotlinc/release/compileReleaseKotlin/classes"
RELEASE_JAVA="$REPO/app/build/intermediates/javac/release/compileReleaseJavaWithJavac/classes"
MODEL="$REPO/core-model/build/libs/core-model-0.3.2-commons-dev.jar"
DOMAIN="$REPO/core-domain/build/libs/core-domain-0.3.2-commons-dev.jar"
PRODUCTION="$RELEASE:$RELEASE_JAVA:$MODEL:$DOMAIN"
mapfile -t COMPILED < <(find "$RELEASE/com/easysavevideo/app/entitlements" -name '*.class' | sort)
COMPILED+=("$RELEASE_JAVA/com/easysavevideo/app/BuildConfig.class" "$MODEL" "$DOMAIN")
sha256sum "${COMPILED[@]}" > "$OUT/actual-release-input-sha256.txt"
"$JAVA" -m jdk.jdeps/com.sun.tools.javap.Main -classpath "$RELEASE_JAVA" -constants com.easysavevideo.app.BuildConfig > "$OUT/release-build-config.txt"
python3 - "$OUT/release-build-config.txt" <<'PY_RELEASE'
from pathlib import Path
import sys
assert 'public static final boolean DEBUG = false;' in Path(sys.argv[1]).read_text()
print('PASS ACTUAL_RELEASE_BUILD_CONFIG_DEBUG_FALSE')
PY_RELEASE
python3 - "$REPO/docs/m10a/evidence/frozen-review-source-manifest.json" "$OUT/release-store-frozen-before.json" <<'PY_FROZEN'
from pathlib import Path
import hashlib,json,sys
m=json.loads(Path(sys.argv[1]).read_text())
for f in m['files']:
 b=Path(f['path']).read_bytes(); assert len(b)==f['size'] and hashlib.sha256(b).hexdigest()==f['sha256'], f['path']
Path(sys.argv[2]).write_text(json.dumps(m,indent=2)+'\n')
print('PASS frozen source manifest exact hashes:',len(m['files']))
PY_FROZEN
sha256sum "${SOURCES[@]}" docs/m10a/evidence/run-release-store-host.sh docs/m7c/evidence/independent-final-toolchain-sha256.txt > "$OUT"/independent-compiled-source-sha256.txt
sha256sum "$COMPILER" "$STDLIB" "$SCRIPT" "$REFLECT" "$COROUTINES" "$ANNOTATIONS" "$JUNIT" "$HAMCREST" > "$OUT"/independent-toolchain-sha256.txt
python3 - "$OUT/independent-toolchain-sha256.txt" "$REPO/docs/m7c/evidence/independent-final-toolchain-sha256.txt" > "$OUT/independent-toolchain-match.log" <<'PY_CHECK'
from pathlib import Path
import json, sys

def artifacts(path):
    rows = [line.split(maxsplit=1) for line in Path(path).read_text().splitlines() if line.strip()]
    result = {Path(name).name: digest for digest, name in rows}
    assert len(rows) == len(result) == 8, "Expected exactly eight distinct toolchain artifacts"
    return result

current, admitted = map(artifacts, sys.argv[1:])
assert current == admitted, "Standalone compiler/runtime/JUnit hashes differ from the prior M7-C record"
print(json.dumps({"result": "PASS", "artifacts_matched": len(current),
    "reference": "docs/m7c/evidence/independent-final-toolchain-sha256.txt", "comparison": "artifact basename and exact SHA-256"}, indent=2))
PY_CHECK
"$JAVA" -cp "$COMPILER:$STDLIB:$SCRIPT:$REFLECT:$COROUTINES:$ANNOTATIONS" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -classpath "$PRODUCTION:$STDLIB:$JUNIT" -d "$CLASSES" "${SOURCES[@]}"
set +e
"$JAVA" -cp "$CLASSES:$PRODUCTION:$STDLIB:$JUNIT:$HAMCREST" org.junit.runner.JUnitCore com.easysavevideo.app.entitlements.StorePurchaseBoundaryTest | tee "$OUT"/independent-results.log
RESULT=${PIPESTATUS[0]}
set -e
sha256sum --check "$OUT"/independent-compiled-source-sha256.txt > "$OUT"/independent-source-unchanged.log
python3 - "$OUT/release-store-frozen-before.json" "$OUT/release-store-frozen-after.log" <<'PY_FROZEN'
from pathlib import Path
import hashlib,json,sys
m=json.loads(Path(sys.argv[1]).read_text())
for f in m['files']:
 b=Path(f['path']).read_bytes(); assert len(b)==f['size'] and hashlib.sha256(b).hexdigest()==f['sha256'], f['path']
Path(sys.argv[2]).write_text('PASS exact frozen manifest: '+str(len(m['files']))+' files unchanged before/after\n')
PY_FROZEN
sha256sum --check "$OUT/actual-release-input-sha256.txt" > "$OUT/actual-release-input-unchanged.log"
exit "$RESULT"
