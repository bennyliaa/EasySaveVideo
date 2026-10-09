#!/usr/bin/env bash
# Reuse the established offline build gates for this bounded Commons-only correction.
set -euo pipefail
REPO=$(cd "$(dirname "$0")/../.." && pwd)
cd "$REPO"
ROOT=${ESV_TOOLCHAIN_ROOT:-$(dirname "$REPO")}
OUT=${ESV_EVIDENCE_DIR:-$REPO/docs/commons-unicode/evidence/final}
mkdir -p "$OUT"
OUT=$(cd "$OUT" && pwd)
export ESV_EVIDENCE_DIR="$OUT" PYTHONDONTWRITEBYTECODE=1
export JAVA_HOME=${JAVA_HOME:-$ROOT/esv-toolchain-recovery/jdk21-module-launchers}
export GRADLE_USER_HOME=${GRADLE_USER_HOME:-$ROOT/android-build-home/gradle-home}
export ANDROID_HOME=${ANDROID_HOME:-$ROOT/esv-toolchain-recovery/sdk}
export ANDROID_USER_HOME=${ANDROID_USER_HOME:-$ROOT/android-build-home/android-user}
export ESV_BUILD_HOME=${ESV_BUILD_HOME:-$ROOT/android-build-home/home}
export HOME="$ESV_BUILD_HOME" PATH="$JAVA_HOME/bin:$PATH"
python3 - "$OUT" <<'PY'
import hashlib,json,subprocess,sys
from pathlib import Path
base='19e51aa50705782dd4087dd3bfd2e7b8cfa19453'
changed=subprocess.check_output(['git','diff','--name-only',base,'--']).decode().splitlines()
allowed='commons-provider/src/main/kotlin/com/easysavevideo/commons/StrictJson.kt'
new_test='commons-provider/src/test/kotlin/com/easysavevideo/commons/StrictJsonUnicodeTest.kt'
assert set(changed)<={allowed,new_test}|{p for p in changed if p.startswith('docs/commons-unicode/')},changed
untracked=subprocess.check_output(['git','ls-files','--others','--exclude-standard']).decode().splitlines()
assert all(p==new_test or p.startswith('docs/commons-unicode/') for p in untracked),untracked
paths=subprocess.check_output(['git','ls-files','-z','--cached','--others','--exclude-standard']).decode().split('\0')
rows={p:hashlib.sha256(Path(p).read_bytes()).hexdigest() for p in paths if p and not p.startswith('docs/commons-unicode/evidence/')}
Path(sys.argv[1],'inputs-before.json').write_text(json.dumps(rows,indent=2)+'\n')
PY
scripts/gradle-workspace.sh --offline --dependency-verification strict --rerun-tasks -Pkotlin.incremental=false \
  :core-model:test :core-domain:test :ui-theme:test :discovery:test :commons-provider:test :searxng-provider:test \
  :save:test :recording:test :library:test :hls:test :app:testDebugUnitTest \
  :app:compileDebugKotlin :app:compileReleaseKotlin :app:lintDebug :app:lintRelease \
  :app:runtimeInventory :app:verifyRuntimeAdmission :app:compileReleaseJavaWithJavac > "$OUT/strict-offline-aggregate.log" 2>&1
grep -F 'License admission: exact 74 reviewed runtime artifacts verified for both variants' "$OUT/strict-offline-aggregate.log" > "$OUT/exact74-runtime-closure.log"
for name in boundaries stream-admission recording-admission library-admission; do
  python3 "scripts/check-$name.py" > "$OUT/check-$name.log" 2>&1
