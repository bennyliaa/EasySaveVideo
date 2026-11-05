import hashlib,json,pathlib,sys,threading,unittest
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
sys.path.insert(0,str(pathlib.Path(__file__).resolve().parents[1]))
from esv_verify.service import VerificationService
from esv_verify.capability import FixtureCapability,FixtureRule
from esv_verify.protocol import Request

class DynamicHandler(BaseHTTPRequestHandler):
    paths=[]
    def do_GET(self):
        type(self).paths.append(self.path)
        if self.path=='/page':
            body=b'''<main><h1>Owned dynamic</h1></main><script>fetch('/api').then(r=>r.json()).then(d=>{let v=document.createElement('video');v.setAttribute('data-esv-primary','true');v.setAttribute('data-video-id',d.videoId);v.src='/owned.mp4';document.querySelector('main').append(v);document.querySelector('main').setAttribute('data-ready','true');});</script>''';kind='text/html'
        elif self.path=='/api':
            body=json.dumps({'pageUrl':'https://fixture.example.org/page','videoId':'owned','title':'Owned dynamic'}).encode();kind='application/json'
        else:self.send_error(403);return
        self.send_response(200);self.send_header('Content-Type',kind);self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body)
    def log_message(self,*args):pass

class WindowsRealDynamicTests(unittest.TestCase):
    def test_real_scrapling_chromium_sandbox_windows_job(self):
        receipt=json.loads(pathlib.Path('services/verification-service/tests/data/local-windows-20261009/windows-browser-admission.json').read_text(encoding='utf-8'))
        server=ThreadingHTTPServer(('127.0.0.1',0),DynamicHandler);thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
        capability=FixtureCapability('http://127.0.0.1:'+str(server.server_address[1]),(FixtureRule('/page','/api','owned'),),receipt['executable'],receipt['sha256'],'https://fixture.example.org')
        service=VerificationService(capability,1);request=Request(1,'c'*32,'d'*64,'https://fixture.example.org/page',10000,True)
        try:
            result=service.verify(request)
            self.assertEqual('PAGE_EVIDENCE',result['outcome'])
            self.assertEqual('DYNAMIC_VIDEO_XHR',result['evidence'])
            self.assertFalse(service._windows.runners[0].poisoned)
            self.assertEqual(['/page','/api'],DynamicHandler.paths)
        finally:service.close();server.shutdown();server.server_close();thread.join(1)

if __name__=='__main__':unittest.main()
