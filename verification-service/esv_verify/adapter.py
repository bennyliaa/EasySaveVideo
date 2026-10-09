"""Original adapter for the separately admitted exact Scrapling runtime.

No crawler, stealth fetcher, credentials, public-source rules or fallback transport.
All browser navigation is route-fulfilled from bounded, owned loopback responses.
"""
import hashlib
import importlib.metadata
import re
import time
from pathlib import Path
from urllib.parse import urljoin
from .protocol import InvalidRequest, page_url, public_thumbnail, strict_json, valid_title

MAX_HTML = 256 * 1024
MAX_XHR = 32 * 1024
MAX_DOM = 512 * 1024
EXCLUDED_DEFAULT_ARGS = [
    '--disable-client-side-phishing-detection',
    '--disable-features=AvoidUnnecessaryBeforeUnloadCheckSync,DestroyProfileOnBrowserClose,DialMediaRouteProvider,GlobalMediaControls,HttpsUpgrades,LensOverlay,MediaRouter,PaintHolding,ThirdPartyStoragePartitioning,BlockOriginHeaderModificationOnRedirect,Translate,AutoDeElevate,OptimizationHints,msForceBrowserSignIn,msEdgeUpdateLaunchServicesPreferredVersion',
    '--disable-hang-monitor', '--disable-ipc-flooding-protection', '--disable-popup-blocking',
    '--password-store=basic', '--use-mock-keychain', '--unsafely-disable-devtools-self-xss-warnings',
    '--enable-unsafe-swiftshader',
    '--blink-settings=primaryHoverType=2,availableHoverTypes=2,primaryPointerType=4,availablePointerTypes=4',
]


class Rejected(Exception):
    pass


class Unavailable(Exception):
    pass


def remaining(deadline):
    seconds = deadline - time.monotonic()
    if seconds <= 0:
        raise TimeoutError()
    return seconds


def read_fixture(url, allowed_urls, limit, deadline):
    """Fetcher streams to a bounded buffer before a real Selector parses it."""
    if url not in allowed_urls:
        raise Rejected()
    from scrapling.fetchers import Fetcher
    body = bytearray()
    over_limit = False

    def receive(chunk):
        nonlocal over_limit
        if len(body) + len(chunk) > limit:
            over_limit = True
            return 0xFFFFFFFF  # curl_cffi CURL_WRITEFUNC_ERROR; zero is ignored
        body.extend(chunk)
        return len(chunk)

    try:
        response = Fetcher.get(url, verify=True, impersonate=None, stealthy_headers=False,
            headers={'User-Agent': 'EasySaveVideo-Owned-Fixture-Verification/1',
                     'Accept': 'text/html, application/json', 'Accept-Encoding': 'identity'},
            proxy=None, proxies={}, follow_redirects=False, max_redirects=0, retries=1,
            retry_delay=0, timeout=remaining(deadline), discard_cookies=True, cookies={},
            http3=False, content_callback=receive, accept_encoding='identity',
            selector_config={'huge_tree': False, 'adaptive': False})
    except Exception as error:
        if over_limit or getattr(error, "code", None) == 61:
            raise Rejected() from error
        if time.monotonic() >= deadline:
            raise TimeoutError() from error
        raise Unavailable() from error
    if over_limit:
        raise Rejected()
    headers = {key.lower(): value for key, value in response.headers.items()}
    if (response.url != url or response.history or 300 <= response.status < 400
            or 'set-cookie' in headers or headers.get('content-encoding', 'identity').lower() != 'identity'):
        raise Rejected()
    if response.status != 200:
        raise Unavailable()
    if sum(len(k) + len(v) for k, v in headers.items()) > 16384:
        raise Rejected()
    return bytes(body), headers.get('content-type', '').split(';', 1)[0].strip().lower()


def one_primary_video(selector):
    all_videos = selector.css('video')
    mains = selector.css('main')
    if len(all_videos) != 1 or len(mains) != 1:
        return None
    videos = mains[0].css('video')
    if len(videos) != 1 or videos[0].attrib.get('hidden') is not None:
        return None
    return videos[0]


