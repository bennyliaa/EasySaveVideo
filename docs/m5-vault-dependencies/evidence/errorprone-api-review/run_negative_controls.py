#!/usr/bin/env python3
"""Exercise rejection paths by mutating only the auditor's in-memory API model."""
import json, subprocess, sys
from pathlib import Path
root=Path(__file__).resolve().parent
cases={
 'remove_class':'unresolved_type',
 'rename_element':'unresolved_annotation_element',
 'wrong_element_descriptor':'annotation_element_value_type_mismatch',
 'remove_default':'missing_required_annotation_element',
 'remove_enum_constant':'unresolved_member',
}
results=[]
for case,expected in cases.items():
    out=root/'negative-controls'/case; out.mkdir(parents=True,exist_ok=True)
    with (out/'run.log').open('w') as log:
        p=subprocess.run([sys.executable,str(root/'audit_errorprone_runtime.py'),*sys.argv[1:],'--negative-control',case,'--out',str(out)],stdout=log,stderr=subprocess.STDOUT)
    summary=json.loads((out/'summary.json').read_text()); kinds={x['kind'] for x in summary['failures']}
    result=dict(control=case,expected_rejection=expected,returncode=p.returncode,failure_kinds=sorted(kinds),pass_=p.returncode==1 and expected in kinds and summary['status']=='FAIL')
    results.append(result); print(json.dumps(result),flush=True)
(root/'negative-controls.json').write_text(json.dumps(results,indent=2)+'\n')
assert all(x['pass_'] for x in results),results
