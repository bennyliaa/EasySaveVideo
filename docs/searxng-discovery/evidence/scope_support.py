"""Exact first-layer SearXNG scope/freeze; historical inputs are never rewritten."""
from pathlib import Path
import hashlib
import json
import os
import stat
import subprocess

ROOT = Path(__file__).resolve().parents[3]
BASE = '9e9dea537c5f6e269adfb6791b47829eb4c440de'
BASELINE_FILES = 4644
BASELINE_JVM_TESTS = 2035
DOCS = 'docs/searxng-discovery/'
EVIDENCE = DOCS + 'evidence/'
OUTPUTS = EVIDENCE + 'runs/'
APP = 'app/src/main/kotlin/com/easysavevideo/app/'
EDITS = {
    'app/build.gradle.kts', 'settings.gradle.kts',
    'app/src/debug/kotlin/com/easysavevideo/app/discovery/BuildDiscovery.kt',
    'app/src/release/kotlin/com/easysavevideo/app/discovery/BuildDiscovery.kt',
    APP + 'discovery/AndroidDiscoveryController.kt',
    APP + 'discovery/DiscoveryFilterPresentationPolicy.kt',
    APP + 'storage/DiscoveryCheckpointCodec.kt', APP + 'ui/DiscoveryScreen.kt',
    'discovery/src/main/kotlin/com/easysavevideo/discovery/DiscoveryModels.kt',
    'discovery/src/main/kotlin/com/easysavevideo/discovery/DiscoveryStore.kt',
    'discovery/src/main/kotlin/com/easysavevideo/discovery/ValidationPipeline.kt',
    'discovery/src/main/kotlin/com/easysavevideo/discovery/VideoDiscoveryCoordinator.kt',
}
SCRIPT = 'scripts/verify-searxng-workspace.sh'
ALLOWLIST_PATH = EVIDENCE + 'scope-allowlist.json'
COUNT_PATH = EVIDENCE + 'jvm-count-expectation.json'
MANIFEST_PATH = EVIDENCE + 'frozen-review-source-manifest.json'
SLICE = 'SEARXNG_FIRST_LAYER'
MEMBERSHIP = 'ALL_4644_BASELINE_FILES_PLUS_EXPLICIT_CURRENT_SOURCES_AND_ALL_CURRENT_REVIEW_INPUTS'


def git(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT)


def baseline():
    assert git('rev-parse', '--show-object-format').strip() == b'sha1', 'UNEXPECTED_GIT_OBJECT_FORMAT'
    result = {}
    for item in git('ls-tree', '-rz', BASE).split(b'\0'):
        if not item:
            continue
        meta, name = item.split(b'\t', 1)
        mode, kind, oid = meta.decode().split()
        path = name.decode()
        assert kind == 'blob' and mode in ('100644', '100755'), ('BASELINE_FILE_TYPE', path)
        assert path not in result, 'DUPLICATE_BASELINE_FILE'
        result[path] = (mode, oid)
    assert len(result) == BASELINE_FILES, ('EXACT_BASELINE_FILE_COUNT_REQUIRED', len(result), BASELINE_FILES)
    return result


def regular(path):
    assert isinstance(path, str) and not Path(path).is_absolute() and '..' not in Path(path).parts, ('UNSAFE_PATH', path)
    file = ROOT / path
    assert file.resolve().is_relative_to(ROOT.resolve()), ('INPUT_ESCAPES_WORKTREE', path)
    assert all(not parent.is_symlink() for parent in file.parents if parent != ROOT and ROOT in parent.parents), ('SYMLINKED_INPUT_PARENT', path)
    assert stat.S_ISREG(file.lstat().st_mode), ('NON_REGULAR_INPUT', path)
    return file


def fingerprint(path):
    file = regular(path)
    content = file.read_bytes()
    return {'path': path, 'size': len(content), 'sha256': hashlib.sha256(content).hexdigest(),
            'executable': bool(file.stat().st_mode & 0o111)}


def allowed_new_sources():
    data = json.loads(regular(ALLOWLIST_PATH).read_text())
    assert data['baseline'] == BASE and data['slice'] == SLICE, 'ALLOWLIST_SCOPE_MISMATCH'
    assert data['allowed_baseline_edits'] == sorted(EDITS), 'EXPLICIT_BASELINE_EDIT_SET_MISMATCH'
    paths = data['new_source_files']
    assert paths and paths == sorted(set(paths)), 'FINAL_SORTED_EXPLICIT_SOURCE_ALLOWLIST_REQUIRED'
    roots = (APP + 'discovery/', APP + 'storage/', APP + 'ui/',
             'app/src/test/kotlin/com/easysavevideo/app/discovery/',
             'app/src/test/kotlin/com/easysavevideo/app/storage/',
             'discovery/src/main/kotlin/com/easysavevideo/discovery/',
             'discovery/src/test/kotlin/com/easysavevideo/discovery/',
             'searxng-provider/src/main/kotlin/com/easysavevideo/searxng/',
             'searxng-provider/src/test/kotlin/com/easysavevideo/searxng/')
    for name in paths:
        assert isinstance(name, str) and (name == 'searxng-provider/build.gradle.kts' or
            (name.startswith(roots) and name.endswith('.kt'))), ('UNSCOPED_SOURCE_PERMISSION', name)
        assert '..' not in Path(name).parts and not Path(name).is_absolute(), ('UNSAFE_PATH', name)
    return set(paths)


