#!/usr/bin/env bash
set -euo pipefail
# Uses already-approved cached compiler/JUnit artifacts; no Gradle, downloads or APK assembly.
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
CACHE=${ESV_GRADLE_CACHE:-/workspace/scratch/df6c1284d5ab/android-build-home/gradle-home/caches/modules-2/files-2.1}
OUT=$(mktemp -d /tmp/esv-commons-check.XXXXXX)
trap 'rm -rf "$OUT"' EXIT
jar() { find "$CACHE/$1/$2/$3" -name "$2-$3.jar" -print -quit; }
COMPILER=$(jar org.jetbrains.kotlin kotlin-compiler-embeddable 2.2.10)
STDLIB=$(jar org.jetbrains.kotlin kotlin-stdlib 2.2.10)
REFLECT=$(jar org.jetbrains.kotlin kotlin-reflect 1.6.10)
SCRIPT=$(jar org.jetbrains.kotlin kotlin-script-runtime 2.2.10)
COROUTINES=$(jar org.jetbrains.kotlinx kotlinx-coroutines-core-jvm 1.8.0)
ANNOTATIONS=$(find "$CACHE/org.jetbrains/annotations" -name '*.jar' -print -quit)
JUNIT=$(jar junit junit 4.13.2)
HAMCREST=$(jar org.hamcrest hamcrest-core 1.3)
mapfile -t MAIN < <(find "$ROOT/core-model/src/main/kotlin" "$ROOT/discovery/src/main/kotlin" "$ROOT/commons-provider/src/main/kotlin" -name '*.kt' | sort)
mapfile -t TEST < <(find "$ROOT/commons-provider/src/test/kotlin" -name '*.kt' | sort)
java -cp "$COMPILER:$STDLIB:$REFLECT:$SCRIPT:$COROUTINES:$ANNOTATIONS" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -jvm-target 17 -classpath "$STDLIB:$ANNOTATIONS:$JUNIT:$HAMCREST" \
  -d "$OUT/classes" "${MAIN[@]}" "${TEST[@]}"
if ((${#TEST[@]})); then
  cd "$ROOT"
  java -cp "$OUT/classes:$ROOT/commons-provider/src/test/resources:$STDLIB:$JUNIT:$HAMCREST" org.junit.runner.JUnitCore \
    com.easysavevideo.commons.CommonsMetadataProviderTest \
    com.easysavevideo.commons.CommonsProtocolTest \
    com.easysavevideo.commons.CommonsProviderRuntimeTest \
    com.easysavevideo.commons.CommonsDiscoveryBridgeTest
fi
