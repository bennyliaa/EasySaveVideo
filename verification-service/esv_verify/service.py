"""Bounded request orchestration and physical worker isolation (Linux fixtures)."""
from dataclasses import asdict
import json
import os
from pathlib import Path
import select
import signal
import subprocess
import sys
import tempfile
import threading
import time
from .protocol import MAX_RESPONSE, InvalidRequest, encode_result, strict_json

POLL_SECONDS = .02
MAX_TREE_RSS = 768 * 1024 * 1024
MAX_PROCESSES = 24
MAX_ENVELOPE = 32768
PIPE_WRITE_CHUNK = 4096


def encode_envelope(request, capability, deadline):
    """Bound the private worker message before allocating a physical process."""
    envelope = {"request": asdict(request), "capability": asdict(capability), "deadline": deadline}
    encoded = bytearray()
    encoder = json.JSONEncoder(ensure_ascii=True, allow_nan=False, separators=(",", ":"))
    for part in encoder.iterencode(envelope):
        chunk = part.encode("ascii")
        if len(encoded) + len(chunk) > MAX_ENVELOPE:
            raise InvalidRequest("ENVELOPE_SIZE")
        encoded.extend(chunk)
    return bytes(encoded)


def process_table():
    """Only process metadata, never command lines or another process's environment."""
    rows = {}
    for path in Path('/proc').iterdir():
        if not path.name.isdigit():
            continue
        try:
            data = (path / 'stat').read_text()
            fields = data[data.rfind(')') + 2:].split()
            rows[int(path.name)] = (int(fields[1]), fields[19], fields[0], int(fields[21]) * os.sysconf('SC_PAGE_SIZE'))
        except (OSError, ValueError, IndexError):
            continue
    return rows


def own_tree(root_pid, rows, known):
    # Root identity is pinned by the spawning caller before any wait()/poll().
    # Previously observed PIDs are never adopted under a new start identity.
    selected = {pid for pid, start in known.items() if pid in rows and rows[pid][1] == start}
    while True:
        more = {pid for pid, row in rows.items() if row[0] in selected and pid not in known}
        if not more:
            break
        for pid in more:
            known[pid] = rows[pid][1]
        selected.update(more)
    return {pid: rows[pid] for pid in selected if rows[pid][2] != 'Z'}


def signal_owned(pid, start, sig):
    # pidfd prevents the stat-to-signal PID reuse race. No process-group signal
    # can accidentally target a newly reused group identifier.
    try:
        fd = os.pidfd_open(pid)
    except ProcessLookupError:
        return
    try:
        row = process_table().get(pid)
        if row is not None and row[1] == start:
            signal.pidfd_send_signal(fd, sig)
    except ProcessLookupError:
        pass
    finally:
        os.close(fd)


def freeze_owned_tree(root_pid, tracked, include_root=True):
    for _ in range(40):
        alive = own_tree(root_pid, process_table(), tracked)
        targets = {pid: row for pid, row in alive.items() if include_root or pid != root_pid}
        for pid, row in targets.items():
            if row[2] not in {'T', 't'}:
                signal_owned(pid, tracked[pid], signal.SIGSTOP)
        time.sleep(.005)
        observed = own_tree(root_pid, process_table(), tracked)
        observed = {pid: row for pid, row in observed.items() if include_root or pid != root_pid}
        if (set(observed) == set(targets)
                and all(row[2] in {'T', 't'} for row in targets.values())
                and all(row[2] in {'T', 't'} for row in observed.values())):
            return observed
    return None