def metadata(selector, video):
    headings = selector.css('main h1')
    title = None
    if len(headings) == 1:
        candidate = ' '.join(str(headings[0].get_all_text()).split())
        if candidate and valid_title(candidate):
            title = candidate
    raw_duration = video.attrib.get('data-duration-ms', '')
    duration = int(raw_duration) if re.fullmatch('[0-9]{1,8}', raw_duration) else None
    if duration is not None and not 1 <= duration <= 86400000:
        duration = None
    return title, public_thumbnail(video.attrib.get('poster')), duration


def static_evidence(selector, request):
    video = one_primary_video(selector)
    if video is None or video.attrib.get('data-esv-primary') != 'true':
        return None
    sources = list(video.css('source::attr(src)').getall())
    if video.attrib.get('src'):
        sources.append(video.attrib['src'])
    if not sources or len(sources) > 8:
        return None
    try:
        for source in sources:
            if (not source or source != source.strip() or any(ord(c) <= 32 or 127 <= ord(c) <= 159 for c in source)
                    or source.startswith(('#', 'data:', 'blob:'))):
                return None
            if ':' in source.split('/', 1)[0]:
                page_url(source)
            resolved = urljoin(request.candidateUrl, source)
            if resolved == request.candidateUrl:
                return None
            page_url(resolved)
    except InvalidRequest:
        return None
    title, thumbnail, duration = metadata(selector, video)
    return request.result('PAGE_EVIDENCE', 'HTML_VIDEO', title, thumbnail, duration)


