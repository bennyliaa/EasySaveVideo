"""Paired control for A marker gate; neither control is substituted into the 100-case corpus."""
import pathlib,sys,hashlib,json,threading,unittest
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
sys.path.insert(0,str(pathlib.Path(__file__).resolve().parents[1]))
from esv_verify.service import VerificationService
from esv_verify.capability import FixtureCapability,FixtureRule
from esv_verify.protocol import Request
class FrozenMarkerControlTests(unittest.TestCase):
    def test_ordinary_page_no_longer_requires_marker(self):
        root=pathlib.Path('../ab-validation-20261008/corpus');manifest=json.loads((root/'manifest.json').read_text(encoding='utf-8'))
        case=next(x for x in manifest['cases'] if x['id']=='static-00')
        original=(root/case['path'].lstrip('/')).read_bytes();assert hashlib.sha256(original).hexdigest()==case['sha256']
        marked=original.replace(b'<video ',b'<video data-esv-primary="true" ',1);assert marked!=original
        bodies={'/frozen-control':original,'/marked-control':marked};calls=[]
        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                calls.append(self.path);data=bodies[self.path]
                self.send_response(200);self.send_header('Content-Type','text/html');self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data)
            def log_message(self,*args):pass
        server=ThreadingHTTPServer(('127.0.0.1',0),Handler);thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
        cap=FixtureCapability('http://127.0.0.1:'+str(server.server_address[1]),tuple(FixtureRule(x) for x in bodies),logical_origin='https://fixture.example.org')
        service=VerificationService(cap,1);outcomes=[]
        try:
            for i,path in enumerate(bodies):
                request=Request(1,str(i+1)*32,'a'*64,'https://fixture.example.org'+path,5000,False)
                result=service.verify(request);outcomes.append(result['outcome'])
                self.assertEqual(request.requestId,result['requestId']);self.assertEqual(request.generation,result['generation'])
                self.assertFalse(service._windows.runners[0].poisoned)
            self.assertEqual(['PAGE_EVIDENCE','PAGE_EVIDENCE'],outcomes);self.assertEqual(list(bodies),calls)
            self.assertEqual(case['sha256'],hashlib.sha256((root/case['path'].lstrip('/')).read_bytes()).hexdigest())
            pathlib.Path('services/verification-service/tests/data/local-windows-20261009/ordinary-marker-control.json').write_text(json.dumps({'scope':'separate paired diagnostic controls, not part of benchmark','actual_real_scrapling_workers':2,'original_frozen_hash':case['sha256'],'original_untouched':True,'same_admission_protocol_budget':True,'outcomes':outcomes,'production_gate_changed':True},indent=2)+'\n',encoding='utf-8')
        finally:service.close();server.shutdown();server.server_close();thread.join(1)
if __name__=='__main__':unittest.main()
