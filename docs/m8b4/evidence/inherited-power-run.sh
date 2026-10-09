#!/usr/bin/env bash
set -euo pipefail
REPO=${ESV_REVIEW_REPO:-$(cd "$(dirname "$0")/../../.." && pwd)}
cd "$REPO"
OUT=${1:-${ESV_EVIDENCE_DIR:-${TMPDIR:-/tmp}/esv-m9a-inherited-power}}
[[ "$OUT" == /* && "$OUT" != / && "$OUT" != "$REPO" ]] || { echo 'Absolute independent output directory required' >&2; exit 2; }
ROOT=${ESV_TOOLCHAIN_ROOT:-$(dirname "$REPO")}
CACHE=${ESV_REVIEW_CACHE:-$ROOT/android-build-home/gradle-home/caches/modules-2/files-2.1}
one() { find "$CACHE/$1" -name '*.jar' ! -name '*-sources.jar' ! -name '*-all.jar' | sort | head -1; }
COMPILER=$(one org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.10)
STDLIB=$(one org.jetbrains.kotlin/kotlin-stdlib/2.2.10)
SCRIPT=$(one org.jetbrains.kotlin/kotlin-script-runtime/2.2.10)
REFLECT=$(one org.jetbrains.kotlin/kotlin-reflect)
COROUTINES=$(one org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.9.0)
ANNOTATIONS=$(one org.jetbrains/annotations/13.0)
JUNIT=$(one junit/junit/4.13.2)
HAMCREST=$(one org.hamcrest/hamcrest-core/1.3)
JAVA=${ESV_REVIEW_JAVA:-$ROOT/esv-toolchain-recovery/jdk21-module-launchers/bin/java}
mkdir -p "$OUT"
CLASSES=$(mktemp -d "$OUT/independent-power-classes.XXXXXX")
trap 'rm -rf -- "$CLASSES"' EXIT
mapfile -t SOURCES < <(find core-model/src/main/kotlin discovery/src/main/kotlin -name '*.kt' | sort)
SOURCES+=(app/src/main/kotlin/com/easysavevideo/app/discovery/DiscoveryPresentationPolicy.kt
  app/src/main/kotlin/com/easysavevideo/app/discovery/DiscoveryPowerPresentation.kt
  app/src/main/kotlin/com/easysavevideo/app/discovery/DiscoveryOsPowerBridge.kt
  docs/m8b3/evidence/independent-power-review.kt)
VARIANTS=(app/src/debug/kotlin/com/easysavevideo/app/discovery/BuildDiscoveryPower.kt
  app/src/release/kotlin/com/easysavevideo/app/discovery/BuildDiscoveryPower.kt)
STATIC=(app/src/main/kotlin/com/easysavevideo/app/discovery/AndroidDiscoveryController.kt
  app/src/main/kotlin/com/easysavevideo/app/ui/DiscoveryPowerControls.kt)
MANIFEST=docs/m8b4/evidence/frozen-review-source-manifest.json
python3 - "$MANIFEST" "$OUT/independent-power-frozen-before.json" "${SOURCES[@]}" "${VARIANTS[@]}" "${STATIC[@]}" docs/m8b4/evidence/inherited-power-run.sh <<'PY'
from pathlib import Path
import hashlib,json,sys
m=json.loads(Path(sys.argv[1]).read_text())
rows=m['files']; paths=[f['path'] for f in rows]
assert len(paths)==len(set(paths)), 'Duplicate frozen manifest paths'
assert set(sys.argv[3:]) <= set(paths), 'Some compiled/reviewed inputs are not frozen: '+str(set(sys.argv[3:])-set(paths))
for f in rows:
    b=Path(f['path']).read_bytes()
    assert len(b)==f['size'] and hashlib.sha256(b).hexdigest()==f['sha256'], f['path']
Path(sys.argv[2]).write_text(json.dumps(m,indent=2)+'\n')
print('PASS exact frozen source manifest:',len(rows))
PY
sha256sum "${SOURCES[@]}" "${VARIANTS[@]}" "${STATIC[@]}" docs/m8b4/evidence/inherited-power-run.sh docs/m7c/evidence/independent-final-toolchain-sha256.txt > "$OUT/independent-power-input-sha256.txt"
sha256sum "$COMPILER" "$STDLIB" "$SCRIPT" "$REFLECT" "$COROUTINES" "$ANNOTATIONS" "$JUNIT" "$HAMCREST" > "$OUT/independent-power-toolchain-sha256.txt"
python3 - "$OUT/independent-power-toolchain-sha256.txt" docs/m7c/evidence/independent-final-toolchain-sha256.txt > "$OUT/independent-power-toolchain-match.log" <<'PY'
from pathlib import Path
import json,sys
def artifacts(path):
    rows=[line.split(maxsplit=1) for line in Path(path).read_text().splitlines() if line.strip()]
    result={Path(name).name:digest for digest,name in rows}
    assert len(rows)==len(result)==8, 'Expected exactly eight distinct standalone toolchain artifacts'
    return result
current,admitted=map(artifacts,sys.argv[1:])
assert current==admitted, 'Unadmitted standalone compiler/runtime/JUnit artifact'
print(json.dumps({'result':'PASS','artifacts_matched':8,'comparison':'basename and exact SHA-256',
    'reference':'docs/m7c/evidence/independent-final-toolchain-sha256.txt'},indent=2))
PY
python3 - "$OUT/independent-power-source-review.json" <<'PY'
from pathlib import Path
import json,sys
controller=Path('app/src/main/kotlin/com/easysavevideo/app/discovery/AndroidDiscoveryController.kt').read_text()
ui=Path('app/src/main/kotlin/com/easysavevideo/app/ui/DiscoveryPowerControls.kt').read_text()
def method(name,next_name):
    return controller.split(name,1)[1].split(next_name,1)[0]
initialize=method('private fun initialize(', 'fun setVisibility(')
assert 'c.setPowerSignals(power.selection.signals, 0L)' in initialize, 'Initialization must not acknowledge a later policy fence'
lifecycle=method('private fun applyLifecycle(', 'fun setVisibility(')
assert lifecycle.index('currentSuspensionGeneration()') < lifecycle.index('saveActive')
assert lifecycle.index('currentSuspensionGeneration()') < lifecycle.index('foreground')
assert lifecycle.index('c.setPriority(saving, recording)') < lifecycle.index('c.setVisibility(active, shown, generation)')
assert controller.count('.setVisibility(')==1, 'All lifecycle applications must use generation-capturing helper'
assert 'applyLifecycle(c)' in initialize
select=method('fun selectPowerScenario(', 'fun refreshSystemSignals(')
assert 'BuildDiscoveryPower.selection(id) ?: return' in select
assert 'simulatedPower = selection.takeIf { it.scenarioId != null }' in select
assert 'offerPowerLocked(desiredPower.get().os)' in select
feed=method('private fun offerPowerLocked(', 'private fun applyPowerChange(')
assert feed.index('suspendPowerImmediately()') < feed.index('powerDelivery.offer(')
assert 'desiredPower.getAndSet(input)' in feed
# Source integration assertions migrate to the new bounded mailbox; old case bodies are unchanged.
drain=method('private fun applyPowerChange(', 'fun updateSettings(')
assert 'change.coordinator === c' in drain
assert drain.index('pageDemandPending = false') < drain.index('c.setPowerSignals(')
assert 'requireNotNull(change.revision)' in drain and 'refresh(readOnly = true)' in drain
assert 'c.requestMore(' not in drain and 'c.page(' not in drain and 'c.setVisibility(' not in drain
mailbox=Path('app/src/main/kotlin/com/easysavevideo/app/discovery/DiscoveryLatestDelivery.kt').read_text()
assert 'latest = value' in mailbox and 'if (scheduled) return true' in mailbox
assert 'latest.also { latest = null }' in mailbox and 'if (!closed && latest != null) scheduleLocked()' in mailbox
assert 'BuildDiscoveryPower.scenarios.isNotEmpty()' in ui and 'controller.selectPowerScenario(scenario.id)' in ui
assert 'identityMatch = DiscoveryOriginMediaIdentityMatch.UNKNOWN' in controller
assert 'DiscoveryOriginMediaIdentityMatch.VERIFIED' not in controller
for path in ['app/src/main/kotlin/com/easysavevideo/app/discovery/DiscoveryPowerPresentation.kt',
    'app/src/debug/kotlin/com/easysavevideo/app/discovery/BuildDiscoveryPower.kt',
    'app/src/release/kotlin/com/easysavevideo/app/discovery/BuildDiscoveryPower.kt']:
    text=Path(path).read_text()
    assert not any(x in text for x in ['getSharedPreferences(', 'BatteryManager', 'ConnectivityManager', 'PowerManager', 'schedule(']), path
Path(sys.argv[1]).write_text(json.dumps({'result':'PASS','evidence':'STATIC_SOURCE_ONLY',
    'integration':['generation-zero initialization','generation-before-flags lifecycle helper','release allowlist refusal','bounded coalesced drain',
      'policy edits discard old pending page demand','debug controls explicit','native SAVED/PLAYED identity unchanged'],
    'android_execution':'NOT_RUN','os_signals':'APP_LAYER_BRIDGE_DEVICE_NOT_RUN','energy_measurement':'NOT_RUN'},indent=2)+'\n')
PY
RESULT=0
for variant in debug release; do
  mkdir -p "$CLASSES/$variant"
  "$JAVA" -cp "$COMPILER:$STDLIB:$SCRIPT:$REFLECT:$COROUTINES:$ANNOTATIONS" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -no-reflect -jvm-target 17 -classpath "$STDLIB:$JUNIT" -d "$CLASSES/$variant" \
    "${SOURCES[@]}" "app/src/$variant/kotlin/com/easysavevideo/app/discovery/BuildDiscoveryPower.kt" \
    > "$OUT/independent-power-$variant-compile.log" 2>&1
  set +e
  "$JAVA" -Desv.review.variant="$variant" -cp "$CLASSES/$variant:$STDLIB:$JUNIT:$HAMCREST" org.junit.runner.JUnitCore \
    com.easysavevideo.discovery.IndependentPowerReviewTest | tee "$OUT/independent-power-$variant-results.log"
  STATUS=${PIPESTATUS[0]}
  set -e
  if [[ "$STATUS" != 0 ]]; then RESULT=$STATUS; fi
done
sha256sum --check "$OUT/independent-power-input-sha256.txt" > "$OUT/independent-power-input-unchanged.log"
python3 - "$OUT/independent-power-frozen-before.json" "$MANIFEST" "$OUT/independent-power-frozen-after.log" <<'PY'
from pathlib import Path
import hashlib,json,sys
m=json.loads(Path(sys.argv[1]).read_text())
assert m==json.loads(Path(sys.argv[2]).read_text()), 'Shared manifest changed during review'
for f in m['files']:
    b=Path(f['path']).read_bytes()
    assert len(b)==f['size'] and hashlib.sha256(b).hexdigest()==f['sha256'], f['path']
Path(sys.argv[3]).write_text('PASS exact frozen manifest: '+str(len(m['files']))+' files unchanged before/after\n')
PY
exit "$RESULT"
