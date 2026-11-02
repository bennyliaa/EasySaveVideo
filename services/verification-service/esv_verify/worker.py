"""Private request worker. Capabilities arrive on an inherited pipe, never a CLI flag."""
import ctypes
import json
import logging
import os
import resource
import sys
import time
from dataclasses import asdict
from .capability import FixtureCapability, FixtureRule
from .protocol import Request, encode_result, strict_json
from .service import MAX_ENVELOPE


def main():
    # Set before importing any optional runtime. No ambient proxy, netrc or credentials.
    logging.disable(logging.CRITICAL)
    # Per-process child-reaping behavior only; no sandbox/security changes.
    if ctypes.CDLL(None, use_errno=True).prctl(36, 1, 0, 0, 0) != 0:
        raise RuntimeError('SUBREAPER_UNAVAILABLE')
    for key in tuple(os.environ):
        if key.lower() in {'http_proxy', 'https_proxy', 'all_proxy', 'no_proxy'}:
            del os.environ[key]
    os.environ['NO_PROXY'] = '127.0.0.1'
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    resource.setrlimit(resource.RLIMIT_CPU, (12, 12))
    resource.setrlimit(resource.RLIMIT_FSIZE, (8 * 1024 * 1024,) * 2)
    resource.setrlimit(resource.RLIMIT_NOFILE, (256, 256))
    # Browser address space is intentionally not capped: Chromium reserves huge
    # virtual mappings. Parent monitors process-tree RSS, wall time and descendants.
    raw_envelope = sys.stdin.buffer.read(MAX_ENVELOPE + 1)
    if not 1 <= len(raw_envelope) <= MAX_ENVELOPE:
        raise ValueError('ENVELOPE_SIZE')
    envelope = strict_json(raw_envelope)
    request = Request.parse(json.dumps(envelope['request']).encode())
    cap = envelope['capability']
    cap['rules'] = tuple(FixtureRule(**rule) for rule in cap['rules'])
    capability = FixtureCapability(**cap)
    deadline = float(envelope['deadline'])
    rule = capability.rule_for(request.candidateUrl)
    if not request.dynamicAllowed or rule is None or rule.api_path is None:
        resource.setrlimit(resource.RLIMIT_AS, (512 * 1024 * 1024,) * 2)
    from .adapter import verify
    from .service import finish_owned_children
    try:
        response = verify(request, capability, deadline)
    finally:
        finish_owned_children()
    sys.stdout.buffer.write(encode_result(request, response))
    sys.stdout.buffer.flush()


if __name__ == '__main__':
    try:
        main()
    except BaseException:
        # Technical failure only. Never emit URL, exception text or diagnostics.
        raise SystemExit(70)
