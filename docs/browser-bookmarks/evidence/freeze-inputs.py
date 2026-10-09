#!/usr/bin/env python3
"""Explicit final source freeze; never called by the verification runner."""
import argparse
import hashlib
import json
from scope_support import BASE, BASELINE_JVM_TESTS, EXPECTED_JVM_TESTS, ROOT, MANIFEST_PATH, candidate_membership, fingerprint, verify_scope

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--write', action='store_true', required=True)
parser.add_argument('--expected-jvm-tests', type=int, required=True)
args = parser.parse_args()
assert args.expected_jvm_tests == EXPECTED_JVM_TESTS, 'EXPECTED_JVM_TEST_TOTAL: 1,959 hotfix-inclusive baseline + 76 bookmark cases = 2,035'
scope = verify_scope()
rows = [fingerprint(path) for path in sorted(candidate_membership())]
manifest = {'scope': 'M1-B1 ordinary browser bookmarks; host/source development acceptance only',
            'baseline': BASE, 'baseline_jvm_tests': BASELINE_JVM_TESTS,
            'expected_jvm_tests': args.expected_jvm_tests,
            'membership': 'ALL_BASELINE_FILES_PLUS_EXPLICIT_CURRENT_SOURCE_AND_REVIEW_INPUTS',
            'files': rows}
path = ROOT / MANIFEST_PATH
assert not path.is_symlink(), 'SYMLINKED_MANIFEST'
path.write_text(json.dumps(manifest, indent=2) + '\n')
print(json.dumps({'files': len(rows), 'sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
                  'expected_jvm_tests': args.expected_jvm_tests, 'scope': scope}, indent=2))
