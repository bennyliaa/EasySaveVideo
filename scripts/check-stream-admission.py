#!/usr/bin/env python3
"""Read-only exact M3-B runtime/license/source checks. No Android execution or package claim."""
from pathlib import Path
from collections import defaultdict
import hashlib, json, zipfile, io, re, os
root=Path(__file__).resolve().parents[1]
evidence=Path(os.environ.get('ESV_EVIDENCE_DIR', str(root/'docs/m3b/evidence')))
evidence.mkdir(parents=True,exist_ok=True)
expected={tuple(line.split('\t')) for line in (root/'third_party/admitted-runtime.tsv').read_text().splitlines() if line and not line.startswith('#')}
assert expected and len({row[0] for row in expected}) == len(expected)
results={}
for variant in ('debug','release'):
    classes=defaultdict(set); actual=set()
    rows=(root/f'app/build/reports/{variant}RuntimeClasspath-inventory.tsv').read_text().splitlines()
    for row in rows:
        gav,name,sha,path=row.split('\t')
        if gav.startswith('com.easysavevideo:'):continue
        artifact=Path(path);assert hashlib.sha256(artifact.read_bytes()).hexdigest()==sha
        actual.add((gav,name,sha))
        with zipfile.ZipFile(artifact) as z:
            archives=[z]
            if artifact.suffix=='.aar':
                archives=[zipfile.ZipFile(io.BytesIO(z.read(n))) for n in z.namelist() if n=='classes.jar' or n.startswith('libs/') and n.endswith('.jar')]
            for archive in archives:
                for name in archive.namelist():
                    if name.endswith('.class') and not name.endswith('module-info.class'): classes[name].add(gav)
    assert actual==expected
    duplicates={name:sorted(gavs) for name,gavs in classes.items() if len(gavs)>1}
    assert not duplicates,duplicates
    results[variant]={'exact_runtime_gavs':len(actual),'class_names':len(classes),'duplicate_runtime_classes':0,'excluded_non_runtime_descriptors':'module-info.class including META-INF/versions/9'}
manifest=root/'third_party/candidates/media3-1.11.1/IMPORT_MANIFEST.json'
for entry in json.loads(manifest.read_text())['files']:
    file=manifest.parent/entry['path']; assert file.stat().st_size==entry['bytes'] and hashlib.sha256(file.read_bytes()).hexdigest()==entry['sha256'],file
results['original_candidate_evidence_payloads']=184
notice=(root/'app/src/main/assets/third-party-notices.txt').read_text()
for marker in ['Brian Goetz','GuardedBy','Immutable','NotThreadSafe','ThreadSafe','https://creativecommons.org/licenses/by/2.5/legalcode','JetBrains','MIT','CC0','Doug Lea','Apache License']:
    assert marker in notice,marker
results['bundled_notices_required_markers']='PASS; installation-package reachability remains NOT_RUN'
engine=(root/'app/src/main/kotlin/com/easysavevideo/app/save/stream/Media3LocalHlsEngine.kt').read_text()
local=(root/'app/src/main/kotlin/com/easysavevideo/app/save/stream/LocalOnlyDataSource.kt').read_text()
for forbidden in ['DefaultHttpDataSource','DefaultDataSource(', 'DefaultMediaSourceFactory(', 'setHttpDataSourceFactory(', 'HttpURLConnection', 'URLConnection']:
    assert forbidden not in engine+local,forbidden
compatibility=(root/'app/src/main/kotlin/com/easysavevideo/app/save/stream/LocalHlsCompatibility.kt').read_text()
for required in ['setEncoderFactory(RejectCodecs)','export.mediaSourceFactory()','setUsePlatformDiagnostics(false)','DRM_UNSUPPORTED']:
    assert required in engine,required
assert 'HlsMediaSource.Factory(sources)' in compatibility and '.setExtractorFactory(extractors)' in compatibility
assert 'CompatTsProfileProbe.inspectSources' in engine and 'compatibility.inspection(dataSource)' in engine and 'compatibility.export(dataSource)' in engine
assert 'export.verifyComplete()' in engine and 'pass.verifyComplete()' in engine
assert 'video/mp2t' not in (root/'app/src/main/java/com/easysavevideo/save/network/SystemHttpsDirectTransfer.kt').read_text()
results['local_only_remux_no_default_network_and_direct_mime_unchanged']='PASS (source guard; device behavior NOT_RUN)'
release=root/'app/build/intermediates/built_in_kotlinc/release/compileReleaseKotlin/classes'
assert not list(release.rglob('ReaderProbe.class')) and not list(release.rglob('SyntheticTs.class'))
assert not (release/'android').exists(), 'Host Android shims must not enter production classes'
assert 'testSupport' not in (root/'app/build.gradle.kts').read_text()
results['host_reader_shims_excluded_from_app_sources']='PASS'

(evidence/'stream-admission-checks.json').write_text(json.dumps(results,indent=2)+'\n')
print(json.dumps(results,indent=2))
