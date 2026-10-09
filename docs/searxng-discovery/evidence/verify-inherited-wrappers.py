#!/usr/bin/env python3
"""Auditable, exact transformations of ordinary immutable bookmark-era wrappers.

Legacy behavioral test bodies, fixtures, source selection, compiler commands
and exact host artifact checks are preserved. Named identity/scope/output paths
and an explicitly declared additive checkpoint-v4 host model are adapted. Historical content is read, never written.
"""
from pathlib import Path
import ast
import hashlib
import json
import sys

ROOT = Path(__file__).resolve().parents[3]
OLD = 'docs/browser-bookmarks/evidence/'
NEW = 'docs/searxng-discovery/evidence/'
BASE = '9e9dea537c5f6e269adfb6791b47829eb4c440de'
NAMES = (
    'check-inherited-capture-preview-admission.py', 'check-inherited-editor-admission.py',
    'check-inherited-purchase-admission.py', 'check-inherited-region-recording-admission.py',
    'check-inherited-os-power-admission.py',
    'inherited-reliability-run.sh', 'inherited-filter-run.sh', 'inherited-media-run.sh',
    'inherited-origin-run.sh', 'inherited-power-run.sh', 'run-channel-matrix.sh',
    'run-distribution-host.sh', 'run-independent-destination-review.sh',
    'run-independent-entitlement-review.sh', 'run-isolation-host.sh',
    'run-independent-folder-review.sh', 'inherited-destination-sqlite.py',
    'inherited-independent-destination-sqlite.py', 'inherited-folder-sqlite.py',
    'independent-os-run.sh', 'independent-bookmark-run.sh', 'independent-combined-source.py',
)
EXTRA_SOURCES = {
    'inherited-discovery-history-sqlite.py': 'scripts/test-discovery-history-sqlite.py',
    'inherited-process-reopen.py': 'docs/m9a/evidence/verify-process-reopen.py',
}
OUTPUT_NAMES = ('independent-reliability', 'isolation-host', 'independent-folder-run',
    'independent-os-run', 'inherited-origin-run', 'independent-filter-run',
    'independent-destination-run', 'inherited-media-run')


def once(text, old, new):
    assert text.count(old) == 1, ('TRANSFORMATION_ANCHOR_NOT_UNIQUE', old)
    return text.replace(old, new, 1)


def transform(name, original):
    expected = original
    for target in (*NAMES, 'verify-frozen.py', 'frozen-review-source-manifest.json'):
        expected = expected.replace(OLD + target, NEW + target)
    for target in OUTPUT_NAMES:
        expected = expected.replace(OLD + target + '}', NEW + 'runs/' + target + '}')
    if name.startswith('check-inherited-'):
        expected = expected.replace("'docs/browser-bookmarks/evidence'", "'docs/searxng-discovery/evidence/runs/final'")
    if name == 'inherited-folder-sqlite.py':
        expected = once(expected,
            "assert manifest['baseline'] == '0c178320cd78391bfedd5f9846e3ee873f0f5fce'",
            "assert manifest['baseline'] == '" + BASE + "'")
    # A newly admitted project module requires precisely two Gradle integration
    # edits. Preserve the old historical-to-baseline protected-file comparison,
    # then independently enforce every current byte/mode with the new scope gate.
    # No behavioral/source assertion or protected path is removed.
    if name in ('check-inherited-editor-admission.py',
                'check-inherited-region-recording-admission.py',
                'check-inherited-capture-preview-admission.py'):
        insertion = 'from scope_support import BASE as CURRENT_BASE, verify_scope\nverify_scope()\n'
        anchor = 'root = Path(__file__).resolve().parents[3]\n' if name == 'check-inherited-editor-admission.py' else 'ROOT = Path(__file__).resolve().parents[3]\n'
        if anchor not in expected:
            anchor = 'ROOT=Path(__file__).resolve().parents[3]\n'
        expected = once(expected, anchor, anchor + insertion)
        for old, new in [
            ("['git','diff','--name-only',base,'--',*protected]", "['git','diff','--name-only',base,CURRENT_BASE,'--',*protected]"),
            ("['git','diff','--name-only',BASE,'--',*protected]", "['git','diff','--name-only',BASE,CURRENT_BASE,'--',*protected]"),
            ("['git', 'diff', '--name-only', BASE, '--', *protected]", "['git', 'diff', '--name-only', BASE, CURRENT_BASE, '--', *protected]"),
        ]:
            if old in expected:
                expected = once(expected, old, new)
                break
        else:
            raise AssertionError(('MISSING_PROTECTED_DIFF', name))
    if name == 'check-inherited-os-power-admission.py':
        expected = expected.replace('M8_B4_UNCHANGED_OS_REGRESSION_WITH_M1_B1_SCOPE_GUARD',
                                    'M8_B4_UNCHANGED_OS_REGRESSION_WITH_SEARXNG_SCOPE_GUARD')
    if name == 'independent-combined-source.py':
        expected = once(expected, 'def verify(repo):\n',
            'def verify(repo):\n    from scope_support import BASE as CURRENT_BASE, EDITS, verify_scope\n    current_scope = verify_scope()\n')
        expected = once(expected,
            "actual=file.read_bytes(); assert actual==expected, ('REVIEWED_BYTES_CHANGED',path)",
            "actual=git('show',CURRENT_BASE+':'+path) if path in EDITS else file.read_bytes()\n        assert actual==expected, ('REVIEWED_BASELINE_OR_UNCHANGED_BYTES_CHANGED',path)")
        expected = once(expected,
            "git('diff','--name-only','--diff-filter=MTDR',HOTFIX_SOURCE)",
            "git('diff','--name-only','--diff-filter=MTDR',HOTFIX_SOURCE,CURRENT_BASE)")
        expected = once(expected, "return {'status':'PASS_SOURCE_IDENTITY_ONLY'",
            "return {'current_source_scope':current_scope, 'identity_adaptation':'Historical hotfix/bookmark identity compared to exact approved baseline; only explicit current edits are separately scope-checked and frozen', 'status':'PASS_BASELINE_AND_CURRENT_SCOPE_IDENTITY_ONLY'")
    if name == 'inherited-discovery-history-sqlite.py':
        expected = once(expected, 'ROOT = Path(__file__).resolve().parents[1]',
            'ROOT = Path(__file__).resolve().parents[3]')
        expected = once(expected, 'import struct\n',
            'import struct\nimport sys\nfrom checkpoint_v4_sqlite_cases import verify_v4_source_guards, read_configuration_tail, test_v4_headers_and_sqlite\n')
        expected = once(expected, 'if version not in (1, 2, 3):',
            'if version not in (1, 2, 3, 4):')
        expected = once(expected,
            "if reader.integer() != 0 or reader.integer() != 0 or reader.offset != len(payload):\n        raise HeaderError('CHECKPOINT_HEADER_NOT_EMPTY')\n    return result",
            "if reader.integer() != 0 or reader.integer() != 0:\n        raise HeaderError('CHECKPOINT_HEADER_NOT_EMPTY')\n    result = read_configuration_tail(reader, result, HeaderError)\n    if reader.offset != len(payload):\n        raise HeaderError('CHECKPOINT_HEADER_NOT_EMPTY')\n    return result")
        expected = once(expected,
            "assert 'require(version in 1..3)' in CODEC_SOURCE",
            "assert 'require(version in 1..4)' in CODEC_SOURCE\n    verify_v4_source_guards(CODEC_SOURCE)")
        expected = once(expected,
            '        properties = test_bounded_properties(directory)\n',
            '        properties = test_bounded_properties(directory)\n    v4_report = test_v4_headers_and_sqlite(sys.modules[__name__])\n')
        expected = once(expected,
            "        'target_schema': 3,\n",
            "        'target_schema': 3,\n        'additive_checkpoint_payload_v4': v4_report,\n")
    if name == 'inherited-process-reopen.py':
        expected = once(expected,
            "ROOT / 'scripts/test-discovery-history-sqlite.py'",
            "ROOT / 'docs/searxng-discovery/evidence/inherited-discovery-history-sqlite.py'")
    return expected


