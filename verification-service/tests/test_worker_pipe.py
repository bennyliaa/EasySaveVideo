"""Real OS pipe-backpressure regressions; owned fixtures only, no public network."""
import dataclasses
import fcntl
import json
import os
import subprocess
import sys
import threading
import time
import unittest
from unittest.mock import patch

from esv_verify import service as module
from esv_verify.capability import FixtureCapability, FixtureRule
from esv_verify.service import VerificationService, encode_envelope, MAX_ENVELOPE
from test_service import OwnedFixtures, request


class WorkerPipeTests(unittest.TestCase):
    def setUp(self):
        self.origin = 'http://127.0.0.1:12345'
        self.rules = (FixtureRule('/static'),) + tuple(
            FixtureRule('/' + str(index) + 'x' * 120) for index in range(63))
        self.capability = FixtureCapability(self.origin, self.rules)
        self.service = VerificationService(self.capability, max_workers=1)
        self.processes = []
        self.capacities = []
        self.original_popen = subprocess.Popen
        self.original_write = os.write

    def tearDown(self):
        self.service.close()
        for process in self.processes:
            if process.poll() is None:
                process.kill()
                process.wait(timeout=2)
            for pipe in (process.stdin, process.stdout):
                if pipe is not None and not pipe.closed:
                    pipe.close()

    def spawn_stalled(self, command, **kwargs):
        self.assertEqual(kwargs['bufsize'], 0)
        process = self.original_popen([sys.executable, '-I', '-B', '-c',
                                      'import time; time.sleep(30)'], **kwargs)
        self.processes.append(process)
        self.capacities.append(fcntl.fcntl(process.stdin.fileno(), fcntl.F_SETPIPE_SZ, 4096))
        return process

    def test_deadline_during_real_pipe_backpressure(self):
        req = request(self.origin + '/static', budgetMs=250)
        encoded = encode_envelope(req, self.capability, time.monotonic() + 1)
        writes = []
        def measured_write(fd, data):
            count = self.original_write(fd, data)
            writes.append(count)
            return count
        started = time.monotonic()
        with patch.object(module.subprocess, 'Popen', side_effect=self.spawn_stalled), \
                patch.object(module.os, 'write', side_effect=measured_write):
            result = self.service.verify(req)
        self.assertEqual(result['outcome'], 'TIMEOUT')
        self.assertGreater(len(encoded), self.capacities[0])
        self.assertEqual(sum(writes), self.capacities[0])
        self.assertLess(sum(writes), len(encoded))
        self.assertLess(time.monotonic() - started, 1.5)
        self.assertIsNotNone(self.processes[0].returncode)
        self.assertFalse(self.service._poisoned)
        self.assertTrue(self.service._slots.acquire(blocking=False))
        self.service._slots.release()

    def cancellation_during_backpressure(self, close_service=False, cleanup_proven=True):
        cancel = threading.Event()
        wrote = threading.Event()
        cleanup_entered = threading.Event()
        allow_cleanup = threading.Event()
        results = []
        errors = []
        original_cleanup = module.terminate_tree
        req = request(self.origin + '/static')
        encoded = encode_envelope(req, self.capability, time.monotonic() + 10)
        total_sent = []

        def write_some(fd, data):
            count = self.original_write(fd, data)
            if count:
                total_sent.append(count)
                wrote.set()
            return count

        def held_cleanup(process, tracked):
            cleanup_entered.set()
            if not allow_cleanup.wait(2):
                raise AssertionError('TEST_CLEANUP_RELEASE_MISSING')
            proved = original_cleanup(process, tracked)
            return proved and cleanup_proven

        def run():
            try:
                results.append(self.service.verify(req, cancel.is_set))
            except BaseException as error:
                errors.append(type(error).__name__)

        with patch.object(module.subprocess, 'Popen', side_effect=self.spawn_stalled), \
                patch.object(module.os, 'write', side_effect=write_some), \
                patch.object(module, 'terminate_tree', side_effect=held_cleanup):
            thread = threading.Thread(target=run)
            thread.start()
            try:
                self.assertTrue(wrote.wait(2))
                self.assertGreater(len(encoded), self.capacities[0])
                if close_service:
                    self.service.close()
                else:
                    cancel.set()
                self.assertTrue(cleanup_entered.wait(1))
                self.assertFalse(self.service._slots.acquire(blocking=False))
                if not close_service:
                    result = self.service.verify(request(self.origin + '/static', requestId='c' * 32))
                    self.assertEqual(result['outcome'], 'BUSY')
                self.assertTrue(thread.is_alive())
            finally:
                cancel.set()
                allow_cleanup.set()
                thread.join(3)
        self.assertFalse(thread.is_alive())
        self.assertEqual(errors, [])
        self.assertEqual(results[0]['outcome'], 'CANCELLED')
        self.assertLess(sum(total_sent), len(encoded))
        self.assertIsNotNone(self.processes[0].returncode)
        self.assertEqual(self.service._poisoned, not cleanup_proven)
        self.assertEqual(self.service._slots.acquire(blocking=False), cleanup_proven)
        if cleanup_proven:
            self.service._slots.release()

    def test_cancel_partial_write_retains_slot_until_actual_cleanup(self):
        self.cancellation_during_backpressure()

    def test_service_close_interrupts_partial_write(self):
        self.cancellation_during_backpressure(close_service=True)

    def test_unproven_cleanup_after_partial_write_poisoned(self):
        self.cancellation_during_backpressure(cleanup_proven=False)
        result = self.service.verify(request(self.origin + '/static'))
        self.assertEqual(result['outcome'], 'BUSY')

    def test_actual_broken_pipe_is_bounded_and_cleanup_proven(self):
        broken = []

        def spawn_closed_stdin(command, **kwargs):
            process = self.original_popen([sys.executable, '-I', '-B', '-c',
                'import os,time; os.close(0); os.write(1,b"closed"); time.sleep(30)'], **kwargs)
            self.processes.append(process)
            fcntl.fcntl(process.stdin.fileno(), fcntl.F_SETPIPE_SZ, 4096)
            return process

        def checked_write(fd, data):
            try:
                return self.original_write(fd, data)
            except BrokenPipeError:
                broken.append(True)
                raise

        started = time.monotonic()
        with patch.object(module.subprocess, 'Popen', side_effect=spawn_closed_stdin), \
                patch.object(module.os, 'write', side_effect=checked_write):
            result = self.service.verify(request(self.origin + '/static', budgetMs=2000))
        self.assertEqual(result['outcome'], 'UNAVAILABLE')
        self.assertTrue(broken)
        self.assertLess(time.monotonic() - started, 1.5)
        self.assertIsNotNone(self.processes[0].returncode)
        self.assertFalse(self.service._poisoned)

    def test_zero_progress_write_fails_closed(self):
        with patch.object(module.subprocess, 'Popen', side_effect=self.spawn_stalled), \
                patch.object(module.os, 'write', return_value=0):
            result = self.service.verify(request(self.origin + '/static'))
        self.assertEqual(result['outcome'], 'UNAVAILABLE')
        self.assertIsNotNone(self.processes[0].returncode)
        self.assertFalse(self.service._poisoned)

    def test_oversized_envelope_rejected_before_spawn(self):
        rules = tuple(FixtureRule('/' + str(i) + 'x' * 120, '/' + str(i) + 'y' * 120, 'v' * 64) for i in range(64))
        cap = FixtureCapability(self.origin, rules, '/' + '🦊' * 1023, '0' * 64)
        req = request(self.origin + rules[0].page_path)
        raw = json.dumps(dict(request=dataclasses.asdict(req), capability=dataclasses.asdict(cap), deadline=1))
        self.assertGreater(len(raw), MAX_ENVELOPE)
        service = VerificationService(cap)
        with patch.object(module.subprocess, 'Popen') as spawn:
            self.assertEqual(service.verify(req)['outcome'], 'UNAVAILABLE')
            spawn.assert_not_called()
        self.assertFalse(service._active)
        self.assertFalse(service._poisoned)

    def test_browser_path_cap_prevents_unbounded_envelope_field(self):
        with self.assertRaises(ValueError):
            FixtureCapability(self.origin, self.rules, '/' + 'x' * 4096, '0' * 64)


