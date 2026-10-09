from pathlib import Path
import json,subprocess,hashlib
B=Path(__file__).resolve().parent;W=B.parent.parent;d=json.load(open(B/'ABI_REPORT.json'));old=json.load(open(B/'old-class-symbols.json'));new=json.load(open(B/'new-class-symbols.json'));j=W/'esv-toolchain-recovery/jdk21-module-launchers/bin/javap';out=B/'javap';out.mkdir(exist_ok=True);e=[]
for pair in d['pairs']:
 for label,key,cl,rows in [('old','old_coordinate',old,d['old_artifacts']),('new','new_coordinate',new,d['new_artifacts'])]:
  gav=pair[key];a=next(x for x in rows if x['coordinate']==gav);names=sorted(n.replace('/','.')for n,c in cl.items()if c['coordinate']==gav and c['access']&1)
  if gav.startswith('com.google.guava:guava:'):names=['com.google.common.util.concurrent.ListenableFuture']
  p=subprocess.run([str(j),'-classpath',a['path'],'-protected','-s',*names],capture_output=True,text=True,timeout=120)
  file=out/(gav.replace(':','__')+'.txt');file.write_text(p.stdout+p.stderr);assert p.returncode==0,(gav,p.stderr)
  e.append({'coordinate':gav,'class_count':len(names),'exit_code':p.returncode,'file':str(file.relative_to(B)),'sha256':hashlib.sha256(file.read_bytes()).hexdigest()})
  print(gav,len(names),'OK',flush=True)
(B/'JAVAP_EVIDENCE.json').write_text(json.dumps(e,indent=2)+'\n')
