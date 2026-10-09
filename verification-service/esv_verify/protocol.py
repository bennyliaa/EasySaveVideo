"""Strict version-one wire grammar. No third-party runtime is imported here."""
from dataclasses import dataclass
import ipaddress
import json
import re
from urllib.parse import urlsplit, unquote

MAX_REQUEST = 8192
MAX_RESPONSE = 32768
REQUEST_KEYS = {'version', 'requestId', 'generation', 'candidateUrl', 'budgetMs', 'dynamicAllowed'}
OUTCOMES = {'PAGE_EVIDENCE', 'INSUFFICIENT', 'UNSUPPORTED', 'REJECTED', 'TIMEOUT', 'CANCELLED', 'BUSY', 'UNAVAILABLE'}

class InvalidRequest(ValueError):
    pass


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise InvalidRequest('DUPLICATE_KEY')
        result[key] = value
    return result


def strict_json(body):
    try:
        result = json.loads(body.decode('utf-8'), object_pairs_hook=unique_object,
                          parse_constant=lambda _: (_ for _ in ()).throw(InvalidRequest('NONFINITE')))
    except (ValueError, UnicodeError, RecursionError) as error:
        raise InvalidRequest('INVALID_JSON') from error
    def check(value):
        if isinstance(value, str):
            value.encode('utf-8', errors='strict')
        elif isinstance(value, dict):
            for key, item in value.items():
                check(key)
                check(item)
        elif isinstance(value, list):
            for item in value:
                check(item)
    try:
        check(result)
    except (UnicodeError, RecursionError) as error:
        raise InvalidRequest('INVALID_UNICODE') from error
    return result


def valid_title(value):
    try:
        return (isinstance(value, str) and len(value.encode('utf-16-le')) <= 1024
                and not any(ord(c) < 32 or 127 <= ord(c) <= 159 for c in value))
    except UnicodeError:
        return False


def page_url(value):
    if not isinstance(value, str) or not 1 <= len(value.encode('utf-8')) <= 4096:
        raise InvalidRequest('INVALID_URL')
    if any(ord(c) <= 32 or ord(c) == 127 for c in value) or '\\' in value:
        raise InvalidRequest('INVALID_URL')
    try:
        parts = urlsplit(value)
        if (parts.scheme not in {'http', 'https'} or not parts.hostname or not parts.netloc
                or parts.username is not None or parts.password is not None or parts.fragment
                or '%' in parts.netloc or not parts.hostname.isascii() or parts.port == 0):
            raise InvalidRequest('INVALID_URL')
        # Hostnames must have an unambiguous DNS/IP grammar; never resolve here.
        try:
            ipaddress.ip_address(parts.hostname)
        except ValueError:
            if not re.fullmatch(r'(?=.{1,253}$)[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?', parts.hostname):
                raise InvalidRequest('INVALID_URL')
            if any(not label or len(label) > 63 or label.startswith('-') or label.endswith('-')
                   for label in parts.hostname.split('.')):
                raise InvalidRequest('INVALID_URL')
    except (ValueError, UnicodeError) as error:
        raise InvalidRequest('INVALID_URL') from error
    return value


def public_thumbnail(value):
    # Same public-DNS URL syntax as the Android client; never resolve metadata.
    try:
        page_url(value)
        parts = urlsplit(value)
        if parts.scheme != 'https' or parts.port not in (None, 443):
            return None
        host = parts.hostname
        if parts.netloc != host + (':443' if parts.port is not None else ''):
            return None
        labels = host.lower().split('.')
        if (not 4 <= len(host) <= 253 or len(labels) < 2
                or any(not re.fullmatch(r'[a-z0-9](?:[a-z0-9-]*[a-z0-9])?', label) or len(label) > 63 for label in labels)
                or not re.fullmatch(r'[a-z]{2,63}|xn--[a-z0-9-]{2,59}', labels[-1])
                or labels[-1] in {'localhost', 'local', 'localdomain', 'internal', 'lan', 'home', 'onion', 'invalid', 'test', 'example', 'alt', 'arpa'}
                or 'localhost' in labels):
            return None
        if (any(ord(c) < 32 or 127 <= ord(c) <= 159 for c in value)
                or re.search(r'%(?![0-9a-fA-F]{2})', value)
                or re.search(r'(?i)%(?:0[0-9a-f]|1[0-9a-f]|7f|5c)', value)
                or any(c in value for c in '<>"{}|^`')
                or any(segment in {'.', '..'} or '\\' in segment for segment in unquote(parts.path).split('/'))
                or '//' in parts.path):
            return None
        return value
    except (InvalidRequest, TypeError, UnicodeError):
        return None


