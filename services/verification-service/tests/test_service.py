import dataclasses
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import importlib.util
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import threading
import time
import unittest
from unittest.mock import patch
from esv_verify.capability import FixtureCapability, FixtureRule
from esv_verify.protocol import Request, InvalidRequest, encode_result, MAX_REQUEST, public_thumbnail
from esv_verify.server import Server
from esv_verify.service import VerificationService

EVAL = Path(os.environ.get('ESV_SCRAPLING_EVAL', '/workspace/scratch/df6c1284d5ab/scrapling-admission-20261008'))
ADMISSION = EVAL / 'next-gate/BROWSER_ADMISSION.json'

def request(url='https://example.org/page', **changes):
    data = dict(version=1, requestId='a' * 32, generation='b' * 64, candidateUrl=url, budgetMs=10000, dynamicAllowed=False)
    data.update(changes)
    return Request.parse(json.dumps(data).encode())

def body(req):
    return json.dumps(dataclasses.asdict(req)).encode()

class ProtocolTests(unittest.TestCase):
    def test_exact_keys(self):
        data = dataclasses.asdict(request())
        for key in data:
            with self.subTest(key=key), self.assertRaises(InvalidRequest):
                Request.parse(json.dumps({k: v for k, v in data.items() if k != key}).encode())
        data['fixture'] = True
        with self.assertRaises(InvalidRequest): Request.parse(json.dumps(data).encode())

    def test_integer_types(self):
        for key, value in [('version', True), ('version', 1.0), ('budgetMs', False), ('budgetMs', 1.0), ('budgetMs', 0), ('budgetMs', 10001), ('dynamicAllowed', 1)]:
            with self.subTest(key=key, value=value), self.assertRaises(InvalidRequest): request(**{key: value})

    def test_duplicates_nonfinite_trailing_utf8(self):
        raw = body(request())
        for malformed in [raw[:-1] + b',"version":1}', raw + b' {}', b'{"version":NaN}', b'\xff', b'[]']:
            with self.subTest(raw=malformed), self.assertRaises(InvalidRequest): Request.parse(malformed)

    def test_bounded_sizes(self):
        for raw in [b'', b' ' * (MAX_REQUEST + 1)]:
            with self.assertRaises(InvalidRequest): Request.parse(raw)
        with self.assertRaises(InvalidRequest): request('https://example.org/' + 'x' * 4096)

    def test_identity_format(self):
        for key, value in [('requestId', 'A' * 32), ('requestId', 'a' * 31), ('generation', 'g' * 64)]:
            with self.assertRaises(InvalidRequest): request(**{key: value})

    def test_credentials_fragment_control_and_odd_url(self):
        for url in ['https://user:secret@example.org/', 'https://example.org/#x', 'file:///a', 'https://example.org/a\nb', 'https://example.org\\a', 'https://%31%32%37.0.0.1/', 'https://example.org:0/', 'http://[bad]/', 'https://x..com/']:
            with self.subTest(url=url), self.assertRaises(InvalidRequest): request(url)

    def test_public_thumbnail_no_lookup(self):
        with patch('socket.getaddrinfo', side_effect=AssertionError('DNS')):
            self.assertEqual(public_thumbnail('https://upload.wikimedia.org/a.png'), 'https://upload.wikimedia.org/a.png')
            for url in ['http://example.org/a', 'https://127.0.0.1/a', 'https://[::1]/a', 'https://x.local/a', 'https://example.org:8443/a', None]: self.assertIsNone(public_thumbnail(url))

    def test_result_identity_and_metadata_fences(self):
        req = request()
        valid = req.result('PAGE_EVIDENCE', 'HTML_VIDEO', 'A', None, 12)
        encode_result(req, valid)
        for key, value in [('requestId', 'c' * 32), ('generation', 'd' * 64), ('pageUrl', 'https://example.org/other'), ('version', True), ('playbackVerified', 0), ('downloadable', True), ('durationMs', True), ('evidence', 'NONE')]:
            with self.subTest(key=key), self.assertRaises(InvalidRequest): encode_result(req, {**valid, key: value})
        with self.assertRaises(InvalidRequest): encode_result(req, {**req.result('INSUFFICIENT'), 'title': 'not allowed'})

    def test_fixture_capability_is_exact(self):
        cap = FixtureCapability('http://127.0.0.1:12345', (FixtureRule('/video'),))
        self.assertIsNotNone(cap.rule_for('http://127.0.0.1:12345/video'))
        for url in ['http://localhost:12345/video', 'http://127.0.0.1:12345/video?x=1', 'http://127.0.0.1:12345/video/', 'http://127.0.0.1:12346/video']: self.assertIsNone(cap.rule_for(url))
        for origin in ['http://localhost:12345', 'https://example.org', 'http://127.0.0.1:12345/']:
            with self.assertRaises(ValueError): FixtureCapability(origin, (FixtureRule('/video'),))

