"""Historical marker-only diagnosis: run from d1d23f4e, not the ordinary-page adapter.
Original evidence is retained; this script intentionally asserts the old policy.
"""
import collections,csv,hashlib,json,pathlib,sys,time
sys.path.insert(0,str(pathlib.Path(__file__).resolve().parents[1]))
from scrapling import Selector
from esv_verify.adapter import static_evidence,one_primary_video
from esv_verify.capability import FixtureCapability,FixtureRule
from esv_verify.protocol import Request
root=pathlib.Path('../ab-validation-20261008/corpus').resolve()
manifest=json.loads((root/'manifest.json').read_text(encoding='utf-8'))
rows={r['id']:r for r in csv.DictReader(pathlib.Path('artifacts/local-windows-20261009/frozen-a-host.tsv').open(encoding='utf-8'),delimiter='\t')}
records=[]
for start in (0,64):
 chunk=manifest['cases'][start:start+64]
 cap=FixtureCapability('http://127.0.0.1:12345',tuple(FixtureRule(c['path']) for c in chunk),logical_origin='https://fixture.example.org')
 for c in chunk:
  body=(root/c['path'].lstrip('/')).read_bytes();assert hashlib.sha256(body).hexdigest()==c['sha256']==rows[c['id']]['body_sha256']
  request=Request.parse(json.dumps(dict(version=1,requestId=hashlib.sha256(c['id'].encode()).hexdigest()[:32],generation='e'*64,candidateUrl='https://fixture.example.org'+c['path'],budgetMs=5000,dynamicAllowed=True)).encode())
  rule=cap.rule_for(request.candidateUrl);assert rule is not None
  selector=Selector(body,url=request.candidateUrl,huge_tree=False,adaptive=False)
  video=one_primary_video(selector)
  value=static_evidence(selector,request)
  assert value is None
  if c['group']=='static':
   assert video is not None and video.attrib.get('data-esv-primary')!='true'
   reason='PRIMARY_MARKER_REQUIRED_ABSENT'
  elif c['group']=='dynamic':
   assert video is None and rule.api_path is None
   reason='NO_INITIAL_VIDEO_AND_NO_ADMITTED_XHR_RULE'
  elif c['group']=='metadata':reason='METADATA_ONLY_NOT_VIDEO_EVIDENCE'
  elif c['group']=='embedded':reason='EMBEDDED_FRAME_NOT_SUPPORTED_BY_ADAPTER'
  elif c['group']=='negative':reason='NO_VIDEO_EVIDENCE_ABSTAIN_NOT_NEGATIVE_PROOF'
  else:reason='AUTHORED_403_THEN_SOURCE_REFUSAL'
  records.append({'id':c['id'],'group':c['group'],'body_hash_verified':True,'constructor_rule_found':True,'protocol_identity_valid':True,'requested_dynamic':request.dynamicAllowed,'xhr_rule_present':rule.api_path is not None,'observed_outcome':rows[c['id']]['outcome'],'host_elapsed_ms':int(rows[c['id']]['host_elapsed_ms']),'reason':reason})
assert len(records)==100
result={'scope':'Offline contract diagnosis of same frozen bytes using actual Scrapling Selector; no corpus/policy changes','cases':100,'public_cases':0,'reasons':dict(collections.Counter(r['reason'] for r in records)),'outcomes':dict(collections.Counter(r['observed_outcome'] for r in records)),'timeout_busy_identity_protocol_or_unavailable_results':0,'fixture_constructor_admission_cases':100,'running_shell_default_source_admission':0,'running_shell_used_for_benchmark':False,'dynamic_corpus_contract_coverage':{'dynamic_cases':20,'xhr_rules_present':0,'chromium_execution_on_these_cases':0},'interpretation':'Adapter admission/evidence coverage limits; not general Scrapling library inability and not complete dynamic A/B acceptance','records':records}
pathlib.Path('artifacts/local-windows-20261009/frozen-a-contract-diagnosis.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
print(json.dumps({k:v for k,v in result.items() if k!='records'},indent=2))
