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
mapfile -t SOURCES < <(find core-model/src/main/kotlin library/src/main/kotlin recording/src/main/kotlin recording/src/test/kotlin -name '*.kt' | sort)
SOURCES+=(app/src/main/kotlin/com/easysavevideo/app/recording/nativeregion/RegionTexturePolicy.kt app/src/main/kotlin/com/easysavevideo/app/recording/nativeregion/RegionSourceAdmission.kt app/src/main/kotlin/com/easysavevideo/app/recording/nativeregion/RegionFrameAdmission.kt app/src/main/kotlin/com/easysavevideo/app/recording/nativeregion/RegionPreviewRasterPolicy.kt app/src/main/kotlin/com/easysavevideo/app/recording/nativeregion/RegionPreviewPixelLease.kt app/src/main/kotlin/com/easysavevideo/app/recording/RecordingNativeLease.kt app/src/main/kotlin/com/easysavevideo/app/recording/ui/RecordingPresentation.kt app/src/main/kotlin/com/easysavevideo/app/recording/ui/RecordingPreviewPresentation.kt)
mapfile -t TESTS < <(find app/src/test/kotlin/com/easysavevideo/app/recording/nativeregion app/src/test/kotlin/com/easysavevideo/app/recording/ui -name '*.kt' | sort)
SOURCES+=(app/src/main/kotlin/com/easysavevideo/app/recording/nativeregion/RegionPreviewHandoffPolicy.kt app/src/main/kotlin/com/easysavevideo/app/recording/nativeregion/RegionPreviewStopSignal.kt app/src/main/kotlin/com/easysavevideo/app/recording/RecordingPreviewHostRequest.kt app/src/test/kotlin/com/easysavevideo/app/recording/RecordingPreviewHostRequestTest.kt app/src/main/kotlin/com/easysavevideo/app/recording/nativeregion/MeasuredVideoTail.kt app/src/main/kotlin/com/easysavevideo/app/recording/nativeregion/MeasuredAudioTail.kt "${TESTS[@]}" app/src/test/kotlin/com/easysavevideo/app/recording/RecordingNativeLeaseTest.kt docs/m6c/evidence/IndependentPreviewAdversarialTest.kt)
sha256sum "${SOURCES[@]}" > "$OUT/source-sha256.txt"
"$JAVA" -cp "$COMPILER:$STDLIB:$SCRIPT:$REFLECT:$COROUTINES:$ANNOTATIONS" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -classpath "$STDLIB:$JUNIT" -d "$OUT/classes" "${SOURCES[@]}"
mapfile -t CLASSES < <(find "$OUT/classes" -name '*Test.class' | sed "s|^$OUT/classes/||;s|/|.|g;s|\.class$||" | sort)
"$JAVA" -cp "$OUT/classes:$STDLIB:$JUNIT:$HAMCREST" org.junit.runner.JUnitCore "${CLASSES[@]}" | tee "$OUT/results.log"
sha256sum --check "$OUT/source-sha256.txt" > "$OUT/source-unchanged.log"