class DefaultServiceTests(unittest.TestCase):
    def test_no_runtime_import_dns_process_or_network(self):
        with patch('socket.getaddrinfo', side_effect=AssertionError('DNS')), patch('subprocess.Popen', side_effect=AssertionError('PROCESS')):
            self.assertEqual(VerificationService().verify(request())['outcome'], 'UNSUPPORTED')
    def test_clean_process_imports_no_optional_runtime(self):
        subprocess.run([sys.executable, '-c', 'from esv_verify.server import Server; import sys; assert not any(n.startswith(("scrapling", "playwright", "curl_cffi")) for n in sys.modules)'], check=True, timeout=5)
    def test_no_cli_or_environment_fixture_switch(self):
        output = subprocess.run([sys.executable, '-m', 'esv_verify', '--help'], check=True, capture_output=True, text=True, timeout=5)
        self.assertNotIn('--fixture', output.stdout)
        with patch.dict(os.environ, {'ESV_FIXTURE_ORIGIN': 'http://127.0.0.1:1', 'ESV_BROWSER': '/tmp/fake'}):
            self.assertEqual(VerificationService().verify(request('http://127.0.0.1:1/video'))['outcome'], 'UNSUPPORTED')
    def test_initial_cancellation_and_deadline(self):
        service = VerificationService()
        self.assertEqual(service.verify(request(), lambda: True)['outcome'], 'CANCELLED')
        self.assertEqual(service.verify(request(budgetMs=1), accepted_at=time.monotonic() - 1)['outcome'], 'TIMEOUT')
        service.close()
        self.assertEqual(service.verify(request())['outcome'], 'CANCELLED')

class HTTPTests(unittest.TestCase):
    def setUp(self):
        self.server = Server()
        self.thread = threading.Thread(target=self.server.serve_forever, kwargs={'poll_interval': .02})
        self.thread.start()
    def tearDown(self):
        self.server.shutdown(); self.server.server_close(); self.thread.join(2)
    def raw(self, raw):
        with socket.create_connection(self.server.server_address, timeout=2) as sock:
            sock.sendall(raw); response = bytearray()
            try:
                while chunk := sock.recv(65536): response.extend(chunk)
            except ConnectionResetError: pass
            return bytes(response)
    def test_real_http_default_contract(self):
        conn = http.client.HTTPConnection(*self.server.server_address, timeout=2)
        req = request()
        conn.request('POST', '/v1/verify', body(req), {'Content-Type': 'application/json', 'Accept-Encoding': 'identity'})
        response = conn.getresponse()
        self.assertEqual(response.status, 200)
        self.assertEqual(json.loads(response.read()), req.result('UNSUPPORTED')); conn.close()
    def test_reject_duplicate_headers_compression_credentials_and_chunking(self):
        raw = body(request())
        for extra in [b'Content-Length: 1\r\n', b'Transfer-Encoding: chunked\r\n', b'Content-Encoding: gzip\r\n', b'Cookie: x=y\r\n', b'Authorization: secret\r\n']:
            response = self.raw(b'POST /v1/verify HTTP/1.1\r\nHost: local\r\nContent-Type: application/json\r\nContent-Length: ' + str(len(raw)).encode() + b'\r\n' + extra + b'\r\n' + raw)
            self.assertTrue(response.startswith(b'HTTP/1.1 400'))
    def test_header_and_body_size(self):
        self.assertTrue(self.raw(b'POST /v1/verify HTTP/1.1\r\nHost: local\r\nX-Large: ' + b'x' * 16384 + b'\r\n\r\n').startswith(b'HTTP/1.1 431'))
        self.assertTrue(self.raw(b'POST /v1/verify HTTP/1.1\r\nHost: local\r\nContent-Type: application/json\r\nContent-Length: 8193\r\n\r\n').startswith(b'HTTP/1.1 413'))
    def test_cannot_bind_public_interface(self):
        with self.assertRaises(ValueError): Server(('0.0.0.0', 0))

