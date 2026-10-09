from pathlib import Path
import json,struct,io,zipfile,hashlib,collections,re
B=Path(__file__).resolve().parent;A=B.parent;W=A.parent
sha=lambda d:hashlib.sha256(d).hexdigest()
class Reader:
 def __init__(self,b):self.b=b;self.p=0
 def take(self,n):v=self.b[self.p:self.p+n];assert len(v)==n;self.p+=n;return v
 def u1(self):return self.take(1)[0]
 def u2(self):return struct.unpack('>H',self.take(2))[0]
 def u4(self):return struct.unpack('>I',self.take(4))[0]
def parse(raw):
 r=Reader(raw);assert r.u4()==0xcafebabe;minor=r.u2();major=r.u2();cnt=r.u2();cp=[None]*cnt;i=1
 while i<cnt:
  t=r.u1()
  if t==1:v=r.take(r.u2()).decode('utf8',errors='replace')
  elif t in(3,4):v=r.take(4).hex()
  elif t in(5,6):v=r.take(8).hex();cp[i]=(t,v);i+=2;continue
  elif t in(7,8,16,19,20):v=r.u2()
  elif t in(9,10,11,12,17,18):v=(r.u2(),r.u2())
  elif t==15:v=(r.u1(),r.u2())
  else:raise ValueError(('cp tag',t))
  cp[i]=(t,v);i+=1
 def utf(i):return cp[i][1]
 def cls(i):return utf(cp[i][1])if i else None
 def attrs():
  out={}
  for _ in range(r.u2()):
   n=utf(r.u2());v=r.take(r.u4());out[n]=v
  return out
 access=r.u2();name=cls(r.u2());parent=cls(r.u2());interfaces=[cls(r.u2())for _ in range(r.u2())]
 members={}
 for kind in ['fields','methods']:
  arr=[]
  for _ in range(r.u2()):
   f=r.u2();n=utf(r.u2());d=utf(r.u2());at=attrs();arr.append({'name':n,'descriptor':d,'access':f,'signature':utf(struct.unpack('>H',at['Signature'])[0])if 'Signature'in at else None})
  members[kind]=arr
 at=attrs();assert r.p==len(raw)
 refs=[]
 for q in cp:
  if q and q[0]in(9,10,11):
   owner,nt=q[1];nn,dd=cp[nt][1];refs.append({'kind':'field'if q[0]==9 else'method','owner':cls(owner),'name':utf(nn),'descriptor':utf(dd),'interface_ref':q[0]==11})
 type_refs=set()
 for q in cp:
  if q and q[0]==7:
   st=utf(q[1]);type_refs.update(re.findall(r'L([^;]+);',st) if st.startswith('[') else [st])
  elif q and q[0]==12:type_refs.update(re.findall(r'L([^;]+);',utf(q[1][1])))
 for kind in ['fields','methods']:
  for m in members[kind]:type_refs.update(re.findall(r'L([^;]+);',m['descriptor']))
 return {'type_references':sorted(type_refs),'name':name,'access':access,'super':parent,'interfaces':interfaces,'major_version':major,'sha256':sha(raw),**members,'references':refs}
def read_artifact(path,gav):
 out={}
 def scan(raw,label,aar=False):
  z=zipfile.ZipFile(io.BytesIO(raw))
  for n in z.namelist():
   if n.endswith('.class')and not n.endswith('module-info.class')and not n.startswith('META-INF/versions/'):
    c=parse(z.read(n));c.update(coordinate=gav,archive=label,entry=n);out[c['name']]=c
   elif n.endswith('.jar')and( not aar or n=='classes.jar'or n.startswith('libs/')):scan(z.read(n),label+'!'+n)
 scan(Path(path).read_bytes(),Path(path).name,str(path).endswith('.aar'));return out
oldrows=[]
for ln in(A/'baseline/debug-runtime-inventory.tsv').read_text().splitlines():
 c,fn,h,p=ln.split('\t')
 if c.startswith('com.easysavevideo:'):continue
 assert sha(Path(p).read_bytes())==h;oldrows.append({'coordinate':c,'filename':fn,'sha256':h,'path':p})