done
# Historical host wrappers intentionally require their old frozen source bytes. Keep
# them immutable; rerun the same channel compile/admission commands here and inspect
# the actual generated identity without claiming a new historical host-test pass.
mkdir -p "$OUT/channel-matrix"
for channel in google_play huawei xiaomi oppo vivo tencent_myapp unconfigured; do
  args=("-Pesv.distribution.channel=$channel")
  if [[ "$channel" == unconfigured ]]; then args=(); fi
  scripts/gradle-workspace.sh --offline --dependency-verification strict -Pkotlin.incremental=false "${args[@]}" \
    :app:compileDebugKotlin :app:compileDebugJavaWithJavac :app:compileReleaseKotlin :app:compileReleaseJavaWithJavac \
    :app:verifyRuntimeAdmission > "$OUT/channel-matrix/$channel-compile.log" 2>&1
  for variant in debug release; do
    classes="$REPO/app/build/intermediates/javac/$variant/compile${variant^}JavaWithJavac/classes"
    "$JAVA_HOME/bin/java" -m jdk.jdeps/com.sun.tools.javap.Main -classpath "$classes" -constants \
      com.easysavevideo.app.BuildConfig > "$OUT/channel-matrix/$channel-$variant-identity.txt"
    grep -Fq "public static final java.lang.String DISTRIBUTION_CHANNEL = \"$channel\";" \
      "$OUT/channel-matrix/$channel-$variant-identity.txt"
  done
done
values=('unknown' '' 'GOOGLE_PLAY' ' google_play' 'google_play"')
for i in "${!values[@]}"; do
  if scripts/gradle-workspace.sh --offline --dependency-verification strict "-Pesv.distribution.channel=${values[$i]}" \
    help > "$OUT/channel-matrix/rejected-$i.log" 2>&1; then
    echo 'Invalid channel unexpectedly accepted' >&2; exit 1
  fi
  grep -Fq 'Unsupported ESV distribution channel; use an exact documented build value' "$OUT/channel-matrix/rejected-$i.log"
done
python3 - "$OUT" <<'PY'
import hashlib,json,shutil,sys,xml.etree.ElementTree as ET
from pathlib import Path
out=Path(sys.argv[1]); baseline=json.loads(Path('docs/searxng-discovery/evidence/runs/final/junit-summary.json').read_text())
summary={};total=dict(tests=0,failures=0,errors=0,skipped=0)
for module in ('core-model','core-domain','ui-theme','discovery','commons-provider','searxng-provider','save','recording','library','hls','app'):
 folder=Path(module)/'build/test-results'/('testDebugUnitTest' if module=='app' else 'test')
 counts=dict(tests=0,failures=0,errors=0,skipped=0)
 for file in folder.glob('TEST-*.xml'):
  root=ET.parse(file).getroot()
  for k in counts:counts[k]+=int(root.attrib[k])
  target=out/'junit'/module;target.mkdir(parents=True,exist_ok=True);shutil.copy2(file,target/file.name)
 expected=dict(baseline[module]);expected['tests']+=9 if module=='commons-provider' else 0
 assert counts==expected,(module,counts,expected)
 summary[module]=counts
 for k in counts:total[k]+=counts[k]
assert total==dict(tests=2184,failures=0,errors=0,skipped=0),total
summary['total']=total;(out/'junit-summary.json').write_text(json.dumps(summary,indent=2)+'\n')
lint={}
for variant in ('debug','release'):
 source=Path(f'app/build/reports/lint-results-{variant}.xml');shutil.copy2(source,out/source.name)
 counts={}
 for issue in ET.parse(source).getroot().findall('issue'):
  level=issue.get('severity');counts[level]=counts.get(level,0)+1
 assert counts.get('Error',0)==0,counts
 lint[variant]=counts
 shutil.copy2(Path(f'app/build/reports/{variant}RuntimeClasspath-inventory.tsv'),out/f'{variant}RuntimeClasspath-inventory.tsv')
for path,digest in json.loads((out/'inputs-before.json').read_text()).items():
 assert hashlib.sha256(Path(path).read_bytes()).hexdigest()==digest,('BUILD_INPUT_CHANGED',path)
report={'scope':'ORDINARY_COMMONS_UNICODE_ASCII_HEX_FIX_HOST_ONLY','jvm':total,'lint':lint,'external_runtime_artifacts':74,'channel_matrix':'PASS_ALL_SEVEN_CHANNELS_BOTH_VARIANTS_AND_FIVE_INVALID_VALUES','unchanged_inputs':'PASS','apk_aab_device_live_endpoint':'NOT_RUN','M0_or_two_layer_acceptance':False}
(out/'summary.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2))
PY