class OwnedFixtures:
    def __init__(self):
        self.seen = []; self.logical_origin = None; owner = self
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args): pass
            def do_GET(self):
                owner.seen.append((self.path, dict(self.headers)))
                if self.path == '/slow': time.sleep(2)
                if self.path == '/redirect':
                    self.send_response(302); self.send_header('Location', 'https://example.org/never'); self.end_headers(); return
                content_type = 'text/html'
                if self.path.startswith('/api-'):
                    page = self.path.removeprefix('/api-').removesuffix('.json')
                    data = dict(videoId='owned-7', pageUrl=(owner.logical_origin or owner.origin) + '/' + page, title='Owned video', durationMs=12000)
                    if page == 'wrong-identity': data['pageUrl'] = owner.origin + '/other'
                    if page == 'wrong-video': data['videoId'] = 'wrong'
                    if page == 'missing': data.pop('title'); data.pop('durationMs')
                    content = json.dumps(data).encode(); content_type = 'application/json'
                elif self.path in {'/dynamic', '/article', '/ambiguous', '/wrong-identity', '/wrong-video', '/missing', '/external'}:
                    name = self.path[1:]
                    video = "const v=document.createElement('video');v.dataset.videoId=x.videoId;v.preload='none';"
                    if name != 'missing': video += "v.dataset.durationMs=String(x.durationMs);"
                    video += 'main.appendChild(v);'
                    if name == 'article': video = ''
                    if name == 'ambiguous': video += "main.appendChild(v.cloneNode());"
                    h1 = "const h=document.createElement('h1');h.textContent=x.title;main.appendChild(h);" if name != 'missing' else ''
                    extra = "fetch('https://example.org/never').catch(()=>{});try{new WebSocket('wss://example.org/never')}catch(e){};" if name == 'external' else ''
                    content = ("<html><main></main><script>" + extra + "fetch('/api-" + name + ".json').then(r=>r.json()).then(x=>{const main=document.querySelector('main');" + h1 + video + "main.dataset.ready='true';});</script></html>").encode()
                elif self.path == '/hang': content = b'<main></main><script>while(true){}</script>'
                elif self.path == '/related': content = b'<main><article><h1>Article</h1><p>Related clip</p><video src="/related.mp4"></video></article></main>'
                elif self.path == '/space-source': content = b'<main><video data-esv-primary="true" src="   "></video></main>'
                elif self.path == '/bad-source': content = b'<main><video data-esv-primary="true" src="https://"></video></main>'
                elif self.path == '/oversized': content = b'x' * (256 * 1024 + 1)
                elif self.path == '/deceptive': content = b'<main><h1>Article</h1><script type="application/ld+json">{"@type":"VideoObject"}</script></main>'
                elif self.path == '/multiple': content = b'<main><video src="/one.mp4"></video><video src="/two.mp4"></video></main>'
                elif self.path == '/outside': content = b'<main><h1>Article</h1></main><video src="/ad.mp4"></video>'
                elif self.path == '/no-source': content = b'<main><h1>Not enough</h1><video></video></main>'
                elif self.path == '/static-missing': content = b'<main><video data-esv-primary="true"><source src="/never.mp4"></video></main>'
                else: content = b'<main><h1>Owned video</h1><video data-esv-primary="true" preload="none" data-duration-ms="12000" poster="https://upload.wikimedia.org/never.jpg"><source src="/never.mp4"></video></main>'
                self.send_response(200); self.send_header('Content-Type', content_type)
                if self.path == '/cookie': self.send_header('Set-Cookie', 'fixture=forbidden')
                if self.path == '/compressed': self.send_header('Content-Encoding', 'gzip')
                self.send_header('Content-Length', str(len(content))); self.end_headers()
                try: self.wfile.write(content)
                except (BrokenPipeError, ConnectionResetError): pass
        self.server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.origin = f'http://127.0.0.1:{self.server.server_port}'
        self.thread = threading.Thread(target=self.server.serve_forever, kwargs={'poll_interval': .02}); self.thread.start()
    def capability(self, browser=False):
        paths = ['static', 'static-missing', 'deceptive', 'multiple', 'outside', 'no-source', 'redirect', 'oversized', 'slow', 'cookie', 'compressed', 'related', 'space-source', 'bad-source']
        rules = [FixtureRule('/' + path) for path in paths]
        rules += [FixtureRule('/' + path, '/api-' + path + '.json', 'owned-7') for path in ['dynamic', 'article', 'ambiguous', 'wrong-identity', 'wrong-video', 'missing', 'external', 'hang']]
        kwargs = {}
        if browser:
            gate = json.loads(ADMISSION.read_text())
            if not gate['allow_execution'] or not gate['explicit_user_terms_approval'] or gate['public_site_execution_admitted']: raise AssertionError('EXISTING_ADMISSION_REQUIRED')
            kwargs = dict(browser_executable=gate['executable_path'], browser_sha256=gate['executable_sha256'])
        return FixtureCapability(self.origin, tuple(rules), **kwargs)
    def close(self):
        self.server.shutdown(); self.server.server_close(); self.thread.join(2)

