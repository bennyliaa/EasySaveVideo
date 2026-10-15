#!/usr/bin/env bash
set -euo pipefail
HERE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
REPO=$(realpath "${1:?Supply repository}"); OUT=$(realpath -m "${2:?Supply external output}")
case "$OUT/" in "$REPO/"*) echo 'Refusing output inside source checkout' >&2; exit 2;; esac
: "${JAVA_HOME:?}" "${ANDROID_HOME:?}" "${GRADLE_USER_HOME:?}" "${ESV_MAIN_CLASSES:?}" "${ESV_TEST_CLASSES:?}" "${ESV_PROJECT_CLASSPATH:?}" "${ESV_SOURCE_MANIFEST:?}"
(cd "$REPO" && sha256sum --check --status "$ESV_SOURCE_MANIFEST")
(cd "$REPO" && sha256sum --check --status "$HERE/ADMITTED_ARTIFACTS.sha256")
mkdir -p "$OUT/classes" "$OUT/jars"
for name in common container extractor datasource exoplayer decoder transformer muxer effect database; do
  unzip -p "$REPO/vendor/media3-maven/androidx/media3/media3-$name/1.11.1/media3-$name-1.11.1.aar" classes.jar > "$OUT/jars/media3-$name-1.11.1.jar"
done
CACHE="$GRADLE_USER_HOME/caches/modules-2/files-2.1"
one() { find "$CACHE/$1" -name '*.jar' ! -name '*-all.jar' | sort | head -1; }
COMPILER=$(one org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.10)
STDLIB=$(one org.jetbrains.kotlin/kotlin-stdlib/2.2.10)
SCRIPT=$(one org.jetbrains.kotlin/kotlin-script-runtime/2.2.10)
REFLECT=$(one org.jetbrains.kotlin/kotlin-reflect)
COROUTINES=$(one org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.9.0)
ANNOTATIONS=$(one org.jetbrains/annotations/13.0)
EXPERIMENTAL=$(find "$CACHE/androidx.annotation/annotation-experimental/1.4.1" -name '*.aar' | sort | head -1)
unzip -p "$EXPERIMENTAL" classes.jar > "$OUT/jars/annotation-experimental-1.4.1.jar"
GUAVA="$REPO/vendor/media3-maven/com/google/guava/guava/33.3.1-android/guava-33.3.1-android.jar"
FAILUREACCESS="$REPO/vendor/media3-maven/com/google/guava/failureaccess/1.0.2/failureaccess-1.0.2.jar"
CP="$ESV_MAIN_CLASSES:$ESV_TEST_CLASSES:$ESV_PROJECT_CLASSPATH:$STDLIB:$ANNOTATIONS:$GUAVA:$FAILUREACCESS:$(find "$OUT/jars" -name '*.jar' | sort | paste -sd:):$ANDROID_HOME/platforms/android-36/android.jar"
mapfile -t JAVA < <(find "$REPO/app/src/testSupport/hls-mux-conservation/src/test/java/android" "$REPO/app/src/testSupport/hls-eof-compat/src/test/java/android" "$REPO/app/src/testSupport/webm-probe-host/src/test/java" "$HERE/src/test/java" -name '*.java' 2>/dev/null | sort)
"$JAVA_HOME/bin/javac" -source 17 -target 17 -cp "$CP" -d "$OUT/classes" "${JAVA[@]}"
mapfile -t KOTLIN < <(find "$HERE/src/test/kotlin" -name '*.kt' | sort)
"$JAVA_HOME/bin/java" -cp "$COMPILER:$STDLIB:$SCRIPT:$REFLECT:$COROUTINES:$ANNOTATIONS" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -jvm-target 17 -Xfriend-paths="$ESV_MAIN_CLASSES,$ESV_TEST_CLASSES" \
  -classpath "$OUT/classes:$CP" -d "$OUT/classes" "${KOTLIN[@]}"
"$JAVA_HOME/bin/java" -Xmx256m -Desv.webm.output="$OUT" -cp "$OUT/classes:$CP" com.easysavevideo.app.save.conversion.PinnedOwnedWebmTest | tee "$OUT/results.log"
(cd "$REPO" && sha256sum --check --status "$ESV_SOURCE_MANIFEST")
