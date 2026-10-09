#!/usr/bin/env bash
set -euo pipefail
REPO=${ESV_REVIEW_REPO:-$(cd "$(dirname "$0")/../../.." && pwd)}
cd "$REPO"
OUT=${1:-$REPO/docs/m9a/evidence/isolation-host}
ROOT=${ESV_TOOLCHAIN_ROOT:-$(dirname "$REPO")}
CACHE=${ESV_REVIEW_CACHE:-$ROOT/android-build-home/gradle-home/caches/modules-2/files-2.1}
one() { find "$CACHE/$1" -name '*.jar' ! -name '*-sources.jar' ! -name '*-all.jar' | sort | head -1; }
COMPILER=$(one org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.10); STDLIB=$(one org.jetbrains.kotlin/kotlin-stdlib/2.2.10); SCRIPT=$(one org.jetbrains.kotlin/kotlin-script-runtime/2.2.10); REFLECT=$(one org.jetbrains.kotlin/kotlin-reflect); COROUTINES=$(one org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.9.0); ANNOTATIONS=$(one org.jetbrains/annotations/13.0); JUNIT=$(one junit/junit/4.13.2); HAMCREST=$(one org.hamcrest/hamcrest-core/1.3)
JAVA=${ESV_REVIEW_JAVA:-$ROOT/esv-toolchain-recovery/jdk21-module-launchers/bin/java}
mkdir -p "$OUT"
CLASSES=$(mktemp -d "$OUT/classes.XXXXXX")
RAW=$(mktemp -d)
trap 'rm -rf -- "$CLASSES" "$RAW"' EXIT
mapfile -t SOURCES < <(find core-model/src/main/kotlin recording/src/main/kotlin save/src/main/kotlin discovery/src/main/kotlin commons-provider/src/main/kotlin -name '*.kt' | sort)
SOURCES+=(core-domain/src/main/kotlin/com/easysavevideo/core/domain/TaskStateMachine.kt core-domain/src/main/kotlin/com/easysavevideo/core/domain/BrowserSessionController.kt app/src/main/kotlin/com/easysavevideo/app/browser/BrowserInputPolicy.kt app/src/main/kotlin/com/easysavevideo/app/save/MediaTaskActivity.kt app/src/main/kotlin/com/easysavevideo/app/storage/BrowserSessionCodec.kt app/src/main/kotlin/com/easysavevideo/app/save/storage/SaveRecordCodec.kt app/src/test/kotlin/com/easysavevideo/app/reliability/OrdinaryIsolationTest.kt)
python3 - docs/m9a/evidence/frozen-review-source-manifest.json "$OUT/frozen-before.json" "${SOURCES[@]}" docs/m9a/evidence/run-isolation-host.sh <<'PY_FREEZE'
from pathlib import Path
import hashlib,json,os,sys
try:
 manifest=Path(sys.argv[1]); output=Path(sys.argv[2])
 if not manifest.exists():
  assert os.environ.get('ESV_ALLOW_UNFROZEN_REVIEW')=='1'
  output.write_text(json.dumps({'development_unfrozen':True})+'\n')
  print('DEVELOPMENT_UNFROZEN_REVIEW')
 else:
  raw=manifest.read_bytes(); data=json.loads(raw)
  rows=data['files']; indexed={row['path']:row for row in rows}
  assert len(rows)==len(indexed) and set(sys.argv[3:]) <= set(indexed)
  for row in rows:
   content=Path(row['path']).read_bytes()
   assert len(content)==row['size'] and hashlib.sha256(content).hexdigest()==row['sha256']
  output.write_bytes(raw)
  print('PASS FROZEN_INPUTS_BEFORE_COMPILE_SUBSET')
except Exception:
 print('FAIL FROZEN_INPUTS_BEFORE'); raise SystemExit(1)
