"""Constructor-only fixture capability. No production target-policy implementation."""
from dataclasses import dataclass
import re
from urllib.parse import urlsplit


@dataclass(frozen=True)
class FixtureRule:
    page_path: str
    api_path: str | None = None
    video_id: str | None = None
    dynamic_dom: bool = False


@dataclass(frozen=True)
class FixtureCapability:
    origin: str
    rules: tuple[FixtureRule, ...]
    browser_executable: str | None = None
    browser_sha256: str | None = None
    logical_origin: str | None = None

    def __post_init__(self):
        if self.logical_origin not in (None, 'https://fixture.example.org'):
            raise ValueError('FIXTURE_LOGICAL_ORIGIN')
        parts = urlsplit(self.origin)
        if (parts.scheme != 'http' or parts.hostname != '127.0.0.1' or parts.port is None
                or not 1 <= parts.port <= 65535 or self.origin != f'http://127.0.0.1:{parts.port}'):
            raise ValueError('FIXTURE_ORIGIN')
        if not 1 <= len(self.rules) <= 64 or len({rule.page_path for rule in self.rules}) != len(self.rules):
            raise ValueError('FIXTURE_RULES')
        for rule in self.rules:
            if type(rule.dynamic_dom) is not bool or rule.dynamic_dom and rule.api_path is not None:
                raise ValueError('FIXTURE_DYNAMIC_MODE')
            for path in (rule.page_path, rule.api_path):
                if path is not None and not re.fullmatch(r'/[A-Za-z0-9_./-]{1,128}', path):
                    raise ValueError('FIXTURE_PATH')
                if path is not None and ('..' in path or '//' in path):
                    raise ValueError('FIXTURE_PATH')
            if (rule.api_path is None) != (rule.video_id is None):
                raise ValueError('FIXTURE_IDENTITY')
            if rule.video_id is not None and not re.fullmatch(r'[A-Za-z0-9_-]{1,64}', rule.video_id):
                raise ValueError('FIXTURE_IDENTITY')
        if self.browser_executable is not None and (not isinstance(self.browser_executable, str)
                or not 1 <= len(self.browser_executable.encode('utf-8')) <= 4096):
            raise ValueError('FIXTURE_BROWSER_PATH')
        if (self.browser_executable is None) != (self.browser_sha256 is None):
            raise ValueError('FIXTURE_BROWSER')
        if self.browser_sha256 is not None and not re.fullmatch('[0-9a-f]{64}', self.browser_sha256):
            raise ValueError('FIXTURE_BROWSER')

    def rule_for(self, url):
        return next((rule for rule in self.rules if url == (self.logical_origin or self.origin) + rule.page_path), None)
