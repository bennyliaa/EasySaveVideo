#!/usr/bin/env bash
set -euo pipefail
# Standalone host compiler/test runner. No Gradle, downloads, APK, or device invocation.
HERE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
REPO=$(cd "$HERE/../../../.." && pwd)
ROOT=$(dirname "$REPO")
REPO=${1:-"$REPO"}
OUT=${2:-"$REPO/build/hls-mux-conservation-host"}
CACHE=${KOTLIN_CACHE:-"$ROOT/android-build-home/gradle-home/caches/modules-2/files-2.1"}
ANDROID_JAR=${ANDROID_JAR:-"$ROOT/esv-toolchain-recovery/sdk/platforms/android-36/android.jar"}
# Fail closed on a replaced pinned bytecode artifact; hashes are the admitted local AARs.
(cd "$REPO" && sha256sum --check --status <<'PINS'
f68fa5996882a5f08ea0925463bc56e1c2b20ead3f556dadba24686fc4c979f1  vendor/media3-maven/androidx/media3/media3-muxer/1.11.1/media3-muxer-1.11.1.aar
6b2d58d23a0c53425249d8b7c945600019d61a8b404c8fb8cd9ca860c5738508  vendor/media3-maven/androidx/media3/media3-extractor/1.11.1/media3-extractor-1.11.1.aar
PINS
)
mkdir -p "$OUT/jars" "$OUT/classes"
while IFS= read -r archive; do
  unzip -p "$archive" classes.jar > "$OUT/jars/$(basename "$archive").jar"
done < <(find "$REPO/vendor/media3-maven" -name '*.aar' | sort)
# Existing exact admitted runtime annotation, needed by the production API opt-in declarations.
EXPERIMENTAL=$(find "$CACHE/androidx.annotation/annotation-experimental/1.4.1" -name '*.aar' | sort | head -1)
unzip -p "$EXPERIMENTAL" classes.jar > "$OUT/jars/annotation-experimental-1.4.1.jar"
one() { find "$CACHE/$1" -name '*.jar' ! -name '*-all.jar' | sort | head -1; }
COMPILER=$(one org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.10)
STDLIB=$(one org.jetbrains.kotlin/kotlin-stdlib/2.2.10)
SCRIPT=$(one org.jetbrains.kotlin/kotlin-script-runtime/2.2.10)
REFLECT=$(one org.jetbrains.kotlin/kotlin-reflect)
COROUTINES=$(one org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.9.0)
ANNOTATIONS=$(one org.jetbrains/annotations/13.0)
JUNIT=$(one junit/junit/4.13.2)
HAMCREST=$(one org.hamcrest/hamcrest-core/1.3)
CP="$ANDROID_JAR:$STDLIB:$ANNOTATIONS:$JUNIT:$HAMCREST:$(find "$OUT/jars" "$REPO/vendor/media3-maven" -name '*.jar' ! -name '*-sources.jar' | sort | paste -sd:)"
mapfile -t JAVA < <(find "$HERE/src/test/java" "$HERE/../hls-eof-compat/src/test/java" -name '*.java' | sort)
"${JAVAC_BIN:-$ROOT/esv-toolchain-recovery/jdk21-module-launchers/bin/javac}" -source 17 -target 17 -cp "$CP" -d "$OUT/classes" "${JAVA[@]}"
mapfile -t KOTLIN < <(find "$HERE/src/test/kotlin" -name '*.kt' | sort)
for name in CompatTsProfileProbe FinalAccessUnitGate LocalAssetReadSession LocalAvcExtractor LocalHlsCompatibility LocalHlsExtractorFactory PermitDataSourceFactory; do
  KOTLIN+=("$REPO/app/src/main/kotlin/com/easysavevideo/app/save/stream/$name.kt")
done
KOTLIN+=("$REPO/modules/save/src/main/kotlin/com/easysavevideo/save/StreamConservation.kt")
java -cp "$COMPILER:$STDLIB:$SCRIPT:$REFLECT:$COROUTINES:$ANNOTATIONS" \
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 \
  -classpath "$OUT/classes:$CP" -d "$OUT/classes" "${KOTLIN[@]}"
java -Desv.mux.output="$OUT" -cp "$OUT/classes:$CP" org.junit.runner.JUnitCore \
  com.easysavevideo.app.save.stream.PinnedMuxConservationTest | tee "$OUT/results.log"