@unittest.skipUnless(importlib.util.find_spec('scrapling'), 'Exact separately admitted runtime required')
class RuntimeTests(unittest.TestCase):
    def setUp(self):
        self.fixtures = OwnedFixtures(); self.service = VerificationService(self.fixtures.capability())
    def tearDown(self):
        self.service.close(); self.fixtures.close()
    def verify(self, path, **kwargs):
        return self.service.verify(request(self.fixtures.origin + path, **kwargs))
    def test_actual_fetcher_selector_static(self):
        result = self.verify('/static')
        self.assertEqual(result['outcome'], 'PAGE_EVIDENCE', result); self.assertEqual(result['evidence'], 'HTML_VIDEO')
        self.assertEqual(result['title'], 'Owned video'); self.assertEqual(result['durationMs'], 12000)
        self.assertFalse(result['playbackVerified']); self.assertFalse(result['downloadable'])
        self.assertEqual([row[0] for row in self.fixtures.seen], ['/static'])
        headers = {k.lower(): v for k, v in self.fixtures.seen[0][1].items()}
        self.assertFalse(set(headers).intersection({'cookie', 'authorization', 'referer', 'proxy-authorization'})); self.assertEqual(headers['accept-encoding'], 'identity')
    def test_static_unknown_fields_stay_null(self):
        result = self.verify('/static-missing'); self.assertEqual(result['outcome'], 'PAGE_EVIDENCE')
        self.assertTrue(all(result[k] is None for k in ('title', 'thumbnailUrl', 'durationMs')))
    def test_deceptive_ambiguous_nonprimary_sourceless_remain_insufficient(self):
        for path in ['/deceptive', '/multiple', '/outside', '/no-source', '/related', '/space-source', '/bad-source']:
            with self.subTest(path=path): self.assertEqual(self.verify(path)['outcome'], 'INSUFFICIENT')
    def test_redirect_cookie_compression_size_rejected(self):
        for path in ['/redirect', '/cookie', '/compressed', '/oversized']:
            with self.subTest(path=path): self.assertEqual(self.verify(path)['outcome'], 'REJECTED')
        self.assertEqual(len(self.fixtures.seen), 4)
    def test_exact_allowlist_no_query_alias_external_or_other_port(self):
        for url in [self.fixtures.origin + '/static?x=1', self.fixtures.origin.replace('127.0.0.1', 'localhost') + '/static', 'https://example.org/page', 'http://127.0.0.1:1/static']:
            self.assertEqual(self.service.verify(request(url))['outcome'], 'UNSUPPORTED')
        self.assertEqual(self.fixtures.seen, [])
    def test_disabled_dynamic_does_not_launch_browser(self):
        self.assertEqual(self.verify('/dynamic')['outcome'], 'INSUFFICIENT'); self.assertEqual([row[0] for row in self.fixtures.seen], ['/dynamic'])
    def test_missing_browser_unavailable(self):
        self.assertEqual(self.verify('/dynamic', dynamicAllowed=True)['outcome'], 'UNAVAILABLE')
    def test_timeout_kills_worker_and_recovers_slot(self):
        started = time.monotonic(); self.assertEqual(self.verify('/slow', budgetMs=100)['outcome'], 'TIMEOUT')
        self.assertLess(time.monotonic() - started, 1); self.assertEqual(self.verify('/static')['outcome'], 'PAGE_EVIDENCE')
    def test_cancellation_cleanup_and_physical_busy(self):
        cancel = threading.Event(); self.service = VerificationService(self.fixtures.capability(), max_workers=1)
        req = request(self.fixtures.origin + '/slow'); results = []
        thread = threading.Thread(target=lambda: results.append(self.service.verify(req, cancel.is_set))); thread.start()
        deadline = time.monotonic() + 3
        while not self.fixtures.seen and time.monotonic() < deadline: time.sleep(.01)
        self.assertEqual(self.service.verify(request(self.fixtures.origin + '/static', requestId='c' * 32))['outcome'], 'BUSY')
        self.assertEqual(self.service.verify(req)['outcome'], 'BUSY'); cancel.set(); thread.join(2)
        self.assertFalse(thread.is_alive()); self.assertEqual(results[0]['outcome'], 'CANCELLED')
        self.assertEqual(self.verify('/static')['outcome'], 'PAGE_EVIDENCE')
    def test_fail_closed_if_cleanup_unproven(self):
        from esv_verify.service import terminate_tree
        def unproven(process, tracked): terminate_tree(process, tracked); return False
        with patch('esv_verify.service.terminate_tree', side_effect=unproven): self.assertEqual(self.verify('/static')['outcome'], 'UNAVAILABLE')
        self.assertEqual(self.verify('/static')['outcome'], 'BUSY')
    def test_throwing_cleanup_poisoned_slot(self):
        from esv_verify.service import terminate_tree
        def broken(process, tracked):
            terminate_tree(process, tracked)
            raise OSError('SYNTHETIC_PROOF_FAILURE')
        with patch('esv_verify.service.terminate_tree', side_effect=broken):
            self.assertEqual(self.verify('/static')['outcome'], 'UNAVAILABLE')
        self.assertEqual(self.verify('/static')['outcome'], 'BUSY')

    def test_stale_worker_identity_cannot_publish(self):
        from esv_verify import service as module
        original = module.strict_json
        def corrupt(raw):
            parsed = original(raw); parsed['generation'] = 'c' * 64; return parsed
        with patch.object(module, 'strict_json', side_effect=corrupt): self.assertEqual(self.verify('/static')['outcome'], 'UNAVAILABLE')

