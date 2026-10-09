#!/usr/bin/env bash
set -euo pipefail
REPO=$(cd "$(dirname "$0")/.." && pwd)
cd "$REPO"
ROOT=${ESV_TOOLCHAIN_ROOT:-$(dirname "$REPO")}
OUT=${ESV_EVIDENCE_DIR:-$REPO/docs/m8b4/evidence}
mkdir -p "$OUT"
OUT=$(cd "$OUT" && pwd)
# A custom evidence directory must not overwrite an earlier immutable milestone.
case "$OUT" in
  "$REPO/docs/m8b4/evidence"|"$REPO/docs/m8b4/evidence/"*) ;;
  "$REPO/docs/"*) echo 'Historical documentation/evidence is read-only' >&2; exit 2 ;;
esac
export ESV_EVIDENCE_DIR="$OUT"
export JAVA_HOME=${JAVA_HOME:-$ROOT/esv-toolchain-recovery/jdk21-module-launchers}
export GRADLE_USER_HOME=${GRADLE_USER_HOME:-$ROOT/android-build-home/gradle-home}
export ANDROID_HOME=${ANDROID_HOME:-$ROOT/esv-toolchain-recovery/sdk}
export ANDROID_USER_HOME=${ANDROID_USER_HOME:-$ROOT/android-build-home/android-user}
export ESV_BUILD_HOME=${ESV_BUILD_HOME:-$ROOT/android-build-home/home}
export HOME="$ESV_BUILD_HOME"
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_JAR="$ANDROID_HOME/platforms/android-36/android.jar"
export JAVA_BIN="$JAVA_HOME/bin/java" JAVAC_BIN="$JAVA_HOME/bin/javac"
export KOTLIN_CACHE="$GRADLE_USER_HOME/caches/modules-2/files-2.1"
export ESV_REVIEW_CACHE="$KOTLIN_CACHE" ESV_REVIEW_JAVA="$JAVA_BIN" ESV_REVIEW_REPO="$REPO"
# Freeze is a separate, explicit step after the candidate and independent review are complete.
# Missing independent hooks or expected-count manifest fail closed before any Gradle work.
for hook in docs/m8b4/evidence/run-independent-folder-review.sh docs/m8b4/evidence/inherited-folder-sqlite.py scripts/verify-folder-destination-sqlite.py docs/m8b4/evidence/independent-os-run.sh; do
  [[ -f "$hook" ]] || { echo "Required ordinary-folder review hook missing: $hook" >&2; exit 2; }
done
python3 docs/m8b4/evidence/verify-inherited-wrappers.py > "$OUT/inherited-wrapper-integrity.log"
python3 docs/m8b4/evidence/verify-frozen.py > "$OUT/frozen-inputs-before.log"
scripts/gradle-workspace.sh --offline --dependency-verification strict --rerun-tasks -Pkotlin.incremental=false \
  :core-model:test :core-domain:test :ui-theme:test :discovery:test :commons-provider:test \
  :save:test :recording:test :library:test :hls:test :app:testDebugUnitTest \
  :app:compileDebugKotlin :app:compileReleaseKotlin :app:lintDebug :app:lintRelease \
  :app:runtimeInventory :app:verifyRuntimeAdmission :app:compileReleaseJavaWithJavac > "$OUT/strict-offline-aggregate.log" 2>&1
for name in boundaries stream-admission recording-admission library-admission tink-admission os-power-admission; do
  python3 "scripts/check-$name.py" > "$OUT/check-$name.log" 2>&1
done
for name in editor region-recording capture-preview purchase; do
  python3 "docs/m8b4/evidence/check-inherited-$name-admission.py" > "$OUT/check-inherited-$name-admission.log" 2>&1
done
for name in test-discovery-sqlite test-discovery-history-sqlite verify-a4b-library-sqlite verify-a4b-export-sqlite verify-a4b-editor-sqlite verify-folder-destination-sqlite verify-recorded-segments-sqlite; do
  python3 "scripts/$name.py" > "$OUT/$name.log" 2>&1