def terminate_tree(process, tracked):
    frozen = freeze_owned_tree(process.pid, tracked)
    if frozen is None:
        # Keep capacity poisoned rather than claim a cleanup proof. Best-effort
        # signals remain restricted to pidfd-verified identities.
        for pid, row in own_tree(process.pid, process_table(), tracked).items():
            signal_owned(pid, tracked[pid], signal.SIGKILL)
        return False
    # No stopped process can fork. Root remains stopped while descendants die.
    for pid in frozen:
        if pid != process.pid:
            signal_owned(pid, tracked[pid], signal.SIGKILL)
    for _ in range(50):
        children = {pid for pid in own_tree(process.pid, process_table(), tracked) if pid != process.pid}
        if not children:
            break
        time.sleep(.01)
    else:
        return False
    signal_owned(process.pid, tracked[process.pid], signal.SIGKILL)
    try:
        process.wait(timeout=.5)
    except subprocess.TimeoutExpired:
        return False
    return not own_tree(process.pid, process_table(), tracked)


def finish_owned_children():
    """Normal worker exit also proves descendant cleanup before returning data."""
    root = os.getpid()
    tracked = {root: process_table()[root][1]}
    for attempt in range(30):
        children = freeze_owned_tree(root, tracked, include_root=False)
        if children is None:
            raise RuntimeError('CHILD_FREEZE_UNPROVEN')
        for pid in children:
            signal_owned(pid, tracked[pid], signal.SIGKILL)
        try:
            while os.waitpid(-1, os.WNOHANG)[0]:
                pass
        except ChildProcessError:
            pass
        if not children:
            return
        time.sleep(.01)
    raise RuntimeError('CHILD_CLEANUP_UNPROVEN')


