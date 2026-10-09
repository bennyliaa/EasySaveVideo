#!/usr/bin/env python3
"""Fail closed on missing freeze, changed bytes/modes/membership, count or identity."""
import argparse
import hashlib
import json
import sys
sys.dont_write_bytecode = True
from pathlib import Path
from scope_support import BASE, BASELINE_JVM_TESTS, ROOT, OUTPUTS, MANIFEST_PATH, MEMBERSHIP, candidate_membership, expected_jvm_tests, fingerprint, regular, verify_scope

parser = argparse.ArgumentParser(description=__doc__)
mode = parser.add_mutually_exclusive_group()
mode.add_argument('--record', type=Path)
mode.add_argument('--against', type=Path)
args = parser.parse_args()
path = ROOT / MANIFEST_PATH
assert path.is_file() and not path.is_symlink(), 'FINAL_FROZEN_MANIFEST_REQUIRED'
raw = regular(MANIFEST_PATH).read_bytes()
manifest = json.loads(raw)
assert manifest['baseline'] == BASE and manifest['baseline_jvm_tests'] == BASELINE_JVM_TESTS, 'FROZEN_BASELINE_MISMATCH'
assert type(manifest['expected_jvm_tests']) is int and manifest['expected_jvm_tests'] == expected_jvm_tests(), 'EXPECTED_JVM_TEST_TOTAL'
assert manifest['membership'] == MEMBERSHIP, 'FROZEN_MEMBERSHIP_POLICY_MISMATCH'
rows = manifest['files']
indexed = {row['path']: row for row in rows}
assert len(rows) == len(indexed), 'DUPLICATE_FROZEN_PATH'
expected = candidate_membership()
assert set(indexed) == expected, ('FULL_MEMBERSHIP_MISMATCH', sorted(expected - set(indexed)), sorted(set(indexed) - expected))
for name, row in indexed.items():
    assert type(row['executable']) is bool
    assert fingerprint(name) == row, ('FROZEN_BYTES_OR_MODE_MISMATCH', name)
scope = verify_scope()
identity = {'baseline': BASE, 'manifest_sha256': hashlib.sha256(raw).hexdigest(),
    'files': len(rows), 'expected_jvm_tests': manifest['expected_jvm_tests']}
if args.against:
    assert json.loads(args.against.read_text()) == identity, 'MANIFEST_CHANGED_DURING_AGGREGATE'
if args.record:
    record = args.record.resolve()
    assert not record.is_relative_to(ROOT) or record.is_relative_to(ROOT / OUTPUTS) or record.is_relative_to(ROOT / 'build'), 'FROZEN_INPUT_CANNOT_BE_OUTPUT'
    assert not record.is_relative_to(ROOT) or str(record.relative_to(ROOT)) not in indexed, 'FROZEN_INPUT_CANNOT_BE_OUTPUT'
    args.record.write_text(json.dumps(identity, indent=2) + '\n')
print(json.dumps({'status': 'PASS', **identity, 'baseline_files': scope['baseline_files'],
    'historical_bytes_and_git_modes': 'UNCHANGED'}, indent=2))
