"""Temporary exact owned fixture admission for authorized emulator diagnostics.

No public source, persisted setting, download or runtime rule ingestion.
Stopping this process revokes the complete temporary admission.
"""
import hashlib,json,pathlib,sys,threading
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
sys.path.insert(0,str(pathlib.Path(__file__).resolve().parents[1]))
from esv_verify.capability import FixtureCapability,FixtureRule
from esv_verify.service import VerificationService
from esv_verify.server import Server
root=pathlib.Path('../ab-validation-20261008/corpus').resolve()
manifest=json.loads((root/'manifest.json').read_text(encoding='utf-8'))
cases=manifest['cases'];assert len(cases)==100
by_path={c['path']:c for c in cases}
for c in cases:assert hashlib.sha256((root/c['path'].lstrip('/')).read_bytes()).hexdigest()==c['sha256']
class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        c=by_path.get(self.path)
        if c is None:self.send_error(403);return
        body=(root/c['path'].lstrip('/')).read_bytes()
        self.send_response(c['expected_status']);self.send_header('Content-Type','text/html')
        self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body)
    def log_message(self,*args):pass
fixture=ThreadingHTTPServer(('127.0.0.1',0),Handler)
thread=threading.Thread(target=fixture.serve_forever,daemon=True);thread.start()
receipt=json.loads(pathlib.Path('services/verification-service/tests/data/local-windows-20261009/windows-browser-admission.json').read_text(encoding='utf-8'))
origin='http://127.0.0.1:'+str(fixture.server_address[1])
services=[]
for chunk in (cases[:64],cases[64:]):
    cap=FixtureCapability(origin,tuple(FixtureRule(c['path'],dynamic_dom=c['group']=='dynamic') for c in chunk),receipt['executable'],receipt['sha256'],'https://fixture.example.org')
    services.append(VerificationService(cap,1))
class OwnedDispatch:
    def verify(self,request,*args):
        for s in services:
            if s._windows.capability.rule_for(request.candidateUrl) is not None:return s.verify(request,*args)
        return request.result('UNSUPPORTED')
    def close(self):
        for s in services:s.close()
try:
    with Server(('127.0.0.1',8765),OwnedDispatch()) as server:
        print('Temporary owned fixture admission READY; public targets=0',flush=True)
        server.serve_forever(poll_interval=.1)
finally:fixture.shutdown();fixture.server_close();thread.join(1)