newrows=[]
for ln in(W/'EasySaveVideo/app/build/reports/debugRuntimeClasspath-inventory.tsv').read_text().splitlines():
 c,fn,h,p=ln.split('\t')
 if c.startswith('com.easysavevideo:'):continue
 assert sha(Path(p).read_bytes())==h;newrows.append({'coordinate':c,'filename':fn,'sha256':h,'path':p})
oldrows=list({a['coordinate']:a for a in oldrows}.values());newrows=list({a['coordinate']:a for a in newrows}.values())
oldclasses={};newclasses={};artifact_classes={}
for label,rows,target in [('old',oldrows,oldclasses),('new',newrows,newclasses)]:
 for a in rows:
  cl=read_artifact(a['path'],a['coordinate']);artifact_classes[label,a['coordinate']]=cl
  for n,c in cl.items():
   assert n not in target,(label,'duplicate',n);target[n]=c
pairs=[('androidx.concurrent:concurrent-futures:1.1.0','androidx.concurrent:concurrent-futures:1.2.0'),('com.google.guava:listenablefuture:1.0','com.google.guava:guava:33.3.1-android'),('org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.7.3','org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.9.0'),('org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3','org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0')]
old_gavs={p[0]for p in pairs};retained_old={a['coordinate']for a in oldrows}&{a['coordinate']for a in newrows}
def resolve(classes,owner,kind,name,desc,seen=None):
 seen=set()if seen is None else seen
 if owner in seen or owner not in classes:return None
 seen.add(owner);c=classes[owner]
 for m in c['fields'if kind=='field'else'methods']:
  if(m['name'],m['descriptor'])==(name,desc):return {'owner':owner,'access':m['access']}
 if name=='<init>':return None
 for p in ([c['super']]if c['super']else[])+c['interfaces']:
  got=resolve(classes,p,kind,name,desc,seen)
  if got:return got
 return None