done
python3 docs/m8b4/evidence/inherited-destination-sqlite.py > "$OUT/inherited-destination-sqlite.log" 2>&1
python3 docs/m8b4/evidence/inherited-independent-destination-sqlite.py > "$OUT/inherited-independent-destination-sqlite.log" 2>&1
python3 docs/m8b4/evidence/inherited-folder-sqlite.py > "$OUT/independent-folder-sqlite.log" 2>&1
bash docs/m8b4/evidence/independent-os-run.sh "$OUT/independent-os-signal" > "$OUT/independent-os-signal-host.log" 2>&1
bash docs/m8b4/evidence/run-independent-folder-review.sh "$OUT/independent-folder-run" > "$OUT/independent-folder-host.log" 2>&1
bash docs/m8b4/evidence/run-independent-destination-review.sh "$OUT/independent-destination-run" > "$OUT/independent-destination-host.log" 2>&1
node scripts/test-save-detector.mjs > "$OUT/save-detector.log" 2>&1
app/src/testSupport/hls-eof-compat/run.sh "$REPO" "$REPO/build/m8b4-hls-eof" > "$OUT/hls-eof-compat.log" 2>&1
app/src/testSupport/hls-mux-conservation/run.sh "$REPO" "$REPO/build/m8b4-hls-mux" > "$OUT/hls-mux-conservation.log" 2>&1
app/src/testSupport/local-hls-reader/run.sh "$REPO" "$REPO/build/m8b4-hls-reader" > "$OUT/local-hls-reader.log" 2>&1
app/src/testSupport/editor-muxer-release/run.sh "$REPO/build/m8b4-editor-muxer" > "$OUT/editor-muxer-release.log" 2>&1
if [[ -f docs/m7c/evidence/run-independent-provider-host.sh ]]; then
  bash docs/m7c/evidence/run-independent-provider-host.sh "$REPO/build/m8b4-independent-provider" > "$OUT/independent-provider-host.log" 2>&1
fi
bash docs/m8a/evidence/independent-run-discovery-review.sh "$OUT/independent-discovery" > "$OUT/independent-discovery-host.log" 2>&1
bash docs/m8b4/evidence/inherited-origin-run.sh "$OUT/independent-origin" > "$OUT/independent-origin-host.log" 2>&1
bash docs/m8b4/evidence/inherited-media-run.sh "$OUT/independent-media" > "$OUT/independent-media-host.log" 2>&1
bash docs/m8b4/evidence/inherited-power-run.sh "$OUT/independent-power" > "$OUT/independent-power-host.log" 2>&1
bash docs/m8b4/evidence/inherited-filter-run.sh "$OUT/independent-filter" > "$OUT/independent-filter-host.log" 2>&1
python3 docs/m9a/evidence/verify-process-reopen.py > "$OUT/process-reopen.log" 2>&1
bash docs/m8b4/evidence/run-isolation-host.sh "$OUT/isolation-host" > "$OUT/isolation-host.log" 2>&1
bash docs/m8b4/evidence/inherited-reliability-run.sh "$OUT/independent-reliability" > "$OUT/independent-reliability-host.log" 2>&1
bash docs/m8b4/evidence/run-independent-entitlement-review.sh "$OUT/independent-entitlement" > "$OUT/independent-entitlement-host.log" 2>&1
bash docs/m8b4/evidence/run-distribution-host.sh unconfigured "$OUT/distribution-default" > "$OUT/distribution-default-host.log" 2>&1
bash docs/m8b4/evidence/run-channel-matrix.sh "$OUT/channel-matrix" > "$OUT/channel-matrix.log" 2>&1
python3 docs/m8b4/evidence/verify-frozen.py > "$OUT/frozen-inputs-after.log"
python3 - <<'PY'
from pathlib import Path
import os,json,shutil,xml.etree.ElementTree as ET
root=Path.cwd(); out=Path(os.environ['ESV_EVIDENCE_DIR'])
counts={}; total=dict.fromkeys(('tests','failures','errors','skipped'),0)
for module in ('core-model','core-domain','ui-theme','discovery','commons-provider','save','recording','library','hls','app'):
 c=dict.fromkeys(total,0)
 folder=root/module/'build/test-results'/('testDebugUnitTest' if module=='app' else 'test')
 for path in folder.glob('TEST-*.xml'):
  node=ET.parse(path).getroot()
  for key in c: c[key]+=int(node.attrib.get(key,0))
 counts[module]=c
 for key in c:total[key]+=c[key]
counts['total']=total
manifest=json.loads((root/'docs/m8b4/evidence/frozen-review-source-manifest.json').read_text())
expected=manifest['expected_jvm_tests']
assert total['tests']==expected and all(total[k]==0 for k in ('failures','errors','skipped')), {'expected':expected,'actual':total}
(out/'junit-summary.json').write_text(json.dumps(counts,indent=2)+'\n')
lint={}
for variant in ('debug','release'):
 src=root/f'app/build/reports/lint-results-{variant}.xml'
 shutil.copy2(src,out/src.name)
 c={level:0 for level in ('Error','Warning','Hint')}
 for issue in ET.parse(src).getroot().findall('issue'):
  level=issue.attrib.get('severity');c[level]=c.get(level,0)+1
 assert c['Error']==0
 lint[variant]=c
 shutil.copy2(root/f'app/build/reports/{variant}RuntimeClasspath-inventory.tsv',out/f'{variant}RuntimeClasspath-inventory.tsv')
(out/'lint-summary.json').write_text(json.dumps(lint,indent=2)+'\n')
print(json.dumps({'jvm':total,'lint':lint,'offline_compile_admission':'PASS','device':'NOT_RUN'},indent=2))
PY
