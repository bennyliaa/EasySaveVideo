#!/usr/bin/env python3
"""M6-A/M6-B static admission guards; separate from JVM/SQL tests and unrun native acceptance."""
# M10-B: app/build.gradle.kts is independently protected by exact baseline-plus-channel-block comparison.
# M10-A: inherited media checks; only model/debug/release entitlement edits use the new admission gate.
from pathlib import Path
import hashlib, json, os, subprocess
root = Path(__file__).resolve().parents[3]
from scope_support import BASE as CURRENT_BASE, verify_scope
verify_scope()
out = Path(os.environ.get('ESV_EVIDENCE_DIR', root/'docs/searxng-discovery/evidence/runs/final')); out.mkdir(parents=True, exist_ok=True)
base = '9b25083134221bb4a86d71e76601cc65b7d10937'
protected = ['save/src/main','hls/src/main','app/src/main/AndroidManifest.xml',
 'settings.gradle.kts','gradle/verification-metadata.xml','third_party/admitted-runtime.tsv',
 'recording/src/main/kotlin/com/easysavevideo/recording/RecordingController.kt',
 'recording/src/main/kotlin/com/easysavevideo/recording/RecordingTimeline.kt',
 'recording/src/main/kotlin/com/easysavevideo/recording/RecordingBudgets.kt',
 'app/src/main/kotlin/com/easysavevideo/app/recording/AndroidRecordSavePort.kt',
 'app/src/main/kotlin/com/easysavevideo/app/recording/RecordingNativeLease.kt',
 'app/src/main/kotlin/com/easysavevideo/app/recording/RecordingControlOwner.kt',
 'app/src/main/kotlin/com/easysavevideo/app/recording/RecordingResultFence.kt',
 'app/src/main/kotlin/com/easysavevideo/app/recording/ProjectionGrantHandoff.kt',
 'app/src/main/kotlin/com/easysavevideo/app/save/AndroidLocalMp4Verifier.kt',
 'core-domain/src/main/kotlin/com/easysavevideo/core/domain/EntitlementManager.kt',
 ]
changed=subprocess.check_output(['git','diff','--name-only',base,CURRENT_BASE,'--',*protected],cwd=root,text=True)
assert not changed, changed
files = list((root/'app/src/main/kotlin/com/easysavevideo/app/editor').rglob('*.kt')) + list((root/'library/src/main/kotlin/com/easysavevideo/library/edit').rglob('*.kt'))
text='\n'.join(p.read_text() for p in files)
for forbidden in ['SaveRequest(', 'MediaCommitReceipt(', 'RecordingExpectation(', 'StreamExpectation(',
                  'HttpURLConnection','URLConnection','OkHttp','FFmpeg','com.google.crypto.tink','Log.d(', 'Log.i(',
                  'println(', 'printStackTrace(', 'file://', 'MediaProjection', 'RECORD_AUDIO']:
    assert forbidden not in text, forbidden
for p in (root/'library/src/main/kotlin/com/easysavevideo/library/edit').glob('*.kt'):
    for line in p.read_text().splitlines():
        if line.startswith('import '): assert not any(x in line for x in ['android.','androidx.','skinkit','com.android.billing']),line
storage=(root/'app/src/main/kotlin/com/easysavevideo/app/save/storage/AndroidSaveStorage.kt').read_text()
assert '@Synchronized' not in storage
assert 'dbCall(block: () -> T): T = synchronized(fileLock)' in storage
assert 'db.setForeignKeyConstraintsEnabled(db.version !in 1..3)' in storage
assert 'EditSchema.requireForeignKeysValid(db)' in storage
editor=(root/'app/src/main/kotlin/com/easysavevideo/app/editor/storage/AndroidEditStorage.kt').read_text()
assert 'sourceSnapshots(request)' in editor and 'it.validFor(row.output' in editor
assert 'files.assertStagingAbsent' in editor and 'files.assertFinalAbsent' in editor and 'requireFinalUnowned' in editor
assert 'EXECUTION_SQL' in editor and 'segment.executionStartUs' in editor
ui=(root/'app/src/main/kotlin/com/easysavevideo/app/editor/ui/EditorScreen.kt').read_text()
assert all(x in ui for x in ['LocalEsvVisuals','EsvBackdrop','EsvButton','M6-B','示意'])
runtime=(root/'app/src/main/kotlin/com/easysavevideo/app/editor/AndroidEditRuntime.kt').read_text()
assert 'OrdinaryMediaNativeResources.claim' in runtime and 'OrdinaryMediaNativeResources.unconfirmed()' in runtime
assert 'fun cancel()' in runtime and 'cancellation.cancel()' in runtime
assert '原件' in runtime and '无需重复转码' in runtime
report={'kind':'M5A4A_CURRENT_MEDIA_GUARDS_DISTRIBUTION_BUILD_EDITS_REVIEWED_SEPARATELY','existing_runtime_74_and_platform_manifest_unchanged':'PASS',
 'Save_HLS_verifier_and_M4_control_timeline_sources_unchanged':'PASS','core_platform_boundary':'PASS','typed_editor_no_network_or_capture':'PASS',
 'catalog_identity_and_owned_publication_source':'PASS','sole_storage_monitor_and_checked_v3_v4_v5_migration_source':'PASS',
 'skinkit_limits_and_free_cancel_source':'PASS','native_codec_gpu_oem_and_device_acceptance':'NOT_RUN','vault':'DISABLED_UNCHANGED'}
(out/'editor-admission.json').write_text(json.dumps(report,indent=2)+'\n'); print(json.dumps(report,indent=2))