def check_or_write(source, name, expected):
    target = ROOT / NEW / name
    assert not target.is_symlink(), ('SYMLINKED_WRAPPER', name)
    if '--write' in sys.argv:
        target.write_text(expected)
        target.chmod(source.stat().st_mode & 0o777)
    assert target.read_bytes() == expected.encode(), ('INHERITED_BODY_OR_ADAPTATION_MISMATCH', name)
    assert bool(target.stat().st_mode & 0o111) == bool(source.stat().st_mode & 0o111), ('WRAPPER_MODE_MISMATCH', name)
    result = {'source': str(source.relative_to(ROOT)), 'destination': str(target.relative_to(ROOT)),
        'original_sha256': hashlib.sha256(source.read_bytes()).hexdigest(),
        'adapted_sha256': hashlib.sha256(target.read_bytes()).hexdigest(),
        'git_executable_mode_preserved': True}
    if name in EXTRA_SOURCES:
        original = source.read_text()
        def cases(text):
            return {node.name: ast.get_source_segment(text, node) for node in ast.parse(text).body
                    if isinstance(node, ast.FunctionDef) and (node.name.startswith('test_') or
                        name == 'inherited-process-reopen.py')}
        old_cases, new_cases = cases(original), cases(expected)
        assert old_cases == new_cases, ('LEGACY_CASE_BODY_CHANGED', name)
        result['unchanged_legacy_case_functions'] = {
            key: hashlib.sha256(body.encode()).hexdigest() for key, body in sorted(old_cases.items())}
    return result


assert sys.argv[1:] in ([], ['--write']), 'Only --write prepares current wrappers'
rows = []
for name in NAMES:
    source = ROOT / OLD / name
    rows.append(check_or_write(source, name, transform(name, source.read_text())))
for name, relative in EXTRA_SOURCES.items():
    source = ROOT / relative
    rows.append(check_or_write(source, name, transform(name, source.read_text())))
report = {'status': 'PASS', 'behavioral_case_bodies_fixtures_source_selection_and_exact8_checks': 'UNCHANGED',
    'identity_only_adaptations': [
        'Current manifest/self/nested-wrapper/output locations',
        'Current baseline and already-delegated exact full membership for folder SQLite',
        'OS report label refers to new complete baseline byte/mode guard',
        'Three protected historical Git comparisons terminate at approved baseline; complete current byte/mode scope guard added',
        'Combined hotfix/bookmark identity terminates at approved baseline for explicit integration edits; every current byte is separately frozen'],
    'additive_model_adaptations': [
        'Checkpoint payload v4 source guard and bounded optional configuration/strict authorization tail; every legacy history test body unchanged',
        'New explicit v4 header rejection, SQL clear/rollback/reopen cases; SQLite schema3 and schema4 rejection unchanged',
        'Process-reopen wrapper redirects only its history-model import; all four-process behavioral cases unchanged'],
    'mappings': rows}
mapping = ROOT / NEW / 'inherited-wrapper-mapping.json'
assert not mapping.is_symlink(), 'SYMLINKED_WRAPPER_MAPPING'
if '--write' in sys.argv:
    mapping.write_text(json.dumps(report, indent=2) + '\n')
assert json.loads(mapping.read_text()) == report, 'WRAPPER_MAPPING_RECORD_MISMATCH'
print(json.dumps(report, indent=2))