@unittest.skipUnless(ADMISSION.exists() and importlib.util.find_spec('scrapling'), 'Existing admitted browser required')
class BrowserTests(unittest.TestCase):
    def setUp(self):
        self.fixtures = OwnedFixtures(); self.service = VerificationService(self.fixtures.capability(browser=True), max_workers=1)
    def tearDown(self):
        self.service.close(); self.fixtures.close()
    def verify(self, path, **kwargs):
        return self.service.verify(request(self.fixtures.origin + path, dynamicAllowed=True, **kwargs))
    def test_actual_dynamic_javascript_and_captured_xhr(self):
        result = self.verify('/dynamic'); self.assertEqual(result['outcome'], 'PAGE_EVIDENCE', result)
        self.assertEqual(result['evidence'], 'DYNAMIC_VIDEO_XHR'); self.assertEqual(result['title'], 'Owned video')
        self.assertEqual(result['durationMs'], 12000); self.assertIsNone(result['thumbnailUrl'])
        self.assertEqual([row[0] for row in self.fixtures.seen], ['/dynamic', '/api-dynamic.json'])
    def test_dynamic_article_ambiguity_and_identity_negatives(self):
        for path in ['/article', '/ambiguous', '/wrong-identity', '/wrong-video']:
            with self.subTest(path=path): self.assertEqual(self.verify(path)['outcome'], 'INSUFFICIENT')
    def test_dynamic_missing_fields_not_promoted_from_xhr(self):
        result = self.verify('/missing'); self.assertEqual(result['outcome'], 'PAGE_EVIDENCE', result)
        self.assertTrue(all(result[k] is None for k in ('title', 'thumbnailUrl', 'durationMs')))
    def test_external_fetch_websocket_and_media_never_reach_network(self):
        result = self.verify('/external', budgetMs=1800); self.assertIn(result['outcome'], {'PAGE_EVIDENCE', 'REJECTED', 'TIMEOUT'})
        self.assertTrue(self.fixtures.seen)
        self.assertTrue(all(row[0] in {'/external', '/api-external.json'} for row in self.fixtures.seen))
        self.assertLessEqual(len(self.fixtures.seen), 2)
    def test_browser_hash_mismatch_is_unavailable(self):
        cap = dataclasses.replace(self.fixtures.capability(browser=True), browser_sha256='0' * 64)
        self.service = VerificationService(cap); self.assertEqual(self.verify('/dynamic')['outcome'], 'UNAVAILABLE')
        self.assertEqual([row[0] for row in self.fixtures.seen], ['/dynamic'])



