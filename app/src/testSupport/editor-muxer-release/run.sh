#!/usr/bin/env bash
set -euo pipefail
# Offline standalone host check. Never invokes Gradle, a device, a codec, or the network.
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../../../.." && pwd)
WORKSPACE=$(dirname "$REPO")
OUT=${1:-$(mktemp -d "${TMPDIR:-/tmp}/esv-editor-muxer-release.XXXXXX")}
mkdir -p "$OUT/classes" "$OUT/jars"
OUT=$(cd "$OUT" && pwd)
CACHE=${KOTLIN_CACHE:-${GRADLE_USER_HOME:-"$WORKSPACE/android-build-home/gradle-home"}/caches/modules-2/files-2.1}
ANDROID_JAR=${ANDROID_JAR:-${ANDROID_HOME:-"$WORKSPACE/esv-toolchain-recovery/sdk"}/platforms/android-36/android.jar}
if [[ -z "${JAVA_HOME:-}" && -x "$WORKSPACE/esv-toolchain-recovery/jdk21-module-launchers/bin/java" ]]; then
  JAVA_HOME="$WORKSPACE/esv-toolchain-recovery/jdk21-module-launchers"
fi
JAVA=${JAVA_BIN:-${JAVA_HOME:+$JAVA_HOME/bin/}java}
JAVAC=${JAVAC_BIN:-${JAVA_HOME:+$JAVA_HOME/bin/}javac}
[[ -f "$ANDROID_JAR" ]] || { echo 'Missing approved Android 36 SDK jar; set ANDROID_JAR.' >&2; exit 1; }
one() {
  local path="$CACHE/$1"
  [[ -d "$path" ]] || { echo "Missing cached build artifact: $1" >&2; exit 1; }
  find "$path" -name '*.jar' ! -name '*-sources.jar' ! -name '*-all.jar' | sort | head -1
}
COMPILER=$(one org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.10)
STDLIB=$(one org.jetbrains.kotlin/kotlin-stdlib/2.2.10)
SCRIPT=$(one org.jetbrains.kotlin/kotlin-script-runtime/2.2.10)
REFLECT=$(one org.jetbrains.kotlin/kotlin-reflect)
COROUTINES=$(one org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.9.0)
ANNOTATIONS=$(one org.jetbrains/annotations/13.0)
# Check the exact admitted source archives before extracting their classes. No re-resolution.
python3 - "$REPO" "$OUT" "$STDLIB" <<'PY'
import hashlib, pathlib, sys, zipfile
repo, out, stdlib = map(pathlib.Path, sys.argv[1:])
admitted = {}
for line in (repo/'third_party/admitted-runtime.tsv').read_text().splitlines():
    if not line or line.startswith('#'):
        continue
    coordinate, filename, digest = line.split('\t')
    admitted[(coordinate, filename)] = digest

def verify(path, coordinate):
    if hashlib.sha256(path.read_bytes()).hexdigest() != admitted.get((coordinate, path.name)):
        raise SystemExit('Unadmitted artifact bytes: ' + coordinate)

paths = []
for name in ('media3-common', 'media3-container', 'media3-muxer'):
    artifact = repo/'vendor/media3-maven/androidx/media3'/name/'1.11.1'/f'{name}-1.11.1.aar'
    verify(artifact, f'androidx.media3:{name}:1.11.1')
    target = out/'jars'/f'{name}-1.11.1.jar'
    with zipfile.ZipFile(artifact) as archive:
        target.write_bytes(archive.read('classes.jar'))
    paths.append(target)
for group, name, version in (
    ('com.google.guava', 'guava', '33.3.1-android'),
    ('com.google.guava', 'failureaccess', '1.0.2'),
    ('com.google.guava', 'listenablefuture', '9999.0-empty-to-avoid-conflict-with-guava'),
):
    artifact = repo/'vendor/media3-maven'/group.replace('.', '/')/name/version/f'{name}-{version}.jar'
    verify(artifact, f'{group}:{name}:{version}')
    paths.append(artifact)
verify(stdlib, 'org.jetbrains.kotlin:kotlin-stdlib:2.2.10')
(out/'admitted-classpath.txt').write_text(':'.join(map(str, paths)))
PY
CP="$ANDROID_JAR:$STDLIB:$ANNOTATIONS:$(cat "$OUT/admitted-classpath.txt")"
# Compile the real pure production policy and the small pure contracts it references, from source.
"$JAVA" -cp "$COMPILER:$STDLIB:$SCRIPT:$REFLECT:$COROUTINES:$ANNOTATIONS" \
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 \
  -classpath "$STDLIB" -d "$OUT/classes" \
  "$REPO/library/src/main/kotlin/com/easysavevideo/library/LibraryModels.kt" \
  "$REPO/library/src/main/kotlin/com/easysavevideo/library/LibraryPolicies.kt" \
  "$REPO/library/src/main/kotlin/com/easysavevideo/library/edit/"*.kt \
  "$REPO/app/src/main/kotlin/com/easysavevideo/app/editor/engine/EditorEnginePolicy.kt"
mapfile -t SOURCES < <(find "$HERE/src" -name '*.java' | sort)
"$JAVAC" -source 17 -target 17 -cp "$OUT/classes:$CP" -d "$OUT/classes" "${SOURCES[@]}"
"$JAVA" -cp "$OUT/classes:$CP" com.easysavevideo.app.editor.engine.MuxerCancellationHost | tee "$OUT/result.log"
printf 'Host-only evidence: %s/result.log\n' "$OUT"
