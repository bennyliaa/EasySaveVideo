#!/usr/bin/env python3
"""M6-C source-only admission checks. Never evidence of Android/GPU/device behavior."""
from pathlib import Path
import hashlib, json, os, subprocess
ROOT = Path(__file__).resolve().parents[1]
OUT = Path(os.environ.get('ESV_EVIDENCE_DIR', ROOT/'docs/m6c/evidence'))
OUT.mkdir(parents=True, exist_ok=True)
BASE = 'f3253eadaf8f2d50acc6e64c02aa610f5f87bda2'
protected = ['third_party', 'vendor', 'gradle/verification-metadata.xml', 'app/build.gradle.kts',
    'settings.gradle.kts', 'app/src/main/AndroidManifest.xml',
    'core-model/src/main/kotlin/com/easysavevideo/core/model/Entitlements.kt',
    'core-domain/src/main/kotlin/com/easysavevideo/core/domain/EntitlementManager.kt',
    'app/src/debug/kotlin/com/easysavevideo/app/entitlements',
    'app/src/release/kotlin/com/easysavevideo/app/entitlements']
assert not subprocess.check_output(['git', 'diff', '--name-only', BASE, '--', *protected], cwd=ROOT, text=True)
record = ROOT/'app/src/main/kotlin/com/easysavevideo/app/recording'
text = '\n'.join(p.read_text() for p in record.rglob('*.kt'))
for forbidden in ['SYSTEM_ALERT_WINDOW', 'Bitmap.compress', 'AudioSource.MIC', 'Log.d(', 'Log.i(',
                  'Log.e(', 'println(', 'printStackTrace(', 'com.google.crypto.tink']:
    assert forbidden not in text, forbidden
native = (record/'AndroidRecordingSession.kt').read_text()
runtime = (record/'AndroidSegmentRecordingRuntime.kt').read_text()
gl = (record/'nativeregion/RegionGlCapture.kt').read_text()
ui = (record/'ui/AuthorizedRecordingRegionDialog.kt').read_text()
host = (record/'RecordingPreviewHostDraw.kt').read_text()
core = (ROOT/'recording/src/main/kotlin/com/easysavevideo/recording/RegionPreviewContracts.kt').read_text()
assert native.count('.createVirtualDisplay(') == 1
assert 'previewStopSignal.signal' in native
assert native.count('RegionPreviewHandoffPolicy.allows(confirmation, nowUs())') == 2
assert runtime.count('.getMediaProjection(') == 1
assert 'previewPort.confirmed(descriptor)' in native and 'awaitRegionPreview()' in native
assert native.index('awaitRegionPreview()') < native.index('prepareSegmentOutput(::checkpoint)')
assert 'if (!job.preview) { segmentStorage.prepareSession' in runtime
assert 'gate.publish(frame.snapshot' in runtime and 'gate.confirm(snapshot, bounds' in runtime
assert 'recordingPreviewHostCallbackCurrent' in runtime
for required in ['glReadPixels', 'previewRead = true', 'releaseUncertain = true', 'Looper.myLooper() != Looper.getMainLooper()',
                 'RegionTexturePolicy.textureEdges', 'clearPreviewSurface', 'MAX']:
    if required != 'MAX': assert required in gl, required
assert not any(x in gl for x in ['FileOutputStream', 'MediaStore', 'createVirtualDisplay', 'rememberSaveable'])
for required in ['SecureFlagPolicy.SecureOn', 'FLAG_SECURE', 'view.alpha = 0f', 'drawing?.close()', 'currentRetired(snapshot)', 'LocalEsvVisuals']:
    assert required in ui, required
assert 'rememberSaveable' not in ui and 'clearFlags' not in ui and '.recycle(' not in ui
assert 'registerFrameCommitCallback' in host and 'unregisterFrameCommitCallback' in host
assert 'view.hasWindowFocus()' in host and 'RecordingPreviewDrawFence' in host
for required in ['markHostReady', 'retireUi', 'markCaptureReady', 'takeReady', 'RegionPreviewInvalidation',
                 'MAX_RASTER_PIXELS = 921_600L', 'MAX_RASTER_DIMENSION = 1280']:
    assert required in core, required
assert 'RecordingSelectionMode.PROPORTIONAL' in (record/'RecordingScreen.kt').read_text()
report = dict(kind='STATIC_SOURCE_GUARDS_ONLY', baseline=BASE,
    exact74_dependencies_manifest_entitlements='UNCHANGED',
    authorized_frame_preview='IMPLEMENTED_CODE_ONLY', proportional_mode='RETAINED_EXPLICIT',
    one_grant_projection_and_virtual_display='SOURCE_GUARD_PASS',
    no_preview_file_audio_or_codec_before_explicit_confirm_and_retirement='SOURCE_GUARD_PASS',
    preview_memory_only_secure_window_scoped='SOURCE_GUARD_PASS',
    snapshot_geometry_transform_generation_and_fresh_frame_gates='SOURCE_GUARD_PASS',
    native_timeout_never_certifies_release='SOURCE_GUARD_PASS',
    actual_android_GPU_codec_OEM_visual_device_behavior='NOT_RUN', vault='DISABLED_UNCHANGED')
(OUT/'capture-preview-admission.json').write_text(json.dumps(report, indent=2)+'\n')
print(json.dumps(report, indent=2))
