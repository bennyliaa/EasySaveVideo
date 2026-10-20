"""Offline synthetic regression tests; never operates an APK or device."""
import ast
import hashlib
import importlib.util
import os
from pathlib import Path
import shutil
import stat
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parent
MODULE_PATH = Path(os.environ.get('ESV_RECEIVER', ROOT / 'candidate/verify_and_restore.py'))
spec = importlib.util.spec_from_file_location('receiver', MODULE_PATH)
r = importlib.util.module_from_spec(spec)
spec.loader.exec_module(r)

class BaselineIdentityTests(unittest.TestCase):
    def test_pinned_baseline_and_production_constants_preserved(self):
        baseline = (ROOT / 'baseline/verify_and_restore.py').read_bytes()
        self.assertEqual(hashlib.sha1(b'blob ' + str(len(baseline)).encode() + b'\0' + baseline).hexdigest(),
                         '7b1067df8aa9156485073ab96aca67556bb9b205')
        def constants(raw):
            return {n.targets[0].id: ast.dump(n.value) for n in ast.parse(raw).body
                    if isinstance(n, ast.Assign) and isinstance(n.targets[0], ast.Name)}
        self.assertEqual(constants(baseline), constants(MODULE_PATH.read_bytes()))

class PublicationTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.directory = Path(self.tmp.name)
        self.base = self.directory / 'base.apk'
        self.base.write_bytes(b'BASE')
        self.expected = b'BASEnew'
        self.output = self.directory / r.OUTPUT_NAME
        self.other = self.directory / 'other.apk'
        self.other.write_bytes(self.expected)
        values = dict(BASE_SIZE=4, BASE_SHA=hashlib.sha256(b'BASE').hexdigest(),
                      TARGET_SIZE=7, TARGET_SHA=hashlib.sha256(self.expected).hexdigest())
        for key, value in values.items():
            p = patch.object(r, key, value); p.start(); self.addCleanup(p.stop)
        # Isolate publication from exact production delta identities. No fixture is an APK.
        for name, value in [('read_regular', b'fixture'),
                            ('decode_patch', ([('copy', 0, 4), ('literal', 0, 3)], b'new'))]:
            p = patch.object(r, name, return_value=value); p.start(); self.addCleanup(p.stop)
        self.real_copy = shutil.copyfileobj

    def test_success_preserves_base_and_publishes_regular_file(self):
        self.assertEqual(r.restore(self.base, self.directory), self.output)
        self.assertEqual(self.output.read_bytes(), self.expected)
        self.assertFalse(self.output.is_symlink())
        self.assertEqual(self.base.read_bytes(), b'BASE')
        self.assertEqual(list(self.directory.glob('code13-restore-*')), [])

    def test_existing_output_is_untouched(self):
        self.output.write_bytes(b'previous')
        with self.assertRaisesRegex(ValueError, 'Output exists'):
            r.restore(self.base, self.directory)
        self.assertEqual(self.output.read_bytes(), b'previous')

    def test_copy_interruption_retains_unverified_partial_file(self):
        def interrupt(source, target, *args):
            target.write(b'B')
            raise KeyboardInterrupt('injected')
        with patch.object(r.shutil, 'copyfileobj', interrupt):
            with self.assertRaises(KeyboardInterrupt):
                r.restore(self.base, self.directory)
        self.assertTrue(self.output.exists())

    def test_symlink_replacement_during_publish_is_rejected_preserved(self):
        def replace(source, target, *args):
            self.real_copy(source, target, *args)
            self.output.unlink()
            self.output.symlink_to(self.other)
        with patch.object(r.shutil, 'copyfileobj', replace):
            with self.assertRaisesRegex(ValueError, 'publication identity'):
                r.restore(self.base, self.directory)
        self.assertTrue(self.output.is_symlink())
        self.assertEqual(self.other.read_bytes(), self.expected)

    def test_regular_replacement_during_publish_is_rejected_preserved(self):
        def replace(source, target, *args):
            self.real_copy(source, target, *args)
            self.output.unlink()
            self.output.write_bytes(self.expected)
        with patch.object(r.shutil, 'copyfileobj', replace):
            with self.assertRaisesRegex(ValueError, 'publication identity'):
                r.restore(self.base, self.directory)
        self.assertEqual(self.output.read_bytes(), self.expected)

    def test_missing_output_cleanup_does_not_mask_original_error(self):
        def interrupt(source, target, *args):
            self.output.unlink()
            raise KeyboardInterrupt('original interruption')
        with patch.object(r.shutil, 'copyfileobj', interrupt):
            with self.assertRaisesRegex(KeyboardInterrupt, 'original interruption'):
                r.restore(self.base, self.directory)

    def test_corrupt_final_output_is_rejected_and_retained(self):
        def corrupt(source, target, *args):
            target.write(b'INVALID')
        with patch.object(r.shutil, 'copyfileobj', corrupt):
            with self.assertRaisesRegex(ValueError, 'Final output verification'):
                r.restore(self.base, self.directory)
        self.assertTrue(self.output.exists())

    def test_replacement_then_interruption_preserves_replacement(self):
        def interrupt(source, target, *args):
            self.output.unlink()
            self.output.write_bytes(b'other writer')
            raise KeyboardInterrupt('original interruption')
        with patch.object(r.shutil, 'copyfileobj', interrupt):
            with self.assertRaisesRegex(KeyboardInterrupt, 'original interruption'):
                r.restore(self.base, self.directory)
        self.assertEqual(self.output.read_bytes(), b'other writer')

    def test_cleanup_error_does_not_mask_original_error(self):
        real_unlink = Path.unlink
        def fail_output_unlink(path, *args, **kwargs):
            if path == self.output:
                raise PermissionError('injected cleanup failure')
            return real_unlink(path, *args, **kwargs)
        def interrupt(source, target, *args):
            target.write(b'B')
            raise KeyboardInterrupt('original interruption')
        with patch.object(r.shutil, 'copyfileobj', interrupt), patch.object(Path, 'unlink', fail_output_unlink):
            with self.assertRaisesRegex(KeyboardInterrupt, 'original interruption'):
                r.restore(self.base, self.directory)
        self.assertEqual(self.output.read_bytes(), b'B')
        with self.assertRaisesRegex(ValueError, 'Output exists'):
            r.restore(self.base, self.directory)

    def test_oversized_final_output_rejected_and_retained(self):
        def oversized(source, target, *args):
            target.write(self.expected + b'extra')
        with patch.object(r.shutil, 'copyfileobj', oversized):
            with self.assertRaisesRegex(ValueError, 'Final output verification'):
                r.restore(self.base, self.directory)
        self.assertTrue(self.output.exists())

    def test_short_final_output_rejected_and_retained(self):
        def short(source, target, *args):
            target.write(b'B')
        with patch.object(r.shutil, 'copyfileobj', short):
            with self.assertRaisesRegex(ValueError, 'Final output verification'):
                r.restore(self.base, self.directory)
        self.assertTrue(self.output.exists())

    def test_multiblock_publication(self):
        literal = b'x' * (2 * 1024 * 1024 + 17)
        expected = b'BASE' + literal
        with patch.object(r, 'TARGET_SIZE', len(expected)), patch.object(r, 'TARGET_SHA', hashlib.sha256(expected).hexdigest()), patch.object(r, 'decode_patch', return_value=([('copy', 0, 4), ('literal', 0, len(literal))], literal)):
            r.restore(self.base, self.directory)
        self.assertEqual(self.output.read_bytes(), expected)
        self.assertEqual(self.base.read_bytes(), b'BASE')

    def test_zero_identity_fails_closed_without_unlink(self):
        real_fstat = os.fstat
        def zero_identity(fd):
            fields = list(real_fstat(fd))
            if stat.S_ISREG(fields[0]):
                fields[1] = 0
            return os.stat_result(fields)
        with patch.object(r.os, 'fstat', zero_identity):
            with self.assertRaisesRegex(ValueError, 'Unavailable publication identity'):
                r.restore(self.base, self.directory)
        self.assertTrue(self.output.exists())

    @unittest.skipUnless(hasattr(r, 'require_published_identity'), 'candidate-only late-check seam')
    def test_late_replacement_after_hash_is_rejected_and_preserved(self):
        original = r.require_published_identity
        checks = 0
        def replace_on_second_check(output, created):
            nonlocal checks
            checks += 1
            if checks == 2:
                self.output.unlink()
                self.output.write_bytes(b'late replacement')
            return original(output, created)
        with patch.object(r, 'require_published_identity', replace_on_second_check):
            with self.assertRaisesRegex(ValueError, 'Changed publication identity'):
                r.restore(self.base, self.directory)
        self.assertEqual(self.output.read_bytes(), b'late replacement')

if __name__ == '__main__':
    unittest.main(verbosity=2)