@dataclass(frozen=True)
class Request:
    version: int
    requestId: str
    generation: str
    candidateUrl: str
    budgetMs: int
    dynamicAllowed: bool

    @classmethod
    def parse(cls, body):
        if not isinstance(body, bytes) or not 1 <= len(body) <= MAX_REQUEST:
            raise InvalidRequest('REQUEST_SIZE')
        data = strict_json(body)
        if not isinstance(data, dict) or set(data) != REQUEST_KEYS:
            raise InvalidRequest('REQUEST_KEYS')
        if type(data['version']) is not int or data['version'] != 1:
            raise InvalidRequest('VERSION')
        for key, count in [('requestId', 32), ('generation', 64)]:
            if not isinstance(data[key], str) or not re.fullmatch('[0-9a-f]{%d}' % count, data[key]):
                raise InvalidRequest('IDENTITY')
        if type(data['budgetMs']) is not int or not 1 <= data['budgetMs'] <= 10000:
            raise InvalidRequest('BUDGET')
        if type(data['dynamicAllowed']) is not bool:
            raise InvalidRequest('DYNAMIC')
        page_url(data['candidateUrl'])
        return cls(**data)

    def result(self, outcome, evidence='NONE', title=None, thumbnail=None, duration=None):
        if outcome not in OUTCOMES:
            raise ValueError('OUTCOME')
        if outcome != 'PAGE_EVIDENCE':
            evidence, title, thumbnail, duration = 'NONE', None, None, None
        elif evidence not in {'HTML_VIDEO', 'DYNAMIC_VIDEO_XHR'}:
            raise ValueError('EVIDENCE')
        return {'version': 1, 'requestId': self.requestId, 'generation': self.generation,
                'candidateUrl': self.candidateUrl, 'outcome': outcome, 'pageUrl': self.candidateUrl,
                'evidence': evidence, 'title': title, 'thumbnailUrl': thumbnail, 'durationMs': duration,
                'playbackVerified': False, 'downloadable': False}


def encode_result(request, result):
    """The parent revalidates even its isolated worker's response before publishing."""
    expected = request.result('UNAVAILABLE')
    if not isinstance(result, dict) or set(result) != set(expected):
        raise InvalidRequest('RESULT_KEYS')
    for key in ('version', 'requestId', 'generation', 'candidateUrl', 'pageUrl', 'playbackVerified', 'downloadable'):
        if result[key] != expected[key] or type(result[key]) is not type(expected[key]):
            raise InvalidRequest('RESULT_IDENTITY')
    if result['outcome'] not in OUTCOMES:
        raise InvalidRequest('RESULT_OUTCOME')
    if result['outcome'] == 'PAGE_EVIDENCE':
        if (result['evidence'] not in {'HTML_VIDEO', 'DYNAMIC_VIDEO_XHR'}
                or result['evidence'] == 'DYNAMIC_VIDEO_XHR' and not request.dynamicAllowed):
            raise InvalidRequest('RESULT_EVIDENCE')
        title = result['title']
        if title is not None and not valid_title(title):
            raise InvalidRequest('RESULT_TITLE')
        thumb = result['thumbnailUrl']
        if thumb is not None and public_thumbnail(thumb) != thumb:
            raise InvalidRequest('RESULT_THUMBNAIL')
        duration = result['durationMs']
        if duration is not None and (type(duration) is not int or not 1 <= duration <= 86400000):
            raise InvalidRequest('RESULT_DURATION')
    elif result['evidence'] != 'NONE' or any(result[k] is not None for k in ('title', 'thumbnailUrl', 'durationMs')):
        raise InvalidRequest('RESULT_METADATA')
    raw = json.dumps(result, ensure_ascii=True, separators=(',', ':')).encode('utf-8')
    if len(raw) > MAX_RESPONSE:
        raise InvalidRequest('RESULT_SIZE')
    return raw
