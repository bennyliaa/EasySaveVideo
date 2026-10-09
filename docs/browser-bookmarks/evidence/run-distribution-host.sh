#!/usr/bin/env bash
set -euo pipefail
REPO=${ESV_REVIEW_REPO:-$(cd "$(dirname "$0")/../../.." && pwd)}
cd "$REPO"
EXPECTED=${1:?Expected channel required}
OUT=${2:?Absolute output directory required}
[[ "$OUT" == /* && "$OUT" != / && "$OUT" != "$REPO" ]] || exit 2
ROOT=${ESV_TOOLCHAIN_ROOT:-$(dirname "$REPO")}
CACHE=${ESV_REVIEW_CACHE:-$ROOT/android-build-home/gradle-home/caches/modules-2/files-2.1}
one() { find "$CACHE/$1" -name '*.jar' ! -name '*-sources.jar' ! -name '*-all.jar' | sort | head -1; }
COMPILER=$(one org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.10); STDLIB=$(one org.jetbrains.kotlin/kotlin-stdlib/2.2.10); SCRIPT=$(one org.jetbrains.kotlin/kotlin-script-runtime/2.2.10); REFLECT=$(one org.jetbrains.kotlin/kotlin-reflect); COROUTINES=$(one org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.9.0); ANNOTATIONS=$(one org.jetbrains/annotations/13.0); JUNIT=$(one junit/junit/4.13.2); HAMCREST=$(one org.hamcrest/hamcrest-core/1.3)
JAVA=${ESV_REVIEW_JAVA:-$ROOT/esv-toolchain-recovery/jdk21-module-launchers/bin/java}
mkdir -p "$OUT"
sha256sum "$COMPILER" "$STDLIB" "$SCRIPT" "$REFLECT" "$COROUTINES" "$ANNOTATIONS" "$JUNIT" "$HAMCREST" > "$OUT/toolchain-sha256.txt"
python3 - "$OUT/toolchain-sha256.txt" docs/m7c/evidence/independent-final-toolchain-sha256.txt <<'PY'
from pathlib import Path
import sys
def rows(p):
 a=[x.split(maxsplit=1) for x in Path(p).read_text().splitlines()];d={Path(n).name:h for h,n in a};assert len(d)==len(a)==8;return d
assert rows(sys.argv[1])==rows(sys.argv[2]); print('PASS exact8 admitted host toolchain')
PY
python3 docs/browser-bookmarks/evidence/verify-frozen.py > "$OUT/frozen-before.log"
SOURCES=(app/src/test/kotlin/com/easysavevideo/app/entitlements/StorePurchaseBoundaryTest.kt app/src/test/kotlin/com/easysavevideo/app/distribution/DistributionConfigurationTest.kt docs/m10b/evidence/IndependentDistributionReviewTest.kt)
sha256sum "${SOURCES[@]}" docs/browser-bookmarks/evidence/run-distribution-host.sh > "$OUT/test-source-sha256.txt"
MODEL="$REPO/core-model/build/libs/core-model-0.3.2-commons-dev.jar"
DOMAIN="$REPO/core-domain/build/libs/core-domain-0.3.2-commons-dev.jar"
for variant in debug release; do
  suffix=${variant^}
  KOTLIN="$REPO/app/build/intermediates/built_in_kotlinc/$variant/compile${suffix}Kotlin/classes"
  JAVA_CLASSES="$REPO/app/build/intermediates/javac/$variant/compile${suffix}JavaWithJavac/classes"
  PRODUCTION="$KOTLIN:$JAVA_CLASSES:$MODEL:$DOMAIN"
  mapfile -t INPUTS < <(find "$KOTLIN/com/easysavevideo/app/entitlements" "$KOTLIN/com/easysavevideo/app/distribution" -name '*.class' | sort)
  INPUTS+=("$JAVA_CLASSES/com/easysavevideo/app/BuildConfig.class" "$MODEL" "$DOMAIN")
  sha256sum "${INPUTS[@]}" > "$OUT/$variant-production-sha256.txt"
  "$JAVA" -m jdk.jdeps/com.sun.tools.javap.Main -classpath "$JAVA_CLASSES" -constants com.easysavevideo.app.BuildConfig > "$OUT/$variant-build-config.txt"
  python3 - "$OUT/$variant-build-config.txt" "$EXPECTED" <<'PY'
from pathlib import Path
import sys
assert 'public static final java.lang.String DISTRIBUTION_CHANNEL = "'+sys.argv[2]+'";' in Path(sys.argv[1]).read_text()
print('PASS actual generated distribution identity:',sys.argv[2])
PY
  CLASSES=$(mktemp -d "$OUT/$variant-host.XXXXXX")
  trap 'rm -rf -- "$CLASSES"' EXIT
  "$JAVA" -cp "$COMPILER:$STDLIB:$SCRIPT:$REFLECT:$COROUTINES:$ANNOTATIONS" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -classpath "$PRODUCTION:$STDLIB:$JUNIT" -d "$CLASSES" "${SOURCES[@]}"
  "$JAVA" -Desv.expected.channel="$EXPECTED" -Desv.expected.variant="$variant" -cp "$CLASSES:$PRODUCTION:$STDLIB:$JUNIT:$HAMCREST" org.junit.runner.JUnitCore com.easysavevideo.app.entitlements.StorePurchaseBoundaryTest com.easysavevideo.app.distribution.DistributionConfigurationTest com.easysavevideo.m10b.review.IndependentDistributionReviewTest | tee "$OUT/$variant-results.log"
  sha256sum --check "$OUT/$variant-production-sha256.txt" > "$OUT/$variant-production-unchanged.log"
  rm -rf -- "$CLASSES"
done
sha256sum --check "$OUT/test-source-sha256.txt" > "$OUT/test-source-unchanged.log"
python3 docs/browser-bookmarks/evidence/verify-frozen.py > "$OUT/frozen-after.log"