def worktree_paths():
    return {name.decode() for name in git('ls-files', '-z', '--cached', '--others', '--exclude-standard').split(b'\0') if name}


def review_inputs():
    """All current documentation, review code and fixtures, regardless of suffix.

    Only an exact manifest path and the dedicated generated-output directory are
    excluded. Output files may not masquerade as executable review inputs.
    """
    result = set()
    for file in (ROOT / DOCS).rglob('*'):
        path = str(file.relative_to(ROOT))
        assert not file.is_symlink(), ('SYMLINKED_REVIEW_PATH', path)
        if file.is_dir():
            continue
        regular(path)
        if path.startswith(OUTPUTS):
            assert not (file.suffix in ('.py', '.sh', '.kt', '.java', '.js', '.mjs', '.sql') or bool(file.stat().st_mode & 0o111)), ('EXECUTABLE_INPUT_IN_OUTPUT_DIRECTORY', path)
        elif path != MANIFEST_PATH:
            result.add(path)
    return result


def verify_scope():
    prior = baseline()
    sources = allowed_new_sources()
    assert EDITS <= prior.keys(), 'DECLARED_EDIT_NOT_IN_BASELINE'
    assert not sources.intersection(prior), 'NEW_SOURCE_ALREADY_IN_BASELINE'
    changed = []
    for path, (mode, oid) in prior.items():
        file = regular(path)
        assert bool(file.stat().st_mode & 0o111) == (mode == '100755'), ('BASELINE_MODE_CHANGED', path)
        content = file.read_bytes()
        actual = hashlib.sha1(b'blob ' + str(len(content)).encode() + b'\0' + content).hexdigest()
        if actual != oid:
            assert path in EDITS, ('HISTORICAL_OR_UNSCOPED_EDIT', path)
            changed.append(path)
    paths = worktree_paths()
    for path in paths - prior.keys():
        assert path in sources or path == SCRIPT or path.startswith(DOCS), ('UNSCOPED_NEW_FILE', path)
        regular(path)
    assert sources <= paths, ('ADMITTED_SOURCE_MISSING', sorted(sources - paths))
    review_inputs()
    return {'baseline': BASE, 'baseline_files': len(prior), 'changed_files': sorted(changed),
            'allowed_production_and_gradle_edits': sorted(EDITS), 'new_source_files': sorted(sources),
            'unchanged_baseline_files': len(prior) - len(changed),
            'historical_scripts_tests_fixtures_evidence': 'ALL_BASELINE_BYTES_AND_GIT_MODES_UNCHANGED'}


def candidate_membership():
    return set(baseline()) | allowed_new_sources() | {SCRIPT, ALLOWLIST_PATH, COUNT_PATH} | review_inputs()


def expected_jvm_tests():
    data = json.loads(regular(COUNT_PATH).read_text())
    assert data['baseline'] == BASE and data['baseline_jvm_tests'] == BASELINE_JVM_TESTS, 'JVM_COUNT_BASELINE_MISMATCH'
    value = data['expected_jvm_tests']
    assert type(value) is int and value > BASELINE_JVM_TESTS, 'EXPECTED_JVM_TEST_TOTAL_REQUIRED'
    assert data['new_jvm_tests'] == value - BASELINE_JVM_TESTS, 'NEW_JVM_TEST_COUNT_MISMATCH'
    return value


def checked_output():
    out = Path(os.environ.get('ESV_EVIDENCE_DIR', ROOT / OUTPUTS / 'final')).resolve()
    docs = (ROOT / 'docs').resolve()
    allowed = (ROOT / OUTPUTS).resolve()
    assert out != ROOT and out != Path('/'), 'UNSAFE_EVIDENCE_DESTINATION'
    assert not out.is_relative_to(ROOT) or out.is_relative_to(allowed) or out.is_relative_to(ROOT / 'build'), 'GENERATED_OUTPUT_REQUIRES_DEDICATED_DIRECTORY'
    assert not out.is_relative_to(docs) or out.is_relative_to(allowed), 'HISTORICAL_OR_FROZEN_INPUT_DIRECTORY_READ_ONLY'
    out.mkdir(parents=True, exist_ok=True)
    return out
