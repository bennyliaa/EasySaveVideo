#!/usr/bin/env bash
set -euo pipefail
# Standalone host compiler/test runner. No Gradle, downloads, APK, or device invocation.
HERE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
REPO=$(cd "$HERE/../../../.." && pwd)
ROOT=$(dirname "$REPO")
REPO=${1:-"$REPO"}
OUT=${2:-"$REPO/build/mov-owned-files-host"}
CACHE=${KOTLIN_CACHE:-"${GRADLE_USER_HOME:?Set GRADLE_USER_HOME}/caches/modules-2/files-2.1"}
ANDROID_JAR=${ANDROID_JAR:-"${ANDROID_HOME:?Set ANDROID_HOME}/platforms/android-36/android.jar"}
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
PROJECT_JARS=$(find "$REPO/save/build/libs" "$REPO/core-model/build/libs" "$REPO/core-domain/build/libs" "$REPO/recording/build/libs" -name '*.jar' | sort | paste -sd:)
CP="$PROJECT_JARS:$ANDROID_JAR:$STDLIB:$ANNOTATIONS:$JUNIT:$HAMCREST:$(find "$OUT/jars" "$REPO/vendor/media3-maven" -name '*.jar' ! -name '*-sources.jar' | sort | paste -sd:)"
mapfile -t JAVA < <(find "$HERE/../hls-mux-conservation/src/test/java/android" "$HERE/../hls-eof-compat/src/test/java/android" -name '*.java' | sort)
"${JAVAC_BIN:-${JAVA_HOME:?Set JAVA_HOME}/bin/javac}" -source 17 -target 17 -cp "$CP" -d "$OUT/classes" "${JAVA[@]}"
mapfile -t KOTLIN < <(find "$HERE/src/test/kotlin" -name '*.kt' | sort)
for name in MovProfileProbe MovAvcProfile MovRemuxFiles MovRemuxEngine; do
  KOTLIN+=("$REPO/app/src/main/kotlin/com/easysavevideo/app/save/normalization/$name.kt")
done
KOTLIN+=("$REPO/app/src/test/kotlin/com/easysavevideo/app/save/normalization/OriginalMovFixtures.kt")
for name in NormalizationDescriptorIo AndroidNormalizationFiles NormalizationAttemptOwner OwnedMovRemuxAttempt NormalizationWorkspaceCodec NormalizationWorkspaceProtocol PrivateCommitProtocol; do
  KOTLIN+=("$REPO/app/src/main/kotlin/com/easysavevideo/app/save/storage/$name.kt")
done
KOTLIN+=("$REPO/app/src/test/kotlin/com/easysavevideo/app/save/storage/NormalizationDescriptorTestFixtures.kt")
java -cp "$COMPILER:$STDLIB:$SCRIPT:$REFLECT:$COROUTINES:$ANNOTATIONS" \
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 \
  -classpath "$OUT/classes:$CP" -d "$OUT/classes" "${KOTLIN[@]}"
timeout 180s java -Desv.mov.output="$OUT" -cp "$OUT/classes:$CP" \
  com.easysavevideo.app.save.storage.PinnedOwnedMovFilesRunner | tee "$OUT/results.log"
