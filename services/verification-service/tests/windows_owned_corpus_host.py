"""Actual Windows A on frozen owned corpus. No public-source or Android performance claims."""
import csv,hashlib,json,pathlib,sys,threading,time
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
sys.path.insert(0,str(pathlib.Path(__file__).resolve().parents[1]))
from esv_verify.service import VerificationService
from esv_verify.capability import FixtureCapability,FixtureRule
from esv_verify.protocol import Request

root=pathlib.Path('../ab-validation-20261008/corpus').resolve()
manifest=json.loads((root/'manifest.json').read_text(encoding='utf-8'))
assert manifest['count']==100
cases=manifest['cases'];by_path={case['path']:case for case in cases}
for c in cases:assert hashlib.sha256((root/c['path'].lstrip('/')).read_bytes()).hexdigest()==c['sha256']
class OwnedCorpusHandler(BaseHTTPRequestHandler):
    calls=0
    def do_GET(self):
        type(self).calls+=1
        case=by_path.get(self.path)
        if case is None:self.send_error(403);return
        data=(root/self.path.lstrip('/')).read_bytes()
        self.send_response(case['expected_status']);self.send_header('Content-Type','text/html');self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data)
    def log_message(self,*args):pass
server=ThreadingHTTPServer(('127.0.0.1',0),OwnedCorpusHandler);thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
origin='http://127.0.0.1:'+str(server.server_address[1]);records=[]
try:
    for chunk in (cases[:64],cases[64:]):
        capability=FixtureCapability(origin,tuple(FixtureRule(c['path']) for c in chunk),logical_origin='https://fixture.example.org')
        service=VerificationService(capability,1)
        try:
            for c in chunk:
                request=Request(1,hashlib.sha256(c['id'].encode()).hexdigest()[:32],'e'*64,'https://fixture.example.org'+c['path'],5000,True)
                start=time.monotonic();result=service.verify(request)
                assert not service._windows.runners[0].poisoned,'CLEANUP_UNPROVEN'
                records.append({'id':c['id'],'independent_label':{'positive':'VIDEO','negative':'NON_VIDEO','unknown':'UNKNOWN'}[c['label']],'verdict':'VIDEO' if result['outcome']=='PAGE_EVIDENCE' else 'UNKNOWN','outcome':result['outcome'],'evidence':result['evidence'],'body_sha256':c['sha256'],'host_elapsed_ms':int((time.monotonic()-start)*1000)})
                if len(records)%20==0:print('Owned A cases completed',len(records),flush=True)
        finally:service.close()
finally:server.shutdown();server.server_close();thread.join(1)
out=pathlib.Path('artifacts/local-windows-20261009/frozen-a-host.tsv')
with out.open('w',encoding='utf-8',newline='') as f:
    writer=csv.DictWriter(f,fieldnames=records[0].keys(),delimiter='\t');writer.writeheader();writer.writerows(records)
print(json.dumps({'cases':len(records),'physical_fixture_requests':OwnedCorpusHandler.calls,'scope':'Windows real Scrapling only; frozen cases without XHR rules cannot exercise Chromium dynamic evidence; not Android A/B or phone performance'},indent=2))
