"""M1-B1 exact current-scope membership and historical byte/mode protection.

Only current-scope verification code imports this module. Historical inputs are
read, never rewritten. A freeze is explicit; admission and verification cannot
silently refresh hashes or learn new production file permissions.
"""
from pathlib import Path
import hashlib
import json
import os
import stat
import subprocess

ROOT = Path(__file__).resolve().parents[3]
BASE = '0c178320cd78391bfedd5f9846e3ee873f0f5fce'
BASELINE_JVM_TESTS = 1959
EXPECTED_JVM_TESTS = 2035
PRESERVED_CHECKPOINT = '53ee1863a5b60f44752d246e936b599ca1ca7628'
PRESERVED_MANIFEST_SHA256 = '5c33b3627d2ba65ed5412f0321ad16ea654accc858f5817fada8253b6ebd27ef'
PRESERVED_FILE_COUNT = 203
DOCS = 'docs/browser-bookmarks/'
EVIDENCE = DOCS + 'evidence/'
APP = 'app/src/main/kotlin/com/easysavevideo/app/'
EDITS = {APP + 'MainActivity.kt', APP + 'ui/BrowserScreen.kt', APP + 'browser/SystemBrowserHost.kt'}
MANIFEST_PATH = EVIDENCE + 'frozen-review-source-manifest.json'
ALLOWLIST_PATH = EVIDENCE + 'scope-allowlist.json'
SCRIPT = 'scripts/verify-bookmarks-workspace.sh'
PRESERVED_DIR = EVIDENCE + 'interrupted-2020/'
PRESERVED_MANIFEST = PRESERVED_DIR + 'PRESERVED_OUTPUTS.json'


def git(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT)


def git_tree(reference):
    assert git('rev-parse', '--show-object-format').strip() == b'sha1', 'UNEXPECTED_GIT_OBJECT_FORMAT'
    result = {}
    for item in git('ls-tree', '-rz', reference).split(b'\0'):
        if not item:
            continue
        meta, name = item.split(b'\t', 1)
        mode, kind, oid = meta.decode().split()
        path = name.decode()
        assert kind == 'blob' and mode in ('100644', '100755'), ('BASELINE_FILE_TYPE', path)
        assert path not in result
        result[path] = (mode, oid)
    assert result, 'EMPTY_BASELINE'
    return result


def baseline():
    return git_tree(BASE)


def preserved_membership():
    # The recorded provenance is pinned independently of a later candidate freeze.
    # Also compare each archived file against its immutable original Git checkpoint.
    manifest_file = regular(PRESERVED_MANIFEST)
    raw = manifest_file.read_bytes()
    assert hashlib.sha256(raw).hexdigest() == PRESERVED_MANIFEST_SHA256, 'PRESERVED_MANIFEST_CHANGED'
    manifest = json.loads(raw)
    assert manifest['checkpoint'] == PRESERVED_CHECKPOINT, 'PRESERVED_CHECKPOINT_CHANGED'
    rows = manifest['files']
    names = [row['path'] for row in rows]
    assert len(rows) == len(set(names)) == PRESERVED_FILE_COUNT, 'PRESERVED_FILE_COUNT_OR_DUPLICATE'
    for name in names:
        assert not Path(name).is_absolute() and '..' not in Path(name).parts and name != 'PRESERVED_OUTPUTS.json', ('PRESERVED_UNSAFE_PATH', name)
    expected = {PRESERVED_MANIFEST} | {PRESERVED_DIR + name for name in names}
    actual = set()
    actual_directories = set()
    expected_directories = {str(parent) for name in names for parent in Path(name).parents if str(parent) != '.'}
    for file in (ROOT / PRESERVED_DIR).rglob('*'):
        assert not file.is_symlink(), ('PRESERVED_SYMLINK', str(file.relative_to(ROOT)))
        if file.is_dir():
            actual_directories.add(str(file.relative_to(ROOT / PRESERVED_DIR)))
            continue
        actual.add(str(file.relative_to(ROOT)))
    assert actual_directories == expected_directories, ('PRESERVED_DIRECTORY_MEMBERSHIP_MISMATCH', sorted(expected_directories - actual_directories), sorted(actual_directories - expected_directories))
    assert actual == expected, ('PRESERVED_MEMBERSHIP_MISMATCH', sorted(expected - actual), sorted(actual - expected))
    checkpoint = git_tree(PRESERVED_CHECKPOINT)
    for row in rows:
        path = PRESERVED_DIR + row['path']
        assert type(row['executable']) is bool and type(row['bytes']) is int
        current = fingerprint(path)
        assert current == {'path': path, 'size': row['bytes'], 'sha256': row['sha256'], 'executable': row['executable']}, ('PRESERVED_BYTES_OR_MODE_MISMATCH', path)
        original = EVIDENCE + row['path']
        assert original in checkpoint, ('PRESERVED_NOT_IN_CHECKPOINT', original)
        mode, oid = checkpoint[original]
        content = (ROOT / path).read_bytes()
        actual_oid = hashlib.sha1(b'blob ' + str(len(content)).encode() + b'\0' + content).hexdigest()
        assert actual_oid == oid and row['executable'] == (mode == '100755'), ('PRESERVED_CHECKPOINT_BYTES_OR_MODE_MISMATCH', original)
    return expected


