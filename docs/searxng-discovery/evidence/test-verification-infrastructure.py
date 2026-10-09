#!/usr/bin/env python3
"""Isolated synthetic negative controls. No Gradle, network, device or feature runs."""
from pathlib import Path
import importlib.util
import json
import os
import subprocess
import sys
import tempfile
import unittest

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('searxng_scope_under_test', HERE / 'scope_support.py')
subject = importlib.util.module_from_spec(spec)
spec.loader.exec_module(subject)


class ScopeInfrastructureTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='esv-searxng-gate-test-')
        self.root = Path(self.temp.name)
        self.saved = subject.ROOT, subject.BASE, subject.BASELINE_FILES
        subject.ROOT = self.root
        self.git('init', '-q')
        for path in subject.EDITS | {'historical/source.kt', 'historical/fixture.sql', 'historical/gate.sh', '.gitignore'}:
            self.put(path, '__pycache__/\n' if path == '.gitignore' else 'baseline\n')
        (self.root / 'historical/gate.sh').chmod(0o755)
        self.git('add', '.')
        self.git('-c', 'user.name=Synthetic Gate Test', '-c', 'user.email=gate@example.invalid', 'commit', '-qm', 'synthetic baseline')
        subject.BASE = self.git('rev-parse', 'HEAD').strip()
        subject.BASELINE_FILES = len(subject.EDITS) + 4
        self.sources = [subject.APP + 'discovery/SearxngSynthetic.kt',
            'searxng-provider/src/test/kotlin/com/easysavevideo/searxng/SyntheticTest.kt']
        self.allowlist()
        for path in self.sources:
            self.put(path, 'synthetic current source\n')
        self.put(subject.SCRIPT, '#!/bin/sh\n')

    def tearDown(self):
        subject.ROOT, subject.BASE, subject.BASELINE_FILES = self.saved
        self.temp.cleanup()

    def git(self, *args):
        return subprocess.check_output(['git', *args], cwd=self.root, text=True)

    def put(self, path, text):
        file = self.root / path
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(text)

    def allowlist(self, sources=None, edits=None):
        self.put(subject.ALLOWLIST_PATH, json.dumps({'slice': subject.SLICE, 'baseline': subject.BASE,
            'allowed_baseline_edits': sorted(subject.EDITS) if edits is None else edits,
            'new_source_files': self.sources if sources is None else sources}))

    def programs(self):
        support = (HERE / 'scope_support.py').read_text()
        support = support.replace("BASE = '" + self.saved[1] + "'", "BASE = '" + subject.BASE + "'", 1)
        support = support.replace('BASELINE_FILES = ' + str(self.saved[2]), 'BASELINE_FILES = ' + str(subject.BASELINE_FILES), 1)
        self.put(subject.EVIDENCE + 'scope_support.py', support)
        for name in ('freeze-inputs.py', 'verify-frozen.py'):
            self.put(subject.EVIDENCE + name, (HERE / name).read_text())

    def program(self, name, *args):
        return subprocess.run(['python3', str(self.root / subject.EVIDENCE / name), *args],
            cwd=self.root, text=True, capture_output=True, env={**os.environ, 'PYTHONDONTWRITEBYTECODE': '1'})

    def freeze(self):
        self.programs()
        result = self.program('freeze-inputs.py', '--write', '--expected-jvm-tests', '2147')
        self.assertEqual(0, result.returncode, result.stderr)

    def rejected(self, pattern):
        result = self.program('verify-frozen.py')
        self.assertNotEqual(0, result.returncode)
        self.assertIn(pattern, result.stderr)

    def test_accepts_exact_baseline_and_explicit_new_sources(self):
        result = subject.verify_scope()
        self.assertEqual([], result['changed_files'])
        self.assertEqual(subject.BASELINE_FILES, result['baseline_files'])

    def test_accepts_only_twelve_declared_integration_edits(self):
        for name in subject.EDITS:
            self.put(name, 'current integration\n')
        self.assertEqual(sorted(subject.EDITS), subject.verify_scope()['changed_files'])

    def test_rejects_wrong_baseline_count(self):
        subject.BASELINE_FILES += 1
        with self.assertRaisesRegex(AssertionError, 'EXACT_BASELINE_FILE_COUNT_REQUIRED'):
            subject.verify_scope()

    def test_rejects_historical_bytes(self):
        for path in ('historical/source.kt', 'historical/fixture.sql', 'historical/gate.sh'):
            with self.subTest(path=path):
                self.put(path, 'changed\n')
                with self.assertRaisesRegex(AssertionError, 'HISTORICAL_OR_UNSCOPED_EDIT'):
                    subject.verify_scope()
                self.put(path, 'baseline\n')

    def test_rejects_mode_change_even_on_allowed_edit(self):
        (self.root / sorted(subject.EDITS)[0]).chmod(0o755)
        with self.assertRaisesRegex(AssertionError, 'BASELINE_MODE_CHANGED'):
            subject.verify_scope()

    def test_rejects_baseline_deletion(self):
        (self.root / 'historical/source.kt').unlink()
        with self.assertRaises(FileNotFoundError):
            subject.verify_scope()

    def test_rejects_identical_content_symlink(self):
        p = self.root / 'historical/source.kt'
        p.unlink(); p.symlink_to('fixture.sql')
        with self.assertRaisesRegex(AssertionError, 'NON_REGULAR_INPUT'):
            subject.verify_scope()

    def test_rejects_unlisted_production_source(self):
        self.put(subject.APP + 'discovery/Surprising.kt', 'unlisted\n')
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

    def test_rejects_empty_allowlist(self):
        self.allowlist(sources=[])
        with self.assertRaisesRegex(AssertionError, 'FINAL_SORTED_EXPLICIT_SOURCE_ALLOWLIST_REQUIRED'):
            subject.verify_scope()

    def test_rejects_expanded_baseline_edit_permissions(self):
        self.allowlist(edits=sorted(subject.EDITS | {'historical/source.kt'}))
        with self.assertRaisesRegex(AssertionError, 'EXPLICIT_BASELINE_EDIT_SET_MISMATCH'):
            subject.verify_scope()

    def test_rejects_source_permission_outside_current_packages(self):
        self.allowlist(sources=['app/src/main/Unrelated.kt'])
        with self.assertRaisesRegex(AssertionError, 'UNSCOPED_SOURCE_PERMISSION'):
            subject.verify_scope()

    def test_membership_includes_unknown_review_suffix_and_all_baseline(self):
        path = subject.EVIDENCE + 'nested/surprising.unknown'
        self.put(path, 'input\n')
        self.assertIn(path, subject.candidate_membership())
        self.assertTrue(set(subject.baseline()) <= subject.candidate_membership())

    def test_rejects_review_source_hidden_in_outputs(self):
        self.put(subject.OUTPUTS + 'hidden.py', 'input\n')
        with self.assertRaisesRegex(AssertionError, 'EXECUTABLE_INPUT_IN_OUTPUT_DIRECTORY'):
            subject.verify_scope()

    def test_rejects_executable_hidden_in_outputs(self):
        name = subject.OUTPUTS + 'hidden.bin'
        self.put(name, 'input\n'); (self.root / name).chmod(0o755)
        with self.assertRaisesRegex(AssertionError, 'EXECUTABLE_INPUT_IN_OUTPUT_DIRECTORY'):
            subject.verify_scope()

    def test_outputs_excluded_only_in_explicit_directory(self):
        self.put(subject.OUTPUTS + 'result.log', 'output\n')
        self.put(subject.EVIDENCE + 'result.log', 'input\n')
        self.assertNotIn(subject.OUTPUTS + 'result.log', subject.candidate_membership())
        self.assertIn(subject.EVIDENCE + 'result.log', subject.candidate_membership())

    def test_exact_explicit_freeze_passes(self):
        self.freeze()
        result = self.program('verify-frozen.py')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(2147, json.loads(result.stdout)['expected_jvm_tests'])

    def test_missing_freeze_rejected(self):
        self.programs()
        self.rejected('FINAL_FROZEN_MANIFEST_REQUIRED')

    def test_missing_count_expectation_rejected(self):
        self.freeze(); (self.root / subject.COUNT_PATH).unlink()
        self.rejected('FileNotFoundError')

    def test_baseline_only_count_rejected(self):
        self.programs()
        result = self.program('freeze-inputs.py', '--write', '--expected-jvm-tests', '2035')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('EXPECTED_JVM_TEST_TOTAL_REQUIRED', result.stderr)
        self.assertFalse((self.root / subject.MANIFEST_PATH).exists())

    def test_allowed_integration_bytes_changed_after_freeze_rejected(self):
        self.freeze(); self.put(sorted(subject.EDITS)[0], 'later change\n')
        self.rejected('FROZEN_BYTES_OR_MODE_MISMATCH')

    def test_new_source_mode_changed_after_freeze_rejected(self):
        self.freeze(); (self.root / self.sources[0]).chmod(0o755)
        self.rejected('FROZEN_BYTES_OR_MODE_MISMATCH')

    def test_new_review_input_after_freeze_rejected(self):
        self.freeze(); self.put(subject.EVIDENCE + 'new-review.unknown', 'unexpected input\n')
        self.rejected('FULL_MEMBERSHIP_MISMATCH')

    def test_missing_source_after_freeze_rejected(self):
        self.freeze(); (self.root / self.sources[0]).unlink()
        self.rejected('FileNotFoundError')

    def test_manifest_count_mismatch_rejected(self):
        self.freeze()
        path = self.root / subject.MANIFEST_PATH
        data = json.loads(path.read_text()); data['expected_jvm_tests'] += 1
        path.write_text(json.dumps(data)); self.rejected('EXPECTED_JVM_TEST_TOTAL')

    def test_duplicate_frozen_path_rejected(self):
        self.freeze()
        path = self.root / subject.MANIFEST_PATH
        data = json.loads(path.read_text()); data['files'].append(data['files'][0])
        path.write_text(json.dumps(data)); self.rejected('DUPLICATE_FROZEN_PATH')

    def test_count_record_and_manifest_cannot_change_together_unnoticed(self):
        self.freeze()
        path = self.root / subject.COUNT_PATH
        data = json.loads(path.read_text()); data['expected_jvm_tests'] += 1; data['new_jvm_tests'] += 1
        path.write_text(json.dumps(data))
        path = self.root / subject.MANIFEST_PATH
        data = json.loads(path.read_text()); data['expected_jvm_tests'] += 1
        path.write_text(json.dumps(data)); self.rejected('FROZEN_BYTES_OR_MODE_MISMATCH')

    def test_refreeze_during_aggregate_rejected_by_recorded_identity(self):
        self.freeze()
        record = self.root / subject.OUTPUTS / 'identity.json'; record.parent.mkdir(parents=True)
        result = self.program('verify-frozen.py', '--record', str(record))
        self.assertEqual(0, result.returncode, result.stderr)
        self.put(self.sources[0], 'new reviewed bytes\n'); self.freeze()
        result = self.program('verify-frozen.py', '--against', str(record))
        self.assertNotEqual(0, result.returncode)
        self.assertIn('MANIFEST_CHANGED_DURING_AGGREGATE', result.stderr)

    def test_record_cannot_overwrite_frozen_input(self):
        self.freeze()
        result = self.program('verify-frozen.py', '--record', str(self.root / self.sources[0]))
        self.assertNotEqual(0, result.returncode)
        self.assertIn('FROZEN_INPUT_CANNOT_BE_OUTPUT', result.stderr)


