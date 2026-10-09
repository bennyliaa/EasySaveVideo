#!/usr/bin/env python3
"""Synthetic isolated-repository negative controls for the new scope/freeze gate.

Does not edit the development checkout, run Gradle, touch a device or execute an
excluded feature. The tiny Git repository and every mutation live in TemporaryDirectory.
"""
from pathlib import Path
import hashlib
import importlib.util
import json
import subprocess
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('bookmark_scope_under_test', HERE / 'scope_support.py')
subject = importlib.util.module_from_spec(spec)
spec.loader.exec_module(subject)


class ScopeInfrastructureTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='esv-bookmark-gate-test-')
        self.root = Path(self.temp.name)
        self.old_root, self.old_base = subject.ROOT, subject.BASE
        self.old_preservation = (subject.PRESERVED_CHECKPOINT, subject.PRESERVED_MANIFEST_SHA256, subject.PRESERVED_FILE_COUNT)
        subject.ROOT = self.root
        self.git('init', '-q')
        for name in subject.EDITS | {'historical/source.kt', 'historical/fixture.sql', 'historical/gate.sh', '.gitignore'}:
            self.put(name, '__pycache__/\n' if name == '.gitignore' else 'baseline\n')
        (self.root / 'historical/gate.sh').chmod(0o755)
        self.put(subject.EVIDENCE + 'prior-output.log', 'historical partial output\n')
        self.git('add', '.')
        self.git('-c', 'user.name=Synthetic Gate Test', '-c', 'user.email=gate@example.invalid', 'commit', '-qm', 'synthetic baseline')
        subject.BASE = self.git('rev-parse', 'HEAD').strip()
        subject.PRESERVED_CHECKPOINT = subject.BASE
        subject.PRESERVED_FILE_COUNT = 1
        self.put(subject.PRESERVED_DIR + 'prior-output.log', 'historical partial output\n')
        data = (self.root / subject.PRESERVED_DIR / 'prior-output.log').read_bytes()
        manifest = {'checkpoint': subject.BASE, 'reason': 'synthetic preserved partial result',
                    'files': [{'path': 'prior-output.log', 'sha256': hashlib.sha256(data).hexdigest(), 'bytes': len(data), 'executable': False}]}
        self.put(subject.PRESERVED_MANIFEST, json.dumps(manifest))
        subject.PRESERVED_MANIFEST_SHA256 = hashlib.sha256((self.root / subject.PRESERVED_MANIFEST).read_bytes()).hexdigest()
        self.sources = [subject.APP + 'browser/bookmarks/SyntheticBookmark.kt',
                        'app/src/test/kotlin/com/easysavevideo/app/browser/bookmarks/SyntheticBookmarkTest.kt']
        self.put(subject.ALLOWLIST_PATH, json.dumps({'slice': 'M1-B1', 'baseline': subject.BASE, 'new_source_files': self.sources}))
        for path in self.sources:
            self.put(path, 'synthetic current source\n')
        self.put(subject.SCRIPT, '#!/bin/sh\n')

    def tearDown(self):
        subject.ROOT, subject.BASE = self.old_root, self.old_base
        subject.PRESERVED_CHECKPOINT, subject.PRESERVED_MANIFEST_SHA256, subject.PRESERVED_FILE_COUNT = self.old_preservation
        self.temp.cleanup()

    def git(self, *args):
        return subprocess.check_output(['git', *args], cwd=self.root, text=True)

    def put(self, name, text):
        file = self.root / name
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(text)

    def test_accepts_exact_baseline_and_explicit_new_sources(self):
        report = subject.verify_scope()
        self.assertEqual([], report['changed_files'])
        self.assertEqual(8, report['baseline_files'])

    def test_accepts_only_three_declared_integration_edits(self):
        for name in subject.EDITS:
            self.put(name, 'current integration\n')
        self.assertEqual(sorted(subject.EDITS), subject.verify_scope()['changed_files'])

    def test_rejects_historical_source_mutation(self):
        self.put('historical/source.kt', 'changed\n')
        with self.assertRaisesRegex(AssertionError, 'HISTORICAL_OR_UNSCOPED_EDIT'):
            subject.verify_scope()

    def test_rejects_historical_gate_mutation(self):
        self.put('historical/gate.sh', 'changed\n')
        with self.assertRaisesRegex(AssertionError, 'HISTORICAL_OR_UNSCOPED_EDIT'):
            subject.verify_scope()

    def test_rejects_historical_fixture_mutation(self):
        self.put('historical/fixture.sql', 'changed\n')
        with self.assertRaisesRegex(AssertionError, 'HISTORICAL_OR_UNSCOPED_EDIT'):
            subject.verify_scope()

    def test_rejects_mode_change_even_on_allowed_integration(self):
        (self.root / sorted(subject.EDITS)[0]).chmod(0o755)
        with self.assertRaisesRegex(AssertionError, 'BASELINE_MODE_CHANGED'):
            subject.verify_scope()

    def test_rejects_baseline_deletion(self):
        (self.root / 'historical/source.kt').unlink()
        with self.assertRaises(FileNotFoundError):
            subject.verify_scope()

    def test_rejects_symlink_with_identical_target_content(self):
        path = self.root / 'historical/source.kt'
        path.unlink()
        path.symlink_to('fixture.sql')
        with self.assertRaisesRegex(AssertionError, 'NON_REGULAR_INPUT'):
            subject.verify_scope()

    def test_rejects_unlisted_production_source(self):
        self.put(subject.APP + 'browser/bookmarks/Unexpected.kt', 'unlisted\n')
        with self.assertRaisesRegex(AssertionError, 'UNSCOPED_NEW_FILE'):
            subject.verify_scope()

    def test_rejects_unrelated_new_script(self):
        self.put('scripts/unrequested.sh', 'unlisted\n')
        with self.assertRaisesRegex(AssertionError, 'UNSCOPED_NEW_FILE'):
            subject.verify_scope()

    def test_rejects_missing_admitted_source(self):
        (self.root / self.sources[0]).unlink()
        with self.assertRaisesRegex(AssertionError, 'ADMITTED_SOURCE_MISSING'):
            subject.verify_scope()

    def test_rejects_empty_allowlist_instead_of_learning_from_worktree(self):
        self.put(subject.ALLOWLIST_PATH, json.dumps({'slice': 'M1-B1', 'baseline': subject.BASE, 'new_source_files': []}))
        with self.assertRaisesRegex(AssertionError, 'FINAL_EXPLICIT_SOURCE_ALLOWLIST_REQUIRED'):
            subject.verify_scope()

    def test_rejects_expansion_outside_bookmark_packages(self):
        self.put(subject.ALLOWLIST_PATH, json.dumps({'slice': 'M1-B1', 'baseline': subject.BASE, 'new_source_files': ['app/src/main/Unrelated.kt']}))
        with self.assertRaisesRegex(AssertionError, 'UNSCOPED_SOURCE_PERMISSION'):
            subject.verify_scope()

    def test_membership_includes_all_baseline_and_current_nested_review_inputs(self):
        names = [subject.EVIDENCE + 'nested/review' + ext for ext in ('.py', '.sh', '.kt', '.java', '.js', '.mjs', '.sql', '.bin')]
        for name in names:
            self.put(name, 'review input\n')
        generated = subject.EVIDENCE + 'results.log'
        self.put(generated, 'runtime output\n')
        self.put(subject.EVIDENCE + 'synthetic.class', 'compiled output\n')
        expected = set(subject.baseline()) | set(self.sources) | {subject.SCRIPT, subject.ALLOWLIST_PATH} | set(names) | subject.preserved_membership()
        self.assertEqual(expected, subject.candidate_membership())
        self.assertNotIn(generated, subject.candidate_membership())
        self.assertNotIn(subject.EVIDENCE + 'synthetic.class', subject.candidate_membership())
        self.assertNotIn(subject.MANIFEST_PATH, subject.candidate_membership())

    def test_fingerprint_detects_bytes_and_git_mode_independently(self):
        name = self.sources[0]
        before = subject.fingerprint(name)
        self.put(name, 'same-length-other-content\n')
        changed = subject.fingerprint(name)
        self.assertNotEqual(before['sha256'], changed['sha256'])
        (self.root / name).chmod(0o755)
        mode = subject.fingerprint(name)
        self.assertEqual(changed['sha256'], mode['sha256'])
        self.assertNotEqual(changed['executable'], mode['executable'])


    def test_rejects_changed_preserved_output_bytes(self):
        self.put(subject.PRESERVED_DIR + 'prior-output.log', 'rewritten partial result\n')
        with self.assertRaisesRegex(AssertionError, 'PRESERVED_BYTES_OR_MODE_MISMATCH'):
            subject.verify_scope()

    def test_rejects_changed_preserved_output_mode(self):
        (self.root / subject.PRESERVED_DIR / 'prior-output.log').chmod(0o755)
        with self.assertRaisesRegex(AssertionError, 'PRESERVED_BYTES_OR_MODE_MISMATCH'):
            subject.verify_scope()

    def test_rejects_missing_preserved_output(self):
        (self.root / subject.PRESERVED_DIR / 'prior-output.log').unlink()
        with self.assertRaisesRegex(AssertionError, 'PRESERVED_MEMBERSHIP_MISMATCH'):
            subject.verify_scope()

    def test_rejects_additional_unlisted_preserved_output(self):
        self.put(subject.PRESERVED_DIR + 'extra-output.log', 'unexpected\n')
        with self.assertRaisesRegex(AssertionError, 'PRESERVED_MEMBERSHIP_MISMATCH'):
            subject.verify_scope()

    def test_rejects_unlisted_preserved_empty_directory(self):
        (self.root / subject.PRESERVED_DIR / 'unexpected-empty-directory').mkdir()
        with self.assertRaisesRegex(AssertionError, 'PRESERVED_DIRECTORY_MEMBERSHIP_MISMATCH'):
            subject.verify_scope()

    def test_rejects_preservation_manifest_rewrite(self):
        path = self.root / subject.PRESERVED_MANIFEST
        manifest = json.loads(path.read_text())
        manifest['reason'] = 'rewritten'
        path.write_text(json.dumps(manifest))
        with self.assertRaisesRegex(AssertionError, 'PRESERVED_MANIFEST_CHANGED'):
            subject.verify_scope()

    def test_rejects_changed_archive_even_if_row_hash_is_replaced(self):
        self.put(subject.PRESERVED_DIR + 'prior-output.log', 'rewritten partial result\n')
        data = (self.root / subject.PRESERVED_DIR / 'prior-output.log').read_bytes()
        path = self.root / subject.PRESERVED_MANIFEST
        manifest = json.loads(path.read_text())
        manifest['files'][0].update(sha256=hashlib.sha256(data).hexdigest(), bytes=len(data))
        path.write_text(json.dumps(manifest))
        # Even a hypothetically reviewed new manifest cannot redefine old Git bytes.
        subject.PRESERVED_MANIFEST_SHA256 = hashlib.sha256(path.read_bytes()).hexdigest()
        with self.assertRaisesRegex(AssertionError, 'PRESERVED_CHECKPOINT_BYTES_OR_MODE_MISMATCH'):
            subject.verify_scope()

    def freeze_programs(self):
        support = (HERE / 'scope_support.py').read_text()
        support = support.replace("BASE = '0c178320cd78391bfedd5f9846e3ee873f0f5fce'", "BASE = '" + subject.BASE + "'", 1)
        old_checkpoint, old_hash, old_count = self.old_preservation
        support = support.replace("PRESERVED_CHECKPOINT = '" + old_checkpoint + "'", "PRESERVED_CHECKPOINT = '" + subject.PRESERVED_CHECKPOINT + "'", 1)
        support = support.replace("PRESERVED_MANIFEST_SHA256 = '" + old_hash + "'", "PRESERVED_MANIFEST_SHA256 = '" + subject.PRESERVED_MANIFEST_SHA256 + "'", 1)
        support = support.replace('PRESERVED_FILE_COUNT = ' + str(old_count), 'PRESERVED_FILE_COUNT = ' + str(subject.PRESERVED_FILE_COUNT), 1)
        self.put(subject.EVIDENCE + 'scope_support.py', support)
        for name in ('freeze-inputs.py', 'verify-frozen.py'):
            self.put(subject.EVIDENCE + name, (HERE / name).read_text())
        return self.root / subject.EVIDENCE

    def program(self, name, *args):
        return subprocess.run(['python3', str(self.root / subject.EVIDENCE / name), *args],
                              cwd=self.root, text=True, capture_output=True)

    def test_explicit_freeze_and_full_verifier_accept_exact_candidate(self):
        self.freeze_programs()
        frozen = self.program('freeze-inputs.py', '--write', '--expected-jvm-tests', str(subject.EXPECTED_JVM_TESTS))
        self.assertEqual(0, frozen.returncode, frozen.stderr)
        checked = self.program('verify-frozen.py')
        self.assertEqual(0, checked.returncode, checked.stderr)
        self.assertEqual(subject.EXPECTED_JVM_TESTS, json.loads(checked.stdout)['expected_jvm_tests'])

    def test_freezer_rejects_superseded_checkpoint_jvm_count(self):
        self.freeze_programs()
        result = self.program('freeze-inputs.py', '--write', '--expected-jvm-tests', '2020')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('EXPECTED_JVM_TEST_TOTAL', result.stderr)
        self.assertFalse((self.root / subject.MANIFEST_PATH).exists())

    def test_verifier_rejects_wrong_jvm_total_in_manifest(self):
        self.freeze_programs()
        self.assertEqual(0, self.program('freeze-inputs.py', '--write', '--expected-jvm-tests', str(subject.EXPECTED_JVM_TESTS)).returncode)
        path = self.root / subject.MANIFEST_PATH
        manifest = json.loads(path.read_text())
        manifest['expected_jvm_tests'] = 2020
        path.write_text(json.dumps(manifest))
        result = self.program('verify-frozen.py')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('EXPECTED_JVM_TEST_TOTAL', result.stderr)

    def test_verifier_rejects_post_freeze_allowed_source_edit(self):
        self.freeze_programs()
        self.assertEqual(0, self.program('freeze-inputs.py', '--write', '--expected-jvm-tests', str(subject.EXPECTED_JVM_TESTS)).returncode)
        self.put(sorted(subject.EDITS)[0], 'post-freeze integration change\n')
        result = self.program('verify-frozen.py')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('FROZEN_BYTES_OR_MODE_MISMATCH', result.stderr)

    def test_verifier_rejects_post_freeze_new_review_input(self):
        self.freeze_programs()
        self.assertEqual(0, self.program('freeze-inputs.py', '--write', '--expected-jvm-tests', str(subject.EXPECTED_JVM_TESTS)).returncode)
        self.put(subject.EVIDENCE + 'unfrozen-fixture.kt', 'new independent input\n')
        result = self.program('verify-frozen.py')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('FULL_MEMBERSHIP_MISMATCH', result.stderr)

    def test_verifier_rejects_post_freeze_java_review_input(self):
        self.freeze_programs()
        self.assertEqual(0, self.program('freeze-inputs.py', '--write', '--expected-jvm-tests', str(subject.EXPECTED_JVM_TESTS)).returncode)
        self.put(subject.EVIDENCE + 'android-doubles/Unfrozen.java', 'new independent double\n')
        result = self.program('verify-frozen.py')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('FULL_MEMBERSHIP_MISMATCH', result.stderr)

    def test_mid_run_refreeze_cannot_replace_pinned_identity(self):
        evidence = self.freeze_programs()
        self.assertEqual(0, self.program('freeze-inputs.py', '--write', '--expected-jvm-tests', str(subject.EXPECTED_JVM_TESTS)).returncode)
        identity = evidence / 'run-identity.json'
        self.assertEqual(0, self.program('verify-frozen.py', '--record', str(identity)).returncode)
        self.put(sorted(subject.EDITS)[0], 'new candidate\n')
        self.assertEqual(0, self.program('freeze-inputs.py', '--write', '--expected-jvm-tests', str(subject.EXPECTED_JVM_TESTS)).returncode)
        result = self.program('verify-frozen.py', '--against', str(identity))
        self.assertNotEqual(0, result.returncode)
        self.assertIn('MANIFEST_CHANGED_DURING_AGGREGATE', result.stderr)

    def test_identity_record_cannot_overwrite_frozen_input(self):
        self.freeze_programs()
        self.assertEqual(0, self.program('freeze-inputs.py', '--write', '--expected-jvm-tests', str(subject.EXPECTED_JVM_TESTS)).returncode)
        protected = self.root / 'historical/fixture.sql'
        before = protected.read_bytes()
        result = self.program('verify-frozen.py', '--record', str(protected))
        self.assertNotEqual(0, result.returncode)
        self.assertIn('FROZEN_INPUT_CANNOT_BE_OUTPUT', result.stderr)
        self.assertEqual(before, protected.read_bytes())


if __name__ == '__main__':
    unittest.main(verbosity=2)
