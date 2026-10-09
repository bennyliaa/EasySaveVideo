#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO=${ESV_REVIEW_REPO:-$(cd "$HERE/../../.." && pwd)}
OUT=${1:?absolute output directory required}
[[ "$OUT" == /* && "$OUT" != / && "$OUT" != "$REPO" ]] || exit 2
ROOT=${ESV_TOOLCHAIN_ROOT:-$(dirname "$REPO")}
CACHE=${ESV_REVIEW_CACHE:-$ROOT/android-build-home/gradle-home/caches/modules-2/files-2.1}
JAVA=${ESV_REVIEW_JAVA:-$ROOT/esv-toolchain-recovery/jdk21-module-launchers/bin/java}
mkdir -p "$OUT"
CLASSES=$(mktemp -d "$OUT/independent-classes.XXXXXX")
trap 'rm -rf -- "$CLASSES"' EXIT
one() { find "$CACHE/$1" -name '*.jar' ! -name '*-sources.jar' ! -name '*-all.jar' | sort | head -1; }
COMPILER=$(one org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.10); STDLIB=$(one org.jetbrains.kotlin/kotlin-stdlib/2.2.10); SCRIPT=$(one org.jetbrains.kotlin/kotlin-script-runtime/2.2.10); REFLECT=$(one org.jetbrains.kotlin/kotlin-reflect/1.6.10); COROUTINES=$(one org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.9.0); ANNOTATIONS=$(one org.jetbrains/annotations/13.0); JUNIT=$(one junit/junit/4.13.2); HAMCREST=$(one org.hamcrest/hamcrest-core/1.3)
cd "$REPO"
mapfile -t SOURCES < <(find core-model/src/main/kotlin -name '*.kt' | sort)
SOURCES+=(app/src/main/kotlin/com/easysavevideo/app/browser/BrowserInputPolicy.kt)
for NAME in BookmarkPolicy BookmarkCodec BookmarkController AndroidBookmarkStorage; do SOURCES+=("app/src/main/kotlin/com/easysavevideo/app/browser/bookmarks/$NAME.kt"); done
SOURCES+=(docs/browser-bookmarks/evidence/android-doubles/Context.kt docs/browser-bookmarks/evidence/android-doubles/AtomicFile.kt docs/browser-bookmarks/evidence/IndependentBookmarkTest.kt)
STATIC=(app/src/main/kotlin/com/easysavevideo/app/browser/bookmarks/AndroidBookmarkRuntime.kt
 app/src/main/kotlin/com/easysavevideo/app/browser/bookmarks/BookmarkPanel.kt
 app/src/main/kotlin/com/easysavevideo/app/browser/SystemBrowserHost.kt
 app/src/main/kotlin/com/easysavevideo/app/ui/BrowserScreen.kt
 app/src/main/kotlin/com/easysavevideo/app/MainActivity.kt
 docs/browser-bookmarks/evidence/independent-bookmark-source.py
 docs/searxng-discovery/evidence/independent-combined-source.py
 docs/searxng-discovery/evidence/independent-bookmark-run.sh
 docs/m7c/evidence/independent-final-toolchain-sha256.txt)
if [[ "${ESV_INDEPENDENT_PROVISIONAL:-0}" == 1 ]]; then
 printf '%s\n' 'PROVISIONAL: shared final freeze not required for this exploratory run.' > "$OUT/freeze-mode.log"
else
 python3 docs/searxng-discovery/evidence/verify-frozen.py --record "$OUT/frozen-identity.json" > "$OUT/frozen-before.log"
fi
sha256sum "${SOURCES[@]}" "${STATIC[@]}" > "$OUT/source-sha256.txt"
sha256sum "$COMPILER" "$STDLIB" "$SCRIPT" "$REFLECT" "$COROUTINES" "$ANNOTATIONS" "$JUNIT" "$HAMCREST" > "$OUT/toolchain-sha256.txt"
python3 - "$OUT/toolchain-sha256.txt" docs/m7c/evidence/independent-final-toolchain-sha256.txt > "$OUT/toolchain-match.log" <<'PY'
from pathlib import Path
import sys
def read(path):
    rows=[line.split(maxsplit=1) for line in Path(path).read_text().splitlines() if line.strip()]
    result={Path(name).name:digest for digest,name in rows}
    assert len(rows)==len(result)==8
    return result
assert read(sys.argv[1])==read(sys.argv[2])
print('PASS: exact eight previously admitted standalone toolchain SHA-256 values')
PY
python3 docs/searxng-discovery/evidence/independent-combined-source.py "$REPO" > "$OUT/combined-source-identity.json"
python3 docs/browser-bookmarks/evidence/independent-bookmark-source.py "$REPO" > "$OUT/source-review.json"
"$JAVA" -cp "$COMPILER:$STDLIB:$SCRIPT:$REFLECT:$COROUTINES:$ANNOTATIONS" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -classpath "$STDLIB:$JUNIT" -d "$CLASSES" "${SOURCES[@]}" > "$OUT/compile.log" 2>&1
set +e
"$JAVA" -cp "$CLASSES:$STDLIB:$JUNIT:$HAMCREST" org.junit.runner.JUnitCore com.easysavevideo.app.browser.bookmarks.IndependentBookmarkTest 2>&1 | tee "$OUT/results.log"
STATUS=${PIPESTATUS[0]}
set -e
sha256sum --check "$OUT/source-sha256.txt" > "$OUT/source-unchanged.log"
if [[ "${ESV_INDEPENDENT_PROVISIONAL:-0}" != 1 ]]; then
 python3 docs/searxng-discovery/evidence/verify-frozen.py --against "$OUT/frozen-identity.json" > "$OUT/frozen-after.log"
fi
[[ $STATUS -eq 0 ]] || exit "$STATUS"
grep -Fx 'OK (37 tests)' "$OUT/results.log" > "$OUT/exact-test-count.log"
echo 'PASS: 37 independent host tests, 23 source wiring checks and exact combined source identity; no native Android/device/UI acceptance.'