class JunitAccountingTest(unittest.TestCase):
    def setUp(self):
        from junit_support import MODULES, summarize_junit
        self.summarize = summarize_junit
        self.modules = MODULES
        self.temp = tempfile.TemporaryDirectory(prefix='esv-searxng-junit-test-')
        self.root = Path(self.temp.name)
        self.log = self.root / 'aggregate.log'
        self.log.write_text('> Task :core-model:test NO-SOURCE\n')
        for module in self.modules[1:]:
            self.xml(module)

    def tearDown(self):
        self.temp.cleanup()

    def xml(self, module, tests=1, failures=0, errors=0, skipped=0):
        folder = self.root / module / 'build/test-results' / ('testDebugUnitTest' if module == 'app' else 'test')
        folder.mkdir(parents=True, exist_ok=True)
        path = folder / 'TEST-synthetic.xml'
        path.write_text(f'<testsuite tests="{tests}" failures="{failures}" errors="{errors}" skipped="{skipped}"/>')
        return path

    def test_accepts_only_documented_core_model_zero(self):
        result = self.summarize(self.root, self.log, 10)
        self.assertEqual(0, result['core-model']['tests'])
        self.assertEqual(10, result['total']['tests'])

    def test_requires_current_no_source_task_evidence(self):
        self.log.write_text('> Task :core-model:test SKIPPED\n')
        with self.assertRaisesRegex(AssertionError, 'CORE_MODEL_NO_SOURCE_TASK_REQUIRED'):
            self.summarize(self.root, self.log, 10)

    def test_rejects_core_model_stale_xml(self):
        self.xml('core-model', tests=0)
        with self.assertRaisesRegex(AssertionError, 'CORE_MODEL_UNEXPECTED_JUNIT_RESULTS'):
            self.summarize(self.root, self.log, 10)

    def test_rejects_new_core_model_test_source(self):
        path = self.root / 'core-model/src/test/kotlin/Unexpected.kt'
        path.parent.mkdir(parents=True); path.write_text('unexpected test')
        with self.assertRaisesRegex(AssertionError, 'CORE_MODEL_UNEXPECTED_TEST_SOURCE'):
            self.summarize(self.root, self.log, 10)

    def test_rejects_missing_other_module_xml(self):
        self.xml('searxng-provider').unlink()
        with self.assertRaisesRegex(AssertionError, 'MISSING_JUNIT_MODULE_RESULTS'):
            self.summarize(self.root, self.log, 9)

    def test_rejects_zero_other_module(self):
        self.xml('ui-theme', tests=0)
        with self.assertRaisesRegex(AssertionError, 'EMPTY_JUNIT_MODULE_RESULTS'):
            self.summarize(self.root, self.log, 9)

    def test_rejects_total_mismatch(self):
        with self.assertRaises(AssertionError):
            self.summarize(self.root, self.log, 11)

    def test_rejects_failures(self):
        self.xml('app', failures=1)
        with self.assertRaises(AssertionError):
            self.summarize(self.root, self.log, 10)

    def test_rejects_errors(self):
        self.xml('app', errors=1)
        with self.assertRaises(AssertionError):
            self.summarize(self.root, self.log, 10)

    def test_rejects_skips(self):
        self.xml('app', skipped=1)
        with self.assertRaises(AssertionError):
            self.summarize(self.root, self.log, 10)

    def test_rejects_negative_counts(self):
        self.xml('app', skipped=-1)
        with self.assertRaisesRegex(AssertionError, 'NEGATIVE_JUNIT_COUNT'):
            self.summarize(self.root, self.log, 10)

    def test_rejects_unexpected_xml_root(self):
        self.xml('app').write_text('<testsuites tests="1"/>')
        with self.assertRaisesRegex(AssertionError, 'UNEXPECTED_JUNIT_ROOT'):
            self.summarize(self.root, self.log, 10)


if __name__ == '__main__':
    unittest.main(verbosity=2)
