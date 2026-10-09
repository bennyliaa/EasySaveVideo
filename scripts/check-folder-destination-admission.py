#!/usr/bin/env python3
"""M5-A4b-only scope gate. Every other 046209b byte and executable mode is protected."""
from pathlib import Path
import json
import os
import subprocess

ROOT = Path(__file__).resolve().parents[1]
BASE = '046209b39d932f08910a8b79f33d4d02d6183ccd'
OUT = Path(os.environ.get('ESV_EVIDENCE_DIR', ROOT / 'docs/m5a4b/evidence'))
OUT.mkdir(parents=True, exist_ok=True)
# Exact integration files only; baseline tests have only the explicit enum assertion adaptation below.
ALLOWED_BASELINE_EDITS = {
    'app/src/main/kotlin/com/easysavevideo/app/MainActivity.kt',
    'app/src/main/kotlin/com/easysavevideo/app/library/destination/AndroidOrdinaryDestinationRuntime.kt',
    'app/src/main/kotlin/com/easysavevideo/app/library/destination/OrdinaryDestinationSelection.kt',
    'app/src/main/kotlin/com/easysavevideo/app/library/destination/OrdinaryDestinationUi.kt',
    'app/src/main/kotlin/com/easysavevideo/app/library/destination/AndroidOrdinaryDestinationJournal.kt',
    'app/src/main/kotlin/com/easysavevideo/app/save/storage/AndroidSaveStorage.kt',
    'app/src/main/kotlin/com/easysavevideo/app/save/AndroidSaveRuntime.kt',
    'app/src/main/kotlin/com/easysavevideo/app/library/export/AndroidExportPort.kt',
    'app/src/main/kotlin/com/easysavevideo/app/library/export/AndroidExportRuntime.kt',
    'library/src/main/kotlin/com/easysavevideo/library/destination/OrdinaryDestinationContracts.kt',
}
# All surrounding bytes, test names, assertions and cases remain baseline-exact.
EXACT_BASELINE_TRANSFORMS = {
    'library/src/test/kotlin/com/easysavevideo/library/destination/OrdinaryDestinationAdmissionTest.kt': (
        ('assertEquals(listOf("APP_LIBRARY", "ALBUM_COPY"), OrdinaryDestinationChoice.entries.map { it.name })',
         'assertEquals(listOf("APP_LIBRARY", "ALBUM_COPY", "FOLDER_COPY"), OrdinaryDestinationChoice.entries.map { it.name })'),
        ('assertEquals(3, OrdinaryDestinationPreference.entries.size)',
         'assertEquals(4, OrdinaryDestinationPreference.entries.size)'),
    ),
}
NEW_ROOTS = (
    'app/src/main/kotlin/com/easysavevideo/app/library/destination/',
    'app/src/test/kotlin/com/easysavevideo/app/library/destination/',
    'app/src/test/resources/destination/',
    'library/src/main/kotlin/com/easysavevideo/library/destination/',
    'library/src/test/kotlin/com/easysavevideo/library/destination/',
    'docs/m5a4b/',
)
NEW_SCRIPTS = {
    'scripts/check-folder-destination-admission.py',
    'scripts/verify-folder-destination-sqlite.py',
    'scripts/verify-folder-destination-workspace.sh',
    'scripts/verify-a4b-library-sqlite.py',
    'scripts/verify-a4b-export-sqlite.py',
    'scripts/verify-a4b-editor-sqlite.py',
}

def git(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT)

baseline = {}
for line in git('ls-tree', '-r', BASE).decode().splitlines():
    meta, path = line.split('\t', 1)
    mode, kind, oid = meta.split()
    assert kind == 'blob' and mode in ('100644', '100755'), ('UNSUPPORTED_BASELINE_ENTRY', path)
    baseline[path] = (mode, oid)
assert (ALLOWED_BASELINE_EDITS | EXACT_BASELINE_TRANSFORMS.keys()) <= baseline.keys(), 'Allowlist must name existing baseline files'
actual_edits = []
for path, (mode, oid) in baseline.items():
    file = ROOT / path
    assert file.is_file() and not file.is_symlink(), ('MISSING_OR_NONREGULAR_BASELINE', path)
    assert bool(file.stat().st_mode & 0o111) == (mode == '100755'), ('MODE_CHANGED', path)
    current = git('hash-object', str(file)).decode().strip()
    if path in EXACT_BASELINE_TRANSFORMS:
        expected = git('show', BASE + ':' + path).decode()
        for old, new in EXACT_BASELINE_TRANSFORMS[path]:
            assert expected.count(old) == 1, ('TRANSFORM_BASELINE_MISMATCH', path)
            expected = expected.replace(old, new, 1)
        assert file.read_bytes() == expected.encode(), ('NONEXACT_TEST_ADAPTATION', path)
    if current != oid:
        assert path in ALLOWED_BASELINE_EDITS or path in EXACT_BASELINE_TRANSFORMS, ('OUT_OF_SCOPE_BASELINE_EDIT', path)
        actual_edits.append(path)