class AdditionalBoundaryTests(unittest.TestCase):
    def test_lone_surrogates_and_utf16_title_limit(self):
        with self.assertRaises(InvalidRequest): Request.parse(body(request()).replace(b'https://example.org/page', b'https://example.org/\\ud800'))
        req = request()
        encode_result(req, req.result('PAGE_EVIDENCE', 'HTML_VIDEO', '😀' * 256))
        for title in ['😀' * 257, '\ud800', 'a\x85b']:
            with self.assertRaises(InvalidRequest): encode_result(req, req.result('PAGE_EVIDENCE', 'HTML_VIDEO', title))
        with self.assertRaises(InvalidRequest): encode_result(req, req.result('PAGE_EVIDENCE', 'DYNAMIC_VIDEO_XHR'))

    def test_thumbnail_negative_corpus(self):
        for suffix in ['localdomain', 'lan', 'home', 'alt', 'arpa']:
            self.assertIsNone(public_thumbnail('https://a.' + suffix + '/x'))
        for url in ['https://8.8.8.8/x', 'https://example.org:0443/x', 'https://example.org:/x', 'https://localhost.example.org/x', 'https://example.org/a/../x', 'https://example.org/%2e%2e/x', 'https://example.org/%00x', 'https://example.org/%5cx', 'https://example.org/%zz']:
            self.assertIsNone(public_thumbnail(url), url)

    def test_pid_reuse_never_adopts_new_process(self):
        from esv_verify.service import own_tree
        known = {100: 'old-root', 101: 'old-child'}
        rows = {100: (1, 'new-root', 'S', 10), 101: (1, 'new-child', 'S', 10), 102: (100, 'unrelated', 'S', 10)}
        self.assertEqual(own_tree(100, rows, known), {})
        self.assertEqual(known, {100: 'old-root', 101: 'old-child'})
        rows = {101: (1, 'old-child', 'S', 10), 103: (101, 'owned-grandchild', 'S', 10)}
        self.assertEqual(set(own_tree(100, rows, known)), {101, 103})

    def test_freeze_requires_two_fully_stopped_snapshots(self):
        from esv_verify.service import freeze_owned_tree
        running = {100: (1, 'pinned', 'S', 10)}
        stopped = {100: (1, 'pinned', 'T', 10)}
        with patch('esv_verify.service.own_tree', side_effect=[running, stopped, stopped, stopped]) as snapshots, patch('esv_verify.service.process_table', return_value={}), patch('esv_verify.service.signal_owned'), patch('time.sleep'):
            self.assertEqual(freeze_owned_tree(100, {100: 'pinned'}), stopped)
            self.assertEqual(snapshots.call_count, 4)

    def test_pidfd_signal_checks_pinned_start(self):
        from esv_verify.service import signal_owned
        with patch('os.pidfd_open', return_value=42), patch('os.close'), patch('signal.pidfd_send_signal') as send, patch('esv_verify.service.process_table', return_value={100: (1, 'new', 'S', 10)}):
            signal_owned(100, 'old', 9)
            send.assert_not_called()


