#!/usr/bin/env bash
set -euo pipefail
REPO=$(cd "$(dirname "$0")/.." && pwd)
cd "$REPO"
ROOT=${ESV_TOOLCHAIN_ROOT:-$(dirname "$REPO")}
OUT=${ESV_EVIDENCE_DIR:-$REPO/docs/m7b/evidence}
mkdir -p "$OUT"
OUT=$(cd "$OUT" && pwd)
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
scripts/gradle-workspace.sh --offline --dependency-verification strict --rerun-tasks \
  :core-model:test :core-domain:test :ui-theme:test :discovery:test :commons-provider:test \
  :save:test :recording:test :library:test :hls:test :app:testDebugUnitTest \
  :app:compileDebugKotlin :app:compileReleaseKotlin :app:lintDebug :app:lintRelease \
  :app:runtimeInventory :app:verifyRuntimeAdmission > "$OUT/strict-offline-aggregate.log" 2>&1
for name in boundaries stream-admission recording-admission library-admission tink-admission editor-admission region-recording-admission capture-preview-admission provider-maintenance-admission repository-policy-admission; do
  python3 "scripts/check-$name.py" > "$OUT/check-$name.log" 2>&1
done
for name in test-discovery-sqlite verify-library-sqlite verify-export-sqlite verify-editor-sqlite verify-recorded-segments-sqlite; do
  python3 "scripts/$name.py" > "$OUT/$name.log" 2>&1
done
node scripts/test-save-detector.mjs > "$OUT/save-detector.log" 2>&1
app/src/testSupport/hls-eof-compat/run.sh "$REPO" "$REPO/build/m7b-hls-eof" > "$OUT/hls-eof-compat.log" 2>&1
app/src/testSupport/hls-mux-conservation/run.sh "$REPO" "$REPO/build/m7b-hls-mux" > "$OUT/hls-mux-conservation.log" 2>&1
app/src/testSupport/local-hls-reader/run.sh "$REPO" "$REPO/build/m7b-hls-reader" > "$OUT/local-hls-reader.log" 2>&1
app/src/testSupport/editor-muxer-release/run.sh "$REPO/build/m7b-editor-muxer" > "$OUT/editor-muxer-release.log" 2>&1
if [[ -f docs/m7b/evidence/run-independent-provider-host.sh ]]; then
  bash docs/m7b/evidence/run-independent-provider-host.sh "$REPO/build/m7b-independent-provider" > "$OUT/independent-provider-host.log" 2>&1
fi
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
assert total['tests']>1389 and all(total[k]==0 for k in ('failures','errors','skipped')),total
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
