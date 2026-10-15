#!/usr/bin/env bash
set -euo pipefail
# Production preflight source + actual pinned extractor. No Gradle/network/APK/device invocation.
HERE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
REPO=${1:-$(cd "$HERE/../../../.." && pwd)}
OUT=${2:?Supply an external output directory}
CACHE=${KOTLIN_CACHE:-"${GRADLE_USER_HOME:?Set GRADLE_USER_HOME}/caches/modules-2/files-2.1"}
ANDROID_JAR=${ANDROID_JAR:-"${ANDROID_HOME:?Set ANDROID_HOME}/platforms/android-36/android.jar"}
JAVA_HOME=${JAVA_HOME:?Set JAVA_HOME}
REPO=$(realpath "$REPO"); OUT=$(realpath -m "$OUT")
case "$OUT/" in "$REPO/"*) echo 'Refusing output inside source checkout' >&2; exit 2;; esac
mkdir -p "$OUT/jars" "$OUT/classes"
(cd "$REPO" && sha256sum --check --status "$HERE/ADMITTED_ARTIFACTS.sha256")
for name in common container extractor; do
  unzip -p "$REPO/vendor/media3-maven/androidx/media3/media3-$name/1.11.1/media3-$name-1.11.1.aar" classes.jar > "$OUT/jars/media3-$name-1.11.1.jar"
done
(cd "$OUT/jars" && sha256sum --check --status "$HERE/EXTRACTED_CLASSES.sha256")
EXPERIMENTAL=$(find "$CACHE/androidx.annotation/annotation-experimental/1.4.1" -name '*.aar' | sort | head -1)
unzip -p "$EXPERIMENTAL" classes.jar > "$OUT/jars/annotation-experimental-1.4.1.jar"
one() { find "$CACHE/$1" -name '*.jar' ! -name '*-all.jar' | sort | head -1; }
COMPILER=$(one org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.10)
STDLIB=$(one org.jetbrains.kotlin/kotlin-stdlib/2.2.10)
SCRIPT=$(one org.jetbrains.kotlin/kotlin-script-runtime/2.2.10)
REFLECT=$(one org.jetbrains.kotlin/kotlin-reflect)
COROUTINES=$(one org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.9.0)
ANNOTATIONS=$(one org.jetbrains/annotations/13.0)
GUAVA="$REPO/vendor/media3-maven/com/google/guava/guava/33.3.1-android/guava-33.3.1-android.jar"
FAILUREACCESS="$REPO/vendor/media3-maven/com/google/guava/failureaccess/1.0.2/failureaccess-1.0.2.jar"
CP="$ANDROID_JAR:$STDLIB:$ANNOTATIONS:$GUAVA:$FAILUREACCESS:$(find "$OUT/jars" -name '*.jar' | sort | paste -sd:)"
mapfile -t JAVA < <(find \
  "$REPO/app/src/testSupport/hls-mux-conservation/src/test/java/android" \
  "$REPO/app/src/testSupport/hls-eof-compat/src/test/java/android" \
  "$HERE/src/test/java" -name '*.java' | sort)
"$JAVA_HOME/bin/javac" -source 17 -target 17 -cp "$CP" -d "$OUT/classes" "${JAVA[@]}"
mapfile -t KOTLIN < <(find "$HERE/src/test/kotlin" -name '*.kt' | sort)
for name in WebmOwnedInput WebmElementHeader BoundedWebmExtractor WebmProfileProbe; do
  KOTLIN+=("$REPO/app/src/main/kotlin/com/easysavevideo/app/save/conversion/$name.kt")
done
"$JAVA_HOME/bin/java" -cp "$COMPILER:$STDLIB:$SCRIPT:$REFLECT:$COROUTINES:$ANNOTATIONS" \
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 \
  -classpath "$OUT/classes:$CP" -d "$OUT/classes" "${KOTLIN[@]}"
"$JAVA_HOME/bin/java" -Xmx256m -Desv.webm.output="$OUT" -cp "$OUT/classes:$CP" \
  com.easysavevideo.app.save.conversion.PinnedProductionWebmTest | tee "$OUT/results.log"

# Permanent real-fixture/context/value/Format regressions; no FFmpeg invocation.
(cd "$HERE/fixtures" && sha256sum --check --status ORIGINAL_WEBM.sha256)
"$JAVA_HOME/bin/java" -Xmx256m -Desv.webm.output="$OUT" -Desv.webm.fixtures="$HERE/fixtures" -cp "$OUT/classes:$CP" \
  com.easysavevideo.app.save.conversion.ProgressiveSdrWebmTest | tee "$OUT/progressive-sdr-results.log"
