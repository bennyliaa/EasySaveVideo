#!/usr/bin/env python3
"""Fail closed on changed bytes, modes, membership, scope or mid-run manifest."""
import argparse
import hashlib
import json
from pathlib import Path
from scope_support import BASE, BASELINE_JVM_TESTS, EXPECTED_JVM_TESTS, ROOT, MANIFEST_PATH, candidate_membership, fingerprint, verify_scope

parser = argparse.ArgumentParser(description=__doc__)
mode = parser.add_mutually_exclusive_group()
mode.add_argument('--record', type=Path)
mode.add_argument('--against', type=Path)
args = parser.parse_args()
path = ROOT / MANIFEST_PATH
assert path.is_file() and not path.is_symlink(), 'FINAL_FROZEN_MANIFEST_REQUIRED'
raw = path.read_bytes()
manifest = json.loads(raw)
assert manifest['baseline'] == BASE
assert type(manifest['expected_jvm_tests']) is int and manifest['expected_jvm_tests'] == EXPECTED_JVM_TESTS, 'EXPECTED_JVM_TEST_TOTAL'
assert manifest['baseline_jvm_tests'] == BASELINE_JVM_TESTS, 'BASELINE_JVM_TEST_TOTAL'
assert manifest['membership'] == 'ALL_BASELINE_FILES_PLUS_EXPLICIT_CURRENT_SOURCE_AND_REVIEW_INPUTS'
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
    assert record != path.resolve(), 'MANIFEST_CANNOT_BE_OUTPUT'
    assert not record.is_relative_to(ROOT / 'docs') or record.is_relative_to(ROOT / 'docs/browser-bookmarks/evidence'), 'HISTORICAL_EVIDENCE_READ_ONLY'
    assert not record.is_relative_to(ROOT) or str(record.relative_to(ROOT)) not in indexed, 'FROZEN_INPUT_CANNOT_BE_OUTPUT'
    args.record.write_text(json.dumps(identity, indent=2) + '\n')
print(json.dumps({'status': 'PASS', **identity, 'baseline_files': scope['baseline_files'],
                  'historical_bytes_and_git_modes': 'UNCHANGED'}, indent=2))