def browser_options(capability, rule, deadline):
    if not capability.browser_executable or importlib.metadata.version('playwright') != '1.63.0':
        raise Unavailable()
    executable = Path(capability.browser_executable)
    digest = hashlib.sha256()
    with executable.open('rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(chunk)
    if digest.hexdigest() != capability.browser_sha256:
        raise Unavailable()
    return dict(headless=True, google_search=False, dns_over_https=False, proxy=None,
        proxy_rotator=None, cookies=[], real_chrome=False, cdp_url=None, user_data_dir='',
        max_pages=1, timeout=max(1, int(remaining(deadline) * 1000)), retries=1, wait=0,
        network_idle=False, capture_xhr='^' + re.escape((capability.logical_origin or capability.origin) + rule.api_path) + '$',
        selector_config={'huge_tree': False, 'adaptive': False}, executable_path=str(executable),
        additional_args={'ignore_https_errors': False, 'permissions': [], 'accept_downloads': False,
            'service_workers': 'block', 'bypass_csp': False, 'chromium_sandbox': True,
            'user_agent': None, 'color_scheme': 'no-preference', 'device_scale_factor': 1,
            'ignore_default_args': EXCLUDED_DEFAULT_ARGS,
            'args': ['--enable-automation', '--disable-background-networking', '--no-first-run',
                     '--no-default-browser-check', '--js-flags=--max-old-space-size=64']})


def dynamic_evidence(html, request, capability, rule, deadline):
    from scrapling.fetchers import DynamicFetcher
    page_url_value = request.candidateUrl
    api_url = (capability.logical_origin or capability.origin) + rule.api_path
    physical_api_url = capability.origin + rule.api_path
    blocked = False
    counts = {'document': 0, 'api': 0}
    setup_ready = False

    def route_handler(route):
        nonlocal blocked
        req = route.request
        try:
            remaining(deadline)
            if (req.method != 'GET' or req.redirected_from is not None
                    or any(k in req.headers for k in ('cookie', 'authorization', 'proxy-authorization'))):
                raise Rejected()
            if req.url == page_url_value and req.resource_type == 'document' and req.frame == req.frame.page.main_frame:
                counts['document'] += 1
                if counts['document'] != 1:
                    raise Rejected()
                body, content_type = html, 'text/html'
            elif req.url == api_url and req.resource_type in {'xhr', 'fetch'} and req.frame == req.frame.page.main_frame:
                counts['api'] += 1
                if counts['api'] != 1:
                    raise Rejected()
                body, content_type = read_fixture(physical_api_url, {physical_api_url}, MAX_XHR, deadline)
                if content_type != 'application/json':
                    raise Rejected()
            else:
                raise Rejected()
            # The browser does not use continue_() or fetch() and cannot reach a server.
            route.fulfill(status=200, content_type=content_type, body=body,
                headers={'Content-Security-Policy': "default-src 'none'; script-src 'unsafe-inline'; connect-src " + api_url + "; img-src 'none'; media-src 'none'; frame-src 'none'; worker-src 'none'; form-action 'none'; base-uri 'none'",
                         'Cache-Control': 'no-store'})
        except Exception:
            blocked = True
            route.abort()

    def setup(page):
        nonlocal setup_ready
        try:
            page.context.clear_cookies()
            page.context.clear_permissions()
            page.context.route('**/*', route_handler)
            page.context.route_web_socket('**/*', lambda websocket: websocket.close())
            page.on('download', lambda download: download.cancel())
            page.on('popup', lambda popup: popup.close())
            session = page.context.new_cdp_session(page)
            for permission in ('geolocation', 'notifications', 'camera', 'microphone'):
                session.send('Browser.setPermission', {'permission': {'name': permission}, 'setting': 'denied'})
            states = page.evaluate("async () => Promise.all(['geolocation','notifications','camera','microphone'].map(async name => (await navigator.permissions.query({name})).state))")
            if any(state != 'denied' for state in states):
                raise Rejected()
            args = session.send('Browser.getBrowserCommandLine')['arguments']
            if ('--no-sandbox' in args or '--enable-automation' not in args
                    or any(arg.startswith('--ignore-certificate-errors') for arg in args)
                    or set(args).intersection(EXCLUDED_DEFAULT_ARGS)
                    or page.evaluate('navigator.webdriver') is not True):
                raise Rejected()
            setup_ready = True
        except BaseException:
            # Scrapling swallows setup exceptions: close first, preventing navigation.
            page.close()
            raise

    def inspect_dom(page):
        if page.url != page_url_value or page.evaluate('document.documentElement.outerHTML.length') > MAX_DOM:
            page.close()
            raise Rejected()

    options = browser_options(capability, rule, deadline)
    response = DynamicFetcher.fetch(page_url_value, **options, page_setup=setup,
        page_action=inspect_dom, wait_selector='main[data-ready="true"]')
    remaining(deadline)
    if not setup_ready or blocked or response.url != page_url_value or response.history or len(response.body) > MAX_DOM:
        raise Rejected()
    video = one_primary_video(response)
    captured = [entry for entry in response.captured_xhr if entry.url == api_url and entry.status == 200]
    if (video is None or counts != {'document': 1, 'api': 1} or len(captured) != 1
            or video.attrib.get('data-video-id') != rule.video_id or len(captured[0].body) > MAX_XHR):
        return request.result('INSUFFICIENT')
    try:
        data = strict_json(captured[0].body)
    except InvalidRequest:
        return request.result('INSUFFICIENT')
    if (not isinstance(data, dict) or data.get('pageUrl') != page_url_value
            or data.get('videoId') != rule.video_id):
        return request.result('INSUFFICIENT')
    title, thumbnail, duration = metadata(response, video)
    # Cross-check observed metadata; never fill a missing DOM field from an API.
    if (title is not None and data.get('title') != title
            or duration is not None and (type(data.get('durationMs')) is not int or data['durationMs'] != duration)):
        return request.result('INSUFFICIENT')
    return request.result('PAGE_EVIDENCE', 'DYNAMIC_VIDEO_XHR', title, thumbnail, duration)


def verify(request, capability, deadline):
    if capability is None or (rule := capability.rule_for(request.candidateUrl)) is None:
        return request.result('UNSUPPORTED')
    try:
        if importlib.metadata.version('scrapling') != '0.4.15':
            raise Unavailable()
        from scrapling import Selector
        physical_url = capability.origin + rule.page_path
        html, content_type = read_fixture(physical_url, {physical_url}, MAX_HTML, deadline)
        if content_type != 'text/html':
            raise Rejected()
        selector = Selector(html, url=request.candidateUrl, huge_tree=False, adaptive=False)
        result = static_evidence(selector, request)
        if result is not None:
            return result
        if not request.dynamicAllowed or rule.api_path is None:
            return request.result('INSUFFICIENT')
        return dynamic_evidence(html, request, capability, rule, deadline)
    except Rejected:
        return request.result('REJECTED')
    except TimeoutError:
        return request.result('TIMEOUT')
    except Exception:
        return request.result('UNAVAILABLE')
