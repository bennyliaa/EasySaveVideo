import pathlib,sys,unittest,threading,json
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
sys.path.insert(0,str(pathlib.Path(__file__).resolve().parents[1]))
from esv_verify.service import VerificationService
from esv_verify.capability import FixtureCapability,FixtureRule
from esv_verify.protocol import Request

class OwnedHandler(BaseHTTPRequestHandler):
    body=b'<main><h1>Owned Windows fixture</h1><video data-esv-primary="true" src="/owned.mp4"></video></main>'
    requests=0
    def do_GET(self):
        type(self).requests+=1
        if self.path!='/page':self.send_error(403);return
        self.send_response(200);self.send_header('Content-Type','text/html');self.send_header('Content-Length',str(len(self.body)));self.end_headers();self.wfile.write(self.body)
    def log_message(self,*args):pass

class WindowsRealStaticTests(unittest.TestCase):
    def test_real_scrapling_fetcher_selector_private_worker(self):
        server=ThreadingHTTPServer(('127.0.0.1',0),OwnedHandler)
        thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
        origin='http://127.0.0.1:'+str(server.server_address[1])
        capability=FixtureCapability(origin,(FixtureRule('/page'),),logical_origin='https://fixture.example.org')
        service=VerificationService(capability,max_workers=1)
        request=Request(1,'a'*32,'b'*64,'https://fixture.example.org/page',5000,False)
        try:
            result=service.verify(request)
            self.assertEqual('PAGE_EVIDENCE',result['outcome'])
            self.assertEqual('HTML_VIDEO',result['evidence'])
            self.assertFalse(service._windows.runners[0].poisoned)
            self.assertEqual(1,OwnedHandler.requests)
            self.assertFalse(result['playbackVerified']);self.assertFalse(result['downloadable'])
        finally:service.close();server.shutdown();server.server_close();thread.join(1)

if __name__=='__main__':unittest.main()
