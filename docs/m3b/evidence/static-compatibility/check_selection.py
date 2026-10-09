from pathlib import Path
import json,hashlib,shutil,zipfile,xml.etree.ElementTree as ET,datetime,subprocess
B=Path(__file__).resolve().parent;A=B.parent;W=A.parent
sha=lambda d:hashlib.sha256(d).hexdigest()
proposed={x['coordinate']:x for x in json.load(open(A/'combined-inventory.json'))};actual={};source_inventory=[]
for variant in ['debug','release']:
 p=W/f'EasySaveVideo/app/build/reports/{variant}RuntimeClasspath-inventory.tsv';data=p.read_bytes();(B/(variant+'-actual-inventory-snapshot.tsv')).write_bytes(data);out={};own=[]
 for l in data.decode().splitlines():
  c,fn,h,f=l.split('\t')
  if c.startswith('com.easysavevideo:'):own.append(c);continue
  assert sha(Path(f).read_bytes())==h;out[c]={'coordinate':c,'filename':fn,'sha256':h,'path':f}
 actual[variant]=out;source_inventory.append({'variant':variant,'inventory_sha256':sha(data),'external_unique_count':len(out),'excluded_project_gavs':own})
assert actual['debug']==actual['release'];cur=actual['debug'];missing=sorted(set(proposed)-set(cur));extra=sorted(set(cur)-set(proposed));mismatch=[c for c in cur if c in proposed and cur[c]['sha256']!=proposed[c]['sha256']]
assert len(cur)==72 and not extra and not mismatch
assert missing==['org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.8.20','org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.8.20']
roots=[['kotlinx-coroutines-core-jvm','1.7.3'],['kotlinx-coroutines-core-jvm','1.9.0'],['kotlinx-coroutines-android','1.7.3'],['kotlinx-coroutines-android','1.9.0']];md=[]
for a,v in roots:
 p=A/'official-maven/org/jetbrains/kotlinx'/a/v/(a+'-'+v+'.module');d=p.read_bytes();dest=B/'metadata';dest.mkdir(exist_ok=True);shutil.copy2(p,dest/p.name);obj=json.loads(d);md.append({'coordinate':f'org.jetbrains.kotlinx:{a}:{v}','module_sha256':sha(d),'runtime_variants':[{'variant':x['name'],'dependencies':x.get('dependencies',[])}for x in obj['variants']if x['attributes'].get('org.gradle.usage')=='java-runtime'and x['attributes'].get('org.gradle.category')=='library']})
wrapper=[]
for c in missing:
 p=A/proposed[c]['path'];z=zipfile.ZipFile(p);impl=[n for n in z.namelist()if n.endswith('.class')and not n.endswith('module-info.class')];assert not impl
 pp=p.with_suffix('.pom');shutil.copy2(pp,B/'metadata'/pp.name);wrapper.append({'coordinate':c,'binary_sha256':sha(p.read_bytes()),'entries':z.namelist(),'runtime_implementation_classes':0,'pom_sha256':sha(pp.read_bytes())})
result={'captured_at_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'head_observed':subprocess.check_output(['git','-C',str(W/'EasySaveVideo'),'rev-parse','HEAD'],text=True).strip(),'actual_external_unique_gavs':72,'proposed_isolated_gavs':74,'missing_only':missing,'extra_gavs':extra,'binary_hash_mismatches':mismatch,'debug_release_identical':True,'all_23_new_candidates_retained':all(x['coordinate']in cur for x in json.load(open(A/'new-candidate-artifacts.json'))),'license_subset_assessment':'PERMITTED_WITH_SAME_OBLIGATIONS; dropping unneeded empty metadata wrappers does not introduce new artifacts or expand scope','natural_selection_explanation':'Isolated audit made all 55 baseline GAVs direct roots; this conservatively retained jdk7/jdk8 wrappers. Actual app roots do not require them after coroutines1.9 replaces1.7.3: old core/android had kotlin-stdlib-jdk8:1.8.20; new core/android use kotlin-stdlib:2.0.0, naturally converging to existing2.2.10. stdlib constraints do not add absent modules. Not R8 removal or AGP magic.','snapshots':source_inventory,'metadata_evidence':md,'removed_wrappers':wrapper,'actual_artifacts':list(cur.values())}
(B/'SELECTION_74_TO_72.json').write_text(json.dumps(result,indent=2)+'\n')
(B/'ACTUAL_72_RUNTIME.tsv').write_text('\n'.join('\t'.join([c,a['filename'],a['sha256']])for c,a in sorted(cur.items()))+'\n')
print(json.dumps({k:v for k,v in result.items()if k not in ['metadata_evidence','actual_artifacts','snapshots']},indent=2))
