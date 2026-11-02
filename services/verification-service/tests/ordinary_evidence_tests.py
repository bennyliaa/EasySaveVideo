"""Real Selector tests; no network, no changes to benchmark bytes."""
import pathlib,sys,unittest
sys.path.insert(0,str(pathlib.Path(__file__).resolve().parents[1]))
from scrapling import Selector
from esv_verify.adapter import static_evidence,structured_declaration
from esv_verify.capability import FixtureCapability,FixtureRule
from esv_verify.protocol import Request,encode_result,InvalidRequest

class OrdinaryEvidenceTests(unittest.TestCase):
    def test_structured_declaration_is_only_a_candidate(self):
        request=Request(1,'a'*32,'b'*64,'https://fixture.example.org/page',5000,False)
        selector=Selector('<main><script type="application/ld+json">{"@type":"VideoObject","contentUrl":"/media.mp4"}</script></main>')
        self.assertEqual('https://fixture.example.org/media.mp4',structured_declaration(selector,request))
        self.assertIsNone(static_evidence(selector,request))
    def test_primary_and_adversarial_shapes(self):
        request=Request(1,'a'*32,'b'*64,'https://fixture.example.org/page',5000,False)
        player='<video controls src="/media.mp4"></video>'
        cases=[('<main>'+player+'</main>',True),
               ('<main><h1>Title</h1><div>'+player+'</div></main>',True),
               ('<main><article>'+player+'</article></main>',False),
               ('<main><aside>'+player+'</aside></main>',False),
               ('<main class="sponsored">'+player+'</main>',False),
               ('<main hidden>'+player+'</main>',False),
               ('<main style="display: none">'+player+'</main>',False),
               ('<main>'+player+player+'</main>',False),
               ('<main>'+player+'<iframe></iframe></main>',False),
               ('<main><video controls src="/one"><source src="/two"></video></main>',False),
               ('<main><video controls src="https://user:secret@example.org/a"></video></main>',False),
               ('<main><video controls src="/media.mp4#part"></video></main>',False),
               ('<main><script type="application/ld+json">{"@type":"VideoObject","contentUrl":"/media.mp4"}</script></main>',False),
               ('<main></main>',False)]
        for body,expected in cases:
            with self.subTest(body=body):
                result=static_evidence(Selector(body,url=request.candidateUrl),request)
                self.assertEqual(expected,result is not None)
                if result:self.assertFalse(result['playbackVerified']);self.assertFalse(result['downloadable'])

    def test_dom_consent_and_capability_are_distinct(self):
        denied=Request(1,'a'*32,'b'*64,'https://fixture.example.org/page',5000,False)
        with self.assertRaises(InvalidRequest):encode_result(denied,denied.result('PAGE_EVIDENCE','DYNAMIC_VIDEO_DOM'))
        with self.assertRaises(ValueError):FixtureCapability('http://127.0.0.1:1234',(FixtureRule('/page','/api','id',True),))
        allowed=Request(1,'a'*32,'b'*64,'https://fixture.example.org/page',5000,True)
        self.assertIn(b'DYNAMIC_VIDEO_DOM',encode_result(allowed,allowed.result('PAGE_EVIDENCE','DYNAMIC_VIDEO_DOM')))

if __name__=='__main__':unittest.main()