class ActualPartialWriteTests(unittest.TestCase):
    def test_interruptions_and_short_writes_still_complete_real_fetcher(self):
        fixtures = OwnedFixtures()
        service = VerificationService(fixtures.capability(), max_workers=1)
        original_write = os.write
        original_select = module.select.select
        writes = []
        waits = []

        def partial_write(fd, data):
            writes.append(len(data))
            if len(writes) == 1:
                raise InterruptedError()
            if len(writes) == 2:
                raise BlockingIOError()
            return original_write(fd, data[:113])

        def interrupted_select(readable, writable, exceptional, timeout):
            if writable and not waits:
                waits.append(True)
                raise InterruptedError()
            return original_select(readable, writable, exceptional, timeout)

        try:
            with patch.object(module.os, 'write', side_effect=partial_write), \
                    patch.object(module.select, 'select', side_effect=interrupted_select):
                result = service.verify(request(fixtures.origin + '/static'))
            self.assertEqual(result['outcome'], 'PAGE_EVIDENCE')
            self.assertEqual(result['evidence'], 'HTML_VIDEO')
            self.assertEqual(result['title'], 'Owned video')
            self.assertGreater(len(writes), 3)
            self.assertTrue(waits)
            self.assertEqual([row[0] for row in fixtures.seen], ['/static'])
            self.assertFalse(service._poisoned)
        finally:
            service.close()
            fixtures.close()
