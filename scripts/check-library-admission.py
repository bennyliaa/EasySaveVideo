#!/usr/bin/env python3
"""Static ordinary-library scope/boundary guards only. JVM/SQLite tests and Android gates are separate."""
from pathlib import Path
import json, os, re, subprocess, xml.etree.ElementTree as ET
root = Path(__file__).resolve().parents[1]
out = Path(os.environ.get('ESV_EVIDENCE_DIR', root / 'docs/m5a/evidence'))
out.mkdir(parents=True, exist_ok=True)
ns = '{http://schemas.android.com/apk/res/android}'
manifest = ET.parse(root / 'app/src/main/AndroidManifest.xml').getroot()
permissions = {node.attrib[ns + 'name'] for node in manifest.findall('uses-permission')}
assert not permissions.intersection({'android.permission.MANAGE_EXTERNAL_STORAGE',
    'android.permission.READ_EXTERNAL_STORAGE', 'android.permission.WRITE_EXTERNAL_STORAGE',
    'android.permission.READ_MEDIA_VIDEO', 'android.permission.READ_MEDIA_IMAGES'})
assert manifest.find('application').attrib[ns + 'allowBackup'] == 'false'
providers = manifest.find('application').findall('provider')
assert len(providers) == 1, 'Only the scoped ordinary share provider is admitted'
provider = providers[0]
assert provider.attrib[ns + 'name'] == '.library.share.LibraryShareProvider'
assert provider.attrib[ns + 'authorities'] == '${applicationId}.library.share'
assert provider.attrib[ns + 'exported'] == 'false' and provider.attrib[ns + 'grantUriPermissions'] == 'true'
assert not list(provider), 'No broad path or grant pattern is admitted'
paths = list((root / 'library/src/main').rglob('*.kt')) + list((root / 'app/src/main/kotlin/com/easysavevideo/app/library').rglob('*.kt'))
sources = '\n'.join(p.read_text() for p in paths)
for forbidden in ('Log.d(', 'Log.i(', 'Log.e(', 'println(', 'printStackTrace(', 'java.net.', 'HttpURLConnection',
                  'javax.crypto', 'KeyStore', 'FileProvider', 'file://'):
    assert forbidden not in sources, forbidden
for path in (root / 'library/src/main').rglob('*.kt'):
    for line in path.read_text().splitlines():
        if line.startswith('import '):
            assert not any(x in line for x in ('android.', 'androidx.', 'com.android.billing', 'skinkit')), line
ui = (root / 'app/src/main/kotlin/com/easysavevideo/app/library/LibraryScreen.kt').read_text()
assert 'LocalEsvVisuals' in ui and 'EsvBackdrop' in ui and 'EsvButton' in ui
assert 'MaterialTheme' not in sources and 'EntitlementManager' not in sources and 'FeatureGate' not in sources
adapter = (root / 'app/src/main/kotlin/com/easysavevideo/app/save/storage/AndroidSaveStorage.kt').read_text()
assert adapter.count('LibrarySchema.publish(') == 1
assert 'openPublishedMedia(ref: MediaReadRef)' in adapter
assert 'libraryStorage.resolveReceipt(receipt)' in adapter
assert 'state?.isTerminal == true' in adapter
owned_streams = (root / 'app/src/main/kotlin/com/easysavevideo/app/storage/OwnedFileStreams.kt').read_text()
assert 'OwnedFileInputStream(authority.file, fileLock' in adapter and 'authority.lease.close()' in adapter
assert 'OwnedFileOutputStream(file, append, fileLock' in adapter
assert 'if (confirmed && !released)' in owned_streams and 'synchronized(ownershipLock)' in owned_streams
assert owned_streams.count('{ !fd.valid() }') == 2
assert owned_streams.index('closeChannel()') < owned_streams.index('closeStream()')
assert 'if (!synchronized(ownershipLock) { released }) onUnconfirmed()' in owned_streams
schema = (root / 'app/src/main/kotlin/com/easysavevideo/app/library/storage/LibrarySchema.kt').read_text()
assert "m.visible=1 AND t.state='SUCCEEDED'" in schema
proof = (root / 'app/src/main/kotlin/com/easysavevideo/app/library/storage/LibraryPublicationEvidence.kt').read_text()
assert 'record.task.state == TaskState.SUCCEEDED' in proof
assert 'legacy' in proof and '.isValidFor' in proof
# Preserve network/HLS and basic M4 control contracts; the reviewed M6-B capture/codec extension is explicit.
base = '9b25083134221bb4a86d71e76601cc65b7d10937'
protected = ['save/src/main', 'hls/src/main',
    'recording/src/main/kotlin/com/easysavevideo/recording/RecordingTimeline.kt',
    'recording/src/main/kotlin/com/easysavevideo/recording/RecordingController.kt',
    'app/src/main/kotlin/com/easysavevideo/app/recording/AndroidRecordSavePort.kt',
    'app/src/main/kotlin/com/easysavevideo/app/save/AndroidLocalMp4Verifier.kt',
    'app/src/main/kotlin/com/easysavevideo/app/save/RecordingVideoTailState.kt',
    'app/src/main/kotlin/com/easysavevideo/app/save/StreamVideoTailState.kt',
    ]
changed = subprocess.check_output(['git', 'diff', '--name-only', base, '--', *protected], cwd=root, text=True)
assert not changed, changed
result = {'kind': 'STATIC_SOURCE_GUARDS_ONLY', 'core_android_store_boundary': 'PASS',
    'no_broad_storage_permission_and_scoped_readonly_provider': 'PASS', 'no_library_network_crypto_or_sensitive_logs': 'PASS',
    'skinkit_and_free_library_source': 'PASS', 'published_refs_and_release_fences_source': 'PASS',
    'legacy_success_only_migration_and_strict_new_publish_source': 'PASS',
    'direct_hls_verification_and_m4_control_timeline_unchanged': 'PASS',
    'runtime_admission': 'Separately checked by exact Gradle admission and check-stream-admission.py; Tink migration is explicit',
    'android_native_library_acceptance': 'NOT_RUN', 'public_export_share_thumbnails': 'SOURCE_PRESENT_DEVICE_NOT_RUN', 'vault': 'NOT_IMPLEMENTED'}
(out / 'library-admission.json').write_text(json.dumps(result, indent=2) + '\n')
print(json.dumps(result, indent=2))