def allowed_new_sources():
    data = json.loads((ROOT / ALLOWLIST_PATH).read_text())
    assert data['baseline'] == BASE and data['slice'] == 'M1-B1'
    paths = data['new_source_files']
    assert paths and len(paths) == len(set(paths)), 'FINAL_EXPLICIT_SOURCE_ALLOWLIST_REQUIRED'
    roots = (APP + 'browser/bookmarks/', 'app/src/test/kotlin/com/easysavevideo/app/browser/bookmarks/')
    for name in paths:
        assert isinstance(name, str) and name.startswith(roots) and name.endswith('.kt'), ('UNSCOPED_SOURCE_PERMISSION', name)
        assert '..' not in Path(name).parts and not Path(name).is_absolute()
    return set(paths)


def regular(path):
    file = ROOT / path
    assert not Path(path).is_absolute() and '..' not in Path(path).parts, ('UNSAFE_PATH', path)
    assert file.resolve().is_relative_to(ROOT.resolve()), ('INPUT_ESCAPES_WORKTREE', path)
    assert all(not parent.is_symlink() for parent in file.parents if parent != ROOT and ROOT in parent.parents), ('SYMLINKED_INPUT_PARENT', path)
    assert stat.S_ISREG(file.lstat().st_mode), ('NON_REGULAR_INPUT', path)
    return file


def fingerprint(path):
    file = regular(path)
    content = file.read_bytes()
    return {'path': path, 'size': len(content), 'sha256': hashlib.sha256(content).hexdigest(),
            'executable': bool(file.stat().st_mode & 0o111)}


def worktree_paths():
    names = git('ls-files', '-z', '--cached', '--others', '--exclude-standard').split(b'\0')
    return {name.decode() for name in names if name}


def verify_scope(require_all_sources=True):
    prior = baseline()
    preserved = preserved_membership()
    sources = allowed_new_sources()
    assert EDITS <= prior.keys()
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
    if require_all_sources:
        assert sources <= paths, ('ADMITTED_SOURCE_MISSING', sorted(sources - paths))
    return {'baseline': BASE, 'baseline_files': len(prior), 'changed_files': sorted(changed),
            'allowed_production_edits': sorted(EDITS), 'new_source_files': sorted(sources),
            'unchanged_baseline_files': len(prior) - len(changed),
            'preserved_checkpoint': PRESERVED_CHECKPOINT, 'preserved_output_files': len(preserved) - 1}


def candidate_membership():
    # Every baseline input, including all historical tests, scripts, fixtures,
    # notices and recorded evidence, is frozen. Current generated output is not
    # an input; current executable review scripts/fixtures are always included.
    paths = set(baseline()) | allowed_new_sources() | {SCRIPT, ALLOWLIST_PATH} | preserved_membership()
    for file in (ROOT / DOCS).rglob('*'):
        if file.is_file() and file.suffix in ('.py', '.sh', '.kt', '.java', '.js', '.mjs', '.sql', '.bin'):
            paths.add(str(file.relative_to(ROOT)))
    assert MANIFEST_PATH not in paths, 'SELF_REFERENTIAL_MANIFEST'
    return paths


def checked_output():
    out = Path(os.environ.get('ESV_EVIDENCE_DIR', ROOT / EVIDENCE)).resolve()
    docs = (ROOT / 'docs').resolve()
    current = (ROOT / EVIDENCE).resolve()
    assert out != ROOT and out != Path('/'), 'UNSAFE_EVIDENCE_DESTINATION'
    assert not out.is_relative_to(docs) or out.is_relative_to(current), 'HISTORICAL_EVIDENCE_READ_ONLY'
    out.mkdir(parents=True, exist_ok=True)
    return out
