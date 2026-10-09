#!/usr/bin/env python3
"""Source invariants only. Does not establish Android recording or permission acceptance."""
from pathlib import Path
import json, os, re, xml.etree.ElementTree as ET
root=Path(__file__).resolve().parents[1]
out=Path(os.environ.get('ESV_EVIDENCE_DIR',root/'docs/m4/evidence'));out.mkdir(parents=True,exist_ok=True)
ns='{http://schemas.android.com/apk/res/android}'
manifest=ET.parse(root/'app/src/main/AndroidManifest.xml').getroot()
permissions={n.attrib[ns+'name'] for n in manifest.findall('uses-permission')}
assert {'android.permission.FOREGROUND_SERVICE','android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION','android.permission.RECORD_AUDIO'}<=permissions
assert not {'android.permission.SYSTEM_ALERT_WINDOW','android.permission.FOREGROUND_SERVICE_MICROPHONE','android.permission.RECEIVE_BOOT_COMPLETED'} & permissions
service=next(s for s in manifest.find('application').findall('service') if s.attrib[ns+'name']=='.recording.RecordingForegroundService')
assert service.attrib[ns+'exported']=='false' and service.attrib[ns+'foregroundServiceType']=='mediaProjection'
path=root/'app/src/main/kotlin/com/easysavevideo/app/recording'
sources='\n'.join(p.read_text() for p in path.glob('*.kt'))
for forbidden in ['MediaRecorder.AudioSource', 'setAudioSource(', 'AudioSource.MIC', 'SYSTEM_ALERT_WINDOW', 'BootReceiver', 'WorkManager', 'HttpURLConnection', 'Log.d(', 'Log.i(', 'Log.e(', 'println(']:
    assert forbidden not in sources,forbidden
session=(path/'AndroidRecordingSession.kt').read_text()
assert session.count('.createVirtualDisplay(')==1
assert 'setAudioPlaybackCaptureConfig' in session
assert 'registerCallback(projectionCallback' in session
assert 'retainNativeWriter' in session and 'muxer?.release()' in session
port=(path/'AndroidRecordSavePort.kt').read_text()
assert port.count('.getMediaProjection(')==1 and 'ProjectionGrantHandoff.take(requestId)' in port
assert 'resultFence.abandon' in port and 'lease.rejectQueued()' in port
assert 'lease.abandon()' in (path/'RecordingResultFence.kt').read_text()
assert 'control { controller.' in port and 'RecordingControlOwner(controller)' in port
consent=(path/'RecordingConsentController.kt').read_text()
assert 'EphemeralRecordingResults : ActivityResultRegistry()' in consent
assert 'activity.activityResultRegistry' not in consent
assert '.createScreenCaptureIntent()' in consent
fgs=(path/'RecordingForegroundService.kt').read_text()
assert 'START_NOT_STICKY' in fgs and 'stop.identifier = id' in fgs
assert 'getMediaProjection' not in fgs.replace('// FGS promotion precedes the only getMediaProjection call in the recording port.','')
assert 'putExtra' not in (path/'ProjectionGrantHandoff.kt').read_text()
models=(root/'save/src/main/kotlin/com/easysavevideo/save/SaveModels.kt').read_text()
assert 'sealed interface SaveInput' in models and 'data class Capture' in models
assert 'recordingExpectation' in models
preview=(root/'app/src/main/kotlin/com/easysavevideo/app/save/VerifiedMediaPreview.kt').read_text()
assert 'ALLOW_CAPTURE_BY_NONE' in preview and 'input.fd' in preview
assert 'setDataSource(input.fd' in preview and 'Uri.parse' not in preview
for module in ['recording','save']:
    for file in (root/module/'src/main').rglob('*.kt'):
        for line in file.read_text().splitlines():
            if line.startswith('import '): assert not any(x in line for x in ['android.','androidx.','com.android.billing','skinkit']),line
result={'manifest_original_capture_scope':'PASS','single_projection_and_virtual_display_source':'PASS',
        'explicit_playback_only_no_mic_or_automatic_trigger':'PASS','memory_only_ephemeral_consent_source':'PASS',
        'native_ownership_and_published_preview_source':'PASS','core_platform_boundary':'PASS',
        'kind':'STATIC_SOURCE_GUARDS_ONLY','android_device_recording':'NOT_RUN'}
(out/'recording-admission.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result,indent=2))
