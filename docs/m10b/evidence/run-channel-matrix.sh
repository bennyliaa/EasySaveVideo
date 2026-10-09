#!/usr/bin/env bash
set -euo pipefail
REPO=${ESV_REVIEW_REPO:-$(cd "$(dirname "$0")/../../.." && pwd)}
cd "$REPO"
OUT=${1:?Absolute evidence directory required}
[[ "$OUT" == /* && "$OUT" != / && "$OUT" != "$REPO" ]] || exit 2
mkdir -p "$OUT"
# One serialized build process; no new flavors or packaging tasks.
for channel in google_play huawei xiaomi oppo vivo tencent_myapp unconfigured; do
  args=("-Pesv.distribution.channel=$channel")
  # Finish with no explicit selection, proving the safe default and restoring ordinary build state.
  if [[ "$channel" == unconfigured ]]; then args=(); fi
  scripts/gradle-workspace.sh --offline --dependency-verification strict -Pkotlin.incremental=false "${args[@]}" \
    :app:compileDebugKotlin :app:compileDebugJavaWithJavac :app:compileReleaseKotlin :app:compileReleaseJavaWithJavac \
    :app:verifyRuntimeAdmission > "$OUT/$channel-compile.log" 2>&1
  bash docs/m10b/evidence/run-distribution-host.sh "$channel" "$OUT/$channel" > "$OUT/$channel-host.log" 2>&1
done
# Unknown, empty, non-exact, and source-injection-like values must fail during configuration.
values=('unknown' '' 'GOOGLE_PLAY' ' google_play' 'google_play"')
for i in "${!values[@]}"; do
  if scripts/gradle-workspace.sh --offline --dependency-verification strict "-Pesv.distribution.channel=${values[$i]}" help > "$OUT/rejected-$i.log" 2>&1; then
    echo 'Invalid channel unexpectedly accepted' >&2; exit 1
  fi
  grep -Fq 'Unsupported ESV distribution channel; use an exact documented build value' "$OUT/rejected-$i.log"
done
python3 - "$OUT" <<'PY'
from pathlib import Path
import json,re,sys
out=Path(sys.argv[1]); rows=[]
for channel in ('unconfigured','google_play','huawei','xiaomi','oppo','vivo','tencent_myapp'):
 row={'channel':channel,'variants':{}}
 for variant in ('debug','release'):
  txt=(out/channel/f'{variant}-results.log').read_text();match=re.search(r'OK \((\d+) tests\)',txt);assert match
  row['variants'][variant]={'tests':int(match[1]),'status':'PASS','compiled_inputs_hashed':True}
 rows.append(row)
report={'scope':'ACTUAL_COMPILED_IDENTITY_AND_UNCONFIGURED_STORE_HOST_NOT_ANDROID_OR_STORE_ACCEPTANCE','rows':rows,'rejected_build_values':5,'final_default':'unconfigured','payment':'UNCONFIGURED_ALL_CHANNELS'}
(out/'matrix-summary.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2))
PY