PY_FREEZE
sha256sum "$COMPILER" "$STDLIB" "$SCRIPT" "$REFLECT" "$COROUTINES" "$ANNOTATIONS" "$JUNIT" "$HAMCREST" > "$RAW/toolchain"
python3 - "$RAW/toolchain" docs/m7c/evidence/independent-final-toolchain-sha256.txt "$OUT/toolchain-manifest.json" <<'PY'
from pathlib import Path
import json,sys
def rows(p):
 entries=[line.split(maxsplit=1) for line in Path(p).read_text().splitlines() if line.strip()]
 out={Path(name).name:digest for digest,name in entries}
 assert len(entries)==len(out)==8
 return out
actual,admitted=map(rows,sys.argv[1:3]); assert actual==admitted
Path(sys.argv[3]).write_text(json.dumps(actual,indent=2)+'\n')
print('PASS ADMITTED_TOOLCHAIN_8')
PY
python3 - "$OUT/source-manifest.json" "${SOURCES[@]}" docs/m9a/evidence/run-isolation-host.sh <<'PY'
from pathlib import Path
import hashlib,json,sys
Path(sys.argv[1]).write_text(json.dumps({p:hashlib.sha256(Path(p).read_bytes()).hexdigest() for p in sys.argv[2:]},indent=2)+'\n')
PY
if ! "$JAVA" -cp "$COMPILER:$STDLIB:$SCRIPT:$REFLECT:$COROUTINES:$ANNOTATIONS" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -classpath "$STDLIB:$JUNIT" -d "$CLASSES" "${SOURCES[@]}" > "$RAW/compiler" 2>&1; then
  echo 'FAIL HOST_COMPILATION'; exit 1
fi
set +e
"$JAVA" -cp "$CLASSES:$STDLIB:$JUNIT:$HAMCREST" org.junit.runner.JUnitCore com.easysavevideo.app.reliability.OrdinaryIsolationTest > "$RAW/results" 2>&1
RESULT=$?
set -e
python3 - "$RAW/results" "$OUT/results.log" "$RESULT" <<'PY'
from pathlib import Path
import re,sys
raw=Path(sys.argv[1]).read_text()
count=re.search(r'OK \((\d+) tests\)',raw)
if count and sys.argv[3]=='0':
 report='PASS ORDINARY_ISOLATION_JVM_TESTS='+count.group(1)+'\n'
else:
 report='FAIL ORDINARY_ISOLATION_JVM_TESTS\n'
 # Method names and exception class only; never emit arbitrary exception or assertion messages.
 report+='\n'.join(re.findall(r'^\d+\) ([A-Za-z0-9_]+)\(',raw,re.M))+'\n'
Path(sys.argv[2]).write_text(report); print(report,end='')
PY
python3 - "$OUT/source-manifest.json" <<'PY'
from pathlib import Path
import hashlib,json,sys
manifest=json.loads(Path(sys.argv[1]).read_text())
assert all(hashlib.sha256(Path(p).read_bytes()).hexdigest()==sha for p,sha in manifest.items())
print('PASS SOURCE_INPUTS_UNCHANGED')
PY
python3 - docs/m9a/evidence/frozen-review-source-manifest.json "$OUT/frozen-before.json" "$OUT/frozen-after.log" <<'PY_FREEZE'
from pathlib import Path
import hashlib,json,sys
try:
 saved=Path(sys.argv[2]).read_bytes(); data=json.loads(saved)
 if data.get('development_unfrozen'):
  result='DEVELOPMENT_UNFROZEN_REVIEW\n'
 else:
  assert Path(sys.argv[1]).read_bytes()==saved
  for row in data['files']:
   content=Path(row['path']).read_bytes()
   assert len(content)==row['size'] and hashlib.sha256(content).hexdigest()==row['sha256']
  result='PASS FROZEN_INPUTS_AFTER_UNCHANGED\n'
 Path(sys.argv[3]).write_text(result); print(result,end='')
except Exception:
 print('FAIL FROZEN_INPUTS_AFTER'); raise SystemExit(1)
PY_FREEZE
exit "$RESULT"
