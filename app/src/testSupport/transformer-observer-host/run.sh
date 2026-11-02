#!/usr/bin/env bash
set -euo pipefail
# Original production wrappers and admitted Media3 interfaces; no native codec/GL/network/device.
HERE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
REPO=${1:-$(cd "$HERE/../../../.." && pwd)}
OUT=${2:?Supply an external output directory}
SOURCES=${SOURCE_OVERLAY:-"$REPO"}
CACHE=${KOTLIN_CACHE:-"${GRADLE_USER_HOME:?Set GRADLE_USER_HOME}/caches/modules-2/files-2.1"}
ANDROID_JAR=${ANDROID_JAR:-"${ANDROID_HOME:?Set ANDROID_HOME}/platforms/android-36/android.jar"}
JAVA_HOME=${JAVA_HOME:?Set JAVA_HOME}
REPO=$(realpath "$REPO"); OUT=$(realpath -m "$OUT")
case "$OUT/" in "$REPO/"*) echo 'Refusing output inside source checkout' >&2; exit 2;; esac
mkdir -p "$OUT/jars" "$OUT/classes"
python3 - "$REPO" "$OUT" <<'PY'
import hashlib, pathlib, sys, zipfile
repo,out=map(pathlib.Path,sys.argv[1:])
admitted={}
for line in (repo/'third_party/admitted-runtime.tsv').read_text().splitlines():
 if line and not line.startswith('#'):
  coordinate,filename,digest=line.split('\t');admitted[(coordinate,filename)]=digest
for name in ('common','container','decoder','datasource','extractor','transformer','effect','exoplayer','muxer'):
 artifact=repo/f'vendor/media3-maven/androidx/media3/media3-{name}/1.11.1/media3-{name}-1.11.1.aar'
 if hashlib.sha256(artifact.read_bytes()).hexdigest()!=admitted.get((f'androidx.media3:media3-{name}:1.11.1',artifact.name)):
  raise SystemExit('Unadmitted Media3 artifact')
 with zipfile.ZipFile(artifact) as archive: (out/'jars'/f'media3-{name}-1.11.1.jar').write_bytes(archive.read('classes.jar'))
PY
one() { find "$CACHE/$1" -name '*.jar' ! -name '*-all.jar' ! -name '*-sources.jar' | sort | head -1; }
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
CP="$ANDROID_JAR:$STDLIB:$ANNOTATIONS:$GUAVA:$FAILUREACCESS:$(find "$OUT/jars" -name '*.jar' | sort | paste -sd:)"
mapfile -t JAVA < <(find \
 "$REPO/app/src/testSupport/hls-mux-conservation/src/test/java/android" \
 "$REPO/app/src/testSupport/hls-eof-compat/src/test/java/android" \
 "$REPO/app/src/testSupport/transformer-observer-host/src/test/java" -name '*.java' | sort)
"$JAVA_HOME/bin/javac" -source 17 -target 17 -cp "$CP" -d "$OUT/classes" "${JAVA[@]}"
mapfile -t KOTLIN < <(find "$SOURCES/app/src/testSupport/transformer-observer-host/src/test/kotlin" -name '*.kt' | sort)
KOTLIN+=("$REPO/modules/core-model/src/main/kotlin/com/easysavevideo/core/model/Errors.kt"
 "$REPO/modules/core-model/src/main/kotlin/com/easysavevideo/core/model/Operations.kt")
for name in TransformerContracts TransformerRunSpec TransformerOpusPcm TransformerSourceObserver; do
 KOTLIN+=("$SOURCES/app/src/main/kotlin/com/easysavevideo/app/media/transform/$name.kt")
done
# Compile the exact production transport DTO declaration without its unrelated parser entrypoint.
# The emitted carrier grants no registered EOF/source/storage authority.
mkdir -p "$OUT/generated"
python3 - "$SOURCES" "$OUT/generated/WebmTransportCarriers.kt" <<'PYDTO'
from pathlib import Path
import sys
source=Path(sys.argv[1], 'app/src/main/kotlin/com/easysavevideo/app/save/conversion/WebmProfileProbe.kt').read_text()
start=source.index('internal class WebmTrackTransport(')
end=source.index('/**\n * Local synchronous borrowed preflight', start)
Path(sys.argv[2]).write_text('package com.easysavevideo.app.save.conversion\nimport java.util.Collections\n'+source[start:end])
PYDTO
KOTLIN+=("$OUT/generated/WebmTransportCarriers.kt")
"$JAVA_HOME/bin/java" -cp "$COMPILER:$STDLIB:$SCRIPT:$REFLECT:$COROUTINES:$ANNOTATIONS" \
 org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 \
 -classpath "$OUT/classes:$CP" -d "$OUT/classes" "${KOTLIN[@]}"
"$JAVA_HOME/bin/java" -Xmx256m -cp "$OUT/classes:$CP" com.easysavevideo.app.media.transform.TransformerObserverHost | tee "$OUT/results.log"
