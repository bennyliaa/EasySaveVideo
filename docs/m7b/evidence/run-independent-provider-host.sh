#!/usr/bin/env bash
set -euo pipefail
REPO=${ESV_REVIEW_REPO:-$(cd "$(dirname "$0")/../../.." && pwd)}
cd "$REPO"
OUT=${1:?absolute output directory required}
CACHE=${ESV_REVIEW_CACHE:-$(dirname "$REPO")/android-build-home/gradle-home/caches/modules-2/files-2.1}
mkdir -p "$OUT/classes"
one() { find "$CACHE/$1" -name '*.jar' ! -name '*-sources.jar' ! -name '*-all.jar' | sort | head -1; }
COMPILER=$(one org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.10); STDLIB=$(one org.jetbrains.kotlin/kotlin-stdlib/2.2.10); SCRIPT=$(one org.jetbrains.kotlin/kotlin-script-runtime/2.2.10); REFLECT=$(one org.jetbrains.kotlin/kotlin-reflect); COROUTINES=$(one org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.9.0); ANNOTATIONS=$(one org.jetbrains/annotations/13.0); JUNIT=$(one junit/junit/4.13.2); HAMCREST=$(one org.hamcrest/hamcrest-core/1.3)
JAVA=${ESV_REVIEW_JAVA:-$(dirname "$REPO")/esv-toolchain-recovery/jdk21-module-launchers/bin/java}
mapfile -t SOURCES < <(find core-model/src/main/kotlin discovery/src/main/kotlin commons-provider/src/main/kotlin commons-provider/src/test/kotlin -name '*.kt' | sort)
mapfile -t REVIEW_SOURCES < <(find docs/m7b/evidence -maxdepth 1 -name '*Test.kt' | sort)
SOURCES+=("${REVIEW_SOURCES[@]}")
sha256sum "${SOURCES[@]}" > "$OUT/source-sha256.txt"
sha256sum "$COMPILER" "$STDLIB" "$SCRIPT" "$REFLECT" "$COROUTINES" "$ANNOTATIONS" "$JUNIT" "$HAMCREST" > "$OUT/toolchain-sha256.txt"
"$JAVA" -cp "$COMPILER:$STDLIB:$SCRIPT:$REFLECT:$COROUTINES:$ANNOTATIONS" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -classpath "$STDLIB:$JUNIT" -d "$OUT/classes" "${SOURCES[@]}"
mapfile -t CLASSES < <(find "$OUT/classes" -name '*Test.class' | sed "s|^$OUT/classes/||;s|/|.|g;s|\.class$||" | sort)
set +e
"$JAVA" -cp "$OUT/classes:commons-provider/src/test/resources:$STDLIB:$JUNIT:$HAMCREST" org.junit.runner.JUnitCore "${CLASSES[@]}" | tee "$OUT/results.log"
RESULT=${PIPESTATUS[0]}
set -e
sha256sum --check "$OUT/source-sha256.txt" > "$OUT/source-unchanged.log"
exit "$RESULT"
