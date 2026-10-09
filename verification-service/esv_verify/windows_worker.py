"""Windows private worker: containment and descendant cleanup belong to its parent Job Object."""
import json, logging, os, sys, time
from .protocol import Request, strict_json, encode_result
from .capability import FixtureCapability, FixtureRule

def main():
    logging.disable(logging.CRITICAL)
    raw=sys.stdin.buffer.read(32769)
    if not 1<=len(raw)<=32768:raise ValueError('ENVELOPE_SIZE')
    envelope=strict_json(raw)
    if set(envelope)!={'request','capability','deadline'}:raise ValueError('ENVELOPE_KEYS')
    request=Request.parse(json.dumps(envelope['request']).encode())
    cap=envelope['capability'];cap['rules']=tuple(FixtureRule(**r) for r in cap['rules'])
    capability=FixtureCapability(**cap);deadline=float(envelope['deadline'])
    if not time.monotonic()<deadline:raise TimeoutError()
    from .adapter import verify
    response=verify(request,capability,deadline)
    sys.stdout.buffer.write(encode_result(request,response));sys.stdout.buffer.flush()

if __name__=='__main__':
    try:main()
    except BaseException:raise SystemExit(70)