@unittest.skipUnless(ADMISSION.exists() and importlib.util.find_spec('scrapling'), 'Existing admitted browser required')
class LifecycleTests(unittest.TestCase):
    def test_browser_timeout_reaps_owned_tree_and_recovers(self):
        from esv_verify import service as module
        fixtures = OwnedFixtures()
        service = VerificationService(fixtures.capability(browser=True), max_workers=1)
        tracked = {}
        original = module.own_tree
        def track(root, rows, known):
            found = original(root, rows, known)
            tracked.update({pid: row[1] for pid, row in found.items()})
            return found
        try:
            with patch.object(module, 'own_tree', side_effect=track):
                result = service.verify(request(fixtures.origin + '/hang', dynamicAllowed=True, budgetMs=1800))
            self.assertEqual(result['outcome'], 'TIMEOUT')
            self.assertGreaterEqual(len(tracked), 3)
            current = module.process_table()
            self.assertFalse(any(pid in current and current[pid][1] == start and current[pid][2] != 'Z' for pid, start in tracked.items()))
            self.assertEqual(service.verify(request(fixtures.origin + '/static'))['outcome'], 'PAGE_EVIDENCE')
        finally: service.close(); fixtures.close()

    def test_http_disconnect_cancels_and_releases_worker(self):
        fixtures = OwnedFixtures()
        service = VerificationService(fixtures.capability(), max_workers=1)
        server = Server(service=service)
        thread = threading.Thread(target=server.serve_forever, kwargs={'poll_interval': .02}); thread.start()
        try:
            sock = socket.create_connection(server.server_address, timeout=2)
            raw = body(request(fixtures.origin + '/slow'))
            sock.sendall(b'POST /v1/verify HTTP/1.1\r\nHost: local\r\nContent-Type: application/json\r\nContent-Length: ' + str(len(raw)).encode() + b'\r\n\r\n' + raw)
            deadline = time.monotonic() + 2
            while not fixtures.seen and time.monotonic() < deadline: time.sleep(.01)
            sock.close()
            while service._active and time.monotonic() < deadline: time.sleep(.01)
            self.assertFalse(service._active)
            self.assertEqual(service.verify(request(fixtures.origin + '/static'))['outcome'], 'PAGE_EVIDENCE')
        finally: server.shutdown(); server.server_close(); thread.join(2); fixtures.close()