for path in set(git('ls-files', '--cached', '--others', '--exclude-standard').decode().splitlines()):
    if path in baseline:
        continue
    assert path in NEW_SCRIPTS or path.startswith(NEW_ROOTS), ('UNEXPECTED_NEW_PATH', path)
    assert (ROOT / path).is_file() and not (ROOT / path).is_symlink(), ('NONREGULAR_NEW_PATH', path)

app = ROOT / 'app/src/main/kotlin/com/easysavevideo/app'
owner = (app / 'library/destination/AndroidOrdinaryDestinationRuntime.kt').read_text()
router = (ROOT / 'library/src/main/kotlin/com/easysavevideo/library/destination/OrdinaryDestinationRouter.kt').read_text()
contracts = (ROOT / 'library/src/main/kotlin/com/easysavevideo/library/destination/OrdinaryDestinationContracts.kt').read_text()
helper = (app / 'save/storage/AndroidSaveStorage.kt').read_text()
assert 'UUID.randomUUID().toString()' in owner and 'storage.destinations.admit(' in owner
assert owner.index('storage.destinations.admit(') < owner.index('selection.accepted(admitted)')
assert 'preference' not in router.split('class OrdinaryDestinationRouter', 1)[1]
assert 'journal.get(admission.creationId) != admission' in router and 'found.phase != ExportPhase.PREPARED' in router
assert 'firstFailure?.let { throw it }' in router
assert 'FOLDER_COPY' in contracts
assert 'null, 7,' in helper and 'oldVersion !in 1..6 || newVersion != 7' in helper
assert 'if (oldVersion < 6) OrdinaryDestinationSchema.create(db)' in helper
assert helper.count('FolderDestinationSchema.migrate(db)') == 2
for name in ('MainActivity.kt', 'library/destination/OrdinaryDestinationUi.kt'):
    assert 'copyForCreation' not in (app / name).read_text(), name
assert 'OrdinaryDestinationChoice.ALBUM_COPY' in (app / 'library/destination/OrdinaryDestinationUi.kt').read_text()
assert 'setPreference(value)' in owner and 'fun updatePreference' in owner
files = list((app / 'library/destination').glob('*.kt')) + list((ROOT / 'library/src/main/kotlin/com/easysavevideo/library/destination').glob('*.kt'))
for file in files:
    source = file.read_text()
    for forbidden in ('Log.', 'println(', 'printStackTrace(', 'deleteOriginal', 'java.net.'):
        assert forbidden not in source, (file.name, forbidden)
for file in (ROOT / 'library/src/main/kotlin/com/easysavevideo/library/destination').glob('*.kt'):
    for line in file.read_text().splitlines():
        if line.startswith('import '):
            assert not any(x in line for x in ('android.', 'androidx.', 'skinkit.', 'com.android.billing')), (file.name, line)

report = {
    'scope': 'M5_A4B_ORDINARY_REMEMBERED_SAF_FOLDER_COPIES_ONLY',
    'baseline': BASE,
    'baseline_files': len(baseline),
    'byte_exact_baseline_files': len(baseline) - len(ALLOWED_BASELINE_EDITS) - len(EXACT_BASELINE_TRANSFORMS),
    'exact_transformation_baseline_files': len(EXACT_BASELINE_TRANSFORMS),
    'permitted_baseline_edits': sorted(ALLOWED_BASELINE_EDITS),
    'exact_enum_expectation_adaptation': sorted(EXACT_BASELINE_TRANSFORMS),
    'actual_baseline_edits': sorted(actual_edits),
    'unchanged': 'All other baseline files and all baseline executable modes, including historical test bodies apart from the two exact enum expectations, every historical script/evidence file, exact74 dependencies, manifests, SkinKit, rule interpreter, distribution/payment, Save codec/core/native capture, Vault and M7-D',
    'ordinary_destination_source_guards': 'PASS',
    'android_device_saf_media_store_acceptance': 'NOT_RUN',
    'vault_export': 'NOT_IMPLEMENTED',
}
(OUT / 'folder-destination-admission.json').write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps(report, indent=2))