class VerificationService:
    """A constructor-injected fixture capability is the ONLY target-access path.

    Server CLI and HTTP input cannot create one. No optional runtime is imported
    for the normal, unconfigured service, including health/unsupported requests.
    """
    def __init__(self, fixture_capability=None, max_workers=2):
        if type(max_workers) is not int or not 1 <= max_workers <= 4:
            raise ValueError('WORKER_LIMIT')
        self._capability = fixture_capability
        self._slots = threading.BoundedSemaphore(max_workers)
        self._closed = threading.Event()
        self._lock = threading.Lock()
        self._active = set()
        self._poisoned = False
        self._windows = None
        if os.name == 'nt' and fixture_capability is not None:
            from .windows_service import WindowsVerificationService
            self._windows = WindowsVerificationService(fixture_capability, max_workers)

    def close(self):
        self._closed.set()
        if self._windows is not None:
            self._windows.close()

    def verify(self, request, cancelled=lambda: False, accepted_at=None):
        if self._windows is not None:
            return self._windows.verify(request, cancelled, accepted_at)
        started = time.monotonic() if accepted_at is None else accepted_at
        deadline = started + request.budgetMs / 1000
        if cancelled() or self._closed.is_set():
            return request.result('CANCELLED')
        if time.monotonic() >= deadline:
            return request.result('TIMEOUT')
        if self._capability is None or self._capability.rule_for(request.candidateUrl) is None:
            return request.result('UNSUPPORTED')
        try:
            envelope = encode_envelope(request, self._capability, deadline)
        except (InvalidRequest, TypeError, ValueError, OverflowError):
            return request.result('UNAVAILABLE')
        if cancelled() or self._closed.is_set():
            return request.result('CANCELLED')
        if time.monotonic() >= deadline:
            return request.result('TIMEOUT')
        identity = (request.requestId, request.generation)
        with self._lock:
            if self._poisoned or identity in self._active or not self._slots.acquire(blocking=False):
                return request.result('BUSY')
            self._active.add(identity)
        process = None
        cleanup_ok = True
        tracked = {}
        response = request.result('UNAVAILABLE')
        try:
            with tempfile.TemporaryDirectory(prefix='esv-verify-') as scratch:
                # Only explicit runtime necessities; no inherited tokens, proxy,
                # cloud credentials, user HOME, startup hooks or PYTHONPATH.
                env = {'PATH': '/usr/bin:/bin', 'HOME': scratch, 'TMPDIR': scratch,
                       'LANG': 'C.UTF-8', 'PYTHONDONTWRITEBYTECODE': '1', 'NO_PROXY': '127.0.0.1'}
                process = subprocess.Popen([sys.executable, '-I', '-B', '-c',
                    'import sys; sys.path.insert(0, ' + repr(str(Path(__file__).resolve().parent.parent)) + '); from esv_verify.worker import main; main()'],
                    stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                    start_new_session=True, env=env, cwd=scratch, close_fds=True, bufsize=0)
                cleanup_ok = False
                tracked[process.pid] = process_table()[process.pid][1]
                os.set_blocking(process.stdin.fileno(), False)
                os.set_blocking(process.stdout.fileno(), False)
                pending = memoryview(envelope)
                sent = 0
                output = bytearray()
                while True:
                    tree = own_tree(process.pid, process_table(), tracked)
                    if cancelled() or self._closed.is_set():
                        response = request.result('CANCELLED')
                        break
                    if time.monotonic() >= deadline:
                        response = request.result('TIMEOUT')
                        break
                    if len(tree) > MAX_PROCESSES or sum(row[3] for row in tree.values()) > MAX_TREE_RSS:
                        response = request.result('UNAVAILABLE')
                        break
                    # Both pipes are unbuffered/nonblocking. A stalled or early
                    # writing child cannot strand us before deadline/cancellation.
                    try:
                        readable, writable, _ = select.select(
                            [process.stdout], [process.stdin] if sent < len(pending) else [], [],
                            min(POLL_SECONDS, max(0, deadline - time.monotonic())))
                    except InterruptedError:
                        continue
                    if cancelled() or self._closed.is_set():
                        response = request.result('CANCELLED')
                        break
                    if time.monotonic() >= deadline:
                        response = request.result('TIMEOUT')
                        break
                    if writable:
                        try:
                            count = os.write(process.stdin.fileno(), pending[sent:sent + PIPE_WRITE_CHUNK])
                        except (BlockingIOError, InterruptedError):
                            count = None
                        except BrokenPipeError:
                            break
                        if count is not None:
                            if count <= 0:
                                break
                            sent += count
                            if sent == len(pending):
                                # FileIO close performs no buffered flush.
                                process.stdin.close()
                    if readable:
                        try:
                            chunk = os.read(process.stdout.fileno(), MAX_RESPONSE + 1 - len(output))
                        except (BlockingIOError, InterruptedError):
                            chunk = b''
                        output.extend(chunk)
                        if len(output) > MAX_RESPONSE:
                            break
                    if process.poll() is not None:
                        # Drain a bounded nonblocking pipe, then require the
                        # entire envelope to have reached the child before any
                        # worker response can be considered for publication.
                        while len(output) <= MAX_RESPONSE:
                            try:
                                chunk = os.read(process.stdout.fileno(), MAX_RESPONSE + 1 - len(output))
                            except (BlockingIOError, InterruptedError):
                                break
                            if not chunk:
                                break
                            output.extend(chunk)
                        if process.returncode == 0 and sent == len(pending):
                            candidate = strict_json(bytes(output))
                            encode_result(request, candidate)
                            response = candidate
                        break
                cleanup_ok = False
                try:
                    cleanup_ok = terminate_tree(process, tracked)
                except Exception:
                    cleanup_ok = False
                process.stdout.close()
                # Publication is fenced AFTER physical worker cleanup as well.
                if cancelled() or self._closed.is_set():
                    response = request.result('CANCELLED')
                elif time.monotonic() >= deadline:
                    response = request.result('TIMEOUT')
                elif not cleanup_ok:
                    response = request.result('UNAVAILABLE')
        except Exception:
            if process is not None:
                cleanup_ok = False
                try:
                    if tracked:
                        cleanup_ok = terminate_tree(process, tracked)
                    else:
                        process.kill()
                        process.wait(timeout=.5)
                except Exception:
                    pass
            response = request.result('UNAVAILABLE')
        finally:
            if process is not None:
                for pipe in (process.stdin, process.stdout):
                    if pipe is not None and not pipe.closed:
                        pipe.close()
            with self._lock:
                self._active.remove(identity)
                if cleanup_ok:
                    self._slots.release()
                else:
                    # Do not release physical capacity if cleanup cannot be proven.
                    self._poisoned = True
        return response