@unittest.skipUnless(os.environ.get('ESV_KOTLIN_FIXTURE_CLASSPATH'), 'Compile the Kotlin fixture harness first')
class CrossLanguageTests(unittest.TestCase):
    def setUp(self):
        self.fixtures = OwnedFixtures()
        self.fixtures.logical_origin = 'https://fixture.example.org'
        cap = dataclasses.replace(self.fixtures.capability(browser=True), logical_origin=self.fixtures.logical_origin)
        self.server = Server(service=VerificationService(cap))
        self.thread = threading.Thread(target=self.server.serve_forever, kwargs={'poll_interval': .02}); self.thread.start()
    def tearDown(self):
        self.server.shutdown(); self.server.server_close(); self.thread.join(2); self.fixtures.close()
    def run_kotlin(self, path, outcome, evidence='NONE', duration='null'):
        java = str(Path(os.environ['JAVA_HOME']) / 'bin/java') if os.environ.get('JAVA_HOME') else 'java'
        result = subprocess.run([java, '-cp', os.environ['ESV_KOTLIN_FIXTURE_CLASSPATH'],
            'com.easysavevideo.verification.VerificationFixtureHarness', str(self.server.server_address[1]),
            self.fixtures.logical_origin + path, outcome, evidence, duration], capture_output=True, text=True, timeout=15)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertTrue(all(path.startswith(('/static', '/dynamic', '/api-', '/article', '/wrong-identity')) for path, _ in self.fixtures.seen))
    def test_kotlin_python_static(self): self.run_kotlin('/static', 'PAGE_EVIDENCE', 'HTML_VIDEO', '12000')
    def test_kotlin_python_dynamic_xhr(self): self.run_kotlin('/dynamic', 'PAGE_EVIDENCE', 'DYNAMIC_VIDEO_XHR', '12000')
    def test_kotlin_python_article_negative(self): self.run_kotlin('/article', 'INSUFFICIENT')
    def test_kotlin_python_identity_negative(self): self.run_kotlin('/wrong-identity', 'INSUFFICIENT')
    def test_kotlin_python_unknown_fields(self): self.run_kotlin('/static-missing', 'PAGE_EVIDENCE', 'HTML_VIDEO')

class RuntimeAdmissionTests(unittest.TestCase):
    def test_exact_existing_runtime_and_gate(self):
        import importlib.metadata
        self.assertEqual(Path(sys.prefix).resolve(), (EVAL / '.venv').resolve())
        for package, version in [('scrapling', '0.4.15'), ('lxml', '6.1.3+esv.eval1'), ('playwright', '1.63.0'), ('curl_cffi', '0.16.3')]:
            self.assertEqual(importlib.metadata.version(package), version)
        gate = json.loads(ADMISSION.read_text())
        self.assertTrue(gate['allow_execution'])
        self.assertTrue(gate['explicit_user_terms_approval'])
        self.assertFalse(gate['allow_distribution'])
        self.assertFalse(gate['public_site_execution_admitted'])
        self.assertEqual(gate['executable_sha256'], 'ded93a9c9a53a1ae040f08124badcca95c938e9d5015ff340c3b5538c41bf39e')

    def test_real_setup_failure_closes_before_browser_navigation(self):
        fixtures = OwnedFixtures()
        req = request(fixtures.origin + '/dynamic', dynamicAllowed=True)
        cap = fixtures.capability(browser=True)
        script = "import sys;sys.path.insert(0," + repr(str(Path(__file__).resolve().parents[1])) + ");from playwright.sync_api import BrowserContext\n" + \
                 "def fail(*args,**kwargs): raise RuntimeError('INJECTED_SETUP_FAILURE')\n" + \
                 "BrowserContext.route=fail\nfrom esv_verify.worker import main;main()"
        envelope = dict(request=dataclasses.asdict(req), capability=dataclasses.asdict(cap), deadline=time.monotonic() + 8)
        try:
            result = subprocess.run([sys.executable, '-I', '-B', '-c', script], input=json.dumps(envelope).encode(), capture_output=True, timeout=10)
            self.assertEqual(result.returncode, 0)
            self.assertEqual(json.loads(result.stdout)['outcome'], 'UNAVAILABLE')
            self.assertEqual([row[0] for row in fixtures.seen], ['/dynamic'])
        finally: fixtures.close()


if __name__ == '__main__': unittest.main(verbosity=2)