reports=[]
for oc,nc in pairs:
 old=artifact_classes['old',oc];new=artifact_classes['new',nc];missing_classes=[];declared_removed=[];unresolved_removed=[];flag_changes=[];added_abstract=[];generic_changed=[]
 for cn,cl in old.items():
  if not cl['access']&1:continue
  if cn not in newclasses:
   missing_classes.append(cn)
   for kind in ['fields','methods']:
    for m in cl[kind]:
     if m['access']&5:
      row={'kind':'field'if kind=='fields'else'method','owner':cn,'name':m['name'],'descriptor':m['descriptor'],'old_access':m['access'],'resolves_in_new_graph':None,'owner_class_absent':True};declared_removed.append(row);unresolved_removed.append(row)
   continue
  ncl=newclasses[cn]
  if (cl['access']^ncl['access'])&(1|0x10|0x200|0x400):flag_changes.append({'class':cn,'change':'class access/final/interface/abstract','old_access':cl['access'],'new_access':ncl['access']})
  if cl['super']!=ncl['super']:flag_changes.append({'class':cn,'change':'superclass','old':cl['super'],'new':ncl['super']})
  for kind in ['fields','methods']:
   om={(m['name'],m['descriptor']):m for m in cl[kind]if m['access']&5}
   nm={(m['name'],m['descriptor']):m for m in ncl[kind]if m['access']&5}
   for key,m in om.items():
    sym={'kind':'field'if kind=='fields'else'method','owner':cn,'name':key[0],'descriptor':key[1],'old_access':m['access']}
    if key not in nm:
     resolved=resolve(newclasses,cn,sym['kind'],*key);row={**sym,'resolves_in_new_graph':resolved};declared_removed.append(row)
     if not resolved or not resolved['access']&5:unresolved_removed.append(row)
    else:
     n=nm[key]
     if(m['access']^n['access'])&(0x8|0x10|0x400):flag_changes.append({**sym,'change':'static/final/abstract','new_access':n['access']})
     if m['signature']!=n['signature']:generic_changed.append({**sym,'old_signature':m['signature'],'new_signature':n['signature']})
   if kind=='methods':
    for key,m in nm.items():
     if key not in om and m['access']&0x400:added_abstract.append({'owner':cn,'name':key[0],'descriptor':key[1]})
 # Search actual retained baseline class constants for unresolved member/class and invocation flag mismatches.
 removed_classes=set(old)-set(newclasses);references=[];errors=[];missing_type_refs=[];subclass_issues=[]
 for sc,cl in oldclasses.items():
  if cl['coordinate']not in retained_old:continue
  for typ in set(cl['type_references'])&removed_classes:missing_type_refs.append({'baseline_coordinate':cl['coordinate'],'referencing_class':sc,'missing_type':typ})
  for ref in cl['references']:
   if ref['owner']not in old:continue
   before=resolve(oldclasses,ref['owner'],ref['kind'],ref['name'],ref['descriptor']);after=resolve(newclasses,ref['owner'],ref['kind'],ref['name'],ref['descriptor'])
   status='RESOLVES'
   if before and not after:status='UNRESOLVED_AFTER'
   elif before and after and bool(before['access']&8)!=bool(after['access']&8):status='STATIC_INSTANCE_CHANGE'
   elif before and after and before['access']&5 and not after['access']&5:status='PUBLIC_PROTECTED_ACCESS_NARROWED'
   elif ref['owner']in newclasses and bool(oldclasses[ref['owner']]['access']&0x200)!=bool(newclasses[ref['owner']]['access']&0x200):status='CLASS_INTERFACE_KIND_CHANGED'
   elif ref['owner']in newclasses and oldclasses[ref['owner']]['access']&1 and not newclasses[ref['owner']]['access']&1 and sc.rsplit('/',1)[0]!=ref['owner'].rsplit('/',1)[0]:status='OWNER_CLASS_ACCESS_NARROWED'
   elif not before:status='UNRESOLVED_BEFORE_AND_UNASSESSED'if not after else'NEW_RESOLVED_OLD_EXTERNAL'
   row={'baseline_coordinate':cl['coordinate'],'referencing_class':sc,**ref,'before_resolution':before,'after_resolution':after,'status':status};references.append(row)
   if status not in ['RESOLVES','NEW_RESOLVED_OLD_EXTERNAL']:errors.append(row)
 report={'old_coordinate':oc,'new_coordinate':nc,'old_class_count':len(old),'new_class_count':len(new),'old_public_class_count':sum(bool(c['access']&1)for c in old.values()),'public_classes_absent_in_new_graph':missing_classes,'old_public_protected_declarations_not_declared_at_same_owner':declared_removed,'old_public_protected_symbols_unresolved_in_new_graph':unresolved_removed,'abi_flag_or_super_changes':flag_changes,'new_abstract_methods_on_retained_public_classes':added_abstract,'generic_signature_changes':generic_changed,'retained_baseline_constant_pool_reference_count':len(references),'retained_baseline_reference_artifacts':sorted(set(r['baseline_coordinate']for r in references)),'retained_baseline_unresolved_or_flag_problem_references':errors,'retained_baseline_references':references,'retained_baseline_removed_type_references':missing_type_refs}
 reports.append(report)
 print(oc,'=>',nc,'old/new classes',len(old),len(new),'missing public classes',len(missing_classes),'declared removed',len(declared_removed),'unresolved public members',len(unresolved_removed),'refs',len(references),'refproblems',len(errors),flush=True)
(B/'ABI_REPORT.json').write_text(json.dumps({'method':'Static JVM classfile parsing. Runtime classes.jar/libs only, no lint jars. Erased descriptors/public-protected flags, superclass/interface traversal; actual retained baseline constant-pool member refs. No app/library code executed. Does not establish runtime behavioral/API stability or reflection/metadata compatibility.','old_artifacts':oldrows,'new_artifacts':newrows,'pairs':reports},indent=2)+'\n')
for name,cl in [('old',oldclasses),('new',newclasses)]:
 (B/(name+'-class-symbols.json')).write_text(json.dumps(cl,indent=2)+'\n')
