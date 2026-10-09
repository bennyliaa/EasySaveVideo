#!/usr/bin/env bash
set -euo pipefail
# Host-only bytecode regression. Never invoke Gradle, Android, a codec, or a network fetch.
# Usage: ANDROID_JAR=/path/to/android.jar JAVA_BIN=/path/to/java JAVAC_BIN=/path/to/javac \
#        ./run.sh /path/to/EasySaveVideo [output-directory]
HERE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
REPO=$(cd -- "${1:-.}" && pwd)
OUT=${2:-$(mktemp -d /tmp/esv-pinned-reader-XXXXXX)}
: "${ANDROID_JAR:?Set ANDROID_JAR to the approved Android SDK platform android.jar}"
JAVA_BIN=${JAVA_BIN:-java}
JAVAC_BIN=${JAVAC_BIN:-javac}
mkdir -p "$OUT/jars" "$OUT/classes"
while IFS= read -r archive; do
  unzip -p "$archive" classes.jar > "$OUT/jars/$(basename "$archive").jar"
done < <(find "$REPO/vendor/media3-maven/androidx/media3" -name '*1.11.1.aar' | sort)
GUAVA="$REPO/vendor/media3-maven/com/google/guava/guava/33.3.1-android/guava-33.3.1-android.jar"
CP="$ANDROID_JAR:$GUAVA:$(find "$OUT/jars" -name '*.jar' | sort | paste -sd:)"
mapfile -t SOURCES < <(find "$HERE/src" -name '*.java' | sort)
"$JAVAC_BIN" -source 17 -target 17 -cp "$CP" -d "$OUT/classes" "${SOURCES[@]}"
ARGS=()
if [[ -n "${ESV_RAW_CLASSPATH:-}" ]]; then
  CP="$CP:$ESV_RAW_CLASSPATH"
  ARGS+=(--raw)
fi
"$JAVA_BIN" -cp "$OUT/classes:$CP" ReaderProbe "${ARGS[@]}" | tee "$OUT/results.log"
printf 'Host-only evidence: %s\n' "$OUT/results.log"
