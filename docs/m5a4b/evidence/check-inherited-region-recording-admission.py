#!/usr/bin/env python3
"""M6-B/M6-C source invariant guard. Does not execute projection, EGL, codecs, or a device."""
# M10-B: app/build.gradle.kts is independently protected by exact baseline-plus-channel-block comparison.
# M10-A: inherited media checks; only model/debug/release entitlement edits use the new admission gate.
from pathlib import Path
import json, os, subprocess, xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[3]
OUT=Path(os.environ.get('ESV_EVIDENCE_DIR',ROOT/'docs/m5a4a/evidence'));OUT.mkdir(parents=True,exist_ok=True)
BASE='9b25083134221bb4a86d71e76601cc65b7d10937'
protected=['third_party','vendor','gradle/verification-metadata.xml','settings.gradle.kts',
 'app/src/main/AndroidManifest.xml',
 'core-domain/src/main/kotlin/com/easysavevideo/core/domain/EntitlementManager.kt',
 ]
assert not subprocess.check_output(['git','diff','--name-only',BASE,'--',*protected],cwd=ROOT,text=True)
record=ROOT/'app/src/main/kotlin/com/easysavevideo/app/recording'
text='\n'.join(p.read_text() for p in record.rglob('*.kt'))
for forbidden in ['MediaRecorder.AudioSource','AudioSource.MIC','SYSTEM_ALERT_WINDOW','HttpURLConnection','OkHttp','FFmpeg',
 'Log.d(', 'Log.i(', 'Log.e(', 'println(', 'printStackTrace(', 'com.google.crypto.tink']:
    assert forbidden not in text,forbidden
native=(record/'AndroidRecordingSession.kt').read_text()
assert native.count('.createVirtualDisplay(')==1
assert 'segmentOutputs' in native and 'releaseOutputResources' in native and 'reportClosedSegment' in native
assert 'nativeReleaseUnconfirmed' in native
consent=(record/'RecordingConsentController.kt').read_text()
assert 'MediaProjectionConfig.createConfigForDefaultDisplay()' in consent
assert 'EphemeralRecordingResults : ActivityResultRegistry()' in consent and 'requiresDefaultDisplay' in consent
assert 'PendingRecordingConsent' in consent and 'defaultDisplayOnly' in consent
assert 'putExtra' not in (record/'ProjectionGrantHandoff.kt').read_text()
advanced=(record/'AndroidSegmentRecordingRuntime.kt').read_text()
assert advanced.count('.getMediaProjection(')==1 and 'ProjectionGrantHandoff.take(' in advanced
assert 'OrdinaryMediaNativeResources.claim' in advanced and 'OrdinaryMediaNativeResources.unconfirmed()' in advanced
assert 'RecordingProcessingWatchdog' in advanced and 'job.fence.discard' in advanced and 'processingWatchdog.stopping(job)' in advanced
assert 'SaveRequest(' not in advanced and 'TransferResult(' not in advanced and 'MediaCommitReceipt(' not in advanced
gl=(record/'nativeregion/RegionGlCapture.kt').read_text()
for required in ['SurfaceTexture(', 'glDrawArrays(', 'eglSwapBuffers(', 'eglPresentationTimeANDROID(',
 'RegionTexturePolicy.textureEdges', 'isUnscaledFullImage', 'eglQuerySurface']:
    assert required in gl,required
assert all(x not in gl for x in ['FileOutputStream','Bitmap.compress','MediaStore','createVirtualDisplay'])
files=(record/'segments/RecordedSegmentFiles.kt').read_text()
assert 'expectedIdentity' in files and 'expectedBytes' in files and 'SHA-256' in files
assert 'listFiles(' not in files and '.walk(' not in files
bridge=(record/'segments/RecordedSegmentEditorBridge.kt').read_text()
assert 'rawVerifier.verify' in bridge and 'resultVerifier.verify' in bridge and 'verifiedSources' in bridge
assert 'SaveRequest(' not in bridge and 'MediaCommitReceipt(' not in bridge
ui=(record/'RecordingScreen.kt').read_text()+(record/'ui/RecordingRegionDialog.kt').read_text()
assert all(x in ui for x in ['LocalEsvVisuals','EsvButton','示意','系统'])
assert 'not implemented' not in ui.lower()
report={'kind':'M5A4A_CURRENT_MEDIA_GUARDS_DISTRIBUTION_BUILD_EDITS_REVIEWED_SEPARATELY','exact74_inputs_notices_platform_manifest_unchanged':'PASS',
 'one_projection_one_virtual_display_per_session_source':'PASS','ephemeral_per_session_consent_source':'PASS',
 'pre_encoder_GL_crop_not_full_screen_disk_fallback_source':'PASS','owned_private_typed_segment_bridge_source':'PASS',
 'raw_and_final_independent_verification_source':'PASS','native_release_fences_and_bounded_waiting_notices_source':'PASS',
 'selection':'DISCLOSED_RATIO_SCHEMATIC_ON_LATER_AUTHORIZED_IMAGE','actual_content_visual_selection':'M6_C_AUTHORIZED_MEMORY_FRAME_CODE_ONLY',
 'device_GPU_codec_OEM_and_real_capture':'NOT_RUN','vault':'DISABLED_UNCHANGED'}
(OUT/'region-recording-admission.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2))
