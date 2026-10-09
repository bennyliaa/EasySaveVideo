#!/usr/bin/env python3
"""Explicit final freeze after review and first-run count; never invoked by the runner."""
import argparse
import hashlib
import json
import sys
sys.dont_write_bytecode = True
from scope_support import BASE, BASELINE_JVM_TESTS, ROOT, COUNT_PATH, MANIFEST_PATH, MEMBERSHIP, candidate_membership, fingerprint, verify_scope

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--write', action='store_true', required=True)
parser.add_argument('--expected-jvm-tests', type=int, required=True,
                    help='Explicit reviewed total after the first development run, including searxng-provider')
args = parser.parse_args()
assert args.expected_jvm_tests > BASELINE_JVM_TESTS, 'EXPECTED_JVM_TEST_TOTAL_REQUIRED'
scope = verify_scope()
count = ROOT / COUNT_PATH
assert not count.is_symlink(), 'SYMLINKED_COUNT_RECORD'
count.write_text(json.dumps({'baseline': BASE, 'baseline_jvm_tests': BASELINE_JVM_TESTS,
    'expected_jvm_tests': args.expected_jvm_tests, 'new_jvm_tests': args.expected_jvm_tests - BASELINE_JVM_TESTS,
    'basis': 'Explicit build-owner count supplied after development run; verified again during final frozen aggregate'}, indent=2) + '\n')
rows = [fingerprint(path) for path in sorted(candidate_membership())]
manifest = {'scope': 'First-layer SearXNG source/host development acceptance only',
    'baseline': BASE, 'baseline_jvm_tests': BASELINE_JVM_TESTS, 'expected_jvm_tests': args.expected_jvm_tests,
    'membership': MEMBERSHIP, 'files': rows}
path = ROOT / MANIFEST_PATH
assert not path.is_symlink(), 'SYMLINKED_MANIFEST'
path.write_text(json.dumps(manifest, indent=2) + '\n')
print(json.dumps({'files': len(rows), 'sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
    'expected_jvm_tests': args.expected_jvm_tests, 'scope': scope}, indent=2))
