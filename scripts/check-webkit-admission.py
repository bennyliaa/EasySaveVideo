#!/usr/bin/env python3
"""Offline exact WebKit admission check. Runtime resolution remains a Gradle gate."""
from pathlib import Path
import hashlib
import json
import sys
import zipfile
import xml.etree.ElementTree as ET
root = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path(__file__).resolve().parents[1]
coordinate = 'androidx.webkit:webkit:1.17.1'
expected = {
    'webkit-1.17.1.aar': '29dc7a9a32614828c97cf85951eb43c9e455157ab411e5e77d2ca4e72da28a36',
    'webkit-1.17.1.pom': 'b5294d03f04cf806e975afe48a67e70180e6a1e74086e7e62a1fe64c4a636bae',
    'webkit-1.17.1.module': 'fee069430a913a43fa694ad466682f132d21251b62cd03655142ddafe15b52eb',
}
base = root / 'vendor/webkit-maven/androidx/webkit/webkit/1.17.1'
for name, digest in expected.items():
    assert hashlib.sha256((base / name).read_bytes()).hexdigest() == digest, name
metadata = json.loads((base / 'webkit-1.17.1.module').read_text())
for variant in metadata['variants']:
    for artifact in variant.get('files', []):
        if artifact['name'] == 'webkit-1.17.1.aar':
            data = (base / artifact['name']).read_bytes()
            assert len(data) == artifact['size']
            assert hashlib.sha256(data).hexdigest() == artifact['sha256']
ns = {'v':'https://schema.gradle.org/dependency-verification'}
verification = ET.parse(root / 'gradle/verification-metadata.xml')
assert verification.findtext('.//v:verify-metadata', namespaces=ns) == 'true'
component = verification.find('.//v:component[@group="androidx.webkit"][@name="webkit"][@version="1.17.1"]', ns)
assert component is not None
for name, digest in expected.items():
    checksum = component.find(f'v:artifact[@name="{name}"]/v:sha256', ns)
    assert checksum is not None and checksum.attrib['value'] == digest
rows = lambda p: {line for line in p.read_text().splitlines() if line and not line.startswith('#')}
baseline = rows(root / 'third_party/candidates/webkit-1.17.1/baseline-runtime-90.tsv')
assert len(baseline) == 90
addition = coordinate + '\twebkit-1.17.1.aar\t' + expected['webkit-1.17.1.aar']
assert rows(root / 'third_party/admitted-runtime.tsv') == baseline | {addition}
notice = (root / 'app/src/main/assets/third-party-notices.txt').read_text()
assert len(notice.encode('utf-16-le')) // 2 <= 1024 * 1024
candidate = root / 'third_party/candidates/webkit-1.17.1'
assert (candidate / 'WEBKIT-LICENSE.txt').read_text() in notice
assert (candidate / 'CHROMIUM-LICENSE.txt').read_text() in notice
assert (candidate / 'chromium-boundary-attributions.txt').read_text() in notice
with zipfile.ZipFile(base / 'webkit-1.17.1.aar') as archive:
    manifest = ET.fromstring(archive.read('AndroidManifest.xml'))
    assert not manifest.findall('uses-permission')
    assert not manifest.findall('application')
    aar_metadata = archive.read('META-INF/com/android/build/gradle/aar-metadata.properties').decode()
    assert 'minCompileSdk=33' in aar_metadata and 'minAndroidGradlePluginVersion=7.2.0' in aar_metadata
build = (root / 'app/build.gradle.kts').read_text()
assert build.count('implementation("androidx.webkit:webkit:1.17.1")') == 1
assert 'check(actual == admitted)' in build
print('WebKit 1.17.1 exact bytes, 91-artifact admission, verification and both license families verified. Runtime/build/privacy/device acceptance remains separate.')
